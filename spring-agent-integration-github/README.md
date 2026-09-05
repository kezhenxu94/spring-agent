# spring-agent-integration-github

> **Audience:** a developer adding or changing an event source. For turning this on in a deployment,
> see [docs/events.md](../docs/events.md) and the `app.events` block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

GitHub webhook deliveries, read as observations for
[`spring-agent-events`](../spring-agent-events/README.md). It is the smallest complete event source in
the repository — two classes and a prompt file — and the one to copy when adding another.

## What it contributes

One `WebhookSource` bean named `github`, which makes `POST /events/webhooks/github` live, and a
triage prompt under `events/prompts/`. Nothing else: it registers no tools, no listener and no
surface. A run about a GitHub event is a triage run like any other.

## How a delivery is authenticated

`X-Hub-Signature-256`, HMAC-SHA256 over the **raw** request body, compared with
`MessageDigest.isEqual` rather than `String.equals` — the latter returns at the first differing byte,
which is a timing oracle for a patient caller. `X-Hub-Signature` (SHA-1) is deliberately not accepted.

The signature covers the whole body, which is what makes the login inside it *authenticated* rather
than merely claimed, and therefore something `trusted-actors` can be applied to.

A delivery with no configured secret is refused. `ping` is answered and dropped rather than turned
into an observation.

## What an observation carries

`X-GitHub-Delivery` is the delivery id, so a redelivery is not counted twice; where GitHub sends none
the id falls back to a hash of the body. `X-GitHub-Event` becomes the kind. The correlation key is
what decides which deliveries become one situation — read `GitHubWebhookSource` for how it is derived
per event kind, since that choice is the whole behaviour of the module.

## Setting it up in GitHub

Repository or organisation **Settings → Webhooks → Add webhook**: the payload URL is
`https://<your host>/events/webhooks/github`, the content type `application/json`, and the secret the
same value as `EVENTS_GITHUB_SECRET`. Choose the events you want triaged — the source ignores what it
has no correlation for rather than failing.

`app.events.sources.github.trusted-actors` decides which logins are heard at all. A private repository
where everybody is already trusted says so as `['.*']`; leaving it out means the same thing but is
named in the log at startup so nobody arrives at it by accident.
