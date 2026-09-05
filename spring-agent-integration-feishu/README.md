# spring-agent-integration-feishu

> **Audience:** a developer depending on this module or changing it. To *deploy* a Feishu bot, read
> [`spring-agent-app-feishu`](../spring-agent-app-feishu/README.md) — it is this module wired into a
> server, and it carries the console setup and the environment variables. The property reference is
> the `app.feishu` block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

Feishu/Lark as an agent surface: a chat with the bot is a conversation with the agent, a turn is
answered in a card written as the run goes, and the platform's documents, spreadsheets, bases, wiki
and drive are offered to the model as tools.

It is a **chat surface**, so at most one of these may be on the classpath at a time — see
[integrations.md § One chat surface per application](../docs/integrations.md#one-chat-surface-per-application).

## What it contributes

| | |
| --- | --- |
| A surface | `FeishuMessageReceiveHandler` turns a message into an `AgentRequest`; `FeishuCardListener` follows the run and writes the card |
| A `Notifier` | `FeishuNotifier` — saying something to a chat with no run behind it, which is also what makes a handoff from the browser possible |
| A reply format | `FeishuReplyFormat`, a `PromptVariablesContributor` filling `{replyFormat}` — **conditional on the request's `chatType`**, see the gotcha below |
| A question handler | `FeishuQuestionHandler`, asynchronous: the ask tool persists a `PendingQuestion` and the run ends |
| An event source | `FeishuChatObservations` reports messages in a watched chat the bot was not addressed in, under the source name `feishu-chat` |
| Tools | Documents, spreadsheets, bases, wiki, drive, files, chats and permissions — `FeishuDocTools`, `FeishuSheetTools`, `FeishuBitableTools`, `FeishuWikiTools`, `FeishuDriveAccess`, `FeishuChatTools`, `FeishuPermissionTools`, `FeishuImportExportTools`, `FeishuBotTools` |
| A `/config` form | `FeishuConfigHandler` — choosing which model answers you, deliberately never going through the LLM |
| A greeting | `FeishuGreetings` and `FeishuUpdates`, the welcome card and the per-version notes |
| A long connection | `FeishuLongConnection`, watched and reopened by this module rather than by the SDK |

## The card

A turn is answered in one card that is rewritten as the run goes: the answer as it streams, what the
model thought, every tool call and what it returned, a panel per subagent, the to-do list, and what
the turn cost.

Feishu allows a card 30KB and 200 elements, and a long turn outgrows both. So when a card fills up
the agent finishes it and replies another onto the same message, carrying on where it left off, as
many times as the turn needs — a very long answer arrives as a run of cards rather than stopping
partway, and the stop button is always on the one still being written. `FeishuCardElements` is where
that bound is counted; `FEISHU_CARD_ELEMENTS`, `FEISHU_CARD_STREAM_CHARACTERS` and
`FEISHU_CARD_STREAM_INTERVAL` tune it.

The card JSON itself lives in template files (`FEISHU_REPLY_CARD`, `FEISHU_WELCOME_CARD`,
`FEISHU_UPDATE_CARD`), so a deployment restyles a card without touching Java.

## Two rules about identity

**What the agent makes in Feishu belongs to the person who asked for it.** The first time somebody
has the agent create a document, spreadsheet, base or file, `FeishuUserFolders` makes them a folder of
their own in the bot's drive space, hands them its ownership, and puts that and everything after it in
there. Each artefact is handed over the same way as it is made, so it counts against their drive and
stays theirs if the bot is ever uninstalled; in a group chat the chat itself is left able to view it.
The bot keeps full access to what it hands over, which is what lets it go on editing it.

**And it will only open what Feishu would have shown you.** Every call the bot makes carries its own
credentials, not the asker's, so left alone it would read out any document it can see to whoever
asked. `FeishuAccessInterceptor` therefore checks each call naming a document, spreadsheet, base,
file, folder or wiki space against that thing's collaborators first: you are let in if you are on the
list, or if it is shared with a chat you are in, and refused in so many words otherwise. A document
shared only by link is refused — add yourself or the chat as a collaborator. The same rule covers
writing: the agent will not post a message or a file into a group you are not in, and will not fetch
one out of it. `app.ai.admins` are exempt, as they are everywhere else.

Two exemptions, and only two. The second is **a run whose identity is the bot itself** — an unattended
triage run owned by an `app.events.sources.<name>.owner`. A chat's member list and a document's
collaborator list record people and never the bot, so the check would refuse such a run everything.
`FeishuChatAccess` therefore reads its membership off `members`/`is_in_chat` and `FeishuDriveAccess`
off `permissions/members/auth`, both of which answer for whoever the token represents, which is always
this bot. That is what `FEISHU_BOT_OPEN_ID` is for, beyond telling a group mention from somebody
else's: it reaches the chats the bot is in and the documents Feishu grants it, and nothing else.

## Gotchas worth knowing before changing this module

- **`FeishuReplyFormat` must stay conditional on `chatType`.** A `PromptVariablesContributor` is a
  bean and is asked about *every* run in the context, including a browser's where this module sits
  beside [`spring-agent-integration-websocket`](../spring-agent-integration-websocket/README.md). It
  filled `{replyFormat}` unconditionally once, which would have had every browser answer written in
  Feishu card markdown and rendered as literal `<at>` tags.
- **Do not ask for `applicationTaskExecutor`.** That bean is `@ConditionalOnMissingBean(Executor.class)`
  and a STOMP application registers four executors, so in `spring-agent-app-web-feishu` there is no
  such bean and the context fails to start. This module declares its own,
  `FeishuAutoConfiguration.TASK_EXECUTOR`.
- **`FeishuNotifier.quoted` is load-bearing rather than tidy.** `<at id=all></at>` typed into the web
  composer would otherwise have the bot notify a whole Feishu group.
- **Asking a question ends the turn.** The answer arrives later as a *new* `AgentRequest` on the same
  `conversationId`. Do not assume an answer is available in the same run.
- **`"feishu-chat"` is duplicated** in `FeishuChatObservations` and in `EventsProperties`, because a
  compile dependency between the two modules is not allowed. Both sides say so; renaming one silently
  stops the other working.
