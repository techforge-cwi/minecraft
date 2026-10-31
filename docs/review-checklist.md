# Officer review checklist

The **Plugin rules** check is a tripwire, not a sandbox: anything on the server can read anything the server can. You are the real security boundary. Read the whole diff.

## Every pull request

- [ ] **Checks are green.** If a first-time contributor's run says "awaiting approval," skim the diff *before* approving the run. Their code runs on GitHub's machine, not ours, and with no secrets, but don't approve obviously malicious code.
- [ ] **It only touches its own plugin folder.** Changes to `.github/`, `forge-core/`, `templates/`, or any `*.gradle.kts` file get a second, slower look.
- [ ] **I understand what every file does.** If you can't tell, ask the author to explain or simplify.
- [ ] **No sneaky I/O.** It doesn't read or write files outside `getDataFolder()`. It doesn't encode strings (base64, char arrays, string-building class names) to dodge the check. It doesn't load other plugins' classes or hook into ForgeCore internals.
- [ ] **Commands and permissions make sense.** Anything that spawns, deletes, teleports, or gives items is `default: op` or permission-gated.
- [ ] **What players see is school-appropriate,** including the persona text and any hard-coded messages.
- [ ] **AI cost is reasonable.** No calls in a loop, on every tick, or on every chat message from every player. One request per player action is the pattern.
- [ ] **It won't lag the server.** No blocking work (sleeping, waiting on futures with `.join()`/`.get()`, big file reads) on the main thread.
- [ ] **The README is filled in.**

## Policy exceptions

If a plugin genuinely needs a flagged API, add one line to `.github/policy-exceptions.txt` in its own PR: `plugin-folder: rule  # why, who approved`. Keep exceptions rare and specific, and re-review the plugin whenever it changes.

## Deploy approvals

Merging doesn't deploy. The **Deploy to server** run waits for an officer's **Review deployments → Approve and deploy**. Approve it when a restart of about a minute is OK. The script warns players in chat 30 seconds ahead.

## If something goes wrong

1. `/forgeai off` stops all AI calls immediately. `/forgeai on` turns them back on.
2. Revert the PR on GitHub (**Revert** button), merge the revert, and approve the deploy.
3. If a key may have leaked, rotate it at the provider, then upload a new `plugins/ForgeCore/api-key.txt` and run `/forgeai reload`.
4. `plugins/ForgeCore/usage.log` shows which plugin and player made each AI request.
