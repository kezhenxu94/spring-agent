# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, test, lint

Gradle multi-module build (`./gradlew`, no Maven — `.github/workflows/maven.yaml` only *publishes* Maven artifacts). The `Makefile` wraps the three commands used every day:

```sh
make                       # ./gradlew build
make test                  # ./gradlew test
make TESTS='ChatMemoryRedisTest' test        # a single test class
make TESTS='*.SpringAgentTest.someMethod' test   # a single method
make lint                  # ./gradlew spotlessApply
```

Scoping to one module is much faster than the aggregate task: `./gradlew :spring-agent-core:test --tests 'SpringAgentTest'`.

Watch out: `failOnNoMatchingTests = false` is set in the common conventions, so a typo'd `--tests` pattern passes silently rather than failing. Confirm the test actually ran.

Formatting is Spotless with `googleJavaFormat().reflowLongStrings()`; `spotlessCheck` runs as part of `build`. Run `make lint` before committing.

Other tasks:

```sh
./gradlew :spring-agent-app-feishu:bootRun   # the Feishu server
./gradlew :spring-agent-app-slack:bootRun    # the Slack server
./gradlew :spring-agent-app-cli:bootRun      # the command line (stdin/tty wired for JLine)
./gradlew :spring-agent-app-webui:bootRun    # the browser UI, on :8080
./gradlew :spring-agent-app-feishu:bootBuildImage   # container image (Paketo buildpack, no Dockerfile)
./gradlew :spring-agent-app-cli:nativeCompile -Pnative
```

`-Pnative` is required for any native task: the GraalVM plugin is applied conditionally so that a plain `bootBuildImage` does not silently turn into a native build (see the comment in `spring-agent-app-feishu/build.gradle`).

Tests need a running Docker daemon — `AbstractIntegrationTest` starts MongoDB and Redis containers via Testcontainers. Unit tests sit beside the class they cover; cross-cutting integration tests live in `spring-agent-app-feishu/src/test`. A behaviour that must hold for every persistence backend goes in `AbstractPersistenceBackendTest`, which is run once per backend by `PersistenceJpaTest`/`PersistenceMongoTest`/`PersistenceRedisTest` — add the assertion there rather than to one backend's test. That class also covers Spring AI's conversation-memory repository, which is not one of core's contracts but is selected by the same switch — `chatMemoryPreservesTheOrderOfATurn` is what caught MongoDB returning a turn answer-first.

`.github/workflows/build.yaml` runs `./gradlew build` on every push to `main` and every pull request against it, on a GitHub-hosted Ubuntu runner for the Docker daemon Testcontainers needs. The other three workflows publish only. It carries no path filters on purpose, so that it can be made a required check — read the comment at the top of the file before adding one. Verification is still cheapest locally: run `make build` before pushing.

## Tech stack

- Java: bytecode targets **21** (`options.release = 21`), built with a **GraalVM 25** toolchain because `native-image` ships with it. Do not use APIs newer than 21.
- Spring Boot 4, Spring AI 2.x, Spring Shell 4 (CLI), Lombok, JUnit 5 + Testcontainers.
- Exact versions live in `gradle/libs.versions.toml`; several pins there carry load-bearing comments explaining why the BOM version is wrong. Read the comment before changing a version.
- Lombok is configured with **fluent accessors** (`lombok.config`), so getters are `foo()`, not `getFoo()`.

## Modules

```
spring-agent-core                                 the agent runtime and every SPI; backend-agnostic
spring-agent-persistence-{jpa,mongodb,redis}
spring-agent-tools-shell-{kubernetes,docker}
spring-agent-events                               observations -> situations -> a triage run; serves /events/webhooks/<source>
spring-agent-integration-{github,gitlab,grafana}  webhook readers for spring-agent-events
spring-agent-integration-email                    a watched mailbox as observations; dials out, so app.email.enabled
spring-agent-rag-milvus                           the knowledge base; the only implementation of core's KnowledgeBase
spring-agent-provider-openai                      the OpenAI wire protocol, and so most gateways: chat, embeddings, transcription, images, per-user endpoints
spring-agent-provider-dashscope                   DashScope's own image API and vision endpoint; depends on provider-openai, since compatible-mode *is* that protocol
spring-agent-provider-google-genai                Gemini spoken natively: thinking levels, Gemini's embeddings, image models that edit from a reference
spring-agent-provider-anthropic                   Claude, chat only, from Anthropic's API or from a Vertex AI project; `spring.ai.anthropic.backend` picks
spring-agent-integration-{feishu,slack}           chats and cards / channels and Block Kit, as a surface
spring-agent-integration-websocket                a browser as a surface: the SPA, its REST endpoints, STOMP run streaming, the knowledge base and skills as pages
spring-agent-app-feishu                           deployable server whose surface is Feishu; depends on every optional module
spring-agent-app-slack                            the same server, with Slack as its surface instead
spring-agent-app-cli                              laptop command line; jpa + local shell only
spring-agent-app-webui                            the same server, with a browser as its surface
spring-agent-app-web-feishu                       both surfaces at once, so a conversation is handed between chat and browser
```

`spring-agent-core` must stay free of any persistence backend. This is enforced by `checkRuntimeClasspathIsolation` (wired into `check`, defined in `buildSrc/.../springagent.classpath-isolation.gradle`, configured at the bottom of `spring-agent-core/build.gradle`): it fails the build if Hibernate, the Mongo driver, Jedis, Milvus, fabric8 and friends reach core's runtime classpath. If that task fails, a dependency became `api` or grew a new transitive — fix the dependency, do not widen the allow-list.

## Architecture

**One entry point for running the agent.** An integration builds an `AgentRequest` (a record + builder: user/chat identity, `AgentScenario`, prompt variables, tool context, listeners) and hands it to `SpringAgent` (`core/agent/SpringAgent.java`). Everything after that — tool composition, system-prompt rendering, the Spring AI `ChatClient` call, MCP client lifecycle, listener fan-out, cancellation — happens inside `SpringAgent`. Integrations never touch `AgentToolsProvider`, MCP clients or Reactor directly.

**Integrations observe runs through `AgentResponseListener`.** Attached to a request it covers that run; declared as a `@Bean` it covers *every* run, which is how a surface takes part in runs it did not initiate (a scheduled task firing, say). `onStart(AgentRunRegistry)` is the hook for contributing per-run state.

**Tools** are beans annotated `@AgentTool` (allowed on a `@Bean` method too, for library types), whose Spring AI `@Tool` methods `AgentToolsProvider.compose(...)` assembles per request. `AgentScenario` gates which runs get the tool: it is an interface, `BuiltInScenarios` holds the ones shipped here, and a consumer can implement it and pass their own on the request. The annotation carries no scenario — an annotation attribute cannot have an interface type, which would confine gating to the built-in enum — so the scenario decides: `AgentScenario.offers(tool)` is asked about every tool a run is composed of and says yes by default. That is how `SCHEDULED_TASK` keeps `ScheduledTaskTool` out of a run that fires on a schedule, and how `SUBAGENT` keeps both it and `SubagentTools` out — which is what caps subagent depth at one, with no counter to get wrong.

**Every tool, and not the `@AgentTool` beans alone**, which is what lets a scenario write an allow-list rather than the usual few exclusions — `KNOWLEDGE_BASE` names the knowledge-base, memory and vision tools and receives those and nothing else, no sandbox and no MCP. There are two overloads because a tool that arrives already built as a `ToolCallback` (an MCP server's, a skill's, the ask where the answer comes later) has no type of its own to rule on, only the name on its definition; `offers(ToolCallback)` delegates to `offers(Object)` by default. **Java picks an overload statically**, so `composeWith` keeps its `List<ToolCallback>` typed until it has filtered it — merge the callbacks into the tool list first and every one of them silently reaches the wrong overload. What `tools()` still buys over an `offers` that refuses everything is cost and not reach: it is asked before `build()`, so the run is spared the MCP fan-out, where refusing through `offers` dials every server and throws the callbacks away.

`AgentScenario` also decides whether a run uses conversation memory, whether it consults the knowledge base, and two things a person and a surface read. `memoNames()` declares the words that select the scenario from a chat — `/kb`, `/knowledge-base` — and declaring none, the default, is what keeps `SUBAGENT` and `SCHEDULED_TASK` unreachable by typing; `core/agent/ScenarioMemos.java` collects them from `BuiltInScenarios` and from every `AgentScenario` bean, refuses two scenarios claiming one word, and matches case-insensitively as a whole token *anywhere* in the message — anywhere because a group chat mentions the bot first and because a memo is as often typed at the end of a thought as at its start (`... failed? /kb, tell me something`), a whole token so that `/kb/notes/2024` stays a path and `/kb.md` a filename. The matched word goes, with a comma, semicolon or colon typed with it. `toolSearch()` says how a run's tools reach the model: the tool-search advisor *replaces* the tool set rather than indexing it alongside — the model gets `toolSearchTool` and a paragraph about looking, and sees nothing else until it searches — which is worth it for a run reaching a few hundred MCP tools and a wasted round trip for one composed of four, so `KNOWLEDGE_BASE` and `ONE_OFF` decline it. **Nothing in main source names `ToolSearchToolCallingAdvisor`**: Spring AI's autoconfiguration registers its builder under the declared type `ToolCallingAdvisor.Builder<?>`, so the builder core injects silently *is* the searching one where a tool search is configured and the plain one otherwise — core registers the advisor explicitly but never picks the class, because reproducing that builder means copying seven property mappings and an eviction strategy that drift when upstream adds one. That advisor has **no per-request switch**, so `SpringAgent` holds two and hands the run one: the configured (searching) one, or a plain `ToolCallingAdvisor` on the same `ToolCallingManager` bean, so only the search differs. Both are singletons — load-bearing for the searching one, which caches the fingerprint of the tool set it last indexed per key. `interactive()` is what a surface asks before drawing a card, a reply or a gutter, before abandoning a run whose rendering never appeared, and before registering a question handler, which is what decides whether the ask is offered at all; false by default, true on `CHAT` and `KNOWLEDGE_BASE`, and it replaced four surfaces each comparing `scenario() == BuiltInScenarios.CHAT`. Per-request identity reaches a tool through the `toolContext` map, with typed keys in `core/tools/ToolContexts.java` — read them through those keys rather than by string. Cross-cutting behaviour around tool calls goes in a `ToolCallInterceptor` (`core/tools/interceptors/`), which the custom `ToolCallingManager` wires in.

A run is offered exactly what `compose(...)` returns, so tools that come from elsewhere have to be collected there too: alongside the `@AgentTool` beans and the user's own MCP servers it appends the callbacks of every `ToolCallbackProvider` bean in the context. That is how application-wide MCP servers configured under `spring.ai.mcp.client.*` reach the model — Spring AI publishes them as such a bean but never wires it into a `ChatClient` itself. Those clients belong to the context and are never closed by a run; only the per-request MCP clients in `McpTools` are.

**Two runtime switches select beans by condition**, not by classpath alone:

- `app.persistence.type` — `jpa` (default, SQLite) | `mongodb` | `redis`, via `@ConditionalOnPersistenceBackend`. Chooses repositories *and* the conversation-memory repository together. On jpa and redis that is Spring AI's own; on mongodb it is `MongoChatMemoryRepo` in that module, because Spring AI's orders a turn by a millisecond timestamp it stamps itself and so returns it scrambled — read that class before touching it.
- `app.ai.tools.shell.type` — `none` (default) | `kubernetes` | `docker` | `local`, via `@ConditionalOnShellBackend`.

A third switch behaves the same way and is deliberately **not** one of ours: `spring.ai.model.*` picks the model provider. Spring AI gates every model auto-configuration it ships on `spring.ai.model.<kind>`, so naming a provider is what makes the others back off, and there is no `@ConditionalOnProviderBackend` to write — a second switch over the same decision would be a second thing to keep in step. Every kind is now named explicitly, and that is not style: Spring AI's conditions are all `matchIfMissing`, so with four providers on the classpath a kind naming nothing gets *every* provider and the application fails to start with two `ChatModel` beans. Embeddings take two keys with the same value — `spring.ai.model.embedding` is what the OpenAI auto-configuration reads and `spring.ai.model.embedding.text` is what the Google GenAI one reads — so naming one silences one provider and leaves the other matching by default. `openai` is right on DashScope too, whose chat, embedding and transcription endpoints *are* the OpenAI wire protocol, and `spring-agent-provider-dashscope`'s `DashScopeDefaults` points that client at DashScope from `spring.ai.dashscope.*`. Because the switch is per *kind*, mixing providers is supported rather than a workaround: DashScope embeddings under a Gemini chat model needs only the two blocks configured. Anthropic makes that the *only* way to run on it, since it serves chat and nothing else — no embeddings, no images, no transcription — so a Claude deployment always names somebody else for embeddings. `GoogleGenAiProviderCoexistenceTest` and `AnthropicProviderCoexistenceTest` pin all of it.

`spring-agent-provider-anthropic` carries the one provider switch that *is* ours, and it is not a second copy of the above: `spring.ai.model.chat` chooses which provider serves a kind of model, `spring.ai.anthropic.backend` chooses which host that provider's protocol is spoken to — Anthropic's API, or a Google Cloud project through Vertex AI. The request body is identical either way, which is why it is one module with a backend rather than two. Spring AI has no property for it because its auto-configuration hard-wires `AnthropicBackend`, so on `vertex` the module publishes the chat model itself, ordered `beforeName` Spring AI's so that its `@ConditionalOnMissingBean` backs off. Read `VertexAnthropicClients` before touching it: `AnthropicChatModel` builds a synchronous *and* an asynchronous client, defaulting each independently from `AnthropicSetup`, so a model handed only the first streams to `api.anthropic.com` with a key scraped from the process environment — an agent that works and bills the wrong account.

All three are evaluated during AOT, so in a **native image they are build-time decisions** baked by `-PnativeBackends` (see `springagent.native.gradle`); the environment variable is inert at runtime and must be set to agree with what was baked.

**A property spelled `${SOME_VAR:}` is present and empty when nobody set the variable**, and `@ConditionalOnProperty` calls that configured — it matches anything that is not the literal `false`. Where the configuration *is* the switch (a model name, a credential) gate it with `core/config/ConditionalOnNonBlankProperty` instead; `ConditionalOnUserModels` is the same lesson learned about one key, and both provider modules' vision clients depend on it. Getting this wrong does not fail at startup: it builds a client asking for a model called `""` and the endpoint's rejection reads like a broken gateway.

**Core names no model provider.** It injects Spring AI's `ChatModel`, `EmbeddingModel`, `TranscriptionModel` and `ImageModel`, and `ModelToolsConfiguration` registers the three tools that need one of the latter three only where a provider published it — a tool the model can see is a tool it will try, so an absent tool beats one that always fails. Two contracts a provider must implement itself live in `core/usermodels/`: `UserChatClients` and `BuiltinModels`, because Spring AI's models are all built once at startup from configuration and neither "build a client for an endpoint somebody typed into a chat" nor "ask an endpoint what it serves" is that. `core/agent/ProviderRejection` is a third, for reading what an endpoint said when it refused — that never survives to where core sees the failure. Anything `ImageOptions` cannot carry travels in `ImageMessage`'s metadata map, keyed by `core/tools/ImageGenerationMetadata`, which is what keeps core's `GenerateImage` from naming a provider's type.

**One domain model serves every backend.** The records in `core/dao/models/` carry JPA, MongoDB *and* Redis mapping annotations at once (`@Entity` + `@Document` + `@RedisHash`, both `@Id` flavours). This works because an annotation whose type is absent at runtime is discarded on reflection — which is also why core declares those persistence APIs `compileOnly`. Repository *contracts* live in `core/dao/repo/`; each `spring-agent-persistence-*` module implements them. When adding a model or a query, update all three implementations, and note that Redis has no query planner: an `@Indexed` field is the definition of what can be filtered on, not a tuning knob.

**Vector store** backs the tool-search index only, not retrieval over user data: `spring.ai.vectorstore.type` is `simple` (in-heap, mirrored to a JSON file) or `milvus`. Milvus is a dependency of the two server applications only, deliberately kept out of core.

**The knowledge base is a separate thing from that vector store**, and confusing the two is the easy mistake here. Retrieval over user data — what a user, group or tenant has asked the agent to remember — lives behind the `KnowledgeBase` SPI in `core/knowledge/`, implemented by `spring-agent-rag-milvus`, in its own Milvus collection with its own connection under `app.ai.rag.milvus.*`. It deliberately does not read `spring.ai.vectorstore.type`, so a deployment can run the tool index in the heap and the knowledge base in Milvus.

That module holds its `MilvusVectorStore` as a private field rather than publishing it as a bean. This is load-bearing: Spring AI's Milvus auto-configuration declares its own store `@ConditionalOnMissingBean`, so publishing a second one would make *that* back off and silently take the tool-search index's store with it. It also drops to the raw Milvus client for `list`, because no portable `VectorStore` interface can enumerate — which is the whole reason a knowledge base is a backend module rather than something core implements over any store.

Scoping is one definition, `core/knowledge/KnowledgeScopeFilter`, used both for retrieval and — via `MilvusFilterExpressionConverter` — for the raw listing query. Chunks carry `owner`/`group`/`tenant`, always all three, blank where they do not apply, and **a filter clause is only ever emitted for a non-blank identity**: a blank one would match every document that stores a blank there, which is every other user's. `KnowledgeScopeFilterTest` covers that case by name; read it before changing the filter.

Core registers the knowledge tools only when a `KnowledgeBase` bean exists, ordered with `@AutoConfiguration(afterName = ...)` naming the module's class as a string. Rename that class and the tools silently stop being registered.

Schema is owned by the application (`ddl-auto: update`) — there is no Flyway or Liquibase.

**Not every run starts with somebody talking to the agent.** `core/observing/` is the contract for the other case, and core ships no implementation of it: a transport reports an `Observation` (source, delivery id, kind, correlation key, evidence, and a `Route` saying where a run about it may talk) to `EventIntakes`, which hands it to every `EventIntake` bean, each independent and each isolated from the others' failures. That is why a transport — the Feishu integration, a webhook receiver — depends only on core, and nothing consuming observations depends on a transport. `spring-agent-events` is the intake that correlates observations into situations by their key, debounces, and wakes a triage run; the `spring-agent-integration-{github,gitlab,grafana}` modules each contribute one `WebhookSource` and nothing else. All of it is off unless `app.events.enabled`, and a source not named in `app.events.sources` is dropped at the door. Payload text is written by whoever caused the event: it is evidence, never routing and never instructions, and a triage run must assume an identity of the agent's own rather than a person's — a scenario cannot withhold the files, credentials and MCP servers that come with an identity.

**A browser is a surface like any other, with one thing of its own: the run journal.** `spring-agent-integration-websocket` holds every event a run emitted in a `RunJournal`, and an HTTP or websocket connection is only ever a *reader* of one. Opening a subscription starts nothing and dropping one stops nothing, which is what makes closing a tab safe — only pressing Stop cancels a run. A late or reconnecting browser says how far it got and is replayed from there, so replay is per-subscriber: `RunStreamSubscriptions` therefore writes STOMP frames straight to the asking session rather than publishing to a topic, which would hand one tab another's backlog. `RunJournal.attach` replays and registers the reader under one lock, because "send history then subscribe" loses what arrives in between and "subscribe then send history" sends some of it twice. CSRF is *on* in that surface's application, unlike the webhook servers': a POST there makes the agent act with the logged-in person's credentials, files and MCP servers.

**What a run thought is kept, because a journal is not.** A `RunJournal` is in memory and a Feishu card's thinking panel can be turned off, so reasoning used to end with the run that produced it. `core/agent/ReasoningRecordingListener` is a bean listener — every surface's runs, like `ChatSessionTrackingListener` — writing one `ChatReasoning` row per foreground run that belongs to a conversation, keyed by its `requestId` and uncapped; `app.ai.reasoning.store` turns it off, and a background run (a subagent, a scheduled check) writes nothing. The row also carries a **digest of the answer**, and that is not redundancy: a replayed transcript comes out of chat memory, and no backend stores message metadata — Spring AI's JDBC repository has five fixed columns and `MongoChatMemoryRepo` dropped metadata deliberately after a provider's own object in it made whole conversations unreadable — so a replayed turn carries no run id and cannot be given one without forking all three repositories. `ChatSessions.transcript` therefore pairs a row to a round by digesting each answer, hangs the id on the **user** row that opened the round (where a live run draws its fold), and pairs nothing where two rounds answered identically, since showing one of them the other's reasoning would be wrong and convincing. The text is fetched only when somebody opens the fold — it is routinely longer than the rest of the conversation — and the endpoint that serves it checks both that the conversation is the caller's *and* that the row belongs to that conversation.

It is also the only surface that reaches a store **without a run in between**, and it does so four times — for the knowledge base, for skills, for memories and for MCP servers. `KnowledgeController` puts core's `KnowledgeBase` SPI behind `/api/knowledge` so a person can list, search, read, add to and correct what the agent remembers rather than asking the model to do it with the knowledge tools. Reading one document's stored text is what `KnowledgeBase.read` exists for — no vector store can be asked for a document's own content without a query, which is why enumeration and reading both live on the SPI rather than in core. The scope is derived from the session and never from the request, exactly as `ChatController` derives a run's — the one exception being that an `app.ai.admins` member may name an `owner` on the *read* endpoints and on the delete, which mirrors what `KnowledgeAdminTools` and `PlaybookTools` allow between them — a source's playbooks live under an identity nobody logs in as, so a delete naming an owner is the only way one is ever removed rather than only overwritten. It reaches that identity's own base and no company one, and no other write accepts an owner. Every endpoint answers 404 where no `KnowledgeBase` bean exists, and `/api/me` reports that first so the page never offers the section. A document id travels in the query string or the body and never in the path: a document indexed from a file is identified by its absolute path, whose slashes are rejected encoded and are extra segments unencoded.

`SkillController` does the same for skills, behind `/api/skills`, which is what the page's **Customize** section reads and writes. A skill is a folder holding a `SKILL.md` and whatever else it needs, so the page lists skills, opens one into a file tree beside the file being read, and creates, edits, adds to, imports from a zip and deletes. Four things differ from the knowledge base and each is deliberate: there is **no `owner` parameter on any endpoint** — a knowledge document is prose and an admin reading one is a moderation question, while a skill is instructions the agent will load and act on; **who may write company skills is `app.ai.non-admin-tenant-writes`** (core's `TenantWrites`, off by default, so admins only — the same gate on the tenant scope of memories and of the knowledge base), asked here *and* in `SkillManagementTools`, because a stricter page over an open tool would only make the page the slow way round; **nothing is optional**, so `/api/me` reports `skills.tenant`, which decides whether the Company scope is drawn, and `skills.tenantWritable`, which decides whether that scope's write controls are; a skill goes out and comes back as a zip — `GET /api/skills/export` streams one and `POST /api/skills/import` takes it, exact counterparts speaking the skill's own folder, the export uncapped where the import is capped because only one of the two can fill somebody else's disk; and the list reports **`shadowed`**, since `HomeDir.dirs` answers nearest-scope-first and `SkillsTool` keeps the first of a duplicate name — a company skill whose name the reader also has privately is installed and never loaded, and nothing else says so.

`MemoryController` and `McpController` are the other two, and each is the same split again. `core/memory/MemoryStore` holds the memory path guard because `MemoryTools` and that controller both need it; its scopes are `own` and `tenant`, and **who may write the company's is `TenantWrites` and deliberately not `MemoryScopes.writable`** — that one additionally wants a group chat as the witness to a shared write, and a browser session has no group, so reusing it would leave the company scope read-only whatever the deployment configured. `core/tools/mcp/McpServerRegistry` holds registration — URL validation, the tool-prefix collision check, the live probe, then the save — and throws an `McpRegistryException` carrying a *reason*, which `McpServerManagementTools` turns into `CoreMessages` prose and the controller into an HTTP status; a server's headers are where the token lives and **never go on the wire**, so a response carries their names and a save omitting the field keeps what is stored. MCP has no scope: a server is a row owned by whoever registered it, reaching others by a share.

`core/skills/SkillFiles` is the one guard, and it is in core because both callers need it: `SkillManagementTools` and that controller. Read it before touching either. It resolves the deepest existing part of a path to its **real** location and re-checks containment, because lexical normalizing is symlink-blind and the sandbox shell can create one inside a skills folder; it resolves **both sides** of that comparison, since comparing a real path against a declared directory refuses every write on any host whose storage root is itself a link — which on macOS it always is; it refuses to delete `SKILL.md` as a file, because without it the folder stops being a skill and becomes unreachable by every endpoint that names one; and it validates a zip in full before writing any of it, so a bad archive leaves nothing behind. The controller builds a **single-scope** `HomeDir` per request and never `forRequest`: the guard asks the home it is given whether a path is inside it, and a composite spans both stores.

Every section's content is read in one column of one width — `--page-width` in `base.css`, applied by `.page-column`. The number is written once on purpose: two of them have a person moving between sections read the shift as the page jumping, with nothing on screen to explain it. A conversation is renamed from that bar: it is called the first thing said in it, derived on read so it cannot go stale, and `PATCH /api/conversations/{id}` stores an override that wins — emptying the field clears the override rather than leaving a blank row. The stored name is the one thing `ChatSession` holds that is not derivable from the conversation, which is the exception its class comment now states rather than the rule it breaks; `AbstractPersistenceBackendTest` covers it on all three backends. **The bar above that column is one title bar, and every section's.** It holds the same three things in the same order — the way out of what is on screen, the name of what is on screen, and on a conversation the rename behind that name — and `panels.js` is the only writer of it: `sectionBar(title, back)` for the three sections that are not a conversation, `renderConversationTitle` for the one that is. Do not write `#conversation-title` from anywhere else, and put a new section's name in `panels.js`'s `SECTION` table rather than in its own code; two writers of one element disagree the moment one is reached by a path the other did not expect. Which parts are drawn is a fact about the *width*, so it lives in `chrome.css` and not in the markup: at md and up the bar is the conversation's alone — `showPanel` marks the others `data-bare` and the rule removes it, because there each panel names itself in its own heading and the drawer toggle is gone — while below md it is drawn on all four, the panel's own heading is hidden instead (`.section-name`), and the drawer toggle gives up its slot to the way back whenever one is set. That way back is `.detail-back` in `detail.css`, one shape for one gesture: `backButton` in `detail.js` builds the card's copy and `#header-back` is the bar's, and below md only the bar's is drawn, since the card's scrolls away with the card. A section whose list is in the sidebar (the knowledge base, the schedule) passes no `back` — the list is on screen; the three Customize tabs pass one, through `openCustomizeDetail`.

**A record that has been opened scrolls as one thing.** `detail.css` pins the head of a knowledge document and of a scheduled task and scrolls only the body under it, which is right for one card with a menu that has to stay reachable. Customize undoes that chain (see `#customize-panel.detail-open` in `customize.css`): a skill is a name, a description and *then* two panes, and a memory and an MCP server each carry a spec sheet above their body, so pinning all of it left a phone reading a file through a slot. What stays put there is the title bar, which is why it carries the open thing's name. A panel holding an editor scrolls the same way, whichever section it belongs to — `detail.css` unpins the head of one it finds a `.field-area-fill` in, since the actions a writer needs are under the box and not in that menu. And what is read or written at length takes the height left under the facts about it, floored at `--editor-lines` (twenty) lines of its own type: below that the panel scrolls instead of squeezing it, which is what a phone gets. `.skill-panes` is the same rule with the head of its right-hand pane added to the floor. Below md both label grids — `.detail-facts` and `.detail-form` — stack, key over value: a 6.5rem key column of a 20rem screen leaves a path wrapping to four lines beside a two-word label.

**The page itself is plain ES modules with no bundler**, under `static/js`, and two rules there are load-bearing. Modules are *layered* — the core (`state`, `dom`, `i18n`, `render`, `api`, `toast`, `route`), then `status`/`theme`/`sidebar`, then the features, then `app.js` as the only file importing across all of them — and a module imports only ones earlier than itself, with a backward edge going over the `bus` in `state.js`; a cycle does not fail, it resolves a binding to `undefined` and throws on whichever path nobody clicked. And *navigation is one-way*: `route.js` owns the hash (`#/chat/<id>`, `#/tasks/<id>`, `#/kb/<scope>/<docId>`, `#/customize/<tab>[/<scope>]/<id>?file=`, where the store segment is per tab — `TAB_SCOPES` — because an MCP server is not a file under a home), a click calls `go`, and `app.js`'s `dispatch` decides what is on screen — nothing opens a thing and then writes the hash. The same rule covers what a *list* holds, not just what a panel shows: everything narrowing the knowledge base — the search, the scope, the owner an admin is reading — travels in the hash's query string, so those lists are reachable by a link and by the back button, and the header's controls are written from the route rather than holding a second copy of it. `panels.js` is the only thing that hides and shows the main column's three panels, so a section cannot forget to put the composer back. `styles.css` is a single linked entry that `@import`s `css/*`, and that order is load-bearing: rules there tie on specificity with Tailwind's utilities and with each other, so a rule that must hold regardless of file order buys specificity and says why (see `.drawer-only`). Adding a static file outside `/js/**` or `/css/**` needs a matching `permitAll` in the application's `SecurityConfigurer`, or the page breaks *only* for somebody not signed in yet.

**Asking the user a question ends the turn.** On a surface whose question handler is asynchronous (Feishu), the ask tool persists a `PendingQuestion`, returns no answers, and the run stops; the answer arrives later as a *new* `AgentRequest` on the same `conversationId`. A handler that can answer inline (the CLI) implements the `SynchronousQuestionHandler` marker instead and the turn continues. Do not assume an answer is available in the same run.

Each library module ships a Spring Boot auto-configuration that component-scans its own package (registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), plus an `aot` package of `RuntimeHints` pulled in with `@ImportRuntimeHints`. Native image is first-class here: reflection, resources and proxies used by new code need hints registered there or the binary breaks at runtime while the JVM build passes.

Text the agent writes itself (as opposed to what the model produced) is localized through a `MessageSource` — `CoreMessages`, `FeishuMessages`, `CliMessages`, `WebMessages` over `messages*.properties` (en, zh_CN). Do not hardcode such strings. The browser page has a second bundle for what it says for *itself* — labels and buttons — in `static/js/i18n.js`, read with `t(key)` and with `data-i18n*` attributes in the markup; both bundles carry every language, and a list drawn in JavaScript has to redraw on `language:changed` or it stays in the language the page started in.

## Documentation

Documentation is split by audience, and **every module carries a `README.md` of its own** stating
whose it is in the first line. A change that alters behaviour updates the relevant page in the same
commit.

Root documents:

- **`README.md`** — somebody deciding whether to run this at all. An overview and an index: the
  feature story, one quick start, the table of applications, the two switches. Per-application setup
  belongs in that application's README, not here. Update it when a feature becomes user-visible, when
  the set of applications changes, or when a switch gains or loses a value. Its *At a glance* diagram
  is a cut-down `docs/architecture.md`'s *The whole picture* — no module names, no subgraph
  boundaries — and is the one diagram kept in two places deliberately: a change to the shape updates
  both in the same commit.
- **`docs/integrations.md`** — anybody looking for a module or writing one. What an integration *is*
  here, the kinds there are, the contract every module keeps, the one-chat-surface rule, and the index
  of every module README. Update it when a module is added or removed, or when something becomes true
  of every integration.
- **`docs/events.md`** — an operator turning event sources on. The observing SPIs, how a source is
  configured, playbooks, trusted actors, and what a triage run may do. Update it when the observing
  path, a source's configuration, or a triage run's identity changes.
- **`docs/sdk.md`** — a Java developer embedding the published modules. Dependencies, minimum
  configuration, `SpringAgent`/`AgentRequest`/`AgentResponseListener`, scenarios, tools and tool
  context, the observing and knowledge SPIs, persistence, native image, the module table. Update it
  when a public API, SPI or extension point changes, when a module is published or removed, or when a
  scenario, listener hook or tool-context key is added.
- **`docs/architecture.md`** — anybody orienting themselves, before picking one of the others. Mermaid
  diagrams of the surfaces, the two ways a run starts, what a run is offered, where state lives, and
  which module may depend on which. Keep it structural: it is not a configuration reference and must
  not grow into one. Every other diagram here is the only copy of itself; only *The whole picture* has
  a simplified twin in the root README.
- **`docs/contributing.md`** — somebody changing this repository. Build/test/lint, module layout and
  the classpath rules, how to add each kind of integration, conventions. Update it when the build, the
  test layout or the module rules change, or when a new *kind* of integration becomes possible.
- **`docs/advanced.md`** — a deployment doing something most deployments do not. What is off by
  default, what turning it on costs, and what it cannot do. A feature belongs here when it needs more
  than one application configured together, or when the ordinary way to run the agent never meets it.

Module READMEs:

- A **library module** (`spring-agent-core`, `spring-agent-integration-*`, `spring-agent-persistence-*`,
  `spring-agent-tools-shell-*`, `spring-agent-rag-*`, `spring-agent-events`) is written for the
  **developer** depending on it or changing it: what it contributes, the load-bearing design
  decisions, the gotchas, and a pointer to the switch an operator sets — not the operator's procedure.
- An **application module** (`spring-agent-app-*`) is written for whoever **deploys** it: how to start
  it, the variables it needs, the console setup on the platform it talks to, and what it carries,
  linking down to each carried module rather than restating it.
- A new module gets its README in the commit that adds it, plus a row in `docs/integrations.md`.

None of them duplicates `application.yaml`, which stays the configuration reference — they link to
it. Same for the code: link to the class that explains itself rather than copying its reasoning into
a document that will drift.

## Conventions

Commit messages follow Conventional Commits with lowercase, prose-style subjects that say *why* in plain English, e.g. `feat: let a scheduled task remember the conversation it belongs to (#11)`, `fix: say why a tool call was dropped, and how to get the tool back`. Prefixes in use: `feat`, `fix`, `refactor`, `build`, `ci`, `docs`, `style`. A PR number suffix `(#N)` is added when the change went through a PR.

Comments in this codebase explain **why**, at length, and are load-bearing — build files, `application.yaml` and `docker-compose.yaml` carry paragraphs of rationale that are the closest thing to design documentation here. Match that: when a decision is non-obvious, write down the reason it was made and what breaks without it. Do not describe history in comments; git records that.

Configuration is documented in place. `spring-agent-app-feishu/src/main/resources/application.yaml` is the reference for every property and environment variable, including the system prompt; read it before adding a knob.

**One chat surface per application, and this is a runtime constraint rather than a preference.** Three singletons in this runtime answer for every run rather than for one surface's runs: a `@Bean AgentResponseListener` claims every run, `PromptVariablesContributor`s are merged with `putAll` so the last one registered decides `{replyFormat}`, and `SituationSweeper` resolves its `Notifier` with `getIfAvailable()`, which throws when two exist. None of the three fails at startup, so a second surface on the classpath is a build that passes and a deployment that misbehaves — a Feishu card replied onto a Slack timestamp, the run aborted before it reaches the model. That is why `spring-agent-app-feishu` and `spring-agent-app-slack` are two applications rather than one server with both integrations, and why the constraint holds for a test classpath too: an auto-configuration is still an auto-configuration there. `OneChatSurfaceTest` in `spring-agent-app-slack` is the check that notices.

`spring-agent-integration-websocket` is deliberately not a third entry in that count. It does register a `@Bean AgentResponseListener`, so that a scheduled task firing or a subagent starting is still visible in the page, but that one claims a run only when the request's `chatType` is `web` — which no other surface sets — and it contributes no `PromptVariablesContributor` and no `Notifier`. So it may sit beside a chat surface, which is the point of publishing it.

`spring-agent-app-web-feishu` is that pairing, and the only application here carrying two surfaces. It exists because a handoff between them cannot be done from two processes: a `RunJournal` is held in memory, so a browser can only watch a chat run live if that run is in the same JVM, and putting a browser's answer back on the chat needs a Feishu client in the process that produced it. `chatType` is the whole of what makes it safe, so anything added there has to say which runs it answers for — `OneChatSurfacePlusWebTest` asserts the three singletons and that the two listeners' claims are disjoint. Two traps found while building it, both worth remembering:

- `FeishuReplyFormat` filled `{replyFormat}` **unconditionally** until then, ignoring `chatType`, which would have had every browser answer written in Feishu card markdown and rendered as literal `<at>` tags. A contributor is a bean; it is asked about every run in the context.
- Four classes in each chat module asked for Boot's `applicationTaskExecutor` by name. That bean is `@ConditionalOnMissingBean(Executor.class)` and a STOMP application registers four executors, so the combination had no such bean and failed to start. Each module now declares its own — `FeishuAutoConfiguration.TASK_EXECUTOR`, `SlackAutoConfiguration.TASK_EXECUTOR`. Do not depend on a bean whose existence is conditional on the rest of the application.

**Handing a conversation between the two is `Notifier`, not an SPI of its own.** `core/notify/Notifier` is already "say something to a chat with no run behind it", both chat modules implement it, and a deployment has at most one — which is what makes "the chat surface beside this page" resolvable. It carries two `default` methods for this: `surface()` names the platform so the page can draw its icon, and `quoted(String)` escapes text somebody else wrote into that platform's dialect. `quoted` is load-bearing rather than tidy — `<at id=all></at>` typed into the web composer would otherwise have the bot notify a whole Feishu group — and an implementation carrying foreign text must override it. `ChatMirrors` builds the mirror as a **per-request** listener, so nothing is persisted and no bean has to work out which runs it was wanted for.

**`spring-agent-app-slack`'s, `spring-agent-app-webui`'s and `spring-agent-app-web-feishu`'s `application.yaml` are derived from `spring-agent-app-feishu`'s and have to stay in step with it.** They all run the same runtime, so a setting must mean the same thing in each — a deployment that moves between them should not silently get different tool limits, a different subagent budget or a different sandbox. A knob added to the Feishu server's file belongs in the others as well, with the same default and the same rationale. `spring-agent-app-web-feishu`'s is derived from `spring-agent-app-webui`'s in turn, and adds `app.feishu.*` (the same block as the Feishu server's), `app.web.base-url` and `app.web.follow-chat-runs`, and carries no `slack-login` profile — it matches a signed-in person to their Feishu chat by `open_id`, so `FeishuIdentityCheck` refuses to start on any other provider.

Only these kinds of difference are legitimate, and each is stated in the header comment at the top of the derived file rather than left to be found by diffing:

- **absent modules** — `app.events` and `app.feishu` have no configuration in the web file because it carries neither, and `spring-agent-app-slack` has `app.slack` where the Feishu server has `app.feishu`; configuring absent code is a lie about what the binary does;
- **`app.web.*`** — who may log in, how long a finished run stays replayable, how long an unanswered question lives;
- **what the surface needs and the server does not** — `spring.threads.virtual` for the websocket sessions a page holds open, `spring.mvc.async` for the published files core's `ShareController` streams, `spring.servlet.multipart` for uploads;
- **per-application storage** — its own SQLite file, its own vector-store file, its own published-file URLs.

Anything else drifting is a bug in one of the two. `DockerShellDefaultsTest` in `spring-agent-app-webui` is the check that notices for the shell sandbox, and it binds the properties rather than parsing the YAML, so it also catches a block landing at a nesting level Boot ignores in silence.

## Running locally

Required env vars, no defaults, the app will not start without them: `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL` — **or** `DASHSCOPE_API_KEY` alone, which `DashScopeDefaults` expands into all six. They come from `.env` (gitignored).

`docker-compose.yaml` has a compose profile per value of the two switches, so the containers and the application's own choice cannot drift apart:

```sh
PERSISTENCE_TYPE=redis VECTORSTORE_TYPE=milvus \
  COMPOSE_PROFILES=$PERSISTENCE_TYPE,$VECTORSTORE_TYPE docker compose up   # backends only
```

Add `app` to `COMPOSE_PROFILES` to run everything in containers. The defaults (`jpa` + `simple`) need no server at all.

The knowledge base has a profile of its own, `rag`, which is a feature rather than a third axis — it starts the same Milvus (plus its etcd and MinIO) that the `milvus` profile does, since the tool-search index and the knowledge base share a server and differ only by collection. It is separate so a knowledge base does not drag the tool index off the simple in-heap store:

```sh
RAG_ENABLED=true COMPOSE_PROFILES=rag docker compose up      # simple tool index, Milvus knowledge base
```

`RAG_ENABLED` and the profile go together: the profile starts Milvus, the variable is what makes the application use it, and turning it on with no Milvus reachable stops startup rather than quietly running without a knowledge base. That is why `app.ai.rag.enabled` defaults to false — the same reasoning as `app.ai.tools.shell.type` defaulting to `none`.
