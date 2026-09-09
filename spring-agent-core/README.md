# spring-agent-core

> **Audience:** a developer embedding the agent or changing the runtime. The full API walkthrough —
> dependencies, minimum configuration, every SPI with examples — is [docs/sdk.md](../docs/sdk.md).
> This page is the map of the module.

The agent runtime and every SPI, backend-agnostic. Everything else in this repository is a backend, a
surface or an application over what is here.

## One entry point

An integration builds an `AgentRequest` — a record plus builder carrying user and chat identity, an
`AgentScenario`, prompt variables, tool context and listeners — and hands it to `SpringAgent`
(`agent/SpringAgent.java`). Everything after that happens inside: tool composition, system-prompt
rendering, the Spring AI `ChatClient` call, MCP client lifecycle, listener fan-out, cancellation.

Integrations never touch `AgentToolsProvider`, MCP clients or Reactor directly. If something needs to
happen around every run, it is a listener or an interceptor here, not a special case in a surface.

The fan-out is on a virtual thread of the run's own rather than on the worker Spring AI emits from,
which is what makes a listener free to wait on a write — see the `notifications` scheduler in
`SpringAgent`. A callback that blocks costs the run its own lag and no other run anything.

## The packages

| Package | What lives there |
| --- | --- |
| `agent` | `SpringAgent`, `AgentRequest`, `AgentResponseListener`, `AgentScenario`, `BuiltInScenarios`, the run registry |
| `tools` | `@AgentTool`, `AgentToolsProvider`, `ToolContexts`, the built-in tools, `tools/interceptors/` and the custom `ToolCallingManager` |
| `dao` | `dao/models/` — the one domain model every backend shares — and `dao/repo/`, the repository contracts each `spring-agent-persistence-*` module implements |
| `observing` | `Observation`, `Actor`, `Route`, `EventIntake`, `EventIntakes` — how a run starts without anybody talking. Core ships no implementation; see [docs/events.md](../docs/events.md) |
| `knowledge` | The `KnowledgeBase` SPI and `KnowledgeScopeFilter`, implemented by [`spring-agent-rag-milvus`](../spring-agent-rag-milvus/README.md) |
| `memory` | The agent's file-based memory in all three scopes: `MemoryTools`, `MemoryScopes`, `MemoryFiles` |
| `notify` | `Notifier` — say something to a chat with no run behind it; how a conversation is handed between surfaces |
| `identity`, `security` | Who a run is, and what an `app.ai.admins` member may do |
| `storage`, `share` | A user's home under `app.storage.location`, and `ShareController` publishing a file |
| `scheduling` | Scheduled tasks: a schedule is a column on the task, and each occurrence is won by exactly one replica |
| `usermodels` | Bring-your-own-model: sealed endpoints a person registers, and the `/config` machinery around them. `UserChatClients` and `BuiltinModels` are contracts here — a `spring-agent-provider-*` module implements them |
| `advisors`, `logging`, `config`, `aot` | Spring AI advisors, structured logging, auto-configuration, native-image hints |

## Memory, and why it is a fork

`memory/` is a fork of `spring-ai-agent-utils`' `AutoMemoryTools` and `AutoMemoryToolsAdvisor`. That
pair takes one memory directory at construction, and a request here has three — the person's own,
the group chat's, the tenant's — so there was nowhere to put the other two, and shared memory was
reachable only by the model remembering to `Read` a path. The tool names and parameter names are
byte-identical to upstream's, which is what keeps `core/prompts/tools/Memory*` and the parameter
bundle working; the class names drop the `Auto` prefix because the library's are still on the
classpath for the five other tools it supplies.

Four decisions in there are load-bearing, and each has its reasoning at the code:

- **Scope is a tool parameter**, `own` | `group` | `tenant`, parsed by `knowledge.KnowledgeScope.Target`
  rather than an enum of memory's own. That enum's `named` is the single list of accepted spellings,
  including the `company` synonym, and a second copy is a second thing to drift — the failure being
  a word `IndexKnowledge` accepts and `MemoryCreate` silently reads as `own`. Omitted on a read it
  means every reachable scope; omitted on a write, the requester's own; misspelt, refused either way.
- **Who may write a shared memory** is `MemoryScopes.writable`, keyed on the group root being
  present rather than on `chatType` — the same thing every other scoped decision here keys on. A
  one-to-one chat is a room with one witness, so it reads the tenant's memory and cannot add to it;
  an `app.ai.admins` member is exempt.
- **Nothing creates a shared directory on a read.** `HomeDir.folderPath` exists for that: it names
  where a folder would be without making it, which `folder` cannot do and `dirs` will not answer for
  an absent one. Only `MemoryCreate` and a rename's destination create.
- **`MemoryFiles` departs from upstream in four places**, all consequences of a root being shared:
  symlinks are resolved and re-checked against the root and writes open `NOFOLLOW_LINKS` (a link in a
  group's `memories/` would otherwise expose one member's home to the whole chat); mutations take a
  per-path lock, since a group's `MEMORY.md` now has concurrent writers; a directory in a shared
  scope is not deleted whole; and one call's output is capped per scope.

## Rules this module keeps

**No persistence backend and no model provider, ever.** `checkRuntimeClasspathIsolation` (wired into
`check`, defined in `buildSrc/.../springagent.classpath-isolation.gradle`, configured at the bottom of
`build.gradle`) fails the build if Hibernate, the Mongo driver, Jedis, Milvus, fabric8, the OpenAI SDK
and friends reach core's runtime classpath. If it fails, a dependency became `api` or grew a new
transitive — fix the dependency, do not widen the allow-list.

**Where the model comes from is somebody else's business.** Core names no provider: it injects Spring
AI's `ChatModel`, `EmbeddingModel`, `TranscriptionModel` and `ImageModel`, and a
`spring-agent-provider-*` module publishes them — see
[`spring-agent-provider-openai`](../spring-agent-provider-openai/README.md). Three consequences worth
knowing before changing anything near a model:

- `ModelToolsConfiguration` registers `GenerateImage`, `RecognizeImage` and `TranscribeAudio` **only
  where the deployment has that model**, ordered with `@AutoConfiguration(afterName = ...)` naming each
  provider's class as a string. A provider missing from that list silently loses those three tools; a
  deployment with no such model gets no tool rather than one that always fails.
- `usermodels`' `UserChatClients` and `BuiltinModels`, and `agent/ProviderRejection`, are the only
  three contracts a provider has to implement itself. Everything else it offers is a Spring AI
  interface. Read each one's javadoc for why Spring AI has no counterpart — briefly: its models are
  built once at startup from configuration, and none of "build a client for an endpoint somebody typed
  into a chat", "ask an endpoint what it serves" and "read what an endpoint said when it refused" is
  that.
- Anything `ImageOptions` cannot carry travels in `ImageMessage`'s metadata map, keyed by
  `tools/ImageGenerationMetadata`. That is what keeps `GenerateImage` from naming a provider's own
  options type, and is why core needs no image SPI of its own.

**A setting spelled `${SOME_VAR:}` is present and empty when nobody set the variable.**
`@ConditionalOnProperty` calls that configured — it matches anything that is not the literal
`false` — so where the configuration *is* the switch, gate it with
`config/ConditionalOnNonBlankProperty` instead. `ConditionalOnUserModels` is the same lesson learned
about one key, and both provider modules' vision clients are the general case: gated the wrong way,
the client was built asking for a model named `""` and the endpoint's rejection read like a broken
gateway rather than a feature nobody turned on. Nothing fails at startup when this is got wrong,
which is the whole reason it is written down here.

**One domain model serves every backend.** The records in `dao/models/` carry JPA, MongoDB *and* Redis
mapping annotations at once. That works because an annotation whose type is absent at runtime is
discarded on reflection, which is why core declares those persistence APIs `compileOnly`.

**A scenario decides what a run is offered, not the annotation.** `@AgentTool` carries no scenario — an
annotation attribute cannot have an interface type, which would confine gating to the built-in enum.
`AgentScenario.offers(tool)` is asked about every `@AgentTool` bean and says yes by default. That is
how `SCHEDULED_TASK` keeps `ScheduledTaskTool` out of a run that fires on a schedule, and how
`SUBAGENT` keeps both it and `SubagentTools` out — which caps subagent depth at one with no counter to
get wrong.

**A run is offered exactly what `compose(...)` returns.** Tools from elsewhere have to be collected
there too: alongside the `@AgentTool` beans and the user's own MCP servers it appends the callbacks of
every `ToolCallbackProvider` bean in the context, which is how application-wide MCP servers under
`spring.ai.mcp.client.*` reach the model. Those clients belong to the context and are never closed by
a run; only the per-request ones in `McpTools` are.

**A user-registered MCP server's tools are named after a prefix**, `<prefix>_<tool>`, and the
prefix is the one the user chose (`AddMcpServer`'s `toolPrefix`, stored on `McpServerConfig`) or a
hash of the server name when they chose none — `McpClientFactory.toolPrefix` is the only place that
decides which. The hash is unique by construction, including for names differing only in non-ASCII
text; a chosen prefix is not, so two servers one caller can reach may not share one. That is checked
at registration and again in `compose(...)`, which refuses the run rather than dropping one of the
two: a dropped server means a call meant for staging answered by production.

**Per-request identity reaches a tool through `toolContext`**, with typed keys in `tools/ToolContexts.java`.
Read them through those keys rather than by string.

**A log line says which run it belongs to**, through the MDC keys in `logging/RunMdc.java` —
`requestId`, `conversationId`, `userId`. A run crosses several threads (assembled on the caller's,
streamed on Reactor's, reported to its listeners on a virtual thread of its own, waited out on
another, tool-called from inside the chain), so this is two mechanisms rather than one:
`RunMdc.of(...)` opens an explicit scope that restores what was there when it closes, and
`MdcThreadLocalAccessor` plus
`Hooks.enableAutomaticContextPropagation()` (in `RunContextPropagationConfiguration`, off with
`app.logging.run-context-propagation: false`) makes Reactor carry that MDC across the boundaries —
which is what tags Spring AI's own log lines, since they are written inside the run's chain on
threads nothing here can reach. A surface opens a scope of its own only where it logs about a run
*outside* one of those threads: its receive handler, and the executor it resumes an answered
question on. What the pattern in front of a log line is comes from `logging.pattern.correlation` in
each application's `application.yaml`, and `logging/JsonLogLayout.java` writes the whole MDC as
fields when `LOG_APPENDER=STDOUT_JSON`.

**No hardcoded prose.** Text the agent writes for itself goes through `CoreMessages` over
`messages*.properties`, in every language the module ships. That includes what a tool *answers*
with, which is the half that gets forgotten: the model reads a tool result and writes the user's
answer out of it, so a string literal there is an English sentence in the middle of a conversation
held in another language. A module with prose of its own gets a bundle of its own rather than a key
in core's — `ModuleMessages` is that, ready made, with the host-locale fallback already off for the
reason `ModuleToolTexts` gives at length.

**The i18n checks are published as test fixtures**, under `me.kezhenxu94.springagent.core.i18n` in
`src/testFixtures`, so a module gets them by subclassing rather than by copying two hundred lines of
reflection. Add `testImplementation testFixtures(project(':spring-agent-core'))` and subclass
`AbstractToolTextsParityTest` (every translation names a real tool),
`AbstractEveryToolTranslatedTest` (every real tool has one), `AbstractToolTranslationsCompleteTest`
(no stub, no placeholder), `AbstractLocalizedToolsEndToEndTest` (what reaches the model is the
translation, descriptions and schema alike), `AbstractMessageBundleParityTest` (the bundles say the
same things in every language) and `AbstractPromptFilesTranslatedTest` (every page of prose has a
sibling). They are not published: the fixtures variant is skipped in the Maven publication.
