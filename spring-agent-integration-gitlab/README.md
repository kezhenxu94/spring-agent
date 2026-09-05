# spring-agent-integration-gitlab

> **Audience:** a developer adding or changing an event source. For turning this on in a deployment,
> see [docs/events.md](../docs/events.md) and the `app.events` block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml).

GitLab webhook deliveries, read as observations for
[`spring-agent-events`](../spring-agent-events/README.md). Same shape as
[the GitHub source](../spring-agent-integration-github/README.md), against a product that
authenticates differently.

## What it contributes

One `WebhookSource` bean named `gitlab`, which makes `POST /events/webhooks/gitlab` live, and a triage
prompt under `events/prompts/`.

## How a delivery is authenticated

`X-Gitlab-Token`, a shared secret GitLab sends verbatim, compared in constant time — `String.equals`
would return at the first differing character and let a patient caller recover the token.

Unlike GitHub's signature this does **not** cover the body, so nothing inside a GitLab payload is
authenticated by the check. Only that the sender knew the token. Bear that in mind when reading what
`trusted-actors` is worth for this source: it bounds who may deliver, not what a delivery may claim.

A delivery with no configured secret is refused.

## What an observation carries

`X-Gitlab-Event-UUID` is the delivery id, falling back to a hash of the body plus a time bucket where
GitLab sends none — a bucket rather than the body alone, since GitLab will send byte-identical bodies
for genuinely separate events. `X-Gitlab-Event` becomes the kind.

## Setting it up in GitLab

Project or group **Settings → Webhooks**: the URL is `https://<your host>/events/webhooks/gitlab` and
the secret token the value this deployment configured for the source. Pick the triggers you want
triaged.
