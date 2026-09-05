# spring-agent-integration-slack

> **Audience:** a developer depending on this module or changing it. To *deploy* a Slack bot, read
> [`spring-agent-app-slack`](../spring-agent-app-slack/README.md) — it carries the Slack app creation
> steps and the environment variables. The property reference is the `app.slack` block in
> [`application.yaml`](../spring-agent-app-slack/src/main/resources/application.yaml).

Slack as an agent surface: channels and direct messages are conversations, and a turn is answered in
a Block Kit message rewritten as the run goes. The same design as
[the Feishu integration](../spring-agent-integration-feishu/README.md) against a different product —
which is the point of having both, since the pair is what keeps the surface contract honest.

It is a **chat surface**, so at most one of these may be on the classpath at a time — see
[integrations.md § One chat surface per application](../docs/integrations.md#one-chat-surface-per-application).
`OneChatSurfaceTest` in `spring-agent-app-slack` is the check that notices.

## What it contributes

| | |
| --- | --- |
| A surface | `SlackMessageReceiveHandler` turns a message into an `AgentRequest`; `SlackMessageListener` follows the run and rewrites the message |
| A `Notifier` | `SlackNotifier` |
| A reply format | `SlackReplyFormat`, filling `{replyFormat}` with Slack's mrkdwn rather than markdown |
| A question handler | `SlackQuestionHandler`, asynchronous like Feishu's: the run ends and the answer arrives as a new request |
| An event source | `SlackChatObservations`, under the source name `slack-chat` |
| Tools | `SlackTools` — sending a message, reading history, listing channels and their members, sending a file |
| A `/config` modal | `SlackConfigHandler`, private to the person who opened it, so an API token never enters channel history |
| A greeting | `SlackGreetings` and `SlackUpdates`, over `slack/welcome.md` and `slack/updates/N.md` |
| A socket connection | `SlackSocketConnection` — Socket Mode, so no public URL and no request signing |

## The message

Slack allows a message 50 blocks, and a long turn outgrows that — so when one fills up the agent
finishes it and posts another into the same thread, carrying on where it left off. `SLACK_MESSAGE_BLOCKS`,
`SLACK_STREAM_CHARACTERS` and `SLACK_STREAM_INTERVAL` tune the rewriting; `SLACK_REPLY_MESSAGE`,
`SLACK_WELCOME_MESSAGE` and `SLACK_UPDATE_MESSAGE` point at the Block Kit templates.

## Gotchas worth knowing before changing this module

- **`SLACK_CLIENT_ID` / `SLACK_CLIENT_SECRET` belong to the browser surface's Sign in with Slack, not
  to the bot.** Bolt's `AppConfig` reads those two from the environment and treats an app that has
  them as a multi-workspace OAuth app, authorising each event against an installation store the bot
  does not have — which used to refuse every event with `401 "a request for an unknown workspace
  detected"` while the bot token was perfectly valid. `SlackIdentity` pins this integration to
  single-workspace mode regardless, so the two can share a `.env` safely. Do not undo that.
- **An empty `SLACK_BOT_TOKEN=` is worse than a missing one**, because it resolves: the application
  starts and Bolt calls `auth.test` with an empty token once per delivery. `SlackIdentity` refuses to
  start on that and names the variable.
- **Do not subscribe to `app_mention`.** Slack delivers a message that mentions the bot under both
  `message.*` and `app_mention` with different ids, so subscribing to both answers every mention
  twice.
- **Do not ask for `applicationTaskExecutor`** — see the same note in the Feishu module. This one
  declares `SlackAutoConfiguration.TASK_EXECUTOR`.
- **`"slack-chat"` is duplicated** in `SlackChatObservations` and `EventsProperties`, for the same
  reason `"feishu-chat"` is.
