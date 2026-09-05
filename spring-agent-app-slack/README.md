# spring-agent-app-slack

> **Audience:** whoever deploys this. The same runtime as
> [`spring-agent-app-feishu`](../spring-agent-app-feishu/README.md) with Slack as its surface instead.
> The configuration reference is [`src/main/resources/application.yaml`](src/main/resources/application.yaml),
> which is derived from the Feishu server's file and must stay in step with it.

```sh
./gradlew :spring-agent-app-slack:bootRun
```

## What it carries

Everything [the Feishu server](../spring-agent-app-feishu/README.md#what-it-carries) carries, with
[slack](../spring-agent-integration-slack/README.md) where that has feishu. The `OPENAI_*` and
`EMBEDDING_*` variables, the two switches, `ADMINS`, `USER_MODELS_ENCRYPTION_KEY` and the tool bounds
all mean exactly what they mean there — a deployment moving between the two should not silently get
different limits.

## Its own four variables

| Variable | Where it comes from |
| --- | --- |
| `SLACK_BOT_TOKEN` | **OAuth & Permissions**, after installing the app to the workspace. Starts `xoxb-` |
| `SLACK_APP_TOKEN` | **Basic Information → App-Level Tokens**, generated with the `connections:write` scope. Starts `xapp-`. This is what opens the Socket Mode connection, and it is a different credential from the bot token |
| `SLACK_BOT_USER_ID` | **OAuth & Permissions**, or `curl -H "Authorization: Bearer $SLACK_BOT_TOKEN" https://slack.com/api/auth.test` and read `user_id`. Starts `U` |
| `SLACK_TEAM_ID` | The same `auth.test` call, field `team_id`. Starts `T` |

`.env` is read by `docker compose` and `docker run --env-file` but **not** by `./gradlew bootRun`:

```sh
set -a; . ./.env; set +a
./gradlew :spring-agent-app-slack:bootRun
```

**`SLACK_CLIENT_ID` and `SLACK_CLIENT_SECRET` belong to the browser surface's Sign in with Slack, not
to the bot** — see the note in
[the integration's README](../spring-agent-integration-slack/README.md#gotchas-worth-knowing-before-changing-this-module).
The two can share a `.env` safely. An empty `SLACK_BOT_TOKEN=` is worse than a missing one; the
application refuses to start on it and says which variable it is.

## Creating the Slack app, once

At <https://api.slack.com/apps>:

1. **Socket Mode → Enable Socket Mode.** No public URL is needed, and no request signing.
2. **OAuth & Permissions → Bot Token Scopes**: `chat:write`, `im:history`, `channels:history`,
   `groups:history`, `mpim:history`, `files:read`, `files:write`, `reactions:write`, `users:read`.
3. **Event Subscriptions → Subscribe to bot events**: `message.im`, `message.channels`,
   `message.groups`, `message.mpim`, `app_home_opened`. Deliberately **not** `app_mention` — Slack
   delivers a message that mentions the bot under both events with different ids, so subscribing to
   both answers every mention twice.
4. **Interactivity & Shortcuts → Enable.** With Socket Mode on there is no Request URL to fill in;
   this is what makes the stop button and the question form work.
5. **Slash Commands → Create New Command**, `/config`, description "Choose which chat model answers
   you". Only needed where `USER_MODELS_ENCRYPTION_KEY` is set, and again no Request URL. This one is
   not plumbing the application can do for itself: Bolt only routes commands Slack decides to send,
   and Slack sends none it has not been told about. Creating it adds the `commands` scope, so the app
   has to be reinstalled afterwards. Skipping this costs the modal but not the feature — a message
   reading ` /config`, with a leading space, opens the same form in the channel.
6. **Install to Workspace**, then invite the bot to any channel you want it to answer in.

## What a person meets

Opening a direct message with the bot for the first time is answered with a welcome note, and
afterwards with whatever has changed since — the same `welcome.md` and `updates/N.md` arrangement the
Feishu server uses, under `slack/` and pointed at by `SLACK_WELCOME` and `SLACK_UPDATES`.

A turn is answered in a message rewritten as the run goes: the answer as it is written, the tools it
is calling, its task list, and what it cost. Slack allows a message 50 blocks, and a long turn
outgrows that — so when one fills up the agent finishes it and posts another into the same thread.
