# Building an agent with the SDK

`spring-agent` is a Spring Boot 4 / Spring AI library before it is an application. This document is
for the Java developer embedding it: what to depend on, what to configure, how to start a run, and
what the extension points are. If you only want to run the applications that ship here, read the
[README](../README.md) instead; if you want to change this repository, read
[contributing.md](contributing.md). [architecture.md](architecture.md) draws what the paragraphs
below describe, if a picture first is easier.

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
- [A browser as your surface](#a-browser-as-your-surface)
- [The system prompt and other prose](#the-system-prompt-and-other-prose)
- [Persistence](#persistence)
- [Native image](#native-image)
- [Module reference](#module-reference)

## Dependencies

Take `spring-agent-core` plus exactly one persistence module. Core is backend-agnostic and carries
no database driver; the persistence module is what supplies both the agent's own repositories and
the conversation-memory repository, and those two have to come from the same place. (On jpa and
redis that is Spring AI's own repository; on mongodb the module substitutes one of its own, because
upstream's returns a turn in an undefined order — see `MongoChatMemoryRepo`.)

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
server's [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml). That file is
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
| `knowledgeRetrieval` | What the run's automatic retrieval should look at — a `KnowledgeScope`, an optional narrowing `Filter.Expression`, and an optional fixed query. Null on every request a surface builds, and then the scope is the run's own identity and the query is the message. For a run whose knowledge base is chosen by configuration rather than by who is asking |
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
| `CHAT` | Somebody is talking to the agent | `FiringScheduledTaskTool` — nothing is firing, so there is no task for it to act on |
| `SCHEDULED_TASK` | A task firing on its own schedule | `ScheduledTaskTool` — a run that fires on a schedule must not be able to schedule more, which is how one task becomes a growing pile. It keeps `FiringScheduledTaskTool`, which acts only on the task that is firing: it can end that task or give it its next time, so a run can honour "until X happens" and "remind me again later" without the number of tasks ever growing |
| `SUBAGENT` | A run another run asked for, whose answer is a tool result | `SubagentTools`, `ScheduledTaskTool` and `FiringScheduledTaskTool`; and no conversation memory in either direction, since a subagent is given its task in full and must not write turns nobody said into the history |

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

A tool declared `@AgentTool(admin = true)` is additionally withheld unless the run's user is named
in `app.ai.admins`. That is on the user id alone, so an administrator holds them in their own scheduled
tasks and subagents too — both act on a brief that same administrator wrote.

Which makes the **identity** the boundary, and the thing to be careful with. A run assumes a user id
and holds whatever that id holds; no scenario can narrow it afterwards. So an identity that reads
text written by strangers must never be an administrator — `spring-agent-events` refuses to start
when a source's `owner.user-id` is listed in `app.ai.admins`, because at that point nothing could
tell a triage run from an ordinary run by the same owner.

Two things follow from `offers` taking the tool object rather than a name. Your scenario can rule on
tools this runtime ships, and a scenario shipped here can rule on yours — the annotation deliberately
carries no scenario attribute, because an annotation attribute cannot have an interface type and
that would have confined gating to the built-in enum.

What a scenario **cannot** do is withhold the identity a run acts as. A run that assumes a user id
gets that user's file sandbox, credentials and personal MCP servers whatever its scenario says; that
is why the events module insists on an `owner.user-id` of the agent's own for runs woken by
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

- the `@AgentTool` beans in the context, minus whatever the scenario keeps out and minus the
  admin-only ones unless the run's user is named in `app.ai.admins`;
- filesystem and todo tools bound to that user's home;
- the ask tool, when a handler exists and the property allows it;
- that user's skills;
- the MCP servers that user owns or has been given, connected for this request and closed after it;
- the callbacks of every `ToolCallbackProvider` bean, which is how application-wide MCP servers
  configured under `spring.ai.mcp.client.*` reach the model — Spring AI publishes them as such a
  bean but never wires it into a `ChatClient` itself. Those clients belong to the context and are
  never closed by a run.

It also contributes two advisors, since both are tools in everything but shape:
`AutoMemoryToolsAdvisor`, which adds the memory tools and the paragraph that explains them, and
`AutoSkillToolsAdvisor` (`core.advisors`), which counts a turn's tool calls from inside the
tool-calling loop and, past `app.ai.tools.skills.tool-call-threshold`, appends a paragraph asking the
model to offer the user a skill made of what it just worked out. The second writes nothing itself and
registers no tools — the offer is prose in the reply, and the skill is written by `WriteSkillFile` on
a later turn if the user agrees. It is only added to runs that were given the skill tools, and only
while `app.ai.tools.skills.offer-after-expensive-runs` is on. Both are ordinary advisors with
builders, so an application assembling its own `ChatClient` can use either directly; the skill one
takes only a prompt `Resource` (a template over `TOOL_CALL_COUNT`), a threshold and an order, and
holds no type of this project's own.

Because the set is open-ended and can grow between one turn and the next, turn on Spring AI's
tool-search advisor (`spring.ai.chat.client.tool-search-advisor.enabled`) unless you know your tool
set is small: it retrieves the few tools a turn needs instead of sending the model all of them.

### Cross-cutting behaviour

For anything that has to wrap every tool call — auditing, rate limiting, rewriting or truncating a
result, updating a progress card — implement `ToolCallInterceptor` (`core/tools/interceptors/`)
rather than touching the tools. The custom `ToolCallingManager` wires every such bean in.
`LargeResponseInterceptor` is the shipped example: over `app.ai.tools.max-result-chars` it writes
the result to the user's workspace and hands the model the path, for every tool alike, so a tool of
your own should return what it has rather than cap it first. It writes JSON indented, because the
`Read` tool truncates a line past 2000 characters and a serialized tree is one line.

The other half of that is `ToolInputFileRefs`, which lets an argument be given as `@file:<path>` or
`@file:<path>#<JSON Pointer>` naming such a saved result, so a payload whose only destination is the
next call never passes through the model at all. It is not a `ToolCallInterceptor`: expansion
happens in `InterceptingToolCallback` between the interceptor chain and the delegate, so every
interceptor — and so every surface showing what a call was given — sees the reference the model
wrote rather than the payload it stood for.

**Which parameters accept a reference is an allow-list, and it is a security boundary.** Contribute
one from your own module as a `ToolInputFileRefs.Params` bean, mapping tool name to parameter names,
and say so in the parameter's own `@ToolParam` description — that is how the model finds out. Add a
parameter to it only where the argument is something a previous call produced and this call passes
along unchanged. Expanding one the model composes gains nothing and turns that tool into a
file-reading primitive: a run triaging an observation acts on text written by whoever caused the
event, and one injected sentence is enough to aim a message-sending tool at a file of memories. A
reference on any other parameter is refused rather than written through as text; `@@file:` escapes
for a value that really is that text.

That manager is also where `spring.ai.tools.limits.*` is read: it is built here rather than taken
from Spring AI's auto-configuration, which backs off, so the limits are applied by hand in
`SpringAgentCoreAutoConfiguration`. Core raises the two that bound a turn — `ToolCallingDefaults`
sets `max-calls-per-tool-default` and `max-total-tool-calls` to 200 each, against upstream's 40 and
150 — because a run that reaches for the same tool a hundred times is normal here, and hitting a
limit ends the turn mid-thought rather than answering less well. Set either property to override,
or to `-1` to remove that limit entirely.

### The tools that ship

Roughly: filesystem (`Read`, `Write`, `Edit`), `TodoWrite`, memories (`MemoryView`, `MemoryCreate`,
`MemoryInsert`, `MemoryStrReplace`, `MemoryRename`, `MemoryDelete`), skills (`ListSkills`,
`WriteSkillFile`, `DeleteSkill`, `DeleteSkillFile`), MCP registration (`AddMcpServer`,
`ListMcpServers`, `RemoveMcpServer`, `ShareMcpServer`, `UnshareMcpServer`), credentials
(`SetCredential`, `ListCredentials`, `DeleteCredential`), scheduling (`CreateScheduledTask`,
`ListScheduledTasks`, `UpdateScheduledTask`, `CancelScheduledTask`, plus `StopThisScheduledTask` and
`RescheduleThisScheduledTask` offered only to a firing, for the task it is a firing of), subagents (`StartSubagent`,
`WaitForSubagent`, `CancelSubagent`), publishing (`PublishFile`, `UpdatePublishedFile`,
`RenewPublishedFile`, `UnpublishFile`), knowledge (`SearchKnowledge`, `IndexKnowledge`,
`ListKnowledgeBase`, `UpdateKnowledgeScope`, `DeleteKnowledge`, plus `ListOwnerKnowledgeBase` and
`SearchOwnerKnowledge` over another identity's own knowledge base, for an administrator), media
(`GenerateImage`, `RecognizeImage`, `TranscribeAudio`), `CurrentDateTime`, `AskUserQuestion`, and
— with a shell module and `app.ai.tools.shell.type` set — `Bash`, `BashOutput`, `KillShell`,
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
  raw payload, a `Route`, and an `actor`.
- **`Observation.actor`** — an `Actor`: who caused the event, and whether the transport could vouch
  for it being them. Build it with `Actor.authenticated(name)` where the delivery's authentication
  covers the name, `Actor.claimed(name)` where you could read a name but not check it, and leave the
  field null where the event names nobody at all. Both factories return null for a blank name, so a
  source can pass whatever it found.

  `spring-agent-events` matches `actor.authenticatedName()` — never `actor.name()` — against
  `app.events.sources.<name>.trusted-actors`, and drops what does not match before anything is
  recorded. A claim therefore admits nobody: it costs nothing to report and gains an intake of your
  own the ability to say *who* was at the door rather than only that somebody was, which is what a
  source like email exists to tell you. What must never happen is a name you merely read being
  passed to `Actor.authenticated` — that turns every allow-list in the deployment into a bypass,
  since the attacker then writes both sides of the comparison. GitHub names the actor inside a body
  its HMAC has already covered, so that one is authenticated; an email `From:` header is a string
  anybody can type, so that one is a claim whatever it says.

  An actor is never routing, never the identity a run assumes, and never rendered into a prompt as a
  fact — who caused an event is *also* still evidence, and that half belongs in `summary`.
- **`Route`** — where a run about this may talk, and in whose scope. An observation's route is its
  own and is never filled in from configuration: a chat message knows the chat it came from, an alert
  knows nowhere, and a run about the latter reaches people through what it was told to do rather than
  through an address it was handed.
- **`EventIntake`** — somewhere an observation goes. Every implementation in the context is given
  every observation, so consuming the same events twice for different reasons is ordinary. Intakes
  must be cheap, must not block on the model, and must not throw to mean "not mine".
- **`EventIntakes`** — the fan-out, always a bean. A transport calls it once and needs no error
  handling: one intake failing must not stop another, or stop the transport from acknowledging.
- **`Notifier`** (`core/notify/`) — the other direction: says something to a `Route` with no run
  behind it. Core ships no implementation and a deployment without one simply has nowhere to send
  these. It exists for the one thing a run cannot report about itself — that it failed, or never
  started — so an implementation must not need a model, an agent or a live request to do its work.
  A surface implements it as a `@Bean`; a caller takes an `ObjectProvider<Notifier>` and does nothing
  when there is none.

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
        .summary(oneLine(incident))              // who caused it goes here, as evidence
        .actor(Actor.authenticated(caller))      // Actor.claimed(caller) if you cannot vouch for it
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

`retrieverFor(scope, extra)` takes an optional `Filter.Expression` that narrows a read to part of
what a scope may reach — `retrieverFor(scope)` is the same call with no narrowing. It composes
*under* the scope filter (`KnowledgeScopeFilter.readableBy(scope, extra)`), so it can only ever
narrow and never widen, and both sides are parenthesised because a converter renders `AND` as
`left && right` and adds parentheses for nothing but a `Filter.Group`. This is what `spring-agent-
events` uses to read a source's playbook: `AgentRequest.knowledgeRetrieval` names the owning scope,
the filter and a fixed query, and `AgentToolsProvider` builds the run's `RetrievalAugmentationAdvisor`
from them. The query steers retrieval only — the advisor augments the *original* user message with
what it found — so pinning it does not replace what the model is asked.

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
whatever you name — or build a `MessageSource` of your own for it, as `FeishuMessages` and
`WebMessages` do, so that
two modules are not fighting over one basename. `app.locale`
also chooses the language tool descriptions are rewritten into on the way to the model, from
`core/prompts/tools/<ToolName>_<locale>.md` and `core/tools_<locale>.properties`.

## A browser as your surface

`spring-agent-integration-websocket` is a whole surface as a dependency: a single-page UI, the REST
endpoints behind it, and runs streamed live over STOMP. It is what `spring-agent-app-webui` is made
of, and taking it gives an application of your own the same conversation list, transcript, live run
view, file uploads, task list and question forms.

```groovy
implementation 'me.kezhenxu94:spring-agent-integration-websocket:<version>'
```

Unusually for a surface here, it may sit beside a chat surface. It registers one
`AgentResponseListener` bean, which claims a run only when the request's `chatType` is `web`, and it
fills no `{replyFormat}` and is nobody's `Notifier` — so a Feishu or Slack bot can gain a browser to
read its own conversations in without the collisions described under
[the module reference](#module-reference).

Four things it needs from you:

1. **A `SecurityFilterChain`.** The module deliberately defines none, because who may log in, which
   OAuth2 registration the sign-in button goes to and which of *your* other paths are public are not
   its decisions. It contributes `WebAuthoritiesMapper`, the rule that admits a person whose tenant
   or workspace matches `app.web.auth.tenant-id`; wire it into your `oauth2Login`. Copy
   `spring-agent-app-webui`'s `SecurityConfigurer` as the reference: the page's own assets and
   `/share/public/**` are `permitAll`, `/api/me` is `authenticated` so a refused person can be told
   *why*, and everything else — the `/ws/runs` handshake included — requires the role.
2. **CSRF on, not off.** A `POST /api/conversations/{id}/messages` makes the agent act with the
   logged-in person's credentials, files and MCP servers, so a forgeable request is one that puts
   words in their mouth. Use `CookieCsrfTokenRepository.withHttpOnlyFalse()` — the page reads the
   cookie and echoes it in `X-XSRF-TOKEN` — and call
   `setCsrfRequestAttributeName(null)` on the request handler, or the deferred token means no cookie
   is written until the *first POST has already been refused*.
3. **`@EnableScheduling`.** `RunJournals` sweeps finished runs on a timer. Boot auto-configures a
   scheduler but never the annotation that makes `@Scheduled` mean anything, so without it
   `app.web.journal.retention` stops meaning anything.
4. **A `ChatSessionRepo` and a `ChatMemory`**, which any persistence module already gives you. A
   conversation's `userId` is the same identity every other surface uses, so history lines up across
   them.

`app.web.*` is the module's own configuration: `title` (what the deployment calls itself),
`messages` (below), `auth.provider` and `auth.tenant-id`, `journal.retention`/`journal.max-runs`
(how long and how much of a finished run stays replayable — it is memory, so it is bounded twice
over), `question.ttl` and `locale`.

**Saying it in your own words.** `app.web.messages` is a list of message-bundle basenames consulted
before the module's own `web/messages`, in order, key by key. Naming one key in a bundle of yours
overrides that one string and leaves the rest — so you never take a copy of the module's bundle and
then silently lose whatever is added to it in a later version:

```yaml
app.web.messages: com/acme/agent-messages
```

```properties
# com/acme/agent-messages.properties, and _zh_CN.properties beside it
app-title=Acme Agent
```

`app-title` is what the deployment is called — the browser tab, the sidebar brand, the heading
before a conversation has a title. It is the one string a *reader* sees rather than a person being
refused, and overriding it per bundle is how a deployment whose name is written differently in each
language gives itself that name: `app.web.title` is deliberately one name for every language, since
a name somebody chose is not something this server can translate. `/api/me` reports the resolved
name for every supported language at once, because the language switcher in the page never asks the
server again. In a native image, register your bundle as a resource — the module registers only its
own (`WebRuntimeHints`).

The streaming contract, if you are writing another client against it: connect STOMP to `/ws/runs`,
subscribe to `/app/runs/{requestId}` with a `from` header carrying the last sequence number you
hold. You are sent a `replay` event whose `through` is the last sequence number that already
existed, then the backlog, then live events; `gone` means there is no journal for that id — evicted,
or not yours, deliberately indistinguishable. Frames are `RunEvent` as JSON: `{seq, type, data}`.

## Persistence

`app.persistence.type` — `jpa` (default, SQLite out of the box) | `mongodb` | `redis` — selects
repositories *and* the conversation-memory repository together, through
`@ConditionalOnPersistenceBackend`, so the two can never come from different backends.

One domain model serves every backend: the records in `core/dao/models/` carry JPA, MongoDB and
Redis mapping annotations at once, which works because an annotation whose type is absent at runtime
is discarded on reflection. Schema is owned by the application (`ddl-auto: update`); there is no
Flyway or Liquibase.

Adding a model or a query means updating all three implementations in
`spring-agent-persistence-*`, and on Redis an `@Indexed` field is the *definition* of what can be
filtered on rather than a tuning knob — it has no query planner.

Two methods on those contracts are not queries at all but *conditional writes*, and a fourth backend
has to implement them as such. `ProcessedMessageRepo.claim` takes a unit of work for the caller, and
`ScheduledTaskRepo.claimNextFireAt`/`initNextFireAt` move a scheduled task on from one occurrence to
the next. In each the boolean return **is** the concurrency control: an implementation that reads
and then writes lets two replicas both through, which is the case they exist for. What each backend
uses is an insert `on conflict do nothing`, an `@Modifying` update with the predicate in its `where`
clause, a Mongo update carrying the expected value in its filter, or a Redis `SET NX`.
`AbstractPersistenceBackendTest` is where that contract is asserted, once per backend.

`ChatSessionRepo` is the one contract a surface is likely to want for itself. It records which
`conversationId`s belong to which user, and nothing else: chat memory can enumerate conversation ids
but knows nothing about who owns them, so a "your conversations" listing built on it would show
every user everyone else's. What was *said* in one stays in chat memory, read back by the same id —
so there is one copy of a transcript rather than a second one for the UI to let drift.

`SeenUpdateRepo` is the other one, for a surface that greets people. It holds one number per person:
the version of the last release note they were shown, so a surface can work out what is new *for
them* rather than announcing everything to everybody. Read and written by the person's id on that
surface, and deliberately with no delete — forgetting would greet somebody a second time with what
they have already read. `spring-agent-integration-feishu` is the surface that uses it today; the
notes themselves are that module's, not core's.

Redis needs Redis 8 or Redis Stack, and a server configured to *keep* what it is given
(`maxmemory-policy noeviction`, plus AOF or RDB). These are the agent's own records, not a cache: a
Redis provisioned the usual way for caching will evict a stored credential or an unfired scheduled
task and say nothing.

## A user's own chat model

Every run goes through the one `ChatClient` bean unless the person asking has registered another,
and that indirection is `core/usermodels/UserChatClients#forUser`, which `SpringAgent` asks on the
way into each run. Absent unless `app.ai.user-models.encryption-key` is set — the feature is gated
on being able to store an API token sealed rather than on a flag of its own — so `SpringAgent`
takes it through an `ObjectProvider` and falls back to the application's client when nothing is
there.

Three facts about Spring AI shape this, and any consumer building a `ChatClient` of their own runs
into the same ones:

- `OpenAiChatModel` resolves `baseUrl`, `apiKey` and `timeout` once, in `build()`, into an
  `OpenAIClient` it then holds final. Runtime options carrying a base URL are **ignored**; only the
  model name is read per request. A different endpoint is therefore a different `ChatModel`, and
  cannot be expressed as different options.
- Runtime options **replace** a model's defaults rather than merging with them —
  `buildRequestPrompt` takes the supplied ones whole when there are any. Anything built from
  scratch silently drops everything under `spring.ai.openai.chat`, including
  `stream-options.include-usage`, whose absence shows up not as an error but as runs that report no
  token usage and so no cost. `UserChatClients` starts from `defaultChatModel.getOptions().mutate()`
  and overrides only what makes the endpoint different: base URL, key, model, and the reasoning
  effort the user chose.
- Tools are called by the `ToolCallingAdvisor` `SpringAgent` registers on the prompt, not by the
  model, so a hand-built `ChatModel` needs no `ToolCallingManager`. It does need the context's
  `OpenAiHttpClientBuilderCustomizer` beans, or its provider rejections stay unreadable.

Clients are cached per resolved endpoint rather than per user — two users on the same gateway share
one connection pool, and an edited endpoint is simply a key that is not in the cache. The cache is
bounded, because the table behind it is one users can write to.

The pieces a consumer would extend or reuse:

| Type | What it is for |
| --- | --- |
| `UserModelRegistry` | The rows, and the one place a token is sealed or opened. `activate` clears every other row of that owner *before* setting the new one, so an interrupted switch leaves none activated rather than two — and none means the application's own model. `setEffort` rewrites one row's reasoning effort and nothing else, keeping the sealed token, which is the only way to change it: the token is never readable again. `setActiveEffort` applies one to whichever model the user is on, creating `DEFAULT_ROW` where that is the application's own. |
| `UserChatClients` | Resolving and caching the client, as above. Never throws: an endpoint that cannot be read is a fallback and a log line, because failing here would fail the run the user needs to fix it. `effortInForce` answers what a run for one user will actually be made with, which is what a surface must label its thinking panel from rather than the deployment's property. |
| `UserModelProbe` | The pre-save connection test — one tiny completion, since that exercises URL, token, model name **and** reasoning effort together where `GET /models` does not. |
| `BuiltinModels` | What the application's own endpoint reports it can serve, cached and best-effort; an empty list is an ordinary answer. |
| `ReasoningEfforts` | The efforts a user may choose, taken from the OpenAI SDK's own list rather than typed — Spring AI takes `reasoning_effort` as a bare string, so a typo is an endpoint that fails on every message. Three states: absent leaves the deployment's setting, a value sends it, `NOT_SENT` stops it being sent at all. |
| `AesGcmSealer` (`core/security/`) | AES-GCM with a fresh nonce per write, shared with the shell credential store. Each caller brings its own key so a leak is contained to one feature. |
| `UserModelConfig` (`core/dao/models/`) | The row. A **blank `baseUrl` means the application's own endpoint** and a **blank `model` means its configured model** — that is how choosing one of its models, or only an effort for it, records itself without copying the application's key per user. Such rows are named with a `@` prefix, which user-supplied names may not contain; `@` alone is `UserModelRegistry.DEFAULT_ROW`, the row that carries an effort for the application's model without pinning which model that is. |

A surface that wants to offer this needs no agent run for it: `spring-agent-integration-feishu`'s
`/config` card and `spring-agent-integration-slack`'s `/config` modal both go straight to
`UserModelRegistry`. That is deliberate rather than incidental — a model that has stopped answering
would otherwise break the only route to changing it.

The **embedding** model is not configurable this way, and should not be made so: the knowledge base
is shared and its collections are built with one embedding model.

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
| `spring-agent-integration-email` | A watched IMAP mailbox as observations. Not a webhook: it dials out and holds the connection, so it carries its own `app.email.enabled` on top of `app.events.enabled`. Reports every message it reads, with an `actor` only where DKIM vouched for the sender, so an intake of your own can hear about mail from a stranger; refuses to start without a `trusted-actors` list, which is what keeps such a message out of a triage run |
| `spring-agent-integration-feishu` | Feishu/Lark chats and cards as an agent surface, plus its docs, sheets, base and wiki tools, and drive import/export |
| `spring-agent-integration-slack` | Slack channels and Block Kit messages as an agent surface: streaming replies, a stop button, an asynchronous question form, greetings, chat observation and the message/channel/file tools. Written against Bolt, the Slack SDK's own application framework, over a Socket Mode connection |
| `spring-agent-rag-milvus` | The knowledge base, and the only implementation of core's `KnowledgeBase` |
| `spring-agent-integration-websocket` | A browser as an agent surface: a single-page UI, the REST endpoints behind it, and runs streamed live over STOMP/WebSocket. Contributes no `SecurityFilterChain` — the including application owns that and wires in this module's `WebAuthoritiesMapper` — and needs `@EnableScheduling` on it |
| `spring-agent-app-webui` | The deployable that is nothing but the runtime and the module above; not published, it ships as an image |

**Only one chat surface may be on a classpath at a time.** `spring-agent-integration-feishu` and
`spring-agent-integration-slack` each register a `@Bean AgentResponseListener` that claims every
run, a `PromptVariablesContributor` that fills `{replyFormat}` for every request, and a `Notifier`.
Core merges contributors with `putAll` and `spring-agent-events` resolves the notifier with
`getIfAvailable()`, so with both installed the listeners answer for each other's runs, the reply
format is whichever registered last, and the notifier lookup throws. None of that fails at startup.
Pick the surface your application has; if you need both, run two applications, which is what
`spring-agent-app-feishu` and `spring-agent-app-slack` are.

`spring-agent-integration-websocket` is not a third entry in that list, and adding it to an
application that already has a chat surface is not the mistake the rule is about. It does register a
`@Bean AgentResponseListener` — `WebRunListener`, so that a scheduled task firing or a subagent
starting is still visible in the page — but that one claims a run only when the request's `chatType`
is `web`, which is what every request this module builds carries and no other surface's does. It
contributes no `PromptVariablesContributor`, so it never decides `{replyFormat}`, and no `Notifier`.
So a Feishu bot can gain a browser to read its conversations in by depending on it — which is the
reason it is published at all — as long as the application's own `SecurityFilterChain` reaches the
page's paths. See `spring-agent-app-webui`'s `SecurityConfigurer` for the arrangement, and note that
CSRF must be on: a `POST /api/conversations/{id}/messages` makes the agent act with the logged-in
person's credentials, files and MCP servers.

Adding a module decides nothing on its own. `app.ai.tools.shell.type` decides the shell and defaults
to `none`; `app.events.enabled` decides the events receiver and defaults to false; `app.ai.rag.enabled`
decides automatic retrieval. Each of those defaults to off because turning it on opens something —
a sandbox, an HTTP path, a second vector store — that a deployment should decide on rather than
inherit from its classpath.
