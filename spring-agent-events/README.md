# spring-agent-events

> **Audience:** a developer extending the event path or writing a source for it. The operator's view —
> what to configure, what a triage run may do, and what any of it is worth — is
> [docs/events.md](../docs/events.md).

The intake. It takes observations reported by any transport, correlates them into **situations**,
lets each settle, and then wakes the agent once to decide whether it has anything worth saying. It
also serves `/events/webhooks/<source>` on behalf of every `WebhookSource` bean in the context.

Everything here is off unless `app.events.enabled`, and a source not named in `app.events.sources` is
dropped at the door.

## What it contributes

| | |
| --- | --- |
| `SituationEventIntake` | The `EventIntake` bean. Records an observation against an open situation for its correlation key, or opens one |
| `SituationSweeper` | Fires a triage run for a situation that has settled; reports a failed run through the deployment's `Notifier` |
| `WebhookController` | `POST /events/webhooks/<name>` for every `WebhookSource` bean, authenticating each delivery against that source's secret |
| `WebhookSource` + `WebhookDelivery` | The SPI a source module implements — see below |
| `SituationTools` | `ListOpenSituations`, `GetSituationEvents`, `RecordSituationAssessment`, `ResolveSituation` |
| `PlaybookTools` | `ListPlaybooks`, `WritePlaybook` — admin-only, and the reason `ListOwnerKnowledgeBase` exists in core |
| `SituationTriageScenario` | The `AgentScenario` a triage run carries |
| `EventsDefaults` | An `EnvironmentPostProcessor` supplying the per-source defaults, so a deployment configures only what it wants different |

## The `WebhookSource` SPI

Three methods, and a module implementing them is a complete event source:

```java
String name();                                        // the URL segment, and the policy key
boolean verify(WebhookDelivery delivery, String secret);
Optional<Observation> observation(WebhookDelivery delivery);
```

`WebhookDelivery` carries case-insensitive headers and the **raw** body — raw because a signature is
over bytes, and re-serialising the JSON to verify it is how a signature check comes to pass for a
body that is not the one that was signed. `bodyAsText()` is for parsing and never for verifying.

Returning an empty `Optional` from `observation` is how a source drops a delivery it has nothing to
say about (GitHub's `ping`, say) without failing it.

`spring-agent-integration-github` is the smallest complete example. A source that is not an HTTP
delivery skips this interface entirely and reports to core's `EventIntakes` directly, as
[`spring-agent-integration-email`](../spring-agent-integration-email/README.md) does.

## How a situation is assembled

An observation joins the open situation for its `correlationKey`, or opens one. From there:

- **it settles before anything runs.** `debounce` is how long to wait after the last event,
  `max-debounce` the cap on waiting for a storm that never stops. A thousand alerts from one outage
  become one run.
- **`cooldown`** bounds how often one situation may wake the agent again as new events land on it.
- **`max-events-per-situation`** and **`max-evidence`** bound what one situation holds and how much of
  it a run is shown.
- **a situation ends** either after `resolve-after-quiet` of nothing, or immediately after an
  evaluation where `resolve-after-evaluation` is set, or because a run called `ResolveSituation`.
- **`stuck-investigation-timeout`** is what unwedges a situation whose run never came back.

Defaults for all of these are in `EventsProperties`, and each is documented with its reasoning in the
`app.events` block of
[`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

## What a triage run is

`SituationTriageScenario` says it exactly: **no conversation memory** — a situation is not a
conversation and carrying one would give the model somebody else's chat as context — but **knowledge
retrieval is on**, because the playbooks are in the knowledge base. Neither `ScheduledTaskTool` nor
`FiringScheduledTaskTool` is offered: a triage run is not the firing of any task, so those would find
none and refuse.

The run assumes the source's `owner` identity, with that identity's files, credentials and MCP
servers. That is a decision about what a scenario cannot do rather than an oversight: a scenario
gates tools, and it cannot withhold what comes with an identity. [docs/events.md](../docs/events.md)
spells out what follows from that.

## Two names shared across a module boundary

`EventsProperties.FEISHU_CHAT` (`"feishu-chat"`) and `SLACK_CHAT` (`"slack-chat"`) are the source
names the chat integrations report under, duplicated as strings here because a compile dependency
from this module to a chat integration is not allowed. Both sides carry a comment saying so. Renaming
one half silently stops the other working.
