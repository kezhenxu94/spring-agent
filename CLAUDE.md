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

Tests need a running Docker daemon — `AbstractIntegrationTest` starts MongoDB and Redis containers via Testcontainers. Unit tests sit beside the class they cover; cross-cutting integration tests live in `spring-agent-app-feishu/src/test`. A behaviour that must hold for every persistence backend goes in `AbstractPersistenceBackendTest`, which is run once per backend by `PersistenceJpaTest`/`PersistenceMongoTest`/`PersistenceRedisTest` — add the assertion there rather than to one backend's test.

There is **no CI workflow that builds or tests**. The three workflows publish only. Verification is local; run `make build` before pushing.

## Tech stack

- Java: bytecode targets **21** (`options.release = 21`), built with a **GraalVM 25** toolchain because `native-image` ships with it. Do not use APIs newer than 21.
- Spring Boot 4, Spring AI 2.x, Spring Shell 4 (CLI), Lombok, JUnit 5 + Testcontainers.
- Exact versions live in `gradle/libs.versions.toml`; several pins there carry load-bearing comments explaining why the BOM version is wrong. Read the comment before changing a version.
- Lombok is configured with **fluent accessors** (`lombok.config`), so getters are `foo()`, not `getFoo()`.

## Modules

```
spring-agent-core              the agent runtime and every SPI; backend-agnostic
spring-agent-persistence-{jpa,mongodb,redis}
spring-agent-tools-shell-{kubernetes,docker}
spring-agent-events           observations -> situations -> a triage run; serves /events/webhooks/<source>
spring-agent-integration-{github,gitlab,grafana}   webhook readers for spring-agent-events
spring-agent-rag-milvus       the knowledge base; the only implementation of core's KnowledgeBase
spring-agent-integration-feishu
spring-agent-integration-websocket  a browser as a surface: the SPA, its REST endpoints, STOMP run streaming
spring-agent-app-feishu        deployable server whose surface is Feishu; depends on every optional module
spring-agent-app-slack         the same server, with Slack as its surface instead
spring-agent-app-cli           laptop command line; jpa + local shell only
spring-agent-app-webui         the same server, with a browser as its surface
```

`spring-agent-core` must stay free of any persistence backend. This is enforced by `checkRuntimeClasspathIsolation` (wired into `check`, defined in `buildSrc/.../springagent.classpath-isolation.gradle`, configured at the bottom of `spring-agent-core/build.gradle`): it fails the build if Hibernate, the Mongo driver, Jedis, Milvus, fabric8 and friends reach core's runtime classpath. If that task fails, a dependency became `api` or grew a new transitive — fix the dependency, do not widen the allow-list.

## Architecture

**One entry point for running the agent.** An integration builds an `AgentRequest` (a record + builder: user/chat identity, `AgentScenario`, prompt variables, tool context, listeners) and hands it to `SpringAgent` (`core/agent/SpringAgent.java`). Everything after that — tool composition, system-prompt rendering, the Spring AI `ChatClient` call, MCP client lifecycle, listener fan-out, cancellation — happens inside `SpringAgent`. Integrations never touch `AgentToolsProvider`, MCP clients or Reactor directly.

**Integrations observe runs through `AgentResponseListener`.** Attached to a request it covers that run; declared as a `@Bean` it covers *every* run, which is how a surface takes part in runs it did not initiate (a scheduled task firing, say). `onStart(AgentRunRegistry)` is the hook for contributing per-run state.

**Tools** are beans annotated `@AgentTool` (allowed on a `@Bean` method too, for library types), whose Spring AI `@Tool` methods `AgentToolsProvider.compose(...)` assembles per request. `AgentScenario` gates which runs get the tool: it is an interface, `BuiltInScenarios` holds the ones shipped here, and a consumer can implement it and pass their own on the request. The annotation carries no scenario — an annotation attribute cannot have an interface type, which would confine gating to the built-in enum — so the scenario decides: `AgentScenario.offers(tool)` is asked about every `@AgentTool` bean and says yes by default. That is how `SCHEDULED_TASK` keeps `ScheduledTaskTool` out of a run that fires on a schedule, and how `SUBAGENT` keeps both it and `SubagentTools` out — which is what caps subagent depth at one, with no counter to get wrong. `AgentScenario` also decides whether a run uses conversation memory and whether it consults the knowledge base. Per-request identity reaches a tool through the `toolContext` map, with typed keys in `core/tools/ToolContexts.java` — read them through those keys rather than by string. Cross-cutting behaviour around tool calls goes in a `ToolCallInterceptor` (`core/tools/interceptors/`), which the custom `ToolCallingManager` wires in.

A run is offered exactly what `compose(...)` returns, so tools that come from elsewhere have to be collected there too: alongside the `@AgentTool` beans and the user's own MCP servers it appends the callbacks of every `ToolCallbackProvider` bean in the context. That is how application-wide MCP servers configured under `spring.ai.mcp.client.*` reach the model — Spring AI publishes them as such a bean but never wires it into a `ChatClient` itself. Those clients belong to the context and are never closed by a run; only the per-request MCP clients in `McpTools` are.

**Two runtime switches select beans by condition**, not by classpath alone:

- `app.persistence.type` — `jpa` (default, SQLite) | `mongodb` | `redis`, via `@ConditionalOnPersistenceBackend`. Chooses repositories *and* the Spring AI chat memory repository together.
- `app.ai.tools.shell.type` — `none` (default) | `kubernetes` | `docker` | `local`, via `@ConditionalOnShellBackend`.

Both are evaluated during AOT, so in a **native image they are build-time decisions** baked by `-PnativeBackends` (see `springagent.native.gradle`); the environment variable is inert at runtime and must be set to agree with what was baked.

**One domain model serves every backend.** The records in `core/dao/models/` carry JPA, MongoDB *and* Redis mapping annotations at once (`@Entity` + `@Document` + `@RedisHash`, both `@Id` flavours). This works because an annotation whose type is absent at runtime is discarded on reflection — which is also why core declares those persistence APIs `compileOnly`. Repository *contracts* live in `core/dao/repo/`; each `spring-agent-persistence-*` module implements them. When adding a model or a query, update all three implementations, and note that Redis has no query planner: an `@Indexed` field is the definition of what can be filtered on, not a tuning knob.

**Vector store** backs the tool-search index only, not retrieval over user data: `spring.ai.vectorstore.type` is `simple` (in-heap, mirrored to a JSON file) or `milvus`. Milvus is a dependency of the two server applications only, deliberately kept out of core.

**The knowledge base is a separate thing from that vector store**, and confusing the two is the easy mistake here. Retrieval over user data — what a user, group or tenant has asked the agent to remember — lives behind the `KnowledgeBase` SPI in `core/knowledge/`, implemented by `spring-agent-rag-milvus`, in its own Milvus collection with its own connection under `app.ai.rag.milvus.*`. It deliberately does not read `spring.ai.vectorstore.type`, so a deployment can run the tool index in the heap and the knowledge base in Milvus.

That module holds its `MilvusVectorStore` as a private field rather than publishing it as a bean. This is load-bearing: Spring AI's Milvus auto-configuration declares its own store `@ConditionalOnMissingBean`, so publishing a second one would make *that* back off and silently take the tool-search index's store with it. It also drops to the raw Milvus client for `list`, because no portable `VectorStore` interface can enumerate — which is the whole reason a knowledge base is a backend module rather than something core implements over any store.

Scoping is one definition, `core/knowledge/KnowledgeScopeFilter`, used both for retrieval and — via `MilvusFilterExpressionConverter` — for the raw listing query. Chunks carry `owner`/`group`/`tenant`, always all three, blank where they do not apply, and **a filter clause is only ever emitted for a non-blank identity**: a blank one would match every document that stores a blank there, which is every other user's. `KnowledgeScopeFilterTest` covers that case by name; read it before changing the filter.

Core registers the knowledge tools only when a `KnowledgeBase` bean exists, ordered with `@AutoConfiguration(afterName = ...)` naming the module's class as a string. Rename that class and the tools silently stop being registered.

Schema is owned by the application (`ddl-auto: update`) — there is no Flyway or Liquibase.

**Not every run starts with somebody talking to the agent.** `core/observing/` is the contract for the other case, and core ships no implementation of it: a transport reports an `Observation` (source, delivery id, kind, correlation key, evidence, and a `Route` saying where a run about it may talk) to `EventIntakes`, which hands it to every `EventIntake` bean, each independent and each isolated from the others' failures. That is why a transport — the Feishu integration, a webhook receiver — depends only on core, and nothing consuming observations depends on a transport. `spring-agent-events` is the intake that correlates observations into situations by their key, debounces, and wakes a triage run; the `spring-agent-integration-{github,gitlab,grafana}` modules each contribute one `WebhookSource` and nothing else. All of it is off unless `app.events.enabled`, and a source not named in `app.events.sources` is dropped at the door. Payload text is written by whoever caused the event: it is evidence, never routing and never instructions, and a triage run must assume an identity of the agent's own rather than a person's — a scenario cannot withhold the files, credentials and MCP servers that come with an identity.

**Asking the user a question ends the turn.** On a surface whose question handler is asynchronous (Feishu), the ask tool persists a `PendingQuestion`, returns no answers, and the run stops; the answer arrives later as a *new* `AgentRequest` on the same `conversationId`. A handler that can answer inline (the CLI) implements the `SynchronousQuestionHandler` marker instead and the turn continues. Do not assume an answer is available in the same run.

Each library module ships a Spring Boot auto-configuration that component-scans its own package (registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), plus an `aot` package of `RuntimeHints` pulled in with `@ImportRuntimeHints`. Native image is first-class here: reflection, resources and proxies used by new code need hints registered there or the binary breaks at runtime while the JVM build passes.

Text the agent writes itself (as opposed to what the model produced) is localized through a `MessageSource` — `CoreMessages`, `FeishuMessages`, `CliMessages` over `messages*.properties` (en, zh_CN). Do not hardcode such strings.

## Documentation

`README.md` at the root, everything else under `docs/`. Three documents, three audiences, and a
change that alters behaviour updates the relevant one in the same commit:

- **`README.md`** — somebody running the prebuilt server or CLI. Features as an end user meets them,
  how to start each application, the switches and the environment variables. Update it when a
  feature becomes user-visible, when starting or configuring either application changes, or when a
  switch gains or loses a value.
- **`docs/sdk.md`** — a Java developer embedding the published modules. Dependencies, minimum
  configuration, `SpringAgent`/`AgentRequest`/`AgentResponseListener`, scenarios, tools and tool
  context, the observing and knowledge SPIs, persistence, native image, the module table. Update it
  when a public API, SPI or extension point changes, when a module is published or removed, or when
  a scenario, listener hook or tool-context key is added.
- **`docs/contributing.md`** — somebody changing this repository. Build/test/lint, module layout and
  the classpath rules, how to add each kind of integration, conventions. Update it when the build,
  the test layout or the module rules change, or when a new *kind* of integration becomes possible.

None of them duplicates `application.yaml`, which stays the configuration reference — they link to
it. Same for the code: link to the class that explains itself rather than copying its reasoning into
a document that will drift.

## Conventions

Commit messages follow Conventional Commits with lowercase, prose-style subjects that say *why* in plain English, e.g. `feat: let a scheduled task remember the conversation it belongs to (#11)`, `fix: say why a tool call was dropped, and how to get the tool back`. Prefixes in use: `feat`, `fix`, `refactor`, `build`, `ci`, `docs`, `style`. A PR number suffix `(#N)` is added when the change went through a PR.

Comments in this codebase explain **why**, at length, and are load-bearing — build files, `application.yaml` and `docker-compose.yaml` carry paragraphs of rationale that are the closest thing to design documentation here. Match that: when a decision is non-obvious, write down the reason it was made and what breaks without it. Do not describe history in comments; git records that.

Configuration is documented in place. `spring-agent-app-feishu/src/main/resources/application.yaml` is the reference for every property and environment variable, including the system prompt; read it before adding a knob.

**One chat surface per application, and this is a runtime constraint rather than a preference.** Three singletons in this runtime answer for every run rather than for one surface's runs: a `@Bean AgentResponseListener` claims every run, `PromptVariablesContributor`s are merged with `putAll` so the last one registered decides `{replyFormat}`, and `SituationSweeper` resolves its `Notifier` with `getIfAvailable()`, which throws when two exist. None of the three fails at startup, so a second surface on the classpath is a build that passes and a deployment that misbehaves — a Feishu card replied onto a Slack timestamp, the run aborted before it reaches the model. That is why `spring-agent-app-feishu` and `spring-agent-app-slack` are two applications rather than one server with both integrations, and why the constraint holds for a test classpath too: an auto-configuration is still an auto-configuration there. `OneChatSurfaceTest` in `spring-agent-app-slack` is the check that notices.

**`spring-agent-app-slack`'s and `spring-agent-app-webui`'s `application.yaml` are derived from `spring-agent-app-feishu`'s and have to stay in step with it.** The two applications run the same runtime, so a setting must mean the same thing in both — a deployment that moves between them should not silently get different tool limits, a different subagent budget or a different sandbox. A knob added to the Feishu server's file belongs in the other two as well, with the same default and the same rationale.

Only these kinds of difference are legitimate, and each is stated in the header comment at the top of the derived file rather than left to be found by diffing:

- **absent modules** — `app.events` and `app.feishu` have no configuration in the web file because it carries neither, and `spring-agent-app-slack` has `app.slack` where the Feishu server has `app.feishu`; configuring absent code is a lie about what the binary does;
- **`app.web.*`** — who may log in, how long a finished run stays replayable, how long an unanswered question lives;
- **what the surface needs and the server does not** — `spring.threads.virtual`, `spring.mvc.async` for the SSE streams, `spring.servlet.multipart` for uploads;
- **per-application storage** — its own SQLite file, its own vector-store file, its own published-file URLs.

Anything else drifting is a bug in one of the two. `DockerShellDefaultsTest` in `spring-agent-app-webui` is the check that notices for the shell sandbox, and it binds the properties rather than parsing the YAML, so it also catches a block landing at a nesting level Boot ignores in silence.

## Running locally

Required env vars, no defaults, the app will not start without them: `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL`. They come from `.env` (gitignored).

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
