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

[core](../spring-agent-core/README.md), [jpa](../spring-agent-persistence-jpa/README.md),
[provider-openai](../spring-agent-provider-openai/README.md) and
[provider-google-genai](../spring-agent-provider-google-genai/README.md), and nothing else. No chat
platform, no webhook receiver, no Milvus. Everything lives in SQLite under `~/.spring-agent`.

Two model providers rather than all of them. DashScope is absent for the same reason there is one
persistence backend: what that module carries is a vendor's own APIs, and a laptop points
`OPENAI_BASE_URL` at whatever gateway it uses, DashScope's compatible-mode included. Gemini is here
because it is not that case — its chat, embeddings and image generation are genuinely not the OpenAI
protocol, so reaching it through a compatibility endpoint costs thinking levels and reference-image
editing, and trips over a field Spring AI emits that Gemini rejects. Set `CHAT_MODEL_PROVIDER` and
`EMBEDDING_MODEL_PROVIDER` to `google-genai` and name `GEMINI_API_KEY` to use it; leave them alone
and nothing about this binary changes. So there is no `RecognizeImage` here — this application names
no vision model, and Gemini needs none since its chat models already see images — and `GenerateImage`
reaches `/v1/images/generations` on whatever `OPENAI_BASE_URL` names, which a gateway that proxies
only chat will answer with a 404. Setting `IMAGE_MODEL_PROVIDER=google-genai` points it at Gemini's
image models instead, which is also the one way to edit an image from a reference here.

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
