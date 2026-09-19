# Keeping what a run thought, and reading it back a round at a time

A run's reasoning has only ever existed while the run did. The Feishu card streamed it into a
panel and the browser streams it into a fold, and both are gone the moment the journal is evicted
or the page is reloaded. Now that a deployment can turn the card's panel off, a round's thinking is
lost as soon as it is produced — so it is persisted, and the browser is where it is read back.

## What is stored

`core/dao/models/ChatReasoning`, one row per round, carrying every backend's mapping annotations
like every other model here.

| field | why |
| --- | --- |
| `id` | the run's `requestId`. The row *is* the run: there is nothing to join and no key to invent, the same reasoning `ChatSession.id` gives |
| `conversationId` | `@Indexed`. The only query there is — everything one conversation thought |
| `userId` | so a read can be refused to anybody else without going back to `ChatSession` |
| `answerDigest` | SHA-256 of the final answer, which is how a reloaded turn finds its row |
| `text` | the last `reasoningSoFar` the run reported, in full |
| `createdAt` | ordering, and what a retention sweep would read if one is ever wanted |

`core/dao/repo/ChatReasoningRepo` is the contract — `save`, `findById`, `findByConversationId`,
`deleteByConversationId` — implemented once per `spring-agent-persistence-*` module. The behaviour
goes in `AbstractPersistenceBackendTest`, so all three are held to it at once.

## Who writes it

A `@Bean AgentResponseListener` in core. `onStart` reads the request and attaches nothing to a run
with no `conversationId`, or to a subagent (`parentRequestId` set) — a subagent's thinking belongs
to the tool call that started it, not to the round. Otherwise it attaches a per-run listener that
keeps the latest `onReasoning` and `onContent` strings and writes one row in `onFinished`, whatever
the outcome: a cancelled run thought too. A run whose endpoint reported no reasoning writes nothing,
which is most providers.

In core rather than in the browser surface, because the surface that stopped showing this is
Feishu. A Feishu round's thinking is kept for the same reason a web round's is, and in
`spring-agent-app-web-feishu` the page is where a person goes to read it.

`app.ai.reasoning.store`, default true, switches it off for a deployment that does not want the
bytes. No cap: what is stored is what the model produced. `ChatSessions.delete` also calls
`deleteByConversationId`, or a deleted conversation would leave its thinking on disk.

## Why a digest, and not the requestId

The obvious pairing — put the `requestId` on the turn — is not available, and this is worth writing
down so it is not proposed again.

The replayed transcript comes from chat memory, and **no backend stores message metadata**:

- `jpa` and `redis` use Spring AI's own repositories, whose JDBC table is fixed at
  `conversation_id, content, type, timestamp, sequence_id` (`schema-sqlite.sql` in
  `spring-ai-model-chat-memory-repository-jdbc`). There is nowhere to put one, and the schema is not
  ours.
- `MongoChatMemoryRepo` removed metadata deliberately — read `Entry.Body`: a provider put a
  `com.google.genai.types.FinishReason` in there, Mongo could not read it back, and every run in
  that conversation died. That comment also says nothing here may depend on metadata, precisely
  because the other two backends never had it.

Carrying a `requestId` on a turn therefore means forking all three chat-memory repositories, two of
them upstream, and `MongoChatMemoryRepo` is itself marked to be deleted when spring-ai#6895 lands.

So the `requestId` is the row's id — which is exact for a live or just-finished run, since that is
the id the page already holds — and a digest of the final answer is what a *reload* pairs on.
`transcript()` digests each assistant message and looks the row up. It matches exactly or not at
all, and the two honest misses are a round that produced no reasoning and an answer chat memory has
since trimmed away. A miss draws nothing: no thinking is better than another round's.

## Reading it back

`ChatSessions.Turn` grows a `reasoningId`, set on the **user turn that opened the round** rather
than on the assistant row it was resolved from — that is where a live run puts its fold, and where
a reader looks for it.

`GET /api/conversations/{id}/reasoning/{requestId}` is the deferred read and the only thing that
ever returns the text. Two checks, in order: the conversation is the caller's, exactly as every
other endpoint here derives identity, and the row's `conversationId` equals the one in the path. The
second is not redundant — without it, one's own conversation id plus somebody else's `requestId`
reads their thinking. The id is a UUID this application minted, so it has no slashes and can be a
path variable.

`GET /api/conversations` is untouched: it must not grow a field that drags this text into a sidebar
listing.

## How it is drawn

`appendTurn('user', …)` takes the `reasoningId`, and where there is one appends a `RunView` holding
a single collapsed `fold('reasoning', t('run.thinking'), 'mist')` — the same fold, dot and rail a
live run streams into, for the reason `appendTools` gives: a person looking at a conversation they
reloaded is looking at the run they watched, and a second set of markup would drift from the first.

The fold is built empty. On its first `toggle` to open it fetches, renders the text through the same
`markdown` sanitiser every other replayed turn goes through, and keeps it — collapsing and reopening
costs nothing. Three states: a placeholder while in flight, the text, and a line saying it is no
longer there for a failure or a 404, rather than a fold that opens onto nothing.

A live run is unchanged: it streams into its own fold, and this one appears only in a replay.
