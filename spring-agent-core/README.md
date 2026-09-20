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
| `tools` | `@AgentTool`, `AgentToolsProvider`, `ToolContexts`, `ScopeTarget`, `HomeDir`/`UserWorkspaceFactory`, the built-in tools, `tools/interceptors/` and the custom `ToolCallingManager` |
| `dao` | `dao/models/` — the one domain model every backend shares — and `dao/repo/`, the repository contracts each `spring-agent-persistence-*` module implements |
| `observing` | `Observation`, `Actor`, `Route`, `EventIntake`, `EventIntakes` — how a run starts without anybody talking. Core ships no implementation; see [docs/events.md](../docs/events.md) |
| `knowledge` | The `KnowledgeBase` SPI and `KnowledgeScopeFilter`, implemented by [`spring-agent-rag-milvus`](../spring-agent-rag-milvus/README.md) |
| `memory` | The agent's file-based memory in all three scopes: `MemoryTools`, `MemoryScopes`, `MemoryFiles` |
| `notify` | `Notifier` — say something to a chat with no run behind it; how a conversation is handed between surfaces |
| `identity`, `security` | Who a run is, and what an `app.ai.admins` member may do |
| `storage`, `share` | A user's home under `app.storage.location`, and `ShareController` publishing a file |
| `scheduling` | Scheduled tasks: a schedule is a column on the task, and each occurrence is won by exactly one replica |
| `usermodels` | Bring-your-own-model: sealed endpoints a person registers, and the `/config` machinery around them. `ProviderChatClients` and `BuiltinModels` are contracts here — a `spring-agent-provider-*` module implements them, one per protocol; `UserChatClients` is core's own dispatcher over whichever are on the classpath, which is what lets a row name its protocol |
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

- **Scope is a tool parameter**, `own` | `group` | `tenant`, parsed by `tools.ScopeTarget` rather
  than an enum of memory's own. That enum's `named` is the single list of accepted spellings,
  including the `company` synonym, and a second copy is a second thing to drift — the failure being
  a word `IndexKnowledge` accepts and `MemoryCreate` silently reads as `own`. Omitted on a read it
  means every reachable scope; omitted on a write, the requester's own; misspelt, refused either way.
- **Who may write a shared memory** is `MemoryScopes.writable`, keyed on the group root being
  present rather than on `chatType` — the same thing every other scoped decision here keys on. A
  one-to-one chat is a room with one witness, so it reads the tenant's memory and cannot add to it.
  On top of that the tenant's is an `app.ai.admins` member's to change at all, unless
  `app.ai.non-admin-tenant-writes` is on — `config.TenantWrites`, which answers the same question
  for skills and for the knowledge base, so that the company scope has one rule rather than three.
  Reading it is untouched either way, and the block the model is given says which of the two
  read-only reasons it is: one is answered by asking again in a group chat, and the other by
  nothing the run can try.
- **Nothing creates a shared directory on a read.** `HomeDir.folderPath` exists for that: it names
  where a folder would be without making it, which `folder` cannot do and `dirs` will not answer for
  an absent one. Only `MemoryCreate` and a rename's destination create.
- **`MemoryFiles` departs from upstream in four places**, all consequences of a root being shared:
  symlinks are resolved and re-checked against the root and writes open `NOFOLLOW_LINKS` (a link in a
  group's `memories/` would otherwise expose one member's home to the whole chat); mutations take a
  per-path lock, since a group's `MEMORY.md` now has concurrent writers; a directory in a shared
  scope is not deleted whole; and one call's output is capped per scope.

## TodoWrite, and why it is a fork

`tools/TodoWriteTool.java` is a fork of the library's tool of the same name, and the only difference
is the tool method's parameter: it takes the list of items, where upstream takes the `Todos` wrapper.
Spring AI builds a tool's input schema from the method's parameters, keyed by parameter name, and
inlines each parameter's own type — and upstream's parameter is called `todos` while the wrapper's
single component is also called `todos`. So the only payload the schema accepted was
`{"todos": {"todos": [...]}}`; models send `{"todos": [...]}`, which fails to deserialize, and the
description names no fields, so a run burns turns guessing. `TodoWriteToolTest` asserts the flat
schema.

`Todos`, `Todos.TodoItem`, `Todos.Status` and `TodoEventHandler` are byte-identical to upstream's,
and the tool name and description are too — so the surfaces that render a todo list only changed an
import, and `core/prompts/tools/TodoWrite_zh_CN.md` keeps applying. The fix is
[upstream PR #74](https://github.com/spring-ai-community/spring-ai-agent-utils/pull/74): when it is
released, delete this class, point every import back at `org.springaicommunity.agent.tools`, and put
`TodoWriteTool` back in `CoreEveryToolTranslatedTest`'s list of the library's tools — the package
scan finds it while it is core's own, and stops when it is not. It stays in
`aot/AgentToolsRuntimeHints`' list either way: nothing declares it a bean, so AOT never sees it.

## Finding a file, and what confines it

Four of the library's tools take a path the model wrote: `Read`, `Write` and `Edit` from
`FileSystemTools`, and `Glob`, `Grep` and `ListDirectory` beside them. `AgentToolsProvider.build`
constructs all of them per request and hands each the same allow-list — every home the request
reaches, and nothing else — so what one of them may touch is what the others may.

Two things about the three search tools are worth knowing:

- **The confinement is the library's, and it is new.** `AllowedDirectories`, the check all four
  share, arrived in `spring-ai-agent-utils` 0.12.0; before it these three answered about any path
  this application's operating system user could reach, which on the servers here is the database,
  the configuration and every other user's home. That is why the version carries a comment in
  `gradle/libs.versions.toml` calling it load-bearing, and why `AgentToolsProviderSearchToolsTest`
  asserts a refusal rather than trusting the builder call to mean something.
- **Each is given the requester's own home as its working directory**, which is where a call that
  omits `path` lands. Left unset the library falls back to wherever the JVM was started — this
  repository's own checkout on a laptop — and every such call would come back refused by the check
  above, which reads to the model as the tool being broken rather than as the path being wrong. The
  group's and the tenant's homes are named in the prompt, so a run that wants one asks for it by
  path.

Their English descriptions are overridden in `core/prompts/tools/`, for the reason web search's is:
upstream's point the model at an `Agent` tool and a `Task` tool that do not exist here, and describe
`path` as defaulting to the current working directory, which is exactly what this module changed.

## Web search, and why it is off

`config/WebSearchToolsConfiguration` publishes the library's `BraveWebSearchTool` as `WebSearch`,
and only where `app.ai.tools.web-search.brave.api-key` holds something. The key is the whole of the
switch — there is no `enabled` beside it, because a search with no subscription token is not a mode
— so this is the `ConditionalOnNonBlankProperty` case the rules below describe, and getting it wrong
would offer a tool whose every call comes back unauthorised.

Two things about it are worth knowing before changing anything here:

- **The English description is overridden**, in `core/prompts/tools/WebSearch.md`. Upstream's text
  addresses Claude by name and claims the search is US-only, neither of which is true of every
  deployment; the base file is the supported way to correct English this module cannot edit, since
  every locale inherits what it does not override. The replacement is also where the model is told
  that a result is evidence and never an instruction.
- **It is one of the two library search tools**, and the other — web fetch — is deliberately not
  published. Nothing here constructs it, so it stays out of `aot/AgentToolsRuntimeHints`' list as
  well: fetching a page is what a shell backend or an MCP server is for, and neither of those arrives
  by putting core on a classpath.

Operators configure it in
[`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml); what turning it
on costs is written down in [docs/advanced.md](../docs/advanced.md).

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
- `usermodels`' `ProviderChatClients` and `BuiltinModels`, and `agent/ProviderRejection`, are the only
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

**The conversation window is core's to set, the store is not.** `config/ChatMemoryConfiguration`
builds the `ChatMemory` bean so that `app.ai.memory.window` means something — Spring AI's
auto-configuration takes the builder's 20 messages and reads no property, and 20 is about two
exchanges once every tool call and result is a message of its own. Where the messages land is still
upstream's business and still follows `app.persistence.type`: the repository auto-configurations back
off from a `ChatMemoryRepository` and not from a `ChatMemory`, so this bean takes whichever one the
backend put in the context.

**What a run thought outlives it.** `agent/ReasoningRecordingListener` is a bean listener, so it
covers every surface's runs the way `ChatSessionTrackingListener` does: a foreground run belonging to
a conversation leaves one `dao/models/ChatReasoning` row when it finishes, keyed by its `requestId`
and holding the whole of what the endpoint reported it thinking. Unattended runs write nothing — a
subagent's thinking belongs to the tool call that started it — and so does a run on an endpoint that
reports no reasoning, which is most of them, so a row existing means there is something to read.
`app.ai.reasoning.store` turns it off; nothing is truncated when it is on.

The row also carries a digest of the answer, and the reason is worth knowing before anybody proposes
putting the `requestId` on the turn instead: **no chat-memory backend stores message metadata**.
Spring AI's JDBC repository, which serves `jpa` and `redis`, has five fixed columns, and
`MongoChatMemoryRepo` dropped metadata deliberately after a provider's own object in it made whole
conversations unreadable. So a replayed turn carries no run id and cannot without forking all three
repositories. See `ChatReasoning`.

**One domain model serves every backend.** The records in `dao/models/` carry JPA, MongoDB *and* Redis
mapping annotations at once. That works because an annotation whose type is absent at runtime is
discarded on reflection, which is why core declares those persistence APIs `compileOnly`.

**A scenario decides what a run is offered, not the annotation.** `@AgentTool` carries no scenario — an
annotation attribute cannot have an interface type, which would confine gating to the built-in enum.
`AgentScenario.offers(tool)` is asked about every tool a run is composed of and says yes by default.
That is how `SCHEDULED_TASK` keeps `ScheduledTaskTool` out of a run that fires on a schedule, and how
`SUBAGENT` keeps both it and `SubagentTools` out — which caps subagent depth at one with no counter to
get wrong.

**Every tool, and not the `@AgentTool` beans alone**, which is what lets a scenario write an
allow-list instead of the usual few exclusions. `KNOWLEDGE_BASE` is the one that does: it names the
knowledge-base tools, the memory tools and the vision tools, and receives those and nothing else — no
sandbox, no todo tool, no skills, no MCP. There are two overloads because a tool that arrives already
built as a `ToolCallback` — an MCP server's, a skill's, the ask where the answer comes later — has no
type of its own to rule on, only the name on its definition. `offers(ToolCallback)` delegates to
`offers(Object)` by default, so a scenario wanting none of them still writes one method. **Java picks
an overload statically**, so `composeWith` keeps its `List<ToolCallback>` typed until it has filtered
it; merging the callbacks into the tool list first would route every one of them to the wrong
overload, silently.

What `tools()` still buys over an `offers` that refuses everything is not reach but cost: it is asked
before anything is built, so the run is spared the MCP fan-out. Refusing through `offers` composes the
same empty set, having dialled every server first.

**A person can choose the scenario from the chat.** `AgentScenario.memoNames()` declares the words
that select it — `/kb`, `/knowledge-base`, `/knowledge_base` — and declaring none, the default, is
what keeps `SUBAGENT` and `SCHEDULED_TASK` unreachable by typing. `ScenarioMemos` collects them from
`BuiltInScenarios` and from every `AgentScenario` bean, refuses to start where two claim one word,
and matches case-insensitively as a whole token anywhere in the message — anywhere, because in a
group chat the bot has to be mentioned first and because a memo is as often typed at the end of a
thought as at its start, and a whole token so that `/kb/notes/2024` stays a path and `/kb.md` a
filename. The matched word is taken out of the prompt, with a comma, semicolon or colon it was typed
with, since that punctuation belongs to the interjected word rather than to the sentence. Read
`ScenarioMemos` before changing the rule.

**`toolSearch()` says how a run's tools reach the model.** The tool-search advisor replaces the
tool set rather than indexing it alongside — the model gets `toolSearchTool` and a paragraph about
looking, and sees nothing else until it searches. Worth it for a run reaching a few hundred MCP
tools; a wasted round trip for one composed of four, which is why `KNOWLEDGE_BASE` and `ONE_OFF`
decline it. `ONE_OFF` is the one that was actually wrong: `tools()` empties the composition, but the
advisor puts its own tool into the options and its own suffix into the system message, so a run
documented as having nothing in between was reaching the model with exactly one tool.

**Nothing here names `ToolSearchToolCallingAdvisor`**, and that is worth knowing before reading
`SpringAgent`. Spring AI's `ToolSearchAdvisorAutoConfiguration` registers its builder under the
declared type `ToolCallingAdvisor.Builder<?>`, so the builder core injects silently *is* the
searching one wherever the deployment configured a tool search, and is the plain one otherwise. Core
registers the advisor explicitly — `advisors.add(...)`, rather than letting `ChatClient` do it, for
the two reasons the comment there gives — but it never chooses the class, and deliberately does not:
reproducing that builder means copying seven property mappings and an eviction strategy, which drift
the moment upstream adds one.

There is **no per-request switch on that advisor**, so this is not a flag passed down — `SpringAgent`
holds two advisors and hands the run one of them. The second is a plain `ToolCallingAdvisor` on the
same `ToolCallingManager` bean, so interception, localization and the tool limits are unchanged;
only the search is gone. Both are singletons, and that is load-bearing for the searching one, which
caches the fingerprint of the tool set it last indexed per key. Where the deployment configured no
tool search the builder bean is already plain, and `SpringAgent` uses it for both answers rather
than building a redundant twin.

**`interactive()` is what a surface asks before drawing anything for a run**, and before registering
a question handler — which is what decides whether the agent is offered the ask at all. False by
default and true on `CHAT` and `KNOWLEDGE_BASE`. It replaced four surfaces each comparing
`scenario() == BuiltInScenarios.CHAT`, which made every new scenario invisible until somebody
remembered those lines.

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
