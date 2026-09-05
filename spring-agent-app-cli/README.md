# spring-agent-app-cli

> **Audience:** somebody running the agent on their own machine. The configuration reference is
> [`src/main/resources/application.yaml`](src/main/resources/application.yaml).

The laptop command line: the same runtime, one person, no server to stand up. There is no prebuilt
binary — build it from a clone.

```sh
./gradlew :spring-agent-app-cli:bootRun                 # needs the same OPENAI_*/EMBEDDING_* variables
./gradlew :spring-agent-app-cli:nativeCompile -Pnative  # or a native binary
```

`-Pnative` is required for any native task: the GraalVM plugin is applied conditionally so that a
plain `bootBuildImage` does not silently turn into a native build.

## What it carries

[core](../spring-agent-core/README.md) and [jpa](../spring-agent-persistence-jpa/README.md), and
nothing else. No chat platform, no webhook receiver, no Milvus. Everything lives in SQLite under
`~/.spring-agent`.

## Using it

Type a sentence to talk to the agent; anything starting with `/` is a command — `/help`, `/clear`,
`/session`, `/model`, `/tools`, `/stop`, `/exit`. `/config` lists your models, `/config <name>`
switches, `/config default` returns to the built-in one, and `/config <name> <effort>` sets how hard it
thinks.

Ctrl-C cancels the run in progress rather than the session.

## The one way it differs from every server here

**It answers the agent's questions inline.** Its question handler implements the
`SynchronousQuestionHandler` marker, so when the agent asks something the turn *continues* rather than
ending and waiting for a new request — which is what every chat surface has to do. That axis is the
whole difference between this surface and those, and it is why the CLI is worth keeping as a third
implementation.

## The shell runs on your own machine

`TOOLS_SHELL_TYPE=local` by default here, unlike every server, which defaults to `none`. That is the
point of a laptop tool and worth knowing before you let it run something: there is no sandbox between
the model's commands and your filesystem.
