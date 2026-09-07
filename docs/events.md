# Event sources and intakes

Not every run starts with somebody talking to the agent. Given `app.events.enabled`, the agent
watches instead of waiting: alerts and code-hosting webhooks arrive at `/events/webhooks/<source>`,
mail arrives in a watched mailbox, group chat messages it was not addressed in arrive through the
chat integration. Related events are correlated into one *situation* and left to settle — a thousand
alerts from one outage become one run, not a thousand — and only then is the agent woken to decide
whether it has anything worth saying. Silence is a normal answer.

This page covers the whole of that path: the two SPIs it is built from, how a source is configured,
and what a run started this way is and is not allowed to do. What one source reads out of one
product's payload is in that module's README.

## The shape of it

```
a transport            core/observing            spring-agent-events            core
─────────────          ──────────────            ──────────────────            ────
GitHub webhook  ─┐                          ┌─ correlate by key
GitLab webhook  ─┼─▶  Observation  ─▶ EventIntakes ─▶ debounce ─▶ situation ─▶ SpringAgent
Grafana alert   ─┤                          └─ per-source policy        (a triage run)
IMAP mailbox    ─┤
a group chat    ─┘
```

Two SPIs, deliberately at different levels:

**`core/observing`** is the contract for reporting that something happened, and core ships no
implementation of it. A transport reports an `Observation` — `source`, `deliveryId`, `kind`,
`correlationKey`, a title and summary, the payload as JSON, an `Actor` and a `Route` saying where a
run about it may talk — to `EventIntakes`, which hands it to every `EventIntake` bean, each
independent and each isolated from the others' failures. Three of those fields are required and the
reason is written into `Observation` itself: without a source there is no policy to apply, without a
delivery id every redelivery is counted again, and without a correlation key every observation
becomes a situation of its own and the debounce protects nothing.

That is why a transport — the Feishu integration, a webhook receiver — depends only on core, and why
nothing consuming observations depends on a transport. A deployment can carry a source and no intake,
in which case observations are reported and dropped, and nothing breaks.

**`spring-agent-events`'s `WebhookSource`** is the narrower contract underneath it, for the common
case of an HTTP delivery: `name()`, `verify(delivery, secret)` and `observation(delivery)`. The
module serves `/events/webhooks/<name>` for every such bean, authenticates the delivery, and turns
what comes back into an observation. A source module is then two classes and a prompt file —
`spring-agent-integration-github` is the smallest complete example.

A source that is *not* an HTTP delivery skips `WebhookSource` and reports to `EventIntakes` directly.
[`spring-agent-integration-email`](../spring-agent-integration-email/README.md) is the shipped example
of that, and so is the chat integration reporting messages the bot was not addressed in.

## What arrives

| Source | Module | Arrives as |
| --- | --- | --- |
| GitHub | [`spring-agent-integration-github`](../spring-agent-integration-github/README.md) | `POST /events/webhooks/github`, `X-Hub-Signature-256`, HMAC-SHA256 over the raw body |
| GitLab | [`spring-agent-integration-gitlab`](../spring-agent-integration-gitlab/README.md) | `POST /events/webhooks/gitlab`, `X-Gitlab-Token`, compared in constant time |
| Grafana | [`spring-agent-integration-grafana`](../spring-agent-integration-grafana/README.md) | `POST /events/webhooks/grafana`, an `Authorization` header |
| Mail | [`spring-agent-integration-email`](../spring-agent-integration-email/README.md) | An IMAP mailbox this application watches |
| A group chat | [feishu](../spring-agent-integration-feishu/README.md) / [slack](../spring-agent-integration-slack/README.md) | Messages in a watched chat the bot was not addressed in |

Each webhook source authenticates its own deliveries, and **a source nobody configured a secret for
refuses everything** — so the endpoint is safe to expose but useless until somebody sets it up. A
source not named in `app.events.sources` is dropped at the door.

## Configuring a source

The reference is the `app.events` block in
[`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml), which documents
every key and its default in place. What is worth understanding before reading it:

**Timings are per source**, because an alert and a chat want very different manners: how long to wait
for related events (`debounce`, `max-debounce`), how often at most (`cooldown`), when to give up on a
situation (`resolve-after-quiet`), and how many events one situation may carry. A source that
configures none of them inherits the global defaults, and the two chat sources ship with defaults of
their own.

**Retention is not**, and is the one setting under `app.events` that is global on purpose:
`retention` says how long a closed situation and the observations behind it stay in the database
before they are deleted, which is a statement about the database rather than about a source's
manner. Thirty days by default, measured from the moment the situation was closed; `0` keeps
everything for ever, for a deployment where retention is somebody else's policy to enforce. Nothing
here ever reads a situation that old — closing one takes it out of every query — so the choice is
only about how long the evidence stays available to look up by hand.

**Who a source runs as** is `app.events.sources.<name>.owner`. Its `user-id` must be an identity of
the agent's own and never a person's, since a triage run assumes it along with that identity's files,
credentials and MCP servers. `group-id` and `tenant-id` beside it are optional and say what else that
identity belongs to, which gives the run the group's and the tenant's shared workspaces as well as
its own, and widens its knowledge base exactly as a person's is widened — own, plus the group's and
the tenant's where it has them. A source that names only `user-id` acts in no group and no tenant and
reads that one knowledge base.

Those two are configured and never taken from whatever the event named, so a surface that reports a
tenant cannot pick the shared workspace an unattended run writes into or the knowledge that steers
it. That holds for a chat source too, where the chat's own ids are as real as any: a triage that
should reach the chat's group says so under `owner.group-id`.

**Never list a source's `owner.user-id` in `ADMINS`.** The application refuses to start on that
pairing: a triage run assuming an admin identity would hand the admin-only tools to whoever wrote the
event it is triaging.

**Who a source will listen to** is `app.events.sources.<name>.trusted-actors`: regular expressions,
matched whole and case-insensitively against an identity the source authenticated — a GitHub login
inside a body the signature already covered, say. Anything else is dropped before it is recorded, and
the sender is told nothing, since an answer that distinguished the two would let anybody read the list
back. Leave it out and everybody is heard, which is what an existing deployment keeps on upgrading;
every source without one is named in the log at startup. A private repository or an internal GitLab
where everybody is already trusted says so as `['.*']`.

**`route` is only where a failure is reported.** Nothing routes a run's output any more. Those runs
are unattended, so nothing else would ever mention one that broke; the notice is sent by the
application rather than by the agent, since the failure most worth hearing about is the one where the
model is what broke. It needs a surface that can send and is otherwise logged.

What arrives is enough to decide whether to go and look without opening a database: which source and
which situation, what went wrong, which attempt this was and how much has been observed of it, what
the last look that worked concluded, whether anything will pick the situation up again on its own —
it will not, until something else is observed for it — and the most recent observations, as many as
`max-evidence`. Those last are quoted between a fence saying whose words they are, and everything
whoever caused the event wrote is escaped for the chat first, titles and error messages included: a
mention tag in an alert name must not be able to notify a group through the bot.

## What the agent should *do* about it

Not a setting: a **playbook**. Documents you write into the knowledge base and then edit like any
other document, without a deployment. Each source names which of them are its playbook and what to
look them up with (`playbook.query` and `playbook.filter`), and a triage run is given them before it
decides anything. The bases looked in are the ones that source's `owner` was configured with — the
`user-id`'s, and the `group-id`'s and `tenant-id`'s if it has them — the same three a `SearchKnowledge`
call inside that run reaches, and the same rule a person's run follows. So a playbook can live in a
knowledge base your team already edits rather than only in an identity nobody logs in as. Never a
group or tenant an incoming event named, so what the agent is told to do can never be chosen by
whoever sent the event. A source with no playbook triages on the shipped prompt alone.

Filing a playbook in a shared base means anybody who can write into that base could write a playbook,
which is what `playbook.filter` is for: name the exact document ids that count and an ordinary note
somebody files into the team's knowledge base is not one. Leave the filter blank and every document
those bases hold is a candidate.

Writing them is what the admin-only `ListPlaybooks` and `WritePlaybook` tools are for, alongside
`ListOwnerKnowledgeBase` and `SearchOwnerKnowledge`, which read back a knowledge base nobody logs in
as.

## What this does and does not buy you

Everything an event carries was written by whoever caused it. The triage prompts say so at length —
but a model has no privilege separation between the parts of what it is shown, so that framing raises
the cost of an attack rather than making it impossible. Payload text is evidence, never routing and
never instructions.

Bounding who can send at all is the part that can actually be decided, and `trusted-actors` is where
it is decided. A triage run must assume an identity of the agent's own rather than a person's,
because a scenario cannot withhold the files, credentials and MCP servers that come with an identity.

## Adding a source

[contributing.md § A webhook event source](contributing.md#a-webhook-event-source) and
[§ A polled event source](contributing.md#a-polled-event-source) have the steps. In short: implement
`WebhookSource` for an HTTP delivery, or report to `EventIntakes` directly for anything else; ship a
triage prompt under `events/prompts/`; add the module to whichever applications should carry it; and
add its defaults to the `app.events.sources` block.
