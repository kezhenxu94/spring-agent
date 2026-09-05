# Integrations

Everything the agent touches that is not the runtime itself is an integration: a chat platform, a
browser, a webhook receiver, a mailbox, a store, a sandbox, a knowledge base. They are separate
Gradle modules, each optional, each turned on by an application taking it as a dependency and a
deployment setting its switch.

This page is the part that is the same for all of them — what an integration *is* here, the kinds
there are, the contract every module keeps, and the rules about which may sit beside which. What one
particular integration does, and what it needs to be told, is in that module's own README, listed
below.

- Writing a new one: [contributing.md § Adding an integration](contributing.md#adding-an-integration).
- Where they sit in the whole: [architecture.md](architecture.md).
- Depending on them from your own application: [sdk.md](sdk.md).

## The modules

Each module's README states who it is written for. A library module is written for a **developer**
depending on it, and says at the end which switch an operator sets. An `spring-agent-app-*` README is
written for whoever **deploys** it, and links back down to the modules it carries.

### Surfaces — where a person meets the agent

| Module | What it is |
| --- | --- |
| [`spring-agent-integration-feishu`](../spring-agent-integration-feishu/README.md) | Feishu/Lark chats, cards, documents and drive as a surface |
| [`spring-agent-integration-slack`](../spring-agent-integration-slack/README.md) | Slack channels and Block Kit messages as a surface |
| [`spring-agent-integration-websocket`](../spring-agent-integration-websocket/README.md) | A browser as a surface: the SPA, its REST endpoints, STOMP run streaming, the run journal |

The command line is a surface too, but it is an application rather than a library —
[`spring-agent-app-cli`](../spring-agent-app-cli/README.md).

### Event sources and intakes — where the agent speaks first

The whole of this, including how a source is configured and what a triage run is allowed to do, is
[events.md](events.md).

| Module | What it is |
| --- | --- |
| [`spring-agent-events`](../spring-agent-events/README.md) | The intake: observations → situations → a triage run, and `/events/webhooks/<source>` |
| [`spring-agent-integration-github`](../spring-agent-integration-github/README.md) | GitHub webhook deliveries as observations |
| [`spring-agent-integration-gitlab`](../spring-agent-integration-gitlab/README.md) | GitLab webhook deliveries as observations |
| [`spring-agent-integration-grafana`](../spring-agent-integration-grafana/README.md) | Grafana alert notifications as observations |
| [`spring-agent-integration-email`](../spring-agent-integration-email/README.md) | A watched IMAP mailbox as observations — the one source that dials out |

### Stores and sandboxes

| Module | What it is |
| --- | --- |
| [`spring-agent-persistence-jpa`](../spring-agent-persistence-jpa/README.md) | JPA, SQLite by default — the one that needs no server |
| [`spring-agent-persistence-mongodb`](../spring-agent-persistence-mongodb/README.md) | MongoDB, including a conversation-memory repository of its own |
| [`spring-agent-persistence-redis`](../spring-agent-persistence-redis/README.md) | Redis, where an index is the definition of what can be queried |
| [`spring-agent-tools-shell-kubernetes`](../spring-agent-tools-shell-kubernetes/README.md) | A Pod per user as the shell sandbox, with credentials as Secrets |
| [`spring-agent-tools-shell-docker`](../spring-agent-tools-shell-docker/README.md) | A container per user as the shell sandbox |
| [`spring-agent-rag-milvus`](../spring-agent-rag-milvus/README.md) | The knowledge base: the only implementation of core's `KnowledgeBase` |

### The runtime and the applications

| Module | What it is |
| --- | --- |
| [`spring-agent-core`](../spring-agent-core/README.md) | The runtime and every SPI; backend-agnostic |
| [`spring-agent-app-feishu`](../spring-agent-app-feishu/README.md) | Server, Feishu surface; carries every optional module |
| [`spring-agent-app-slack`](../spring-agent-app-slack/README.md) | The same server with Slack instead |
| [`spring-agent-app-webui`](../spring-agent-app-webui/README.md) | The same runtime with a browser and no chat platform |
| [`spring-agent-app-web-feishu`](../spring-agent-app-web-feishu/README.md) | Browser and Feishu in one process, so a conversation is handed between them |
| [`spring-agent-app-cli`](../spring-agent-app-cli/README.md) | The laptop command line |

## What every integration has in common

A module is an integration if it does all of this and nothing more:

1. **It depends on core, and on nothing beside it.** A compile dependency points from an integration
   to `spring-agent-core`, never the other way, and never from one integration to another — the
   exception being an event source, which depends on `spring-agent-events` as well because that is
   the SPI it implements. Where a name has to be shared across that line it is duplicated as a string
   with a comment on both sides saying so.
2. **It ships an auto-configuration** that component-scans its own package, named in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. Taking the
   dependency is what wires it; there is no manual registration anywhere.
3. **It ships runtime hints.** An `aot` package with a `RuntimeHints` class pulled in with
   `@ImportRuntimeHints`, for anything reflective, resource-loaded or proxied. Without them the JVM
   build passes and the native binary breaks at runtime.
4. **It has a switch if it costs anything.** Anything that opens a socket, accepts traffic or spends
   money is behind `app.<thing>.enabled` — a real boolean, never a check on whether a credential
   property is set, because conditions are evaluated against raw property values and a credential is
   usually a `${PLACEHOLDER}` that fails to resolve exactly when the thing is not configured.
5. **It writes no hardcoded prose.** Text the agent writes for itself goes through a `MessageSource`
   over `messages*.properties`, and a tool's description through the module's own `prompts/tools/`
   files. Every module here carries `en` and `zh_CN`.
6. **It reaches the agent through one door.** An integration builds an `AgentRequest` and hands it to
   `SpringAgent`; it never touches `AgentToolsProvider`, MCP clients or Reactor itself. It follows
   the run through `AgentResponseListener`.

## One chat surface per application

This is a runtime constraint rather than a preference, and it is why there are separate
`spring-agent-app-feishu` and `spring-agent-app-slack` rather than one server carrying both.

Three singletons in this runtime answer for *every* run rather than for one surface's runs: a
`@Bean AgentResponseListener` claims every run; `PromptVariablesContributor`s are merged with
`putAll`, so the last one registered decides `{replyFormat}`; and `SituationSweeper` resolves its
`Notifier` with `getIfAvailable()`, which throws when two exist. None of the three fails at startup,
so a second chat surface on the classpath is a build that passes and a deployment that misbehaves — a
Feishu card replied onto a Slack timestamp, a triage run aborted before it reaches the model. It
holds for a test classpath too: an auto-configuration is still an auto-configuration there.
`OneChatSurfaceTest` in `spring-agent-app-slack` is the check that notices.

`spring-agent-integration-websocket` is deliberately not a third entry in that count. It does register
a `@Bean AgentResponseListener`, so that a scheduled task firing or a subagent starting is visible in
the page, but that one claims a run only when the request's `chatType` is `web`, which no other
surface sets — and it contributes no `PromptVariablesContributor` and no `Notifier`. So it may sit
beside a chat surface, which is the point of publishing it, and
[`spring-agent-app-web-feishu`](../spring-agent-app-web-feishu/README.md) is that pairing.

## Handing a conversation between two surfaces

There is no SPI for this. `core/notify/Notifier` is already "say something to a chat with no run
behind it", both chat modules implement it, and a deployment has at most one — which is what makes
"the chat surface beside this page" resolvable. It carries two `default` methods for the purpose:
`surface()` names the platform so the page can draw its icon, and `quoted(String)` escapes text
somebody else wrote into that platform's dialect. `quoted` is load-bearing rather than tidy —
`<at id=all></at>` typed into the web composer would otherwise have the bot notify a whole Feishu
group — so an implementation carrying foreign text must override it.
