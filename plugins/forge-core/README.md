# ForgeCore

The shared AI gateway. Every other TechForge plugin talks to the AI through ForgeCore and never touches the API key. Officers maintain it.

## What it does for every request

1. Refuses if the AI is off, the question is empty or too long, or it contains a blocked word.
2. Checks the player's cooldown, the player's daily limit, and the server's daily limit.
3. Puts the server's safety preamble in front of the plugin's persona. Plugins can't remove it.
4. Calls the model. In `mock` mode, it returns a canned reply and makes no network call.
5. Cleans the reply (formatting codes, markdown, length), runs the blocked-word filter again, and logs one line to `usage.log`.
6. Hands the reply back on the main server thread.

## Using it from your plugin

```java
// plugin.yml:  depend: [ForgeCore]
ForgeAI.get().ask(this, player, "You are a grumpy blacksmith.", question)
        .thenAccept(reply -> player.sendMessage(Component.text(reply)))
        .exceptionally(error -> { player.sendMessage(ForgeAI.friendlyError(error)); return null; });
```

To pass earlier turns of the conversation, use the `ask(..., List<ChatTurn> history, prompt)` overload. See `oracle-npc` for a full example.

## Commands

| Command | Who | What |
|---|---|---|
| `/forgeai status` | Ops (`techforge.forgeai.admin`) | On or off, which provider, and requests used today |
| `/forgeai reload` | Ops | Re-reads `config.yml` and `blocked-words.txt` |
| `/forgeai on` / `off` | Ops | Kill switch. It's saved to `config.yml` |

## Server files (in `plugins/ForgeCore/`, not in git)

| File | What |
|---|---|
| `config.yml` | Mode, provider URL and model, limits, and the safety preamble. It's documented inline |
| `api-key.txt` | The provider key, on one line. Or set the `FORGE_AI_API_KEY` environment variable instead |
| `blocked-words.txt` | One word or phrase per line |
| `usage.log` | One line per request: time, plugin, player, status, and sizes. It also stores the text if `safety.log-content: true` |

Works with any OpenAI-compatible endpoint: OpenAI, OpenRouter, Groq, Gemini's OpenAI endpoint, Ollama, LM Studio, and llama.cpp.
