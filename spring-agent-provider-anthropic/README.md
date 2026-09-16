# spring-agent-provider-anthropic

> **Audience:** a developer changing or extending a model provider. An operator points chat at this
> module with `spring.ai.model.chat=anthropic`, chooses a host with
> `spring.ai.anthropic.backend` (`ANTHROPIC_BACKEND`), and names either an API key or a Google Cloud
> project; the property reference is that block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

Anthropic's Claude models, served either by Anthropic directly or by a Google Cloud project through
Vertex AI — one module, because the two differ in nothing this project would otherwise write twice.

## Why one provider with a backend, rather than two modules

The request body, the options, the streaming envelope and the tool protocol are identical on both.
Vertex changes two things: the host a request goes to, and how it is signed. The Anthropic SDK
expresses exactly that seam as `com.anthropic.backends.Backend`, and Spring AI's own HTTP layer —
`SpringAiAnthropicHttpClient` — drives a `Backend` entirely through the interface. So the whole of
the Vertex path is:

```
VertexBackend  →  SpringAiAnthropicHttpClient  →  ClientOptions  →  AnthropicClient{,Async}
                                                                        ↓
                                              Spring AI's own AnthropicChatModel, unmodified
```

Everything above that line — options, tool calling, streaming, observations — is Spring AI's code
serving Vertex without knowing it. A second module would have duplicated all of it to change a
hostname.

The cost is one class, [`VertexAnthropicClients`](src/main/java/me/kezhenxu94/springagent/provider/anthropic/VertexAnthropicClients.java),
and it is worth reading before changing anything here: three of its details are load-bearing and
none is visible from the outside.

## `spring.ai.anthropic.backend` is a switch of *this project's*, and that is unusual

Every other provider switch here is Spring AI's — `spring.ai.model.<kind>` — and
[`spring-agent-provider-openai`'s README](../spring-agent-provider-openai/README.md) is emphatic
that a second switch over the same decision is a second thing to keep in step. This one is not over
the same decision. `spring.ai.model.chat` chooses **which provider serves a kind of model**;
`spring.ai.anthropic.backend` chooses **which host that provider's protocol is spoken to**. Spring
AI has no property for the second question, because its auto-configuration hard-wires
`AnthropicBackend` and exposes no seam for another.

## The two auto-configurations, and how only one ever wins

On the `anthropic` backend this module contributes **no chat model at all**. Spring AI's
`AnthropicChatAutoConfiguration` builds it from `spring.ai.anthropic.*`, which is already right;
there is deliberately no code of ours on that path.

On `vertex` there has to be, so
[`AnthropicVertexAutoConfiguration`](src/main/java/me/kezhenxu94/springagent/provider/anthropic/AnthropicVertexAutoConfiguration.java)
declares itself `beforeName` Spring AI's, whose bean is `@ConditionalOnMissingBean`. Ours registers
first, theirs backs off, and there is one bean either way — with the decision in a single property
rather than in an ordering nobody can see. `AnthropicVertexWiringTest` pins both directions and
asserts the class named in `beforeName` still resolves, because it is matched textually.

The bean's return type is `AnthropicChatModel` and not `ChatModel`, and that is not style:
`@ConditionalOnMissingBean` on Spring AI's method infers the type from *its* return type, so a
definition typed as the interface would not satisfy it and both would be built.

## The trap: `AnthropicChatModel` has two clients

Its constructor defaults them independently:

```java
this.anthropicClient      = requireNonNullElseGet(anthropicClient,      () -> AnthropicSetup.setupSyncClient(...));
this.anthropicClientAsync = requireNonNullElseGet(anthropicClientAsync, () -> AnthropicSetup.setupAsyncClient(...));
```

Both fallbacks build an `AnthropicBackend` pointed at Anthropic, filling the credential from
`ANTHROPIC_API_KEY` in the **process** environment when the options carry none. A model given only
the synchronous client is therefore half Vertex-backed: blocking calls reach the project, and every
**streaming** call — which is what a run actually uses — reaches `api.anthropic.com`.

On a machine with no `ANTHROPIC_API_KEY` that is a 401 nobody can explain. On a machine with one it
is worse, because nothing fails: the agent works, and the usage is billed to whoever owns that key.
`VertexAnthropicClientsTest` asserts client identity rather than non-nullity for this reason, and
`VertexAnthropicClients.create` returns a `Clients` pair so that passing one and forgetting the
other is not expressible.

The same fact explains why [`AnthropicUserChatClients`](src/main/java/me/kezhenxu94/springagent/provider/anthropic/AnthropicUserChatClients.java)
makes the opposite choice and builds **no** client: it puts the endpoint in
`AnthropicChatOptions` — which carries `apiKey`, `baseUrl`, `timeout` and `maxRetries` — and lets
Spring AI build both clients from it.

## Carrying this module costs an unconfigured deployment nothing

This is where Anthropic differs from Gemini and why there is no `AutoConfigurationImportFilter` here.
`spring-agent-provider-google-genai` needs one because two of Spring AI's Google GenAI
auto-configurations carry only `@ConditionalOnClass` and throw from an eager `@Bean`. Anthropic ships
a single auto-configuration, gated on `spring.ai.model.chat`, and it builds a client without a
credential rather than refusing to start.

`AnthropicCarriedButUnconfiguredTest` is what says so, and it exists so the day it stops being true
is a failing build rather than a broken deployment that had nothing to do with Claude.

## What it does not serve

Chat, and nothing else. Anthropic has no embeddings API, no image generation and no transcription,
so:

- **a deployment on Claude is always a mixed one.** `spring.ai.model.embedding` *and*
  `spring.ai.model.embedding.text` must name a provider that serves embeddings — `openai` on any
  OpenAI-protocol gateway, or `google-genai` — or there is no knowledge base and no tool-search
  index. This is supported rather than a workaround: the switches are per kind precisely so the sets
  mix. `AnthropicProviderCoexistenceTest` pins it.
- **there is no `visionChatClient` bean**, and that is an absence with a reason rather than an
  omission: Claude's chat models are natively multimodal, so `RecognizeImage` goes through the
  application's own client. `spring.ai.openai.vision` and `spring.ai.dashscope.vision` exist because
  on those providers vision genuinely is a different endpoint.

## Bring your own model stops at Anthropic's API

A person may register their own Anthropic endpoint and key, as they can for OpenAI and Gemini. A row
naming Vertex is refused, and the boundary is honest rather than lazy: a `UserModelConfig` carries a
base URL, a model and an encrypted **token**, whereas a Vertex client needs Google credentials — a
service-account JSON or an ambient identity — which is not a thing a person pastes into a chat and
not a thing this deployment should hold per user. There is nothing a user could supply that turns
into a per-user Vertex client except the deployment's own credential, and lending every row the
deployment's Google identity is not bring-your-own-model.

**A row with no credential of its own borrows the application's built clients, not its API key**,
and that distinction is load-bearing rather than pedantic. `UserModelRegistry.DEFAULT_ROW` — written
whenever somebody picks a reasoning effort for the application's own model — is the commonest row
there is, and it carries no credential by design. Lending an API key could only ever work on the
`anthropic` backend; on Vertex there is none, so that row was refused, `DispatchingUserChatClients`
fell back, and the effort the person chose was silently dropped on every run while each logged a
stack trace. Reusing the built clients is right on both backends and cheaper on each: the connection
is already open, already carries the error-body interceptor, and on Vertex already holds a Google
credential no row could have supplied. Only the options differ, which is all such a row asks for.

The refusal that remains is much narrower: a bare row on a deployment whose chat model belongs to
*another provider*, where there is genuinely nothing of this module's to borrow. That still throws,
and the dispatcher still turns it into the application's own client and a warning.

`requiresBaseUrl()` is `false` on both backends: Anthropic has one well-known host, so asking for a
URL asks somebody to invent one. A URL that *is* given is honoured, for a gateway re-serving the
protocol.

## Reasoning effort is a token budget

Core's ladder — `none, minimal, low, medium, high, xhigh, max` — is the OpenAI one, and core owns it
so a user's stored choice means the same thing whichever provider serves their runs. Anthropic has
no ladder: extended thinking is `budget_tokens`, how many tokens the model may spend reasoning
before it answers. [`AnthropicThinking`](src/main/java/me/kezhenxu94/springagent/provider/anthropic/AnthropicThinking.java)
is that table, and the numbers are a judgement rather than a translation. Two API constraints shape
it: a budget must be at least 1024, and strictly less than `max_tokens` — which is why every rung
sits below the 8192 the applications configure, and why raising one means raising the other.

`none` becomes Anthropic's own disabled configuration rather than a budget of zero, which is rejected
rather than understood. `not-sent` sets nothing at all — a third state, not a synonym, and the way
out for a gateway that refuses a request carrying a thinking configuration it does not implement.

## Two defaults this module deliberately overrides

Both are cases where the SDK not failing is the problem.

- **The model.** `AnthropicChatOptions` carries a `DEFAULT_MODEL` and applies it whenever nothing is
  configured, so a deployment that named no model runs on one nobody chose. On Vertex it is wrong
  twice over, because Vertex has model ids of its own: older ones carry an `@` date where
  Anthropic's carry a hyphen (`claude-sonnet-4-5@20250929`, not `claude-sonnet-4-5-20250929`), and
  newer ones are undated entirely (`claude-opus-5`, `claude-sonnet-5`). Take the id from the Model
  Garden card rather than from Anthropic's own docs. `AnthropicConnectionCheck` fails startup
  instead.
- **The timeout.** The SDK's default is 60 seconds, routinely less than a run takes once it streams
  a long answer and calls tools in between. The symptom is a run dying partway through with a read
  timeout and no explanation from the endpoint. `application.yaml` names 30 minutes, matching the
  OpenAI block, and `VertexAnthropicClients` carries the same value as a floor.

A third belongs with them, and is the reason `AnthropicConnectionCheck` throws rather than warns:
with `spring.ai.anthropic.api-key` blank the SDK falls back to `ANTHROPIC_API_KEY` and
`ANTHROPIC_AUTH_TOKEN` in the **process** environment. A deployment that named `anthropic` and forgot
the key would not fail on a machine that exports one — it would work, and bill somebody else.

## The Vertex credential

Application Default Credentials by default, which covers the three usual ways a deployment proves
itself: `gcloud auth application-default login` on a laptop, `GOOGLE_APPLICATION_CREDENTIALS`
pointing at a file, and workload identity on GKE or Cloud Run. `credentials-location` names a
service-account JSON for the deployment that has none of those — a container handed a mounted secret.

Credentials are scoped to `https://www.googleapis.com/auth/cloud-platform` explicitly. That is a
no-op on a credential that already carries scopes or does not take them, and the case it exists for
is a service-account key, which arrives unscoped and fails to refresh with a message naming neither
Vertex nor this deployment.

The location is part of the hostname rather than a header —
`<location>-aiplatform.googleapis.com`, with `global`, `us` and `eu` having hosts of their own — so
a location that does not serve the model answers 404 rather than falling back, and
`spring.ai.anthropic.base-url` is ignored on this backend.

**Prefer `global`.** Google recommends it, it routes dynamically for availability, and it is the
cheapest: a named region or the `us`/`eu` multi-regions carry a 10% premium and exist for data
residency or provisioned throughput. It also decides which models are reachable — the newest Claude
models are served only from `global` and the multi-regions, so pinning a single region limits what
the deployment can ask for.

## Native image is unverified here

Worth stating plainly rather than leaving to be discovered. `anthropic-java-core` ships **no**
`META-INF/native-image` directory at all — only a ProGuard rules file, which GraalVM does not read —
and `spring-ai-anthropic` ships no `aot` package either. That is unlike both other SDKs here:
openai-java ships query-only entries that `OpenAiSdkRuntimeHints` upgrades, and google-genai ships
invoke-capable ones, which is why `GoogleGenAiRuntimeHints` registers almost nothing.

So [`AnthropicRuntimeHints`](src/main/java/me/kezhenxu94/springagent/provider/anthropic/aot/AnthropicRuntimeHints.java)
is a third shape: it enumerates the SDK's own jar at build time and registers the packages a run
actually reaches, narrowly — the jar holds over eight thousand classes and most of them are the beta
and batch APIs this project never calls, so roughly 1,500 are registered rather than all of them.
`AnthropicRuntimeHintsTest` asserts both halves, because a registrar that quietly stopped finding
the jar and one that quietly stopped narrowing both look like a passing build.

**What has been verified, and what has not.** `./gradlew :spring-agent-app-cli:nativeCompile
-Pnative` with `CHAT_MODEL_PROVIDER=anthropic` set for the build succeeds, and the resulting binary
starts on this provider and reaches its prompt — so the context, the chat model and the tool
registrations survive AOT. **A model call from a native image has not been exercised**, since that
needs a real credential, and it is the part most likely to want a hint this class does not give:
serializing `MessageCreateParams` is where the SDK's reflection is densest, and the Kotlin metadata
it also relies on comes from the GraalVM reachability-metadata repository rather than from here.

Note also that `springagent.native.gradle` bakes persistence, vector store, shell and image into
`processAot` but **not** `spring.ai.model.chat`, so a native image takes whatever
`CHAT_MODEL_PROVIDER` said at *build* time. Building a native CLI that talks to Claude means setting
that variable for the build.

## One thing that is wrong, and is wrong for Gemini too

Thinking tokens are priced at the non-thinking rate. `FeishuCardUpdater` and `SlackMessageUpdater`
test `usage.getNativeUsage() instanceof CompletionUsage`, which is OpenAI's type; on Anthropic the
native usage is the SDK's own. Token **counts** are unaffected — only the money figure. This is the
second provider affected, which is the argument for repairing it portably on core's `Usage` rather
than per provider.
