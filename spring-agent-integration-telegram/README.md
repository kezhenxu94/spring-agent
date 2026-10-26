# spring-agent-integration-telegram

> **Audience:** a developer depending on this module or changing it. To *deploy* a Telegram bot,
> read [`spring-agent-app-telegram`](../spring-agent-app-telegram/README.md) — it carries the
> BotFather steps and the environment variables. The property reference is the `app.telegram`
> block in [`application.yaml`](../spring-agent-app-telegram/src/main/resources/application.yaml).

Telegram as an agent surface: private chats and groups are conversations, and a turn is answered in
a reply edited as the run goes. The same design as [the Slack integration](../spring-agent-integration-slack/README.md),
whose classes most of these are counterparts of and whose comments explain the reasoning they share
— read the Slack class a Telegram one points at before changing either.

It is a **chat surface**, so at most one of these may be on the classpath at a time — see
[integrations.md § One chat surface per application](../docs/integrations.md#one-chat-surface-per-application).
`OneChatSurfaceTest` in `spring-agent-app-telegram` is the check that notices.

## What it contributes

| | |
| --- | --- |
| A surface | `TelegramMessageReceiveHandler` turns a message into an `AgentRequest`; `TelegramMessageListener` follows the run and edits the reply |
| A `Notifier` | `TelegramNotifier`, splitting what is longer than one message |
| A reply format | `TelegramReplyFormat` — short, since the model writes plain markdown and `TelegramMarkdown` translates |
| A question handler | `TelegramQuestionHandler`, asynchronous like Slack's: the run ends and the answer arrives as a new request |
| An event source | `TelegramChatObservations`, under the source name `telegram-chat` |
| Tools | `TelegramTools` — sending a message, sending a file, describing a group |
| `/config` | `TelegramConfigHandler` — buttons to switch, `/config add …` to register an endpoint; private chats only |
| A greeting | `TelegramGreetings` answering `/start`, over `telegram/welcome.md` and `telegram/updates/N.md` |
| A poll | `TelegramUpdatePoller` — long polling, so no public URL |
| Admission | `TelegramAccess`, over `app.telegram.allowed-user-ids` and `allowed-chat-ids` |

**No SDK.** The Bot API is JSON over HTTPS, so `TelegramBotApi` is RestClient and the records in
`TelegramTypes`. The Java SDKs bring OkHttp and Jackson 2 beside Boot 4's Jackson 3, and a
reflection hint per type they bind.

## Where it differs from Slack, and why

- **Admission comes first.** A Telegram bot is reachable by anybody on Telegram, with no workspace
  around it, so `TelegramAccess` decides whether a person may use the agent before anything is
  claimed — and an empty allow-list admits nobody. Every button that starts a run or changes a
  setting is admitted the same way; Stop is not, since it only ever ends the presser's own run.
- **A conversation is not a thread**, because Telegram has none outside a forum and a reply cannot
  be followed back past one link. A private chat is one conversation and a group is one per person
  (per topic, in a forum) — see `TelegramConversation`, whose key is also the `rootMessageId`, so a
  scheduled task fires back knowing where to send.
- **Text is plain characters plus entity spans**, never `parse_mode`. HTML and MarkdownV2 are parsed
  by Telegram, which refuses the whole message over one unclosed tag or unescaped character — a
  streamed answer has one at almost every moment. `TelegramMarkdown` parses what the model wrote with
  flexmark into `TelegramText`, which cannot be refused for its formatting.
- **A reply is as many messages as it needs.** `TelegramMessage` cuts the whole reply into pages of
  at most 4000 characters on every write and edits only the pages that changed; the buttons ride on
  the last one.
- **One poller per bot.** Telegram hands updates to one `getUpdates` caller at a time and answers a
  second with 409, so this surface runs as a single replica. An update is confirmed only after it
  has been handled, and one arriving during shutdown is left unconfirmed for the next process.
- **The question form is buttons.** A message has no inputs, so an option is a button, a form with
  more than one choice to make is marked on its own keyboard and sent with Submit, and an answer in
  words is just a message — which supersedes the form, as on every surface.
- **No history and no member list.** The Bot API cannot read a chat's past or list a group's members,
  so the tools Slack has for those have no counterpart rather than one that always fails.
- **`/config` never reaches the agent**, even where model settings are off: what follows it may be a
  token. A message carrying one is deleted the moment it is read.

**Scenario memos** work as on the other surfaces. A group's command menu sends `/kb@SomeBot`, so the
bot's name is taken off a command before `ScenarioMemos` looks at it.

## Gotchas worth knowing before changing this module

- **Never log, return or attach a request URL.** The token is in the path. `TelegramApiException`
  carries the method and Telegram's description and deliberately no cause; `TelegramBotApiTest`
  holds that line.
- **Button data is at most 64 bytes**, and one longer refuses the whole message. Everything this
  module puts there is an index or a short id; a model name that would not fit is left off the
  `/config` keyboard and reachable as `/config model=…`.
- **A span past the end of the text, or of no length, refuses the message.** `TelegramText.slice`
  and `trimmed` exist so that nothing has to get that right by hand; `TelegramMarkdownTest` checks
  it on every case.
- **`"telegram-chat"` is duplicated** in `TelegramChatObservations` and `EventsProperties`, for the
  same reason `"slack-chat"` is.
- **flexmark is pinned to what `spring-ai-agent-utils` already brings** — see the comment in
  `gradle/libs.versions.toml`.
