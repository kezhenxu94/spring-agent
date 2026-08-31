# Contributing

This document is for changing this repository — most often by adding an integration: a new chat
surface, a new system whose webhooks the agent watches, a new storage backend, a new set of tools.
For embedding the library in your own application, read [sdk.md](sdk.md).

- [Build, test, lint](#build-test-lint)
- [Layout](#layout)
- [Adding an integration](#adding-an-integration)
  - [A chat surface](#a-chat-surface)
  - [A webhook event source](#a-webhook-event-source)
  - [A set of tools](#a-set-of-tools)
  - [A persistence backend](#a-persistence-backend)
  - [A shell backend](#a-shell-backend)
  - [A knowledge base](#a-knowledge-base)
- [Conventions](#conventions)
- [Documentation](#documentation)

## Build, test, lint

Gradle multi-module build. The `Makefile` wraps the three commands used every day:

```sh
make                                             # ./gradlew build
make test                                        # ./gradlew test
make TESTS='ChatMemoryRedisTest' test            # a single test class
make TESTS='*.SpringAgentTest.someMethod' test   # a single method
make lint                                        # ./gradlew spotlessApply
```

Scoping to one module is much faster than the aggregate task:
`./gradlew :spring-agent-core:test --tests 'SpringAgentTest'`.

Watch out: `failOnNoMatchingTests = false` is set in the common conventions, so a typo'd `--tests`
pattern passes silently rather than failing. Confirm the test actually ran.

Tests need a running Docker daemon — `AbstractIntegrationTest` starts MongoDB and Redis via
Testcontainers. Unit tests sit beside the class they cover; cross-cutting integration tests live in
`spring-agent-app-feishu/src/test`. A behaviour that must hold for every persistence backend goes in
`AbstractPersistenceBackendTest`, which is run once per backend by
`PersistenceJpaTest`/`PersistenceMongoTest`/`PersistenceRedisTest` — add the assertion there rather
than to one backend's test.

There is one `ThreadPoolTaskScheduler` in the runtime, defined in `SpringAgentCoreAutoConfiguration`
and named `taskScheduler`, and everything timed shares it: `ScheduledTaskSweeper`,
`SituationSweeper`, and every `@Scheduled` method — that name is the one Boot's scheduling
annotations resolve to. Because it is built by hand it also displaces Boot's own, so
`spring.task.scheduling.*` is read by nothing; `app.scheduling.pool-size` is the knob. Neither sweep
holds its thread for the length of a run, so anything *blocking* added here needs the pool raised
with it.

Formatting is Spotless with `googleJavaFormat().reflowLongStrings()`, and `spotlessCheck` runs as
part of `build`. Run `make lint` before committing.

There is **no CI workflow that builds or tests** — the three workflows publish only. Verification is
local; run `make` before pushing.

Other tasks:

```sh
./gradlew :spring-agent-app-feishu:bootRun   # the Feishu server
./gradlew :spring-agent-app-slack:bootRun    # the Slack server
./gradlew :spring-agent-app-cli:bootRun      # the command line (stdin/tty wired for JLine)
./gradlew :spring-agent-app-web:bootRun      # the browser UI, on :8080
./gradlew :spring-agent-app-feishu:bootBuildImage   # container image (Paketo buildpack, no Dockerfile)
./gradlew :spring-agent-app-cli:nativeCompile -Pnative
```

`-Pnative` is required for any native task: the GraalVM plugin is applied conditionally so that a
plain `bootBuildImage` does not silently turn into a native build.

`spring-agent-app-feishu/src/main/resources/application-local.yaml` holds the switches for running the
server against real services. It is loaded by the `local` profile and every block in it is gated on
a second profile of its own, so `local` alone still changes nothing — keep that shape when adding a
block, or the next person to add one turns on yours by accident:

```sh
./gradlew :spring-agent-app-feishu:bootRun --args='--spring.profiles.active=local,integration-email'
```

It exists for the features whose settings have to agree across several blocks before anything will
start — the mailbox source needs four, and refuses to start rather than run half-configured — so
that turning one on is a profile rather than a shell history. It is committed, so nothing secret
goes in it: credentials are environment variables and come from `.env`, and `application.yaml`
remains the reference for what every property means. `LocalProfilesTest` binds it under each
profile, because both ways this file can be wrong — a block that never activates, and one that
activates when it should not — are silent.

Java bytecode targets **21** (`options.release = 21`), built with a **GraalVM 25** toolchain because
`native-image` ships with it. Do not use APIs newer than 21. Exact dependency versions live in
`gradle/libs.versions.toml`, where several pins carry load-bearing comments explaining why the BOM
version is wrong — read the comment before changing one. Lombok is configured with fluent accessors
(`lombok.config`), so getters are `foo()`, not `getFoo()`.

## Layout

```
spring-agent-core                     the runtime and every SPI; backend-agnostic
spring-agent-persistence-{jpa,mongodb,redis}
spring-agent-tools-shell-{kubernetes,docker}
spring-agent-events                   observations -> situations -> a triage run
spring-agent-integration-{github,gitlab,grafana}   webhook readers for spring-agent-events
spring-agent-integration-feishu       Feishu/Lark chats and cards as a surface
spring-agent-integration-slack        Slack channels and Block Kit messages as a surface
spring-agent-rag-milvus               the knowledge base; the only KnowledgeBase implementation
spring-agent-app-feishu               deployable server, Feishu surface; depends on every optional module
spring-agent-app-slack                the same server, Slack surface
spring-agent-app-cli                  laptop command line; jpa + local shell only
spring-agent-app-web                  browser surface; no integrations, Feishu OAuth for login only
```

`spring-agent-core` must stay free of any persistence backend. This is enforced by
`checkRuntimeClasspathIsolation` (wired into `check`, defined in
`buildSrc/.../springagent.classpath-isolation.gradle`, configured at the bottom of
`spring-agent-core/build.gradle`): it fails the build if Hibernate, the Mongo driver, Jedis, Milvus,
fabric8 and friends reach core's runtime classpath. If that task fails, a dependency became `api` or
grew a new transitive — fix the dependency, do not widen the allow-list.

The same rule holds in spirit for every module: a compile dependency points from an integration to
core, never the other way, and never from one integration to another. Where a name genuinely has to
be shared across that line it is duplicated as a string with a comment on both sides saying so —
`"feishu-chat"` in `FeishuChatObservations` and `EventsProperties`, the auto-configuration class
named by `afterName` in `KnowledgeToolsConfiguration`. Renaming one half silently stops the other
working, which is why both carry the warning.

## Adding an integration

Every new module looks the same from the build's point of view:

1. `include 'spring-agent-<kind>-<name>'` in `settings.gradle`.
2. A `build.gradle` applying `springagent.java-library-conventions`, with a `description` — that is
   what the published POM carries — and `implementation project(':spring-agent-core')`.
3. An auto-configuration class that component-scans its own package, listed in
   `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.
4. An `aot` package with a `RuntimeHints` class, pulled in with `@ImportRuntimeHints`, for anything
   reflective, resource-loaded or proxied. Native image is first class here: without hints the JVM
   build passes and the binary breaks at runtime.
5. A `@ConditionalOnProperty` switch if the module does anything a deployment should have to ask for
   — open a socket, accept traffic, spend money. The pattern is `app.<thing>.enabled` with a real
   flag, not a check on a credential property: conditions are evaluated against raw property values,
   and a credential is usually a `${PLACEHOLDER}` that fails to resolve precisely when the thing is
   not set up.
6. Its own message bundle if it writes text a person reads, and its own `prompts/tools/` files if it
   ships tools whose descriptions should be translatable.
7. A line in `spring-agent-app-feishu/build.gradle` and/or `spring-agent-app-slack/build.gradle` if
   a server should carry it, with a comment saying what taking it does and does not decide.

`spring-agent-integration-github` is the smallest complete example — two classes and a prompt file.

### A chat surface

A surface is anything that turns messages into `AgentRequest`s and shows the answer.
`spring-agent-integration-feishu`, `spring-agent-integration-slack` and `spring-agent-app-cli` are the
shipped examples. The first two are the same design against two products; the CLI sits at the other
end of the one axis that matters: whether the surface can answer a question within the run.

**Exactly one chat surface may be on an application's classpath**, which is why there is a server
application per surface rather than one carrying both. Three singletons answer for *every* run
rather than for one surface's: a `@Bean AgentResponseListener`, the `PromptVariablesContributor`s
that `SpringAgent` merges with `putAll`, and the `Notifier` that `SituationSweeper` resolves with
`getIfAvailable()`. With two surfaces installed the first two answer for each other's runs — a
Feishu card replied onto a Slack timestamp, and the run aborted before it reaches the model — and
the third throws. None of it fails at startup. The constraint holds for a *test* classpath too: an
auto-configuration there is still an auto-configuration, so a `testImplementation` on a second
surface is the same mistake. If you need a cross-surface assertion, put it in each application's own
test rather than importing the other surface.

What a surface owns:

- **Receiving.** Deduplicate redeliveries through `ProcessedMessageRepo#claim`, in a key namespace of
  your own, and release the claim if you fail before the run starts — otherwise a failure becomes a
  message silently dropped, which is worse than the duplicate the claim prevents.
- **Building the request.** Mint a stable `requestId`, pass the identity through
  (`userId`/`chatId`/`chatType`/`groupId`/`tenantId`), and set `conversationId` to whatever groups
  the turns that should share chat memory. Those identifiers are opaque to core; whatever your
  system calls a user is fine.
- **Reporting.** An `AgentResponseListener` per run, or a `@Bean` one if the surface should also
  show runs it did not start — a scheduled task firing, a triage run. Remember `onContent` gives the
  answer *so far*.
- **Questions.** Implement `QuestionHandler`. Add `SynchronousQuestionHandler` only if you can
  really answer inside the call; otherwise persist and let the answer arrive as a new request on the
  same `conversationId`.
- **Reply format.** Implement `PromptVariablesContributor` to fill `{replyFormat}` with how your
  surface wants an answer written, instead of putting formatting rules in the system prompt.
- **Observing.** If the surface sees traffic nobody addressed to the agent — a group chat it is a
  member of — report it as an `Observation` to `EventIntakes` rather than starting a run. See
  `FeishuChatObservations`, which is deliberately a class of its own next to the handler that
  answers, so that it is obvious at the call site that nothing there starts a run.

### A webhook event source

One module per system. Implement `WebhookSource` from `spring-agent-events`, publish it as a bean
from the module's auto-configuration, and stop there: this module never serves HTTP, decides policy,
or knows what becomes of an observation.

```java
public interface WebhookSource {
  String name();                                      // path segment AND settings key
  boolean verify(WebhookDelivery delivery, String secret);
  Optional<Observation> observation(WebhookDelivery delivery);
}
```

`name()` is the last segment of `/events/webhooks/<source>` and the key under
`app.events.sources.<source>` at once, so a deployment cannot configure a policy for a path that
does not exist.

Four things every implementation has to get right:

- **No secret means refuse everything.** Accepting whatever arrives when nobody configured a secret
  turns a forgotten line of configuration into an open door that wakes the agent on anyone's say-so.
- **Compare with `MessageDigest.isEqual`.** `String.equals` and `Arrays.equals` return at the first
  differing byte, which times how much of a guess was right.
- **`verify` must not throw** for a malformed or hostile request. A missing header, a signature of
  the wrong length, unparseable text and an empty body are all just `false`. It is reached by
  unauthenticated traffic.
- **The `correlationKey` is the real design decision.** It is what decides whether the agent sees one
  situation about a pull request or twelve unrelated ones, and it is computed in code precisely so
  that a thousand alerts about one database collapse without an inference costing anything. Pick the
  most specific thing in the payload that names the *subject* rather than the event.
- **Report an `Actor.authenticated` where you can vouch for one, an `Actor.claimed` where you can
  only read a name, and null where the event names nobody.** Only the authenticated name is matched
  against `app.events.sources.<source>.trusted-actors`, and that list is the only thing bounding who
  can put text in front of the agent. A name qualifies as authenticated when the delivery's own
  authentication covers it — GitHub's HMAC covers the body its `sender.login` sits in, so that one
  does. A name you merely read does not, and calling `Actor.authenticated` with one turns the
  allow-list into a bypass, because the attacker then writes both sides of the comparison. Reporting
  it as a claim instead costs nothing and admits nobody, while letting an intake somebody wrote for
  reasons of their own say who was at the door. Null is the correct answer for anything
  machine-generated, and the intake says so out loud the first time a list is configured for such a
  source. Who caused an event is *also* still evidence and still belongs in `summary` — the two are
  not the same thing said twice.

`deliveryId` is the transport's idempotency key: stable across a redelivery, different for genuine
news. Where the vendor mints one, use it. Where it does not, mint one that has both properties and
write down in a comment why yours does.

One delivery is one observation. Do not take a batch apart — the sender already decided what belongs
together, and splitting it asks the agent for an opinion about each alert in a group it was told is
one thing. `Optional.empty()` is the normal answer for a ping, a test button or a body with nothing
in it.

Ship a triage prompt per source at `events/prompts/<source>-triage-prompt.md` (plus its `_zh_CN`
sibling), which falls back to the module's generic one. Read the shipped prompts before writing
yours: they say nobody is waiting, they say the observed text is data and not instructions, and they
say silence is the common correct answer. A prompt that drops the untrusted-input framing hands
whoever can open an issue a prompt of their own.

Do not write policy into the prompt. What a deployment wants done about *its* events — which
repositories matter, who to page, when to stay silent — belongs in that source's **playbook**:
documents in the knowledge base, named by `app.events.sources.<source>.playbook.filter` and looked up
with `playbook.query`. Your prompt says how to read this kind of event and what the shipped defaults
are; the playbook says what this deployment does about it, and wins where the two overlap. Say so in
the prompt, as the shipped ones do.

Payload text is written by whoever caused the event. Treat it as untrusted everywhere: it is
evidence to be shown, never routing, and never instructions. In particular, do not assume the run has
a chat: only an observation that knew its own chat gives it one, and `app.events.sources.<source>.route`
is where a *failed* triage is reported, not where the agent talks.

### A polled event source

Not everything that reports observations is dialled *into*. `spring-agent-integration-email` is the
other shape: it dials out, holds a connection, and reports what arrives on it. There is no SPI for
this and there does not need to be one — a poller depends on core, builds an `Observation`, and
calls `EventIntakes.observe`. Everything in the webhook section above about `correlationKey`,
`deliveryId`, one-delivery-one-observation, the per-source prompt and the playbook applies here
unchanged.

What differs is four things:

- **Taking the module is not free**, so it carries its own `app.<thing>.enabled`, defaulting to
  false, *in addition to* `app.events.enabled`. A webhook source contributes a reader to an endpoint
  somebody else already opened; a poller opens a connection and logs in somewhere.
- **Do not write the connection loop.** Reconnection, backoff, and noticing a connection that died
  without closing are where the subtle failures live, and a maintained client almost certainly has
  them. The email module holds no loop of its own: `ImapIdleChannelAdapter` from
  `spring-integration-mail` does the connecting, the reconnecting and the cancelling of a blocked
  IDLE, and this repository contributes a `MessageHandler`.
- **The delivery id has to come from the server, not from the payload.** For mail that is
  `UIDVALIDITY:UID` and never the `Message-ID` header, which the sender writes: two messages may
  claim the same one, and the second would be passed over for good by a claim that never expires.
  Every polled source has this decision and it is the easiest one to get wrong, because the
  payload's own identifier is right there and looks authoritative.
- **The bookmark has to survive a restart.** The email module marks each message with an IMAP
  keyword of its own after reporting it, in that order, so a crash costs a duplicate — which the
  funnel collapses — rather than a lost message. A high-water mark held in memory loses everything
  that arrives while the application is down.

**Authenticating the sender is the poller's job, and it is not the same job as a signature.** A
webhook source proves a delivery came from the vendor with an HMAC over the body, and then whatever
the body says about who caused the event is authenticated too. A poller usually has nothing of the
kind: an email `From` header is a string anybody can type. So `Observation.actor` — which
`trusted-actors` is matched against — must be filled in only from something that actually
establishes identity, and left empty otherwise. The email module reads a DKIM verdict out of the
`Authentication-Results` header its own mail server wrote, takes only the topmost header bearing the
configured `authserv-id`, and requires the signing domain to align with the `From` domain. Read
`AuthenticationResults` before writing anything similar; the ordering rule in it is the whole
defence, and searching the headers for the first that says `pass` would find the attacker's.

### Somewhere to send a notice

Implement core's `Notifier` (`core/notify/`) if your surface can say something to a `Route` with no
run behind it. There is one caller today — `SituationSweeper` reporting a triage run that failed or
never started — and one implementation, `FeishuNotifier`.

The constraint that shapes it: this exists for the case where the model, the agent or a run is what
broke, so an implementation must reach its service using nothing but its own client. No `SpringAgent`,
no tool context, no `AgentRequest`. Take an `ObjectProvider<Notifier>` if you call one, since core
ships none and having none is an ordinary configuration.

### A set of tools

Beans annotated `@AgentTool`, whose `@Tool` methods `AgentToolsProvider.compose(...)` picks up. See
[sdk.md](sdk.md#tools) for the mechanics; what is specific to this repository:

- Read identity from the tool context through `ToolContexts` keys, never by string.
- A tool that should be kept out of some kind of run is kept out by an `AgentScenario`, not by a
  flag on the annotation. If your tool creates work that outlives the turn, check whether
  `SCHEDULED_TASK` and `SUBAGENT` should be withholding it.
- Descriptions in `@Tool`/`@ToolParam` are English, an annotation value being a compile-time
  constant. Ship translations as `<prefix>/prompts/tools/<ToolName>_<locale>.md` for the tool and a
  `<ToolName>.<parameterName>` key in `<prefix>/tools_<locale>.properties` for its parameters.
  Untranslated tools keep their English, so this can be filled in one tool at a time.
- Anything that has to happen around *every* call is a `ToolCallInterceptor`, not a change to each
  tool.
- A tool returning something large returns it whole and lets `LargeResponseInterceptor` decide;
  it does not truncate in its own way. One threshold, `app.ai.tools.max-result-chars`, governs
  every tool. A second cap inside a tool is worse than none: the interceptor then saves the
  already-cut text to the workspace and tells the model it has the full result, which is how the
  shell backends behaved until their own `max-output-bytes` was removed.
- Where one tool exists only to feed another — its output copied verbatim into the next call's
  argument — the answer is usually to fuse the two rather than to make the copy cheaper. That is
  what `FeishuWriteDocumentBody` is: the conversion, the insert, the chunking and the image
  workflow that used to be four tool calls and three rules the model had to remember. Where the
  copy is genuine, an argument may instead be declared to accept an `@file:` reference to a saved
  tool result, by contributing a `ToolInputFileRefs.Params` bean — see `docs/sdk.md` for why that
  list stays short.

### A persistence backend

Repository *contracts* live in `core/dao/repo/`; each `spring-agent-persistence-*` module implements
them, and the module also supplies the matching Spring AI chat memory repository — the two are
selected together by `@ConditionalOnPersistenceBackend` so they cannot come from different places.

When adding a model or a query, update **all three** implementations. The domain records in
`core/dao/models/` carry JPA, MongoDB and Redis mapping annotations at once, which works because an
annotation whose type is absent at runtime is discarded on reflection — that is also why core
declares those persistence APIs `compileOnly`. Redis has no query planner: an `@Indexed` field is the
definition of what can be filtered on, not a tuning knob.

Not every method on those contracts is a query. `ProcessedMessageRepo.claim` and
`ScheduledTaskRepo.claimNextFireAt`/`initNextFireAt` are **conditional writes**, and their boolean
return is the concurrency control rather than a convenience: an implementation that reads and then
writes lets two replicas both take the same piece of work, which is the case they exist for. Each
backend has to express the predicate as part of the write — an insert `on conflict do nothing`, a
`where` clause on an `@Modifying` update, the expected value in a Mongo update's filter, a Redis
`SET NX`. A related trap on Redis: a hash cannot hold a null, so writing an absent value is a
`PartialUpdate.del`, not a `set(..., null)`, which writes nothing at all.

New behaviour is asserted once, in `AbstractPersistenceBackendTest`.

### A shell backend

Implement the shell tools for your runtime and select them with `@ConditionalOnShellBackend`, adding
a value to `app.ai.tools.shell.type`. The two shipped ones — Kubernetes and Docker — are worth
reading together: a sandbox per user, its own slice of the volume, torn down when idle and rebuilt on
the next command, with credentials arriving as environment variables from a Secret or an encrypted
row and never through a prompt.

### A knowledge base

Implement `KnowledgeBase` and publish it as a bean; core registers the knowledge tools only when one
exists, ordered with `@AutoConfiguration(afterName = ...)` naming the module's class as a string —
rename that class and the tools silently stop being registered.

Two traps `spring-agent-rag-milvus` documents at length. It holds its `MilvusVectorStore` as a
private field rather than publishing it as a bean, because Spring AI's Milvus auto-configuration
declares its own store `@ConditionalOnMissingBean` and publishing a second one would make *that* back
off and take the tool-search index's store with it. And it drops to the raw client for `list`,
because no portable `VectorStore` interface can enumerate — which is the whole reason a knowledge
base is a backend module rather than something core implements over any store.

Scoping is `KnowledgeScopeFilter`, and a filter clause is only ever emitted for a non-blank identity:
a blank one would match every document that stores a blank there, which is every other user's.
`KnowledgeScopeFilterTest` covers that case by name — read it before changing the filter.

## Conventions

**Commit messages** follow Conventional Commits with lowercase, prose-style subjects that say *why*
in plain English: `feat: let a scheduled task remember the conversation it belongs to (#11)`,
`fix: say why a tool call was dropped, and how to get the tool back`. Prefixes in use: `feat`, `fix`,
`refactor`, `build`, `ci`, `docs`, `style`. Add a `(#N)` suffix when the change went through a PR.

**Comments** explain *why*, at length, and are load-bearing. Build files, `application.yaml` and
`docker-compose.yaml` carry paragraphs of rationale that are the closest thing this codebase has to
design documentation. Match that: when a decision is non-obvious, write down the reason it was made
and what breaks without it. Do not describe history in comments; git records that.

**Configuration is documented in place.**
[`spring-agent-app/src/main/resources/application.yaml`](../spring-agent-app/src/main/resources/application.yaml)
is the reference for every property and environment variable. A new knob is added there, with its
rationale, in the same change that introduces it — and with an environment variable, since a
container deployment has no other way to set it.

**Defaults are conservative.** Anything that opens a socket, accepts traffic, runs somebody's code or
spends money is off unless a deployment asks: `app.ai.tools.shell.type: none`, `app.events.enabled:
false`, `app.ai.rag.enabled: false`. A half-configured feature should do nothing rather than do
something nobody secured.

**Security posture worth knowing before you write an integration.** Text that arrived from outside —
a webhook payload, an issue title, a chat message the bot was not addressed in — is data, never
instructions and never routing. A run assumes an identity, and with it that identity's files,
credentials and personal MCP servers, so a run woken by outside text must assume an identity of the
agent's own rather than a person's. No scenario can withhold that.

## Documentation

There are three documents and they have distinct audiences. Keep the change that alters behaviour in
the same commit as the documentation for it:

| Document | Audience | Update it when |
| --- | --- | --- |
| [`README.md`](../README.md) | Somebody running the prebuilt server or CLI | A feature becomes visible to an end user; the way either application is started or configured changes; a switch gains or loses a value |
| [`docs/sdk.md`](sdk.md) | A Java developer embedding the library | A public API, SPI or extension point changes; a module is published or removed; a scenario, listener hook or tool-context key is added |
| [`docs/contributing.md`](contributing.md) | Somebody changing this repository | The build, the test layout or the module rules change; a new *kind* of integration becomes possible |

`application.yaml` remains the configuration reference, and none of the three duplicates it — they
link to it. The same goes for the code: prefer a link to the class that explains itself over copying
its reasoning into a document that will drift.
