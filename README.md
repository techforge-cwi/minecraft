# TechForge Minecraft

The club's Minecraft server, plus the AI-powered plugins members build for it. Every plugin here is reviewed by an officer before it goes live.

> **fork → build a plugin → test it locally → pull request → automatic checks → officer review → merge → it's on the server**

New to pull requests? Do [hello-techforge](https://github.com/techforge-cwi/hello-techforge) first. It takes 10 minutes.

## Play on the server

- You need **Minecraft: Java Edition**.
- The server address is pinned in the TechForge Discord. The server is whitelisted, so ask an officer to add your Minecraft username.
- Find the **Oracle**, a purple-named villager. Right-click it and type a question in chat. Only you see the conversation.

## Build a plugin

Plugins are written in Java against the [Paper API](https://docs.papermc.io/paper/dev). For AI, they call **ForgeAI**, which the `forge-core` plugin provides. You never need an API key. ForgeCore holds the key and handles rate limits and the safety filter.

### 1. Set up (once)

- Install [Git](https://github.com/techforge-cwi/resources/blob/main/how-to/set-up-your-computer.md) and [IntelliJ IDEA Community](https://www.jetbrains.com/idea/download/), or any Java editor.
- You don't need to install Java. The first build downloads Java 25 automatically.
- Fork this repo (**Fork** button at the top right), then:

```sh
git clone https://github.com/YOUR-USERNAME/minecraft.git
cd minecraft
git switch -c my-plugin
```

### 2. Create your plugin

```sh
./gradlew newPlugin -Pplugin=my-plugin -Pauthor=YOUR-USERNAME
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

That makes `plugins/my-plugin/` with a working `/my-plugin <question>` command. Open `plugins/my-plugin/src/main/java/.../MyPluginPlugin.java` and make it yours. For a bigger example, read [`plugins/oracle-npc`](plugins/oracle-npc), an AI villager with memory.

### 3. Test it on your own computer

```sh
./gradlew runServer
```

That starts a private Paper server with every plugin in the repo. Open Minecraft (version **26.3**), go to **Multiplayer → Direct Connection**, and enter `localhost`. To give yourself admin commands, type `op YOUR-MINECRAFT-NAME` in the server window.

By default, the AI runs in **mock mode**: it gives canned replies, with no key and no cost. That's enough to test your plugin's logic. If you want real answers locally, install [Ollama](https://ollama.com), then edit `run/plugins/ForgeCore/config.yml`: set `mode: openai`, `base-url: http://localhost:11434/v1`, and `model:` to a model you've pulled. Then run `/forgeai reload`.

### 4. Send it in

```sh
git add plugins/my-plugin
git commit -m "Add my-plugin"
git push -u origin my-plugin
```

Open your fork on GitHub and click **Compare & pull request**. Fill in the checklist.

## What happens to your pull request

1. **Plugin rules** and **Build & test** run automatically. Each shows a green check or a red X. Click **Details** to see what to fix. Push again, and the PR updates by itself.
   - **"Workflow awaiting approval"** is normal for new contributors. An officer will approve it.
2. An officer reviews your code and may ask for changes.
3. After it merges, an officer approves the deploy when it's a good time to restart. The server warns players and restarts, and your plugin is live.

## Rules the check enforces

- Each plugin lives in its own folder, `plugins/<name>/`, with a `README.md` and a `plugin.yml`. The folder name is lowercase with dashes.
- Code goes in a package under `io.github.techforgecwi.`.
- **AI goes through ForgeAI.** Plugins can't make web requests, read environment variables, run programs, use reflection, or touch ForgeCore's files. If your idea really needs one of those, ask an officer.
- No API keys, no `.jar` or other binary files, and no `libraries:` in plugin.yml.
- Everything players see has to be school-appropriate. ForgeCore adds safety rules to every prompt, but that's on you too.

## What's in here

| Path | What |
|---|---|
| `plugins/forge-core/` | The AI gateway. It holds the key and enforces limits and the safety filter. Officers maintain it |
| `plugins/oracle-npc/` | Example: an AI villager you can talk to |
| `templates/plugin/` | What `newPlugin` copies |
| `.github/` | Automatic checks, deploy, and code owners |
| `docs/` | [Officer setup](docs/officer-setup.md), [review checklist](docs/review-checklist.md), [hosting options](docs/hosting.md) |

## Stuck?

Ask in the TechForge Discord. Someone has been stuck on the same thing. For Paper questions, the [Paper docs](https://docs.papermc.io/paper/dev) are the best reference.
