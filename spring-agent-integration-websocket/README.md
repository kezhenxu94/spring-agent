# spring-agent-integration-websocket

> **Audience:** a developer depending on this module or changing the page it serves. To *deploy* the
> browser UI, read [`spring-agent-app-webui`](../spring-agent-app-webui/README.md), or
> [`spring-agent-app-web-feishu`](../spring-agent-app-web-feishu/README.md) for the browser beside a
> Feishu bot. The property reference is the `app.web` block in
> [`application.yaml`](../spring-agent-app-webui/src/main/resources/application.yaml).

A browser as a surface: the single-page UI, its REST endpoints, STOMP run streaming, and the
knowledge base as a page.

It is **not** a chat surface for the purposes of the one-surface rule. It registers a
`@Bean AgentResponseListener` so that a scheduled task firing or a subagent starting is visible in the
page, but `WebRunListener` claims a run only when the request's `chatType` is `web` — which no other
surface sets — and it contributes no `PromptVariablesContributor` and no `Notifier`. That is what lets
it sit beside a chat integration, and it is the whole of what makes that safe: anything added here
has to say which runs it answers for.

## The run journal, and why a browser is only ever a reader

`RunJournal` holds every event a run emitted, and an HTTP or websocket connection is only ever a
*reader* of one. Opening a subscription starts nothing and dropping one stops nothing, which is what
makes closing a tab safe — only pressing Stop cancels a run.

A late or reconnecting browser says how far it got and is replayed from there, so replay is
per-subscriber: `RunStreamSubscriptions` writes STOMP frames straight to the asking session rather
than publishing to a topic, which would hand one tab another's backlog. `RunJournal.attach` replays
and registers the reader under one lock, because "send history then subscribe" loses what arrives in
between and "subscribe then send history" sends some of it twice.

Journals live in the heap of whichever replica ran the turn — see
[advanced.md](../docs/advanced.md#running-more-than-one-replica-and-replacing-them) for what that
means for a deployment with more than one. `WEB_JOURNAL_RETENTION` and `WEB_JOURNAL_MAX_RUNS` bound
what is kept.

## Reaching a store without a run in between

This is the one surface that does. `KnowledgeController` puts core's `KnowledgeBase` SPI behind
`/api/knowledge` so a person can list, search, read, add to and correct what the agent remembers
rather than asking the model to do it with the knowledge tools. Reading one document's stored text is
what `KnowledgeBase.read` exists for — no vector store can be asked for a document's own content
without a query, which is why enumeration and reading both live on the SPI.

Rules that hold there and must keep holding:

- **The scope is derived from the session, never from the request**, exactly as `ChatController`
  derives a run's. The one exception is that an `app.ai.admins` member may name an `owner` on the
  *read* endpoints, mirroring `KnowledgeAdminTools`; no write accepts one.
- **Every endpoint answers 404 where no `KnowledgeBase` bean exists**, and `/api/me` reports that
  first so the page never offers the section.
- **A document id travels in the query string or the body, never in the path.** A document indexed
  from a file is identified by its absolute path, whose slashes are rejected encoded and are extra
  segments unencoded.

CSRF is on in the applications carrying this module, unlike the webhook servers': a POST here makes
the agent act with the logged-in person's credentials, files and MCP servers.

## The page

Plain ES modules under `src/main/resources/static/js`, no bundler. Two rules there are load-bearing.

**Modules are layered.** The core first (`state`, `dom`, `i18n`, `render`, `api`, `toast`, `route`),
then `status`/`theme`/`sidebar`, then the features, then `app.js` as the only file importing across
all of them. A module imports only ones earlier than itself, and a backward edge goes over the `bus`
in `state.js`. A cycle does not fail loudly: it resolves a binding to `undefined` and throws on
whichever path nobody clicked.

**Navigation is one-way.** `route.js` owns the hash (`#/chat/<id>`, `#/tasks/<id>`, `#/kb/<docId>`), a
click calls `go`, and `app.js`'s `dispatch` decides what is on screen. Nothing opens a thing and then
writes the hash. `panels.js` is the only thing that hides and shows the main column's three panels, so
a section cannot forget to put the composer back.

`styles.css` is a single linked entry that `@import`s `css/*`, and that order is load-bearing: rules
there tie on specificity with Tailwind's utilities and with each other, so a rule that must hold
regardless of file order buys specificity and says why (see `.drawer-only`).

Text the page writes for itself is in `static/js/i18n.js`, read with `t(key)` and `data-i18n*`
attributes. It carries every language, and **a list drawn in JavaScript has to redraw on
`language:changed`** or it stays in the language the page started in.

Adding a static file outside `/js/**` or `/css/**` needs a matching `permitAll` in the application's
`SecurityConfigurer`, or the page breaks *only* for somebody not signed in yet.

## Handing an answer back to a chat

`ChatMirrors` builds the mirror as a **per-request** listener, so nothing is persisted and no bean has
to work out which runs it was wanted for. It resolves "the chat surface beside this page" through
core's `Notifier`, of which a deployment has at most one. The feature itself is described in
[advanced.md](../docs/advanced.md#sending-an-answer-back-to-the-chat).
