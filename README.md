# spring-agent

[![Maven Central](https://img.shields.io/maven-central/v/me.kezhenxu94/spring-agent-core?label=Maven%20Central)](https://central.sonatype.com/artifact/me.kezhenxu94/spring-agent-core)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](#)

A tool-using agent runtime on Spring Boot 4 and Spring AI: shell sandboxes, MCP servers, scheduled
tasks, chat memory, file publishing and a searchable tool index, behind one entry point. Run the
deployable server or the command line as they are, or take the libraries and give the agent a
surface of your own.

## Standalone

### Server

```sh
docker run --env-file .env -p 8080:8080 ghcr.io/kezhenxu94/spring-agent:latest
```

Or from a clone: `./gradlew :spring-agent-app:bootRun`.

These environment variables have no defaults and the application will not start without them —
`OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`,
`EMBEDDING_MODEL`. Everything else is optional and documented in place, property by property, in
[`spring-agent-app/src/main/resources/application.yaml`](spring-agent-app/src/main/resources/application.yaml)
— read that file rather than this README for configuration.

Two switches decide what the deployment is:

| Property (env var) | Values | Default |
| --- | --- | --- |
| `app.persistence.type` (`PERSISTENCE_TYPE`) | `jpa` (SQLite, no server needed), `mongodb`, `redis` | `jpa` |
| `app.ai.tools.shell.type` (`TOOLS_SHELL_TYPE`) | `none`, `kubernetes`, `docker`, `local` | `none` |

The default pair needs nothing running alongside it. For the others, `docker-compose.yaml` has a
profile per value so the containers cannot drift from the application's own choice:

```sh
PERSISTENCE_TYPE=redis VECTORSTORE_TYPE=milvus \
  COMPOSE_PROFILES=$PERSISTENCE_TYPE,$VECTORSTORE_TYPE docker compose up
```

### Command line

```sh
./gradlew :spring-agent-cli:bootRun            # needs the same OPENAI_*/EMBEDDING_* variables
# OR
./gradlew :spring-agent-cli:nativeCompile -Pnative
```

The command line stores everything in SQLite under the user's home. Type a sentence to talk to the
agent; anything starting with `/` is a command (`/help`). Unlike the server it can answer the
agent's questions inline, and Ctrl-C cancels the run in progress rather than the session.

## As an SDK

Take `spring-agent-core` plus exactly one persistence module — core is backend-agnostic and holds no
database driver, and the module is what supplies both the repositories and the chat memory
repository:

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

Each module auto-configures itself, so a plain `@SpringBootApplication` picks up the runtime, the
built-in tools and the repositories. The minimum configuration:

```yaml
spring:
  messages:
    # What the agent writes into a conversation itself, as opposed to what the model wrote.
    # Naming this bundle is what makes those notes resolve; append your own basenames to it.
    basename: core/messages
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

`app.ai.system-prompt` has a surface-neutral default; override it to give the agent your own persona
and house rules. It is rendered per request over a fixed variable set, and naming a variable that is
not supplied fails the render.

### Running the agent

Inject `SpringAgent` and describe the run. `fire` returns immediately and reports only through
listeners, so a caller that has to wait for the answer waits on one of them:

```java
agent.fire(
    AgentRequest.builder()
        .requestId(runId)                     // what SpringAgent.cancel(runId) stops
        .scenario(BuiltInScenarios.CHAT)
        .userId(userId)
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

An `AgentResponseListener` declared as a `@Bean` instead observes *every* run, which is how a
surface takes part in runs it did not start — a scheduled task firing, say. Its `onStart` hook is
the last point at which a run can still be given tool-context entries, listeners or a question
handler.

Asking the user a question normally ends the turn: the question is persisted, the run stops, and the
answer arrives later as a new `AgentRequest` on the same `conversationId`. A surface that can answer
within the call implements the `SynchronousQuestionHandler` marker and the turn continues instead.

### Adding a tool

A bean annotated `@AgentTool` has its Spring AI `@Tool` methods offered to the agent; the annotation
is allowed on a `@Bean` method too, for a type from a library. Every annotated bean is offered to
every run; one that belongs only in some implements `ScenarioGatedTool` and is asked. Per-request
identity arrives through the tool context, under typed keys:

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

For behaviour that has to wrap every tool call — auditing, rate limiting, rewriting a result —
implement `ToolCallInterceptor` rather than touching the tools.

### Optional modules

| Module | What it adds |
| --- | --- |
| `spring-agent-persistence-jpa` | Relational storage, SQLite by default |
| `spring-agent-persistence-mongodb` | MongoDB storage |
| `spring-agent-persistence-redis` | Redis storage; needs Redis 8 or Redis Stack, and a server configured to keep what it is given |
| `spring-agent-tools-shell-kubernetes` | A disposable per-user sandbox Pod for the shell tools |
| `spring-agent-tools-shell-docker` | The same sandbox on a local Docker daemon |
| `spring-agent-integration-feishu` | Feishu/Lark chats and cards as an agent surface |

Adding a shell module decides nothing on its own — `app.ai.tools.shell.type` does, and it defaults
to `none`.

### Native image (Experimental)

Both switches are `@Conditional`, so in a native image they are build-time decisions baked by
`-PnativeBackends`; the environment variable is inert in the binary and has to agree with what was
baked. New code that needs reflection, resources or proxies needs its hints registered in the
module's `aot` package, or the binary breaks at runtime while the JVM build passes.

## Building

```sh
make          # ./gradlew build
make test     # needs a running Docker daemon: the tests start MongoDB and Redis via Testcontainers
make lint     # spotlessApply
```

Java bytecode targets 21, built with a GraalVM 25 toolchain because `native-image` ships with it.
There is no CI that builds or tests — the workflows only publish, so run `make` before pushing.

## License

[Apache 2.0](LICENSE).
