# spring-agent-provider-dashscope

> **Audience:** a developer changing or extending a model provider. An operator names one credential
> in `spring.ai.dashscope.api-key` (`DASHSCOPE_API_KEY`) and selects the image API with
> `spring.ai.model.image=dashscope` (`IMAGE_MODEL_PROVIDER`); the property reference is that block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

Alibaba Cloud DashScope: its image API, its vision endpoint, and one key for the OpenAI-compatible
rest.

## It depends on spring-agent-provider-openai, deliberately

This is the only dependency in this repository between two modules that are not core, so it is worth
saying why rather than leaving it to be found in a build file.

It is an `implementation` dependency rather than `api`, which matters to whoever reads an
application's build file: nothing this module exposes is an OpenAI type — the two bean methods that
name one are package-private — so an application naming both providers is naming two things it
actually needs, rather than one plus a line that does nothing.

DashScope's chat, embedding and transcription endpoints **are** the OpenAI wire protocol — that is
what `compatible-mode/v1` means. Writing a `ChatModel` here would be a second copy of the OpenAI SDK
plumbing sending the same bytes, plus a second copy of the whole per-user endpoint machinery in
[`spring-agent-provider-openai`](../spring-agent-provider-openai/README.md)'s `usermodels` code. So
for those three, this module's job is to say *where* the endpoint is, not to speak to it:
`DashScopeDefaults` fills in `spring.ai.openai.*` from `spring.ai.dashscope.*`.

`spring.ai.model.chat` therefore stays `openai` on a DashScope deployment, and that is not a
workaround — it names the protocol, and the protocol is right.

## One credential

DashScope issues a single key and serves chat, embeddings, vision and image generation off it, so
`DASHSCOPE_API_KEY` is the whole of the *credential* a deployment names. Configuring each of those
endpoints separately would be the same secret written four times, and four places for it to go stale.

It is not the whole of the *configuration*: `DASHSCOPE_CHAT_MODEL` and `DASHSCOPE_EMBEDDING_MODEL`
still have to be given, because no endpoint has a default model and an empty name is refused by all
of them. `OpenAiConnectionCheck` in
[spring-agent-provider-openai](../spring-agent-provider-openai/README.md) is what says so at startup
instead of letting the first run fail.

**`DashScopeDefaults` fills in only a *blank* target**, and that is the load-bearing part rather than
politeness. The usual way to contribute defaults is a lowest-precedence property source — what
`EventsDefaults` does — and it would not work here: every `application.yaml` in this repository says
`api-key: ${OPENAI_API_KEY:}`, which leaves the property *present and empty* when nobody set the
variable, and present beats any source added last. So each target is read as resolved and written
only where nothing gave it a real value. This is the same trap `ConditionalOnUserModels` exists for.

The upshot for an operator: an explicit `OPENAI_*` still wins. Setting both points chat at that
gateway and leaves DashScope serving only what is its own.

Two things are **not** filled in:

- **the transcription endpoint** — DashScope serves it, but under model names Spring AI's OpenAI
  defaults do not know, so supplying only a host would produce a client that looks configured and
  asks for a model that is not there;
- **`app.ai.embedding.batch-size`** — core's default of 10 is already under DashScope's ceiling. That
  ceiling is **20**: a larger batch is rejected outright with `batch size is invalid, it should not
  be larger than 20`, regardless of how few tokens it carries, which is why core batches by row
  count at all rather than by Spring AI's token-count strategy. Raise `EMBEDDING_BATCH_SIZE` to 20
  and no further; `EMBEDDING_CONCURRENCY` is the knob that actually makes a cold index quick.

## One host, and the paths are this module's business

`spring.ai.dashscope.base-url` is a **host with no path** — `https://dashscope.aliyuncs.com`, the
international `dashscope-intl.aliyuncs.com`, or a Model Studio workspace's own
`ws-<id>.<region>.maas.aliyuncs.com`. Both APIs this project uses live under it, and this module
appends the paths:

| | |
| --- | --- |
| chat, embeddings, vision | `/compatible-mode/v1` — the `/v1` included, because `spring.ai.openai.base-url` means the whole endpoint and the OpenAI SDK appends nothing to it |
| image generation | `/api/v1/services/aigc/multimodal-generation/generation` |

Asking a deployment for those URLs in full would be asking it to write the same host repeatedly and
to know two paths that are not its choice — and getting either wrong gives a 404 from a URL that
looks right. A trailing slash on the host is trimmed, because a copied URL usually has one and the
double slash that follows is routed by some gateways and refused by others.

Each model may still name a host of its own — `spring.ai.dashscope.vision.base-url` and friends,
empty by default, the way Spring AI lets a model override the common connection. A workspace that
serves only one of the models is the case for it. **Those are hosts too, never full endpoints:** one
rule for the whole block, since two would be a trap.

## The image API is the part that is genuinely different

`DashScopeImageModel` is not a configuration of Spring AI's image model, because it is not the same
API. It posts to a multimodal generation endpoint — not under `compatible-mode`, and not even the
same host — whose request is chat-shaped (`input.messages[].content[]`, text and reference images
interleaved) beside a `parameters` object, and whose answer is
`output.choices[].message.content[].image`.

Nothing about either end is checked by a compiler: the request is a `Map` assembled by hand, and the
response records have to match a JSON document written elsewhere. `DashScopeImageModelTest` pins both
against a `MockWebServer`, and is the thing to update first when this endpoint changes.

Two consequences worth knowing:

- **Reference images must be publicly reachable URLs.** There is no upload path here and deliberately
  none — DashScope wants a signed OSS object, which means credentials for a bucket this project does
  not have. Core's `GenerateImage` says so in its own tool description: publish the local file with
  `PublishFile (visibility=public, ttl=30m)` and pass the link it returns.
- **The answer is a URL that expires within the hour**, never base64. Core downloads it into the
  user's artifacts directory immediately, which is what makes the answer outlive the link.

Selecting this provider and configuring nothing for it fails at startup rather than at the first
call. That is the choice `spring.ai.model.image=none` exists to express — the alternative is a
`GenerateImage` the model is offered, reaches for, and gets a 401 from, which reads to it as an
endpoint to retry.

## Vision is a second endpoint, and naming it is what turns the tool on

Vision here is a different model (`qwen3-vl-*`) from the one a run's turns go to, on the same
endpoint unless `spring.ai.dashscope.vision.base-url` names a host of its own. It is still the OpenAI
wire protocol, so it is still built on Spring AI's `OpenAiChatModel`; only the model name differs.

Naming no `vision.model` means no `visionChatClient` bean and therefore no `RecognizeImage` tool at
all, rather than a tool that refuses every call. See
[`spring-agent-provider-openai`](../spring-agent-provider-openai/README.md) for why that distinction
matters to a model.

**The condition is `@ConditionalOnNonBlankProperty`, not `@ConditionalOnProperty`, and that is not a
detail.** The yaml spells this setting `${DASHSCOPE_VISION_MODEL:}`, so an unset variable leaves the
property *present and empty* — which `@ConditionalOnProperty` calls configured, since it matches
anything that is not the literal `false`. Gated that way the bean was built with an empty model name
and every call came back `400 InvalidParameter: The length of model should be between 1 and 512`,
which reads to the agent as a broken endpoint to retry rather than a feature nobody turned on.
`DashScopeVisionWiringTest` covers the blank case by name.
