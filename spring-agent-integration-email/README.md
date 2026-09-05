# spring-agent-integration-email

> **Audience:** a developer working on this source, and the operator who has to decide whether their
> mail plumbing makes it safe. The property reference is the `app.email` block in
> [`application.yaml`](../spring-agent-app-feishu/src/main/resources/application.yaml), which carries
> the reasoning for every default in place.

A watched IMAP mailbox as observations for [`spring-agent-events`](../spring-agent-events/README.md).
It is the one source that **dials out** rather than being dialled, which is why it has an
`app.email.enabled` switch of its own on top of `app.events.enabled`.

Nothing ever replies to a sender.

## What it contributes

No `WebhookSource` — mail is not an HTTP delivery, so `MailObservations` reports to core's
`EventIntakes` directly. That is the shipped example of a **polled** source. Plus a triage prompt
under `events/prompts/`, and the source name `email`, which is spelled again in `application.yaml`
and in the prompt's file name; renaming it in one place leaves the source silently unconfigured and
reading the generic prompt.

## Who counts as a sender

This is the part to understand before turning it on, and the reason this source is the strictest of
them all: unlike a signed webhook, a mailbox is reachable by anybody who learns the address.

A `From` header is a string its author typed. On its own it is no more an identity than the subject
line, so an allow-list matched straight against it would be worse than no list at all — it would take
the part of a message most obviously under an attacker's control and let it decide whether the agent
reads the rest.

What makes it an identity is a DKIM signature that verified for a domain the address belongs to.
**Nothing here verifies that signature.** `AuthenticationResults` reads the verdict out of the
RFC 8601 `Authentication-Results` header, taking only the topmost one bearing the configured
`authserv-id` — headers accumulate downward, so the first one that is yours is the one your own last
hop wrote, and anything below it the sender could have typed.

So this is worth exactly what three assumptions about *your mail plumbing* are worth:

1. the mailbox is fed by a server you control;
2. that server verifies DKIM and records the result in that header;
3. that server **strips** inbound headers already bearing your `authserv-id` before adding its own.
   RFC 8601 §5 says it should. If yours does not, any stranger can write a header claiming a pass.

The application refuses to start when `authserv-id` is unset, because the alternative is
authenticating nobody, dropping every message, and looking exactly like a mailbox nobody has written
to. `app.events.sources.email.trusted-actors` then decides which of the verified senders are heard.

## How the mailbox is read

- **IMAP IDLE**, reissued every `cancel-idle-interval` (9 minutes, under the 29 the RFC allows). IDLE
  blocks until the server speaks, so a connection that died without being closed looks exactly like a
  quiet mailbox, for ever — the reissue is what finds it within one interval instead of at the next
  delivery nobody gets.
- **A user flag of its own** (`spring-agent-seen`) marks a message already reported, rather than
  `\Seen`. The agent's progress through the mailbox is then not the same bit a person toggles by
  opening a message, and somebody reading the mailbox cannot make it skip mail.
- **`max-fetch-size`** (50) bounds one pass, so a first start against a mailbox holding a thousand
  messages does not become a thousand situations at once. The rest follow.
- **`max-body-length`** (8000 characters) bounds what is kept as evidence; a body has no useful upper
  bound and the situation it lands in stores a bounded number of them.
- **`reconnect-delay`** (30s) after a failure. A server that is down stays down for minutes, and a
  tight loop against it is how a mailbox gets its account locked.

## Turning it on

`EMAIL_ENABLED=true`, plus `EMAIL_HOST`, `EMAIL_USERNAME`, `EMAIL_PASSWORD` and `EMAIL_AUTHSERV_ID`.
It connects over `imaps` on 993 by default; the store URI is built without credentials in it — see
`EmailAutoConfiguration` for why. `app.events.enabled` must be on as well, and the source needs a
policy under `app.events.sources.email` like any other.
