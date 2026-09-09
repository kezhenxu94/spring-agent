# spring-agent-provider-openai

> **Audience:** a developer choosing or writing a model provider. An operator points it at an
> endpoint with `spring.ai.openai.*` (`OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`,
> `EMBEDDING_*`); the property reference is that block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

The OpenAI-compatible provider, and the baseline one: chat, embeddings, transcription, images, and
the endpoints a person may register for themselves.

"OpenAI-compatible" is doing real work in that sentence. This module is not for OpenAI the company —
it is for the wire protocol, which is what almost every gateway, self-hosted server and cloud
inference product speaks. `spring-agent-provider-dashscope` is built on it for exactly that reason.

## Spring AI does most of this, and that is the design

Every model bean comes from Spring AI's own `spring-ai-starter-model-openai`, which this module
depends on and does not reimplement. That starter binds `spring.ai.openai.*` and publishes a
`ChatModel`, an `EmbeddingModel`, a `TranscriptionModel` and an `ImageModel`, each gated on
`spring.ai.model.<kind>` being `openai`.

So **the provider switch is Spring AI's, not this project's**. There is no `app.ai.provider.type`
beside `app.persistence.type` and `app.ai.tools.shell.type`, and there should not be: a second switch
over the same decision is a second thing to keep in step. Naming a provider under `spring.ai.model.*`
is what makes the others back off, which is why two `spring-agent-provider-*` modules can sit on one
classpath without the ambiguity two chat surfaces would be.

Core, correspondingly, names no provider at all — it injects Spring AI's interfaces and lets
`ModelToolsConfiguration` register the tools that need one only where a provider published it. See
[`spring-agent-core/README.md`](../spring-agent-core/README.md).

## What this module adds on top

Four things, each because Spring AI has no way to know it:

- **`OpenAiUserChatClients` and `OpenAiBuiltinModels`** — two of the three contracts a provider
  implements itself (`core/usermodels/`; the third is `ProviderRejection`, below). Spring AI's models are built once, at
  startup, from configuration; "build a client for an endpoint somebody typed into a chat five
  seconds ago" and "ask an endpoint what it serves" are neither of those. Read
  [`ApplicationEndpoint`](src/main/java/me/kezhenxu94/springagent/provider/openai/ApplicationEndpoint.java)
  before touching either: `OpenAiChatProperties.toOptions()` carries no base URL and no credential,
  and the SDK handed no credential goes looking in the environment — so a client built from the
  model bean's own options can end up quietly pointed at the public OpenAI endpoint. A hand-built
  chat model also has to be handed the context's `ToolCallingManager`: the advisor `SpringAgent`
  registers only *executes* tool calls, while the request's tool list is still resolved by the
  model, so a model built without one offers the endpoint tool definitions none of the runtime's
  rewrites reached — no `_display_description`, so no tool call has a title on a card, and no
  localized descriptions. `OpenAiUserModelToolsTest` reads that off the wire.
- **`OpenAiErrorBodyLoggingInterceptor`** — the only place a rejected request's body still exists.
  openai-java renders a non-JSON error envelope as the words `400: Unknown`, so without this a run
  fails for literally unknowable reasons.
- **`OpenAiProviderRejection`** — the same bytes again, this time so core can log them beside the id
  of the run that was refused. The interceptor knows the HTTP call; only core knows the run.
- **`OpenAiAgentImageModel`** — a thin decorator, for the one place OpenAI's image API is narrower
  than the tool. `/v1/images/generations` takes a prompt and nothing else, and Spring AI ignores
  `ImageMessage.getMedia()` in silence, so a reference image would produce an unrelated picture
  reported as a success. It fails instead. It also translates `16:9` and friends into the
  `WIDTHxHEIGHT` this API names.

## The vision client is optional, and usually absent

`spring.ai.openai.vision.model` names a separate model for `RecognizeImage`, on the main connection
unless `.base-url`/`.api-key` say otherwise. A `base-url` here is the **whole endpoint**, `/v1`
included — what it means everywhere under `spring.ai.openai.*`, since the OpenAI SDK appends nothing
to it. `spring-agent-provider-dashscope` deliberately differs, a base URL there being a host,
because that module serves two APIs off one host and owns both paths. Naming nothing is the normal case here — GPT-class chat
models are already multimodal, so the tool goes through the application's own client.

Naming nothing is *not* the same as a tool that refuses. When no bean called `visionChatClient`
exists, core registers no `RecognizeImage` at all: a tool the model can see is a tool it will try,
and one that fails on configuration it cannot change reads to it as an endpoint to retry.

The client's options are a copy of the application's own resolved ones — the connection, the
timeout, the sampling parameters — with one field dropped rather than copied: the reasoning effort.
`spring.ai.openai.chat.options.reasoning-effort` says how hard the model that runs a turn should
think, and the vision model is a different model asked one question about an image. Copied over, it
is sent to a model nobody configured it for, and a gateway that translates `reasoning_effort` into
its own thinking parameter then refuses every call with an error naming neither the setting nor the
tool. See `OpenAiProviderAutoConfiguration#visionOptions`.

The gate is core's `@ConditionalOnNonBlankProperty` rather than `@ConditionalOnProperty`, because
the yaml spells the setting `${OPENAI_VISION_MODEL:}` and an unset variable therefore leaves the
property *present and empty* — which `@ConditionalOnProperty` treats as configured, matching
anything that is not the literal `false`. That builds a client asking for a model called `""`, and
the endpoint's rejection reads like a broken gateway. `OpenAiVisionWiringTest` covers it.

## Writing a third provider

If the endpoint speaks this protocol, do not write one — configure this module, the way
`spring-agent-provider-dashscope` does. If it genuinely does not (Anthropic, Bedrock, Vertex), a new
`spring-agent-provider-*` module needs, on top of Spring AI's own starter for it:

1. an auto-configuration named in `AutoConfiguration.imports`, **and added to the `afterName` list in
   core's `ModelToolsConfiguration`** — it is matched textually, so a module missing from it silently
   loses the image, vision and transcription tools;
2. implementations of core's `UserChatClients` and `BuiltinModels`, or no per-user models on that
   provider;
3. a `visionChatClient` bean if vision is a separate endpoint there;
4. a `ProviderRejection` bean, or rejections that log as bare stack traces;
5. an `aot` `RuntimeHints` for whatever its SDK reaches by name.

And a rule that applies to any of them: where a setting *is* the switch — a model name, a
credential — gate it with core's `@ConditionalOnNonBlankProperty`. `@ConditionalOnProperty` calls
`${SOME_VAR:}` configured when nobody set the variable.
