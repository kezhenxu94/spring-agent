# spring-agent-provider-google-genai

> **Audience:** a developer changing or extending a model provider. An operator names one credential
> in `spring.ai.google.genai.api-key` (`GEMINI_API_KEY`) and points a kind of model at it with
> `spring.ai.model.chat`, `spring.ai.model.embedding` **and** `spring.ai.model.embedding.text`, or
> `spring.ai.model.image`; the property reference is that block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

Google Gemini, spoken natively: chat with thinking levels, Gemini's own embeddings, and the image
models that edit from a reference image.

## What "natively" means here, concretely

`spring-ai-google-genai` sits on Google's official `com.google.genai` SDK.
`GoogleGenAiChatModel` calls `genAiClient.models.generateContent(...)`, and the paths the SDK puts on
the wire are `{model}:generateContent`, `{model}:streamGenerateContent?alt=sse` and
`{model}:embedContent` against `generativelanguage.googleapis.com`. There is no
`/chat/completions` and no `/v1beta/openai/` anywhere in it. Configuring
`spring.ai.model.chat=google-genai` gets a `GoogleGenAiChatModel` bean, which
`GoogleGenAiProviderCoexistenceTest` asserts by type.

## Why a module at all, when Spring AI supports Gemini first-class

Not the reason `spring-agent-provider-dashscope` exists. That module exists because Spring AI ships
nothing for DashScope *and* DashScope's image API is not OpenAI-shaped, so it had to write one.

The right comparison is [`spring-agent-provider-openai`](../spring-agent-provider-openai/README.md),
which also wraps a first-class Spring AI starter and adds nothing but implementations of **core's own
contracts**: `UserChatClients`, `BuiltinModels`, `ProviderRejection`, and an `ImageModel` that reads
`ImageGenerationMetadata`. Spring AI does not know those exist, so a starter alone cannot satisfy
them, and its README states the rule directly — a provider module is what those need *"on top of
Spring AI's own starter for it"*.

Gemini having first-class support is what makes this module **small** rather than unnecessary: no
SDK plumbing, no wire protocol, no client to build. Five core contracts, two translations, and two
workarounds for real defects in Spring AI's own auto-configuration, below.

## Why not just point the OpenAI provider at Gemini's compatible endpoint

That works, and it stays supported — `spring-agent-provider-openai`'s README is right that a module
should not be written for an endpoint that speaks the protocol. Three things are lost by doing it,
which is what justifies also having this:

- **Thinking is a real option.** `GoogleGenAiChatOptions` has `thinkingLevel` and `thinkingBudget`;
  the compatible endpoint has whatever it chooses to map `reasoning_effort` onto, which several
  gateways get wrong in the direction of refusing the request outright.
- **Image generation is a different API, not a different host.** `gemini-2.5-flash-image` — "nano
  banana" — and `gemini-3-pro-image` generate through `generateContent` with image parts, and take
  reference images. `/v1/images/generations` has no field for one.
- **The compatible endpoint rejects a request Spring AI sends.** Told to build `stream_options`,
  Spring AI writes both fields from null-collapsing ternaries, so the request also carries
  `include_obfuscation: false` — a field only OpenAI knows. Gemini answers `Unknown name
  "include_obfuscation" at 'stream_options'` and fails the turn. The `spring.ai.openai.chat` block in
  every `application.yaml` here carries a comment about not setting `stream-options.include-usage`
  purely because of it.

## Carrying this module has to cost nothing, and that took work

This is the load-bearing part of the module and the first thing to read:
[`GoogleGenAiAutoConfigurationFilter`](src/main/java/me/kezhenxu94/springagent/provider/googlegenai/GoogleGenAiAutoConfigurationFilter.java).

Spring AI ships five Google GenAI auto-configurations. Three are gated on `spring.ai.model.<kind>`
and are harmless. **Two are not gated at all** — `GoogleGenAiImageConnectionAutoConfiguration` and
`GoogleGenAiEmbeddingConnectionAutoConfiguration` carry only `@ConditionalOnClass` — and their eager
`@Bean` methods throw when no credential is configured:

```
Incomplete Google GenAI configuration: Provide 'api-key' for Gemini API
or 'project-id' and 'location' for Vertex AI.
```

So adding this module to an application would break startup for every deployment that has not
configured Gemini, which today is all of them. A condition cannot fix that — the beans are Spring
AI's and are `@ConditionalOnMissingBean`, which is not something this module can satisfy. An
`AutoConfigurationImportFilter` can: it is Boot's own extension point, it runs over the candidate
list before any of them is evaluated, and core already uses the same mechanism in
`PersistenceAutoConfigurationFilter`. With no key the filter removes all five and this module
contributes nothing at all.

The check is on a **non-blank** value rather than presence, for the reason core's
`ConditionalOnNonBlankProperty` exists: the yaml spells it `${GEMINI_API_KEY:}`, so an unset variable
leaves the property present and empty.

Filtering silently is not the same as saying nothing. A deployment that names `google-genai` and sets
no key would otherwise start with no model of that kind and fail later about a missing bean;
[`GoogleGenAiConnectionCheck`](src/main/java/me/kezhenxu94/springagent/provider/googlegenai/GoogleGenAiConnectionCheck.java)
turns that into a startup error naming both the variable and the switch.

## Every `spring.ai.model.*` key now has to be named

A consequence of this module existing, and it reaches every `application.yaml` in the repository.

Spring AI gates each model auto-configuration with `matchIfMissing = true`, so "unset means `openai`"
was only ever true because one provider could answer. With two, a kind that names nothing gets
**both**, and the application fails to start with two `ChatModel` beans. Every application file now
names `chat`, `embedding`, `embedding.text` and `image` explicitly.

**Embeddings take two keys with the same value**, and this is the part nobody will remember:

| property | read by |
| --- | --- |
| `spring.ai.model.embedding` | Spring AI's OpenAI embedding auto-configuration |
| `spring.ai.model.embedding.text` | Spring AI's Google GenAI text-embedding auto-configuration |

Naming only one silences one provider and leaves the other matching by default — which happens to
give the right answer, but by accident of which key was named. Setting both is correct in either
direction. `GoogleGenAiProviderCoexistenceTest` asserts all of it, including the two traps.

Because the switch is per *kind*, mixing providers is a supported configuration rather than a
workaround. DashScope embeddings under a Gemini chat model is the one this was written for:

```yaml
spring.ai.model.chat: google-genai
spring.ai.model.image: google-genai
spring.ai.model.embedding: openai        # DashScope, via compatible-mode
spring.ai.model.embedding.text: openai
```

## Running on Gemini alone

Five variables, and one switch that is easy to miss:

```sh
GEMINI_API_KEY=...
GEMINI_CHAT_MODEL=gemini-2.5-pro
GEMINI_EMBEDDING_MODEL=gemini-embedding-001

CHAT_MODEL_PROVIDER=google-genai
EMBEDDING_MODEL_PROVIDER=google-genai      # fills both embedding keys from one variable
IMAGE_MODEL_PROVIDER=google-genai          # optional; `none` leaves no GenerateImage tool
TRANSCRIPTION_MODEL_PROVIDER=none          # the one that is easy to miss — see below
```

No `OPENAI_*` or `EMBEDDING_*` variable is needed, and none has to be set to a placeholder value
either.

**`GEMINI_EMBEDDING_DIMENSIONS` defaults to 1024, which is not Gemini's own width.**
`gemini-embedding-001` emits 3072 and is Matryoshka-trained, so 768, 1536 and 3072 are the sizes
Google recommends truncating to. 1024 is the default here because it is what every other provider in
this repository defaults to and what both Milvus collections are created at, so switching provider
does not silently break indexing. Moving up means moving `VECTORSTORE_MILVUS_DIMENSION` and
`RAG_MILVUS_DIMENSION` with it and rebuilding both collections.

Getting that wrong is worth recognising, because the message does not mention embeddings at all — a
run dies on `Stream processing failed`, and only the cause underneath says
`Incorrect dimension for field 'embedding': the no.0 vector's dimension: 1536 is not equal to
field's dimension: 1024`. The `theDimensionsAgree` case in each application's
`ModelProviderSwitchesTest` is what stops the shipped defaults drifting into it again.

**`TRANSCRIPTION_MODEL_PROVIDER=none` is not optional on a Gemini-only deployment.** Spring AI
ships no Google GenAI `TranscriptionModel`, so `spring.ai.model.audio.transcription` has only ever
had one answer and every application file left it at `openai`. On a deployment with no OpenAI
endpoint that still builds an `OpenAiAudioTranscriptionModel` — pointed at nothing — and core
registers `TranscribeAudio` on the mere existence of a `TranscriptionModel` bean. The model is then
offered a tool that can only fail, which is exactly what `ModelToolsConfiguration` exists to avoid.
Setting it to `none` makes Spring AI's auto-configuration back off and takes the tool with it.
`GenAiOnlyStartupTest` in `spring-agent-app-cli` asserts the whole arrangement against a started
context, including that no `TranscriptionModel` bean survives.

Two things were wrong until that test was written, both about what the rest of an application does
with a provider it is not using, and neither visible without booting one:

- the command line's `spring.ai.openai.*` placeholders had no defaults, and a *required* placeholder
  resolves as soon as anything binds the block — which the transcription auto-configuration does
  even when chat and embeddings have gone to Gemini. A Gemini-only laptop failed to start on
  `OPENAI_BASE_URL`, a variable it had no reason to hold;
- its `/model` command injected `OpenAiChatProperties` directly, a bean that does not exist when
  that auto-configuration has backed off. It reads the model name off the `ChatModel` bean now, and
  shows an endpoint only where the OpenAI connection is the one in force.

### One thing that is still OpenAI-shaped: the thinking price tier

`FeishuCardUpdater` and `SlackMessageUpdater` decide which half of `app.ai.model-pricing` a call is
billed at by asking `usage.getNativeUsage() instanceof CompletionUsage` and reading
`completionTokensDetails().reasoningTokens()`. On Gemini the native usage is a
`GoogleGenAiUsage`, so that test is false and **a thinking run is priced at the non-thinking rate**.

Token counts themselves are unaffected — they come from `Usage.getPromptTokens()` and
`getCompletionTokens()`, which are portable — so the footer's `↑`/`↓` figures are right and only the
`~$` figure is low. A deployment that configures no pricing for its Gemini models never sees it.

Left alone deliberately rather than patched: `GoogleGenAiUsage.getThoughtsTokenCount()` is right
there, but reading it would put a Gemini type into two chat-surface modules that are supposed to
depend on core and nothing beside it — which is the same coupling `CompletionUsage` already is, and
doubling it is not a fix. The right repair is a portable way to ask "how many of these were thinking
tokens", which belongs in core beside `Usage`, and is worth doing when a second provider's pricing
actually matters to somebody.

## Bring your own model, on Gemini

Yes — `app.ai.user-models.encryption-key` works on a Gemini deployment, and a person registering an
endpoint gets `GoogleGenAiUserChatClients`: a `Client` built with their own API key, their own model
name, and their chosen effort mapped onto a thinking level. `HttpOptions.baseUrl` is set only when
they name one, so a row with no base URL is the application's own endpoint with a different model
on it — the same meaning it has on the OpenAI provider. `GenAiByomTest` in
`spring-agent-app-feishu` asserts the whole arrangement against a started context.

**A person may now choose the protocol, not only the URL.** `UserModelConfig` carries a `provider`
— null meaning the deployment's own, which is what every row written before the field existed means
— and core's `DispatchingUserChatClients` picks between the `ProviderChatClients` beans on the
classpath per row. So on a Gemini deployment somebody can register an OpenAI endpoint and have their
key go out over `/chat/completions`, and on an OpenAI deployment they can register a native Gemini
one.

Two things follow, and both are asserted rather than assumed:

- **The select offers only what this classpath serves.** It is drawn from
  `UserChatClients.providers()`, which is exactly the published beans — never a fixed list. Offering
  a protocol no module implements would let somebody register an endpoint that could only ever fail,
  which is the shape of mistake this project avoids everywhere else.
- **With one provider module there is no select at all.** A choice of one is a question with no
  answers, and drawing it would imply the others are available here. Most deployments carry one, so
  most `/config` cards look exactly as they did.

A provider publishes its `ProviderChatClients` **whether or not it built the application's chat
model** — that is what makes the choice real — so bean order says nothing about which protocol the
deployment itself speaks. `spring.ai.model.chat` decides that, being the same word the rows hold.

That BYOM worked here at all was luck until it was checked. Both providers published
`UserChatClients` under `@ConditionalOnUserModels` and `@ConditionalOnMissingBean` alone, and the
OpenAI one takes an `OpenAiChatModel` — absent on a Gemini deployment. Whichever auto-configuration
was visited first won, and it happened to be the right one; nothing declared that order, and the
other order is an `UnsatisfiedDependencyException` at startup. That is gone now: a provider
contributes `ProviderChatClients`, core publishes the single `UserChatClients`, and nothing depends
on visit order. `OpenAiUserModelsBackOffTest` and `DispatchingUserChatClientsTest` hold it.

## One credential, and Spring AI reads it from two places

`GEMINI_API_KEY` is the whole of the credential. Spring AI does not quite agree: chat and image bind
`spring.ai.google.genai.api-key`, while the embedding connection binds **its own**
`spring.ai.google.genai.embedding.api-key`, which the common one does not reach. A deployment setting
only the common key gets working chat and image models and an embedding connection that falls through
to its Vertex AI branch and fails startup with `Google GenAI project-id must be set!` — a message
about a setting nobody was asked for.

[`GoogleGenAiDefaults`](src/main/java/me/kezhenxu94/springagent/provider/googlegenai/GoogleGenAiDefaults.java)
fills that in, the same way and for the same reasons `DashScopeDefaults` does: an
`EnvironmentPostProcessor` so it runs before anything binds, `LOWEST_PRECEDENCE` so the values it
reads to decide "already set" are there, and **only a blank target is filled**, so an embedding key
somebody named themselves still wins.

## Only the Gemini Developer API

One key; no project, no location, no service-account file. Spring AI's properties bind the Vertex AI
fields too, so a deployment could set them — and the filter above keys off the API key alone, so it
would remove the very beans that configuration was for and leave no models and no explanation. The
connection check refuses that at startup instead. Half-supporting it would be worse than not.

## What the image decorator translates

`GoogleGenAiAgentImageModel` is a decorator rather than an image model of its own, because unlike
DashScope's this *is* the API Spring AI already speaks. Two things need translating.

**Reference images have to be bytes.** Spring AI maps a `Media` carrying a `URI` to `Part.fromUri`,
i.e. `fileData.fileUri`, which on the Developer API accepts a Files API or GCS URI and never a public
link. Core resolves a local path or `file://` URL into bytes before a provider sees it — see
`MediaSources` in core, and the note below — so the common case arrives ready. A genuine `http(s)`
URL is downloaded here, because somebody has to and Gemini will not. That download lives in this
module rather than in core because it is a fact about this endpoint, not about the tool.

**Size is two fields.** `ImageGenerationMetadata.SIZE` arrives as the model typed it, untranslated,
which is that contract's whole point. `GoogleGenAiImageOptions` splits it into `aspectRatio`
(`16:9`) and `imageSize` (`2K`), so the vocabularies have to be told apart; `1024x1024` is reduced to
the ratio it describes, since there is no field for pixels. Anything unrecognised means the
endpoint's default and a log line, never a guess. `ImageGenerationMetadata.THINKING_MODE` maps to no
field here and is ignored.

### Core changed for this, and two other things got fixed

`GenerateImage` used to build every reference as a URL for the provider to fetch. That made a Gemini
edit of an image the agent had just generated absurd: publish the local file publicly with
`PublishFile`, then have Gemini refuse the link anyway. `MediaSources` in core now resolves a local
path, a `file://` URL or an `http(s)` URL to `Media`, confined to the asking user's own workspace.
Two bugs fell out of it:

- `VisionTools` never handled `file://` — `Path.of("file:///…")` is a relative path whose first
  segment is the literal `file:` — so a model handed a `file://` URL by `GenerateImage` and asked to
  describe it got nothing.
- `DashScopeImageModel` did `String.valueOf(media.getData())`, which for a `byte[]` is `[B@1f2a3b4c`:
  a well-formed request carrying a string that is not a URL. It now refuses local bytes with a tool
  error naming `PublishFile`, which is a thing the model can act on.

## Reasoning effort is a thinking level, and the ladders differ

Core owns the vocabulary — `none, minimal, low, medium, high, xhigh, max`, plus the `not-sent`
sentinel — so a user's stored choice means the same thing whichever provider serves their runs.
Gemini offers `MINIMAL, LOW, MEDIUM, HIGH` and nothing above, so the top three land on `HIGH`: asking
for more than an endpoint has is not an error, it is the most it will do.

`none` is the one that is not a level. Gemini's enum has no "off" — `MINIMAL`'s own documentation
says it "does not guarantee that thinking is off" — so `none` sets a thinking budget of zero and
leaves the level alone. `not-sent` sets neither field, which is a third state and not a synonym. See
[`GoogleGenAiThinking`](src/main/java/me/kezhenxu94/springagent/provider/googlegenai/GoogleGenAiThinking.java).

## Two things that come free, and were checked rather than assumed

- **Gemini's tool-schema conversion.** Gemini needs tool schemas in OpenAPI shape with upper-cased
  type values, which is `GoogleGenAiToolCallingManager`'s job. That looked like a conflict with
  core's own decorated `ToolCallingManager` — `Intercepting` over `Localizing` over `Describing` —
  but `GoogleGenAiChatModel`'s constructor wraps whatever manager it is handed unless it already is
  one, so core's stack is preserved and the conversion applied on top. Nothing to do. `GoogleGenAiUserChatClients`
  must still pass the context's manager, though, for the reason `UserChatClients` gives.
- **Native-image hints.** [`aot/GoogleGenAiRuntimeHints`](src/main/java/me/kezhenxu94/springagent/provider/googlegenai/aot/GoogleGenAiRuntimeHints.java)
  is almost empty, and deliberately. `OpenAiSdkRuntimeHints` is large because openai-java ships
  ~12,000 *query-only* entries that have to be upgraded to invoke-capable; the `google-genai` SDK
  ships its `auto-value` reflect-config with `allDeclaredConstructors` and `allDeclaredMethods`
  already set, alongside proxy, resource, JNI and serialization configs, and Spring AI covers its own
  model classes. Copying that class here would be a file that does nothing.

## No vision client, and no transcription

Both are absences with reasons rather than gaps.

Gemini's chat models are natively multimodal, so `RecognizeImage` goes through the application's own
client — which the OpenAI provider's README calls the normal case. A `visionChatClient` bean is for a
deployment whose vision model is a *different endpoint*, which Gemini's is not.

Spring AI ships no Google GenAI `TranscriptionModel`, so core registers no `TranscribeAudio` on a
Gemini-only deployment. A tool the model can see is a tool it will try, and an absent one beats one
that always fails.
