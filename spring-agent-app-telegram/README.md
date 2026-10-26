# spring-agent-app-telegram

> **Audience:** whoever deploys this. The same runtime as
> [`spring-agent-app-feishu`](../spring-agent-app-feishu/README.md) with Telegram as its surface
> instead. The configuration reference is [`src/main/resources/application.yaml`](src/main/resources/application.yaml),
> which is derived from the Feishu server's file and must stay in step with it.

```sh
./gradlew :spring-agent-app-telegram:bootRun
```

## What it carries

Everything [the Feishu server](../spring-agent-app-feishu/README.md#what-it-carries) carries, with
[telegram](../spring-agent-integration-telegram/README.md) where that has feishu. The `OPENAI_*` and
`EMBEDDING_*` variables (or `DASHSCOPE_API_KEY`, `GEMINI_API_KEY`, or the `ANTHROPIC_*` set in their
place), the three switches, `ADMINS`, `USER_MODELS_ENCRYPTION_KEY` and the tool bounds all mean
exactly what they mean there.

What it does not carry is a browser sign-in. The Feishu and Slack servers log a person in with their
platform's OpenID Connect provider; Telegram has none, so nothing behind a login is served at all.
Public published files, the actuator and the event webhooks work as they do there — a file the agent
publishes *privately* cannot be opened by anybody.

## Its own variables

| Variable | |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | From [@BotFather](https://t.me/BotFather): `/newbot`, or `/token` for an existing bot |
| `TELEGRAM_ALLOWED_USER_IDS` | Comma-separated Telegram user ids who may use the bot |
| `TELEGRAM_ALLOWED_CHAT_IDS` | Comma-separated group chat ids whose every member may (negative numbers) |
| `TELEGRAM_OBSERVED_CHAT_IDS` | Groups watched without the bot being addressed — off when empty |

**Who may use the bot is not optional.** A Telegram bot can be messaged by anybody on Telegram, and a
run acts with the asker's files, credentials and MCP servers on this deployment's model bill. With
both allow-lists empty the bot answers nobody. Somebody it refuses in a private chat is told their
own user id once — that number is what goes into `TELEGRAM_ALLOWED_USER_IDS`. A group's id is in the
log line the bot writes for every message it receives there.

`.env` is read by `docker compose` and `docker run --env-file` but **not** by `./gradlew bootRun`:

```sh
set -a; . ./.env; set +a
./gradlew :spring-agent-app-telegram:bootRun
```

## Setting the bot up, once, with @BotFather

1. `/newbot`, and keep the token it gives you.
2. Optionally `/setcommands`, so the client offers them from its menu:
   ```
   start - What I can do
   config - Choose which model answers you
   kb - Answer only from what I was told to remember
   mini - A quick aside that leaves no trace
   ```
3. For groups: **turn privacy mode off** with `/setprivacy` → *Disable*, then add the bot to the
   group (or remove and re-add it, if it is there already — Telegram applies the setting on
   joining). With privacy mode on, a group sends the bot only replies to its own messages and
   commands naming it, so `@yourbot what is…` never arrives and the bot looks dead. Turning it off
   does not make the bot answer everything: it still answers only a message that mentions it,
   replies to it or names it on a command, and the rest is dropped unless the group is in
   `TELEGRAM_OBSERVED_CHAT_IDS`.

The bot receives its messages by **long polling**: no public URL, no certificate. Two things follow.
A bot with a webhook set cannot be polled, so the application refuses to start until it is deleted,
rather than deleting it and silently cutting off whatever it pointed at. And only one process may
poll a bot at a time, so **run one replica** — a second one makes both log `409 Conflict` and take
turns at the messages.

## What a person meets

`/start` in a private chat is answered with the welcome note and, after the first time, with whatever
has changed since — the same `welcome.md` and `updates/N.md` arrangement the Feishu server uses,
under `telegram/` and pointed at by `TELEGRAM_WELCOME` and `TELEGRAM_UPDATES`.

A turn is answered in a reply edited as the run goes: the answer as it is written, its thinking and
its tool calls folded into expandable quotes, its task list, and what it cost, with a Stop button
for the person it is answering. A message sent while it works gets 👀, then 👌 once the run has read
it. A long answer carries on in further messages. In a group each person has a conversation of their
own with the bot; in a private chat there is one, as long as the chat.

`/config` (in a private chat) shows which model answers you, with buttons to switch model, thinking
effort and protocol. `/config add name=… url=… model=… token=…` registers an endpoint of your own; the
message is deleted as soon as it is read, since it carries the token.
