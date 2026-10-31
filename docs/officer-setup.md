# Officer setup

You do this once. After that, the routine is: **review the PR → merge → approve the deploy**.

## 1. GitHub (about 15 minutes)

**Officers team.** CODEOWNERS sends every review to it.
1. Go to the org, then **Teams → New team**. Name it `officers` and add the officers.
2. In this repo, go to **Settings → Collaborators and teams → Add teams**, add `officers`, and give it **Write**. Code owners need write access, or GitHub ignores them.

**Protect `main`.** Go to **Settings → Rules → Rulesets → New branch ruleset**:
1. Name it `main`, set Enforcement to **Active**, and set Target to **Include default branch**.
2. Turn on **Restrict deletions** and **Block force pushes**.
3. Turn on **Require a pull request before merging**, then set:
   - Required approvals: **1**
   - **Dismiss stale pull request approvals when new commits are pushed**
   - **Require review from Code Owners**
   - **Require approval of the most recent reviewable push**
4. Turn on **Require status checks to pass** and add **Plugin rules** and **Build & test**. They only show up in the list after the checks have run once, so open a test PR first if they're missing.
5. Leave the bypass list empty.

**Fork PRs.** Go to **Settings → Actions → General**. Under the approval setting for fork pull request workflows, pick the strictest option: require approval for all external contributors.

**Deploy environment.** Go to **Settings → Environments → New environment**, name it `production`, then:
1. **Required reviewers:** add `officers`, and check **Prevent self-review**.
2. **Deployment branches and tags:** choose Selected branches, then add `main`.
3. Add the secrets from step 3 below as **environment secrets**, not repository secrets, so only approved deploys can read them.

## 2. The server

1. Create a **Paper 26.3** server. It needs **Java 25**. Most host panels have a Paper option with a version dropdown. See [hosting.md](hosting.md) to pick a host.
2. In `server.properties`, set `white-list=true` and `enforce-whitelist=true`, and keep `online-mode=true`. Add members with `/whitelist add NAME`.
3. Only `/op` officers. Ops can spawn NPCs and turn the AI on and off.
4. Make the hosting account, the AI provider account, and any domain belong to the **club email**, not a personal one. Officers turn over.

## 3. Automatic deploys

Collect these values and add them as `production` environment secrets.

**If the host has a Pterodactyl-style panel** (most game hosts do; if the panel has "SFTP Details" and "Account → API Credentials," it's this one):

1. Make a dedicated deploy login. In the panel, open **Users → New User** with a club email address, and give it only **Control: Start, Stop, Console** and **File: Create, Read, Update, Delete, SFTP**. Use that login for everything below, so the GitHub secrets can't touch billing or other servers.
2. Collect the secrets:

| Secret | Where to find it |
|---|---|
| `SFTP_HOST`, `SFTP_PORT` | Panel → **Settings → SFTP Details** (the port is usually 2022) |
| `SFTP_USER` | Same place. It looks like `deploy.abc12345` |
| `SFTP_PASSWORD` | The deploy login's panel password |
| `SFTP_HOST_KEY` | On your computer, run `ssh-keyscan -p PORT HOST` and paste the whole output |
| `PTERO_PANEL_URL` | The panel's address, like `https://panel.example.com` |
| `PTERO_SERVER_ID` | The short ID in the panel URL: `/server/abc12345` |
| `PTERO_API_KEY` | Logged in as the deploy user: **Account → API Credentials → Create**. It starts with `ptlc_` |

**If it's your own VPS:**
1. Run the server as a systemd service named `minecraft`.
2. Create a `deploy` user with an SSH key.
3. Add a sudoers rule that lets `deploy` run only `systemctl stop minecraft` and `systemctl start minecraft`.
4. Collect the secrets:

| Secret / variable | Value |
|---|---|
| `SFTP_HOST`, `SFTP_PORT`, `SFTP_USER` | The VPS address, `22`, and `deploy` |
| `SFTP_PRIVATE_KEY` | The deploy user's private key (unencrypted ed25519) |
| `SFTP_HOST_KEY` | The output of `ssh-keyscan -p 22 HOST` |
| `SSH_STOP_COMMAND`, `SSH_START_COMMAND` | `sudo systemctl stop minecraft` and `sudo systemctl start minecraft` |
| Variable `SFTP_PLUGINS_DIR` | For example, `/opt/minecraft/plugins` |

**Turn it on.** Go to **Settings → Secrets and variables → Actions → Variables**, then:
1. Add `DEPLOY_ENABLED` = `true` and `DEPLOY_DRY_RUN` = `1`.
2. Go to **Actions → Deploy to server → Run workflow**, approve it, and read the plan it prints. It connects but changes nothing.
3. If the plan looks right, delete `DEPLOY_DRY_RUN` and run it again for real.

Optionally, set `RESTART_WARNING_SECONDS` to change the default 30-second chat warning.

The deploy only replaces `tf-*.jar` files. It removes `tf-*.jar` files whose plugin was deleted from the repo, and it leaves other plugins you installed by hand (LuckPerms and so on) alone. If the host has SFTP but no API, leave `DEPLOY_ENABLED` off. Download the `deploy-jars` artifact from the workflow run and upload it with the panel's file manager while the server is stopped.

## 4. Turn on the AI

The first deploy installs ForgeCore in **mock mode**, which makes no AI calls and costs nothing. To go live:

1. Create an account with an OpenAI-compatible provider, then make a key just for this server. **Set a monthly spending limit in the provider's dashboard.**
2. With the panel's file manager, upload `plugins/ForgeCore/api-key.txt`, containing just the key.
3. In `plugins/ForgeCore/config.yml`, set `mode: openai` and fill in `provider.base-url` and `provider.model`. A small, cheap model is plenty.
4. Add anything you want filtered to `plugins/ForgeCore/blocked-words.txt`.
5. In game, run `/forgeai reload`, then `/forgeai status`.
6. Stand where the Oracle should live, and run `/oracle spawn`.

`limits.server-daily-requests` is a hard daily cap, a second safety net under the provider's spending limit.

## Updating Minecraft

1. In `gradle.properties`, bump `mcVersion` and `paperApiVersion` together. The newest `paperApiVersion` is the last `-stable` entry in [paper-api's metadata](https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/maven-metadata.xml).
2. Open a PR and let CI build every plugin against the new API.
3. After it merges, update the server's Paper version in the panel, then approve the deploy.
