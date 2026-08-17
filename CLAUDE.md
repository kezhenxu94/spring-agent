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
./gradlew :spring-agent-app:bootRun          # the server
./gradlew :spring-agent-cli:bootRun          # the command line (stdin/tty wired for JLine)
./gradlew :spring-agent-app:bootBuildImage   # container image (Paketo buildpack, no Dockerfile)
./gradlew :spring-agent-cli:nativeCompile -Pnative
```

`-Pnative` is required for any native task: the GraalVM plugin is applied conditionally so that a plain `bootBuildImage` does not silently turn into a native build (see the comment in `spring-agent-app/build.gradle`).

Tests need a running Docker daemon — `AbstractIntegrationTest` starts MongoDB and Redis containers via Testcontainers. Unit tests sit beside the class they cover; cross-cutting integration tests live in `spring-agent-app/src/test`. A behaviour that must hold for every persistence backend goes in `AbstractPersistenceBackendTest`, which is run once per backend by `PersistenceJpaTest`/`PersistenceMongoTest`/`PersistenceRedisTest` — add the assertion there rather than to one backend's test.

There is **no CI workflow that builds or tests**. The three workflows publish only. Verification is local; run `make build` before pushing.

## Tech stack

- Java: bytecode targets **21** (`options.release = 21`), built with a **GraalVM 25** toolchain because `native-image` ships with it. Do not use APIs newer than 21.
- Spring Boot 4, Spring AI 2.x, Spring Shell 4 (CLI), Lombok, JUnit 5 + Testcontainers.
- Exact versions live in `gradle/libs.versions.toml`; several pins there carry load-bearing comments explaining why the BOM version is wrong. Read the comment before changing a version.
- Lombok is configured with **fluent accessors** (`lombok.config`), so getters are `foo()`, not `getFoo()`.

## Modules

```
spring-agent-core              the agent runtime; backend-agnostic
spring-agent-persistence-{jpa,mongodb,redis}
spring-agent-tools-shell-{kubernetes,docker}
spring-agent-integration-feishu
spring-agent-app               deployable server; depends on every backend module
spring-agent-cli               laptop command line; jpa + local shell only
```

`spring-agent-core` must stay free of any persistence backend. This is enforced by `checkRuntimeClasspathIsolation` (wired into `check`, defined in `buildSrc/.../springagent.classpath-isolation.gradle`, configured at the bottom of `spring-agent-core/build.gradle`): it fails the build if Hibernate, the Mongo driver, Jedis, Milvus, fabric8 and friends reach core's runtime classpath. If that task fails, a dependency became `api` or grew a new transitive — fix the dependency, do not widen the allow-list.

## Architecture

**One entry point for running the agent.** An integration builds an `AgentRequest` (a record + builder: user/chat identity, `AgentScenario`, prompt variables, tool context, listeners) and hands it to `SpringAgent` (`core/agent/SpringAgent.java`). Everything after that — tool composition, system-prompt rendering, the Spring AI `ChatClient` call, MCP client lifecycle, listener fan-out, cancellation — happens inside `SpringAgent`. Integrations never touch `AgentToolsProvider`, MCP clients or Reactor directly.

**Integrations observe runs through `AgentResponseListener`.** Attached to a request it covers that run; declared as a `@Bean` it covers *every* run, which is how a surface takes part in runs it did not initiate (a scheduled task firing, say). `onStart(AgentRunRegistry)` is the hook for contributing per-run state.

**Tools** are beans annotated `@AgentTool` (allowed on a `@Bean` method too, for library types), whose Spring AI `@Tool` methods `AgentToolsProvider.compose(...)` assembles per request. `AgentScenario` on the annotation gates which runs get the tool. Per-request identity reaches a tool through the `toolContext` map, with typed keys in `core/tools/ToolContexts.java` — read them through those keys rather than by string. Cross-cutting behaviour around tool calls goes in a `ToolCallInterceptor` (`core/tools/interceptors/`), which the custom `ToolCallingManager` wires in.

**Two runtime switches select beans by condition**, not by classpath alone:

- `app.persistence.type` — `jpa` (default, SQLite) | `mongodb` | `redis`, via `@ConditionalOnPersistenceBackend`. Chooses repositories *and* the Spring AI chat memory repository together.
- `app.ai.tools.shell.type` — `none` (default) | `kubernetes` | `docker` | `local`, via `@ConditionalOnShellBackend`.

Both are evaluated during AOT, so in a **native image they are build-time decisions** baked by `-PnativeBackends` (see `springagent.native.gradle`); the environment variable is inert at runtime and must be set to agree with what was baked.

**One domain model serves every backend.** The records in `core/dao/models/` carry JPA, MongoDB *and* Redis mapping annotations at once (`@Entity` + `@Document` + `@RedisHash`, both `@Id` flavours). This works because an annotation whose type is absent at runtime is discarded on reflection — which is also why core declares those persistence APIs `compileOnly`. Repository *contracts* live in `core/dao/repo/`; each `spring-agent-persistence-*` module implements them. When adding a model or a query, update all three implementations, and note that Redis has no query planner: an `@Indexed` field is the definition of what can be filtered on, not a tuning knob.

**Vector store** backs the tool-search index only, not retrieval over user data: `spring.ai.vectorstore.type` is `simple` (in-heap, mirrored to a JSON file) or `milvus`. Milvus is a dependency of `spring-agent-app` only, deliberately kept out of core.

Schema is owned by the application (`ddl-auto: update`) — there is no Flyway or Liquibase.

**Asking the user a question ends the turn.** On a surface whose question handler is asynchronous (Feishu), the ask tool persists a `PendingQuestion`, returns no answers, and the run stops; the answer arrives later as a *new* `AgentRequest` on the same `conversationId`. A handler that can answer inline (the CLI) implements the `SynchronousQuestionHandler` marker instead and the turn continues. Do not assume an answer is available in the same run.

Each library module ships a Spring Boot auto-configuration that component-scans its own package (registered in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), plus an `aot` package of `RuntimeHints` pulled in with `@ImportRuntimeHints`. Native image is first-class here: reflection, resources and proxies used by new code need hints registered there or the binary breaks at runtime while the JVM build passes.

Text the agent writes itself (as opposed to what the model produced) is localized through a `MessageSource` — `CoreMessages`, `FeishuMessages`, `CliMessages` over `messages*.properties` (en, zh_CN). Do not hardcode such strings.

## Conventions

Commit messages follow Conventional Commits with lowercase, prose-style subjects that say *why* in plain English, e.g. `feat: let a scheduled task remember the conversation it belongs to (#11)`, `fix: say why a tool call was dropped, and how to get the tool back`. Prefixes in use: `feat`, `fix`, `refactor`, `build`, `ci`, `docs`, `style`. A PR number suffix `(#N)` is added when the change went through a PR.

Comments in this codebase explain **why**, at length, and are load-bearing — build files, `application.yaml` and `docker-compose.yaml` carry paragraphs of rationale that are the closest thing to documentation here (there is no README). Match that: when a decision is non-obvious, write down the reason it was made and what breaks without it. Do not describe history in comments; git records that.

Configuration is documented in place. `spring-agent-app/src/main/resources/application.yaml` is the reference for every property and environment variable, including the system prompt; read it before adding a knob.

## Running locally

Required env vars, no defaults, the app will not start without them: `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL`. They come from `.env` (gitignored).

`docker-compose.yaml` has a compose profile per value of the two switches, so the containers and the application's own choice cannot drift apart:

```sh
PERSISTENCE_TYPE=redis VECTORSTORE_TYPE=milvus \
  COMPOSE_PROFILES=$PERSISTENCE_TYPE,$VECTORSTORE_TYPE docker compose up   # backends only
```

Add `app` to `COMPOSE_PROFILES` to run everything in containers. The defaults (`jpa` + `simple`) need no server at all.
