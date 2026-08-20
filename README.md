# spring-agent

[![Maven Central](https://img.shields.io/maven-central/v/me.kezhenxu94/spring-agent-core?label=Maven%20Central)](https://central.sonatype.com/artifact/me.kezhenxu94/spring-agent-core)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](#)

An SDK for standing up a tool-using agent on Spring Boot 4 and Spring AI: shell sandboxes, MCP
servers, skills, memories, scheduled tasks, chat memory, file publishing and a searchable tool
index, behind one entry point. Add two dependencies and a `@SpringBootApplication` and you have an
agent; or run the surfaces that ship here — a deployable server with Feishu/Lark chat, and a command
line — as they are.

Every property and environment variable is documented in place, with the reason for its default, in
[`spring-agent-app/src/main/resources/application.yaml`](spring-agent-app/src/main/resources/application.yaml).
That file, not this README, is the configuration reference.

## (Nearly) everything is a tool

The agent is not a fixed feature list. Almost everything it can do arrives as a tool, and the
registries that decide what tools exist are themselves tools — so the agent extends itself, in
conversation, without a redeploy:

| Ask it to… | …and it calls | …which gives the next run |
| --- | --- | --- |
| use an MCP server you name | `AddMcpServer` | every tool that server offers |
| learn a procedure | `WriteSkillFile` | a skill, loaded on demand |
| remember something about you | the memory tools | notes it reads back before replying |
| hold a token for later | `SetCredential` | the secret in its sandbox, never in a prompt |
| do this every Monday | `CreateScheduledTask` | a run that fires on its own |

Nothing here is registered up front. `AgentToolsProvider.compose(...)` assembles the tool set once
per request out of the `@AgentTool` beans in the context, the MCP servers that request's user can
reach, that user's skills, and the callbacks of every `ToolCallbackProvider` bean — so a set that
grew a minute ago is offered to the next turn. Because the set is open-ended, both surfaces here
turn on Spring AI's tool-search advisor, which retrieves the few tools a turn actually needs instead
of sending the model all of them.

Which is also how you extend it from the outside: a bean with `@Tool` methods is a tool, an MCP
server is a tool, a skill is a tool. `AgentScenario.offers(tool)` is the one gate — that is how a
run that fired on a schedule is denied `ScheduledTaskTool` and cannot breed more of itself.

## Every user gets their own agent

Every run carries a user id, and that id — not the process — owns the state. Under
`app.storage.location` each user has a home with `memories/`, `skills/`, `workspace/` and
`artifacts/`; the filesystem tools are confined to that root, so one user's agent cannot read
another's files. The same line runs through everything else:

- **MCP servers** are registered by their owner. `ShareMcpServer` grants use to another person, a
  group chat, or everyone, while editing, removing and re-sharing stay with the owner — the
  recipient never sees the URL or headers. Servers the deployment configures for everybody under
  `spring.ai.mcp.client.*` are listed alongside them and belong to nobody.
- **Skills** are folders with a `SKILL.md` in the user's own skills directory. The agent writes and
  deletes them on request; paths outside that directory are refused.
- **Memories** are files in the user's memories directory, written and read back by the agent
  itself.
- **Credentials** are per-user: a Kubernetes Secret mounted into that user's sandbox, or an
  encrypted row, so a token reaches a shell as an environment variable and never a prompt.
- **The sandbox shell** is a Pod or container per user, with its own slice of the volume, torn down
  when idle and rebuilt on the next command.

A user needs no administrator to set any of this up — they ask the agent, and it registers it for
them. On the command line the same machinery serves the one person at the keyboard, out of
`~/.spring-agent`.

## Standalone

### Server

```sh
docker run --env-file .env -p 8080:8080 ghcr.io/kezhenxu94/spring-agent:latest
```

Or from a clone: `./gradlew :spring-agent-app:bootRun`. It comes with the Feishu/Lark integration
wired up, so a bot in a chat is an agent surface with no code written.

These environment variables have no defaults and the application will not start without them —
`OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`,
`EMBEDDING_MODEL`. Everything else is optional, and set in
[`application.yaml`](spring-agent-app/src/main/resources/application.yaml).

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

That is the minimum, not the whole of it. Everything you can turn on from here — the shell sandbox,
the tool-search advisor, per-user storage and published-file links, the MCP SSRF allow-list, the
question tool's lifetime — is written out with its rationale in the server's
[`application.yaml`](spring-agent-app/src/main/resources/application.yaml); copy the blocks you want
out of it rather than rediscovering the property names.

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
every run unless the run's scenario keeps it out, by overriding `AgentScenario.offers(tool)`.
Per-request identity arrives through the tool context, under typed keys:

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
