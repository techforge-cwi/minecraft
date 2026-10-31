# OracleNpc

An AI villager you can talk to. It's the example to copy when you build your own NPC.

## How to use it

- An officer runs `/oracle spawn` where the Oracle should stand. `/oracle remove` deletes any Oracle within 5 blocks.
- Right-click the Oracle, then type in chat. Your messages go only to the Oracle, not to everyone, and only you see its replies.
- Type `bye` to stop. The conversation also ends by itself after 60 seconds of silence.

## How it works

| Piece | Where |
|---|---|
| Who the Oracle is | `PERSONA` at the top of `OracleNpcPlugin.java` |
| Talking to the AI | `ForgeAI.ask(...)` in `onChat`. ForgeCore handles the API key, rate limits, and safety filter |
| Remembering the conversation | `Conversation` keeps the last 3 questions and answers and sends them as `history` |
| Telling the Oracle apart from normal villagers | A tag in the villager's persistent data (`npcTag`) |

## Make your own NPC

1. Copy this folder to `plugins/your-npc-name/`.
2. Rename the package folder and update `package` lines, `main:` and `name:` in `plugin.yml`.
3. Change `NPC_NAME` and `PERSONA`. Rename the command and permission.
4. Run `./gradlew runServer` and try it.

Ideas: a quest-giver that invents a quest from what you're holding, a shopkeeper who haggles, a tutor that quizzes you on Python.
