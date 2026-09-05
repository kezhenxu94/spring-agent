# spring-agent-core

> **Audience:** a developer embedding the agent or changing the runtime. The full API walkthrough —
> dependencies, minimum configuration, every SPI with examples — is [docs/sdk.md](../docs/sdk.md).
> This page is the map of the module.

The agent runtime and every SPI, backend-agnostic. Everything else in this repository is a backend, a
surface or an application over what is here.

## One entry point

An integration builds an `AgentRequest` — a record plus builder carrying user and chat identity, an
`AgentScenario`, prompt variables, tool context and listeners — and hands it to `SpringAgent`
(`agent/SpringAgent.java`). Everything after that happens inside: tool composition, system-prompt
rendering, the Spring AI `ChatClient` call, MCP client lifecycle, listener fan-out, cancellation.

Integrations never touch `AgentToolsProvider`, MCP clients or Reactor directly. If something needs to
happen around every run, it is a listener or an interceptor here, not a special case in a surface.

## The packages

| Package | What lives there |
| --- | --- |
| `agent` | `SpringAgent`, `AgentRequest`, `AgentResponseListener`, `AgentScenario`, `BuiltInScenarios`, the run registry |
| `tools` | `@AgentTool`, `AgentToolsProvider`, `ToolContexts`, the built-in tools, `tools/interceptors/` and the custom `ToolCallingManager` |
| `dao` | `dao/models/` — the one domain model every backend shares — and `dao/repo/`, the repository contracts each `spring-agent-persistence-*` module implements |
| `observing` | `Observation`, `Actor`, `Route`, `EventIntake`, `EventIntakes` — how a run starts without anybody talking. Core ships no implementation; see [docs/events.md](../docs/events.md) |
| `knowledge` | The `KnowledgeBase` SPI and `KnowledgeScopeFilter`, implemented by [`spring-agent-rag-milvus`](../spring-agent-rag-milvus/README.md) |
| `notify` | `Notifier` — say something to a chat with no run behind it; how a conversation is handed between surfaces |
| `identity`, `security` | Who a run is, and what an `app.ai.admins` member may do |
| `storage`, `share` | A user's home under `app.storage.location`, and `ShareController` publishing a file |
| `scheduling` | Scheduled tasks: a schedule is a column on the task, and each occurrence is won by exactly one replica |
| `usermodels` | Bring-your-own-model: sealed endpoints a person registers, and the `/config` machinery around them |
| `advisors`, `logging`, `config`, `aot` | Spring AI advisors, structured logging, auto-configuration, native-image hints |

## Rules this module keeps

**No persistence backend, ever.** `checkRuntimeClasspathIsolation` (wired into `check`, defined in
`buildSrc/.../springagent.classpath-isolation.gradle`, configured at the bottom of `build.gradle`)
fails the build if Hibernate, the Mongo driver, Jedis, Milvus, fabric8 and friends reach core's
runtime classpath. If it fails, a dependency became `api` or grew a new transitive — fix the
dependency, do not widen the allow-list.

**One domain model serves every backend.** The records in `dao/models/` carry JPA, MongoDB *and* Redis
mapping annotations at once. That works because an annotation whose type is absent at runtime is
discarded on reflection, which is why core declares those persistence APIs `compileOnly`.

**A scenario decides what a run is offered, not the annotation.** `@AgentTool` carries no scenario — an
annotation attribute cannot have an interface type, which would confine gating to the built-in enum.
`AgentScenario.offers(tool)` is asked about every `@AgentTool` bean and says yes by default. That is
how `SCHEDULED_TASK` keeps `ScheduledTaskTool` out of a run that fires on a schedule, and how
`SUBAGENT` keeps both it and `SubagentTools` out — which caps subagent depth at one with no counter to
get wrong.

**A run is offered exactly what `compose(...)` returns.** Tools from elsewhere have to be collected
there too: alongside the `@AgentTool` beans and the user's own MCP servers it appends the callbacks of
every `ToolCallbackProvider` bean in the context, which is how application-wide MCP servers under
`spring.ai.mcp.client.*` reach the model. Those clients belong to the context and are never closed by
a run; only the per-request ones in `McpTools` are.

**Per-request identity reaches a tool through `toolContext`**, with typed keys in `tools/ToolContexts.java`.
Read them through those keys rather than by string.

**No hardcoded prose.** Text the agent writes for itself goes through `CoreMessages` over
`messages*.properties`, in every language the module ships.
