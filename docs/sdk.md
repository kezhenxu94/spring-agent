# Building an agent with the SDK

`spring-agent` is a Spring Boot 4 / Spring AI library before it is an application. This document is
for the Java developer embedding it: what to depend on, what to configure, how to start a run, and
what the extension points are. If you only want to run the applications that ship here, read the
[README](../README.md) instead; if you want to change this repository, read
[contributing.md](contributing.md).

Artifacts are published to Maven Central under the `me.kezhenxu94` group. Java 21 is the floor —
the modules target bytecode 21 and use nothing newer.

- [Dependencies](#dependencies)
- [Minimum configuration](#minimum-configuration)
- [Running the agent](#running-the-agent)
- [Listeners: how a surface follows a run](#listeners-how-a-surface-follows-a-run)
- [Asking the user a question](#asking-the-user-a-question)
- [Scenarios](#scenarios)
- [Tools](#tools)
- [Where a user's files live](#where-a-users-files-live)
- [Watching what other systems do](#watching-what-other-systems-do)
- [The knowledge base](#the-knowledge-base)
- [The system prompt and other prose](#the-system-prompt-and-other-prose)
- [Persistence](#persistence)
- [Native image](#native-image)
- [Module reference](#module-reference)

## Dependencies

Take `spring-agent-core` plus exactly one persistence module. Core is backend-agnostic and carries
no database driver; the persistence module is what supplies both the agent's own repositories and
the Spring AI chat memory repository, and those two have to come from the same place.

```groovy
implementation 'me.kezhenxu94:spring-agent-core:<version>'
implementation 'me.kezhenxu94:spring-agent-persistence-jpa:<version>'
```

```xml
<dependency>
  <groupId>me.kezhenxu94</groupId>
  <artifactId>spring-agent-core</artifactId>
  <version>VERSION</version>
</dependency>
<dependency>
  <groupId>me.kezhenxu94</groupId>
  <artifactId>spring-agent-persistence-jpa</artifactId>
  <version>VERSION</version>
</dependency>
```

Every module auto-configures itself and component-scans its own package, so a plain
`@SpringBootApplication` picks up the runtime, the built-in tools and the repositories with no
`@Import` and no scan of your own.

Selecting a backend is a two-step thing on purpose. Depending on a module makes it *available*;
`app.persistence.type` (and `app.ai.tools.shell.type` for the shell) is what *chooses*. An
application that depends on exactly one backend module can leave the property alone — a
`@ConditionalOnPersistenceBackend` with nothing to choose between resolves to the one that is there.

## Minimum configuration

```yaml
spring:
  ai:
    openai:
      base-url: ${OPENAI_BASE_URL}
      api-key: ${OPENAI_API_KEY}
      chat.model: ${OPENAI_MODEL}
      embedding:
        base-url: ${EMBEDDING_BASE_URL}
        api-key: ${EMBEDDING_API_KEY}
        model: ${EMBEDDING_MODEL}
```

An embedding model is not optional even if you never index a document: the tool-search advisor
builds its index by embedding tool descriptions, and the knowledge base embeds what it stores.

Nothing else is required. In particular you do not have to name core's message bundle: the text the
agent writes into a conversation itself resolves through your application's own `MessageSource`, and
`MessagesDefaults` appends `core/messages` to `spring.messages.basename` for you — after whatever
you named there, so your own bundles keep winning.

That is the minimum, not the whole of it. Everything you can turn on from here — the shell sandbox,
the tool-search advisor, per-user storage and published-file links, the MCP allow-list, the question
tool's lifetime, the events receiver, the knowledge base — is written out with its rationale in the
server's [`application.yaml`](../spring-agent-app/src/main/resources/application.yaml). That file is
the configuration reference for the SDK as much as for the server; copy the blocks you want out of
it rather than rediscovering property names.

## Running the agent

One entry point. Inject `SpringAgent` and describe the run; `SpringAgent` turns the request into a
prompt, a tool set and a tool context, so an integration never touches `AgentToolsProvider`, the MCP
client lifecycle or Reactor.

`fire` returns immediately and reports only through listeners, so a caller that has to wait for the
answer waits on one of them.

```java
agent.fire(
    AgentRequest.builder()
        .requestId(runId)                     // what SpringAgent.cancel(runId) stops
        .scenario(BuiltInScenarios.CHAT)
        .userId(userId)                       // whose workspace, skills, memories and MCP servers
        .chatId(conversationId)
        .conversationId(conversationId)       // groups the runs that share chat memory
        .userMessage(message -> message.text(text))
        .listener(
            new AgentResponseListener() {
              @Override
              public void onContent(final String contentSoFar) {
                surface.update(contentSoFar); // the answer so far, not the latest delta
              }

              @Override
              public void onFinished(final AgentOutcome outcome) {
                surface.done(outcome);        // exactly once per run, however it ended
              }
            })
        .build());
```

`AgentOutcome` is `COMPLETED`, `FAILED` or `CANCELLED`; core owns that classification so a surface
never has to infer it from the shape of an error.

### What an `AgentRequest` carries

| Field | What it is for |
| --- | --- |
| `requestId` | The key `SpringAgent.cancel(requestId)` stops this run by |
| `parentRequestId` | The run that started this one. Cancelling the parent cancels this; usage is reported to the parent's listeners too, and the parent does not finish until this one has |
| `description` / `brief` | One line saying what the run is for, and the whole task it was given, for a surface showing a run it is not streaming |
| `scenario` | Which tools the run gets, whether it uses conversation memory, whether it consults the knowledge base |
| `userId` | Whose workspace, skills, memories, credentials and MCP servers the run acts with |
| `chatId`, `chatType`, `groupId`, `tenantId` | Opaque identifiers minted by the integration and never interpreted by core; the last two scope shared homes and knowledge |
| `conversationId` | Groups the runs that share chat memory |
| `rootMessageId`, `replyMessageId` | Opaque thread and message identifiers, passed through to tools |
| `background` | An unattended run: nothing is streamed anywhere and the answer is not delivered, so it reaches a person only through what it sends while running |
| `promptVariables` | Extra system-prompt variables; the identity ones are filled in by core |
| `userMessage` | A `Consumer<ChatClient.PromptUserSpec>`, so text, media and options are yours to set |
| `toolContext` | Extra tool-context entries; core's identity keys are filled in and win on conflict |
| `listeners`, `todoEventHandlers` | Per-run observers, in addition to the bean-declared ones |

`fireOrQueue(...)` is the variant for a surface where a user may type again while a run is still
working: the message joins the run in flight rather than starting a second one, and the run reads it
through the `QUEUED_MESSAGES` tool-context key. `cancel(requestId)` stops a run; `onShutdown()` lets
in-flight runs finish on a graceful shutdown.

## Listeners: how a surface follows a run

`AgentResponseListener` is the whole of the reporting contract. Every method has a default, so
implement the ones you need:

| Hook | When |
| --- | --- |
| `onStart(AgentRunRegistry)` | Before the run begins — the last point at which it can be given tool-context entries, extra listeners, a question handler or a todo handler |
| `onSubscribe`, `onModel(model)` | The run has started; which model it resolved to |
| `onContent(contentSoFar)` | The answer **so far**, not the latest delta — a surface that appends will double the text |
| `onReasoning(reasoningSoFar)` | The same, for a reasoning model's visible thinking |
| `onUsage(model, usage)` | Token usage, including a child run's usage reported to its parent |
| `onKnowledgeRetrieved(refs)` | What the knowledge base contributed to this turn |
| `onSubagent(event)` | A subagent starting, progressing or finishing |
| `onMessageQueued`, `onQueuedMessageRead` | A message arrived mid-run, and was picked up |
| `onError(throwable)` | The run failed |
| `onFinished(outcome)` | Exactly once per run, however it ended |
| `shouldContinue()` | Return false to stop consuming, which cancels the run |

Attached to a request, a listener covers that run. Declared as a `@Bean`, it covers **every** run —
which is how a surface takes part in runs it did not initiate: a scheduled task firing, a triage run
waking on an alert, a subagent its own agent started. `AgentRunRegistry`, handed to `onStart`, is
where a bean-scoped listener contributes per-run state:

```java
@Component
class MySurfaceListener implements AgentResponseListener {

  @Override
  public void onStart(final AgentRunRegistry registry) {
    registry.addToolContext("myTicket", ticketFor(registry));
    registry.addQuestionHandler(myQuestionHandler);
    registry.addResponseListener(new MyProgressView());
  }
}
```

`registry.abort(reason)` refuses the run outright, which is how a surface enforces its own
preconditions without a tool of its own.

## Asking the user a question

Asking normally **ends the turn**. The ask tool persists a `PendingQuestion`, returns no answers,
and the run stops; the answer arrives later as a *new* `AgentRequest` on the same `conversationId`.
That is the shape any asynchronous surface has — a chat message, a form in a card.

A surface that can answer within the call — a terminal, a blocking RPC — implements the
`SynchronousQuestionHandler` marker alongside `QuestionHandler`, and the turn continues with the
answers in hand instead. There is no third mode: do not write a handler that blocks a run's thread
waiting for an answer that arrives on another one.

Two independent gates gate the tool at all: a question handler has to exist for the run, and
`app.ai.tools.ask-user-question.enabled` has to be true. `...ttl` is how long an unanswered question
stays answerable.

## Scenarios

`AgentScenario` is the one gate over what a run is and what it may do. It is an interface rather
than an enum so that your own scenarios are first-class:

```java
public interface AgentScenario {
  default boolean conversationMemory() { return true; }   // read and append chat memory
  default boolean offers(Object tool) { return true; }    // is this @AgentTool bean offered?
  default boolean knowledgeRetrieval() { return true; }   // consult the knowledge base first
}
```

`BuiltInScenarios` holds the ones shipped here:

| Scenario | What it is | What it withholds |
| --- | --- | --- |
| `CHAT` | Somebody is talking to the agent | Nothing |
| `SCHEDULED_TASK` | A task firing on its own schedule | `ScheduledTaskTool` — a run that fires on a schedule must not be able to schedule more, which is how one task becomes a growing pile |
| `SUBAGENT` | A run another run asked for, whose answer is a tool result | `SubagentTools` and `ScheduledTaskTool`; and no conversation memory in either direction, since a subagent is given its task in full and must not write turns nobody said into the history |

`spring-agent-events` adds `SituationTriageScenario` for a run woken by something the agent
observed rather than by a person.

Writing your own is the ordinary way to make a new kind of run:

```java
public enum MyScenarios implements AgentScenario {
  BATCH_IMPORT {
    @Override public boolean conversationMemory() { return false; }
    @Override public boolean offers(final Object tool) {
      return !(tool instanceof ScheduledTaskTool) && !(tool instanceof PublishFileTool);
    }
  }
}
```

Two things follow from `offers` taking the tool object rather than a name. Your scenario can rule on
tools this runtime ships, and a scenario shipped here can rule on yours — the annotation deliberately
carries no scenario attribute, because an annotation attribute cannot have an interface type and
that would have confined gating to the built-in enum.

What a scenario **cannot** do is withhold the identity a run acts as. A run that assumes a user id
gets that user's file sandbox, credentials and personal MCP servers whatever its scenario says; that
is why the events module insists on an `owner-user-id` of the agent's own for runs woken by
attacker-authored text.

## Tools

A bean annotated `@AgentTool` has its Spring AI `@Tool` methods offered to the agent. The annotation
is allowed on a `@Bean` factory method too, for a type from a library that cannot be annotated:

```java
@AgentTool
@Component
public class ProfileTool {

  @Tool(name = "MyProfile", description = "The profile of the person in this conversation.")
  public Profile myProfile(final ToolContext context) {
    final var userId = ToolContexts.require(context, ToolContexts.USER_ID);
    ...
  }
}
```

Per-request identity reaches a tool through the tool context, under typed keys in
`core/tools/ToolContexts.java` — `REQUEST_ID`, `USER_ID`, `CHAT_ID`, `CHAT_TYPE`, `ROOT_MESSAGE_ID`,
`REPLY_MESSAGE_ID`, `GROUP_ID`, `TENANT_ID`, `QUEUED_MESSAGES`. Read them through the keys
(`ToolContexts.get` / `require`), not by string.

### What a run is actually offered

`AgentToolsProvider.compose(...)` assembles the set once per request out of:

- the `@AgentTool` beans in the context, minus whatever the scenario keeps out;
- filesystem and todo tools bound to that user's home;
- the ask tool, when a handler exists and the property allows it;
- that user's skills;
- the MCP servers that user owns or has been given, connected for this request and closed after it;
- the callbacks of every `ToolCallbackProvider` bean, which is how application-wide MCP servers
  configured under `spring.ai.mcp.client.*` reach the model — Spring AI publishes them as such a
  bean but never wires it into a `ChatClient` itself. Those clients belong to the context and are
  never closed by a run.

Because the set is open-ended and can grow between one turn and the next, turn on Spring AI's
tool-search advisor (`spring.ai.chat.client.tool-search-advisor.enabled`) unless you know your tool
set is small: it retrieves the few tools a turn needs instead of sending the model all of them.

### Cross-cutting behaviour

For anything that has to wrap every tool call — auditing, rate limiting, rewriting or truncating a
result, updating a progress card — implement `ToolCallInterceptor` (`core/tools/interceptors/`)
rather than touching the tools. The custom `ToolCallingManager` wires every such bean in.
`LargeResponseInterceptor` is the shipped example: over `app.ai.tools.max-result-chars` it writes
the result to the user's workspace and hands the model the path, for every tool alike, so a tool of
your own should return what it has rather than cap it first.

### The tools that ship

Roughly: filesystem (`Read`, `Write`, `Edit`), `TodoWrite`, memories (`MemoryView`, `MemoryCreate`,
`MemoryInsert`, `MemoryStrReplace`, `MemoryRename`, `MemoryDelete`), skills (`ListSkills`,
`WriteSkillFile`, `DeleteSkill`, `DeleteSkillFile`), MCP registration (`AddMcpServer`,
`ListMcpServers`, `RemoveMcpServer`, `ShareMcpServer`, `UnshareMcpServer`), credentials
(`SetCredential`, `ListCredentials`, `DeleteCredential`), scheduling (`CreateScheduledTask`,
`ListScheduledTasks`, `UpdateScheduledTask`, `CancelScheduledTask`), subagents (`StartSubagent`,
`WaitForSubagent`, `CancelSubagent`), publishing (`PublishFile`, `UpdatePublishedFile`,
`RenewPublishedFile`, `UnpublishFile`), knowledge (`SearchKnowledge`, `IndexKnowledge`,
`ListKnowledgeBase`, `UpdateKnowledgeScope`, `DeleteKnowledge`), media (`GenerateImage`,
`RecognizeImage`, `TranscribeAudio`), `CurrentDateTime`, `AskUserQuestion`, and — with a shell
module and `app.ai.tools.shell.type` set — `Bash`, `BashOutput`, `KillShell`,
`RestartShellContainer`.

## Where a user's files live

Every run carries a user id, and that id — not the process — owns the state. Under
`app.storage.location` each identity has a home with `memories/`, `skills/`, `workspace/` and
`artifacts/`, and the filesystem tools are confined to those roots, so one user's agent cannot read
another's files. A request that names a `groupId` or a `tenantId` also reaches the group's and the
tenant's homes, which is how a group chat has knowledge and skills of its own; `{homeDirs}` in the
system prompt is what tells the model which homes this run can see and who else can read what it
writes there.

`UserWorkspaceFactory.forRequest(userId, groupId, tenantId)` is the entry point if your own code
needs the same paths; `HomeDir` names the subdirectories.

Published files (`PublishFile`) are served by core's `ShareController` under
`/share/{visibility}/{userId}/{token}/**`, with the public half deliberately reachable without a
login — a published link carries a token this application checks itself, and expires.
`app.ai.tools.publish-file.base-url` is the origin those links are spelled with; `app.storage.base-url`
and `cdn-url` are the separate thing that serves an uploaded file directly.

## Watching what other systems do

Not everything the agent reacts to is a message addressed to it. `core/observing/` is the contract
for the other case, and core ships no implementation of it:

- **`Observation`** — one thing a surface saw that nobody asked about: an alert that fired, an issue
  somebody opened, a message in a group chat the bot was not addressed in. It carries a `source`
  (the name policy is configured under), a `deliveryId` (the transport's idempotency key, and the
  whole definition of what counts as a redelivery), a `kind`, a `correlationKey` (what groups
  observations into one situation, computed in code and never by the model), a title, a summary, the
  raw payload, and a `Route`.
- **`Route`** — where a run about this may talk, and in whose scope. Resolved whole, never field by
  field: an observation that knows its own chat must not pick up a tenant from configuration meant
  for another source.
- **`EventIntake`** — somewhere an observation goes. Every implementation in the context is given
  every observation, so consuming the same events twice for different reasons is ordinary. Intakes
  must be cheap, must not block on the model, and must not throw to mean "not mine".
- **`EventIntakes`** — the fan-out, always a bean. A transport calls it once and needs no error
  handling: one intake failing must not stop another, or stop the transport from acknowledging.

A transport therefore depends only on core, and nothing that consumes observations depends on a
transport. Reporting one is a single call:

```java
eventIntakes.observe(
    Observation.builder()
        .source("my-system")
        .deliveryId(delivery.id())        // stable across a redelivery, different for real news
        .kind("incident.opened")
        .correlationKey("incident:" + incidentId)
        .title(incident.title())
        .summary(oneLine(incident))
        .payloadJson(raw)
        .route(Route.builder().chatId(chatId).chatType("group").build())
        .build());
```

`spring-agent-events` is the intake that makes something of them: it correlates observations into
situations by their key, debounces (every arriving observation pushes the deadline out, which is
what turns a thousand alerts into one run), applies a cooldown and a maximum debounce, and then
wakes a triage run that decides for itself whether anything is worth saying. Take it plus one
`spring-agent-integration-*` module per system you receive webhooks from, or write your own intake
for anything else you want done with the same events.

Everything about it is off by default and per source: `app.events.enabled` gates the module, and a
source not named in `app.events.sources` is dropped at the door, so an endpoint nobody set a secret
for refuses everything.

## The knowledge base

Retrieval over user data lives behind the `KnowledgeBase` SPI in `core/knowledge/` —
`index`/`search`/`list`/`delete`/`move`, scoped by `KnowledgeScope` (owner, group, tenant).
`spring-agent-rag-milvus` is the implementation that ships. Core registers the knowledge tools only
when a `KnowledgeBase` bean exists, so taking that module is what decides a deployment has one at
all; `app.ai.rag.enabled` turns automatic retrieval back off.

This is a different thing from `spring.ai.vectorstore.type`, which backs the **tool-search index**
only. The two are deliberately independent: a deployment can run the tool index in the heap and the
knowledge base in Milvus.

Scoping is one definition, `KnowledgeScopeFilter`, used for retrieval and listing alike, and a
filter clause is only ever emitted for a non-blank identity — a blank one would match every document
that stores a blank there, which is every other user's.

## The system prompt and other prose

`app.ai.system-prompt` has a surface-neutral default that lives in core as
`core/prompts/system-prompt.md` and its per-locale siblings. Override the property to give the agent
your own persona and house rules. It is rendered per request over a fixed variable set — `{userId}`
`{chatId}` `{chatType}` `{messageId}` `{threadId}` `{parentId}` `{mentions}` `{replyFormat}`
`{homeDirs}` — and naming a variable that is not supplied fails the render, so a replacement has to
carry every one of them. `PromptVariablesContributor` is how a bean of yours adds more.

`{replyFormat}` is filled by whichever surface the answer lands on, with how it wants the answer
written. Implement it for your own surface rather than putting formatting rules in the system prompt.

Text the agent writes itself — as opposed to what the model produced — is localized through a
`MessageSource` (`CoreMessages` over `core/messages*.properties`, contributed to
`spring.messages.basename` by `MessagesDefaults`). Do not hardcode such strings in a module of your
own; ship a bundle, and either name it in `spring.messages.basename` — core's is appended after
whatever you name — or build a `MessageSource` of your own for it, as `FeishuMessages` does, so that
two modules are not fighting over one basename. `app.locale`
also chooses the language tool descriptions are rewritten into on the way to the model, from
`core/prompts/tools/<ToolName>_<locale>.md` and `core/tools_<locale>.properties`.

## Persistence

`app.persistence.type` — `jpa` (default, SQLite out of the box) | `mongodb` | `redis` — selects
repositories *and* the Spring AI chat memory repository together, through
`@ConditionalOnPersistenceBackend`.

One domain model serves every backend: the records in `core/dao/models/` carry JPA, MongoDB and
Redis mapping annotations at once, which works because an annotation whose type is absent at runtime
is discarded on reflection. Schema is owned by the application (`ddl-auto: update`); there is no
Flyway or Liquibase.

Redis needs Redis 8 or Redis Stack, and a server configured to *keep* what it is given
(`maxmemory-policy noeviction`, plus AOF or RDB). These are the agent's own records, not a cache: a
Redis provisioned the usual way for caching will evict a stored credential or an unfired scheduled
task and say nothing.

## Native image

Both runtime switches are `@Conditional` and are evaluated during AOT, so in a native image they
are **build-time** decisions baked by `-PnativeBackends`; the environment variable is inert in the
binary and has to agree with what was baked. New code that needs reflection, resources or proxies
needs its hints registered in a `RuntimeHints` class pulled in with `@ImportRuntimeHints`, or the
binary breaks at runtime while the JVM build passes.

## Module reference

| Module | What it adds |
| --- | --- |
| `spring-agent-core` | The runtime, the built-in tools, the SPIs. Backend-agnostic |
| `spring-agent-persistence-jpa` | Relational storage, SQLite by default |
| `spring-agent-persistence-mongodb` | MongoDB storage |
| `spring-agent-persistence-redis` | Redis storage; needs Redis 8 or Redis Stack |
| `spring-agent-tools-shell-kubernetes` | A disposable per-user sandbox Pod for the shell tools |
| `spring-agent-tools-shell-docker` | The same sandbox on a local Docker daemon |
| `spring-agent-events` | Correlates observations into situations and wakes the agent for the ones worth an opinion; serves `/events/webhooks/<source>` |
| `spring-agent-integration-github` | Reads GitHub webhook deliveries as observations |
| `spring-agent-integration-gitlab` | The same for GitLab |
| `spring-agent-integration-grafana` | The same for Grafana alert notifications |
| `spring-agent-integration-feishu` | Feishu/Lark chats and cards as an agent surface, plus its docs, sheets, base and wiki tools |
| `spring-agent-rag-milvus` | The knowledge base, and the only implementation of core's `KnowledgeBase` |

Adding a module decides nothing on its own. `app.ai.tools.shell.type` decides the shell and defaults
to `none`; `app.events.enabled` decides the events receiver and defaults to false; `app.ai.rag.enabled`
decides automatic retrieval. Each of those defaults to off because turning it on opens something —
a sandbox, an HTTP path, a second vector store — that a deployment should decide on rather than
inherit from its classpath.
