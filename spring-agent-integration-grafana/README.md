# spring-agent-integration-grafana

> **Audience:** a developer adding or changing an event source. For turning this on in a deployment,
> see [docs/events.md](../docs/events.md) and the `app.events` block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

Grafana alert notifications, read as observations for
[`spring-agent-events`](../spring-agent-events/README.md). This is the source the debounce was
designed for: an outage produces a great many alerts that are one thing worth saying.

## What it contributes

One `WebhookSource` bean named `grafana`, which makes `POST /events/webhooks/grafana` live, and a
triage prompt under `events/prompts/`.

## How a delivery is authenticated

The `Authorization` header, in either scheme Grafana's webhook contact point can send:

- `Bearer <secret>`, compared in constant time;
- `Basic <base64>`, of which **only the password half is checked**. The username is a free-text field
  a deployment fills in with anything, so requiring a particular value would reject genuine traffic
  for a reason nothing in the configuration explains, while checking it against nothing adds no
  security — the password is the secret.

Any other scheme, or no configured secret, is refused.

## What an observation carries

Grafana sends no delivery id of its own, so it is a hash of the body plus a time bucket: identical
bodies do arrive for separate firings, and the bucket is what keeps them apart without counting a
genuine redelivery twice. The correlation key is derived from the alert's own labels and truncated to
a hash past a length bound — read `GrafanaWebhookSource` for exactly which labels, since that choice
decides what becomes one situation.

## Setting it up in Grafana

**Alerting → Contact points → Add contact point**, integration *Webhook*, URL
`https://<your host>/events/webhooks/grafana`. Under the optional settings choose either bearer or
basic auth and put this deployment's secret in as the token or the password. Then point a
notification policy at the contact point.
