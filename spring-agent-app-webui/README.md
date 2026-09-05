# spring-agent-app-webui

> **Audience:** whoever deploys this. The runtime with a browser as its only surface. The
> configuration reference is [`src/main/resources/application.yaml`](src/main/resources/application.yaml),
> which is derived from [the Feishu server's](../spring-agent-app-feishu/src/main/resources/application.yaml)
> and must stay in step with it.

```sh
./gradlew :spring-agent-app-webui:bootRun
```

Then open <http://localhost:8080>.

## What it carries

[core](../spring-agent-core/README.md) ·
[websocket](../spring-agent-integration-websocket/README.md) ·
[jpa](../spring-agent-persistence-jpa/README.md) /
[mongodb](../spring-agent-persistence-mongodb/README.md) /
[redis](../spring-agent-persistence-redis/README.md) ·
[kubernetes](../spring-agent-tools-shell-kubernetes/README.md) /
[docker](../spring-agent-tools-shell-docker/README.md) shell ·
[rag-milvus](../spring-agent-rag-milvus/README.md).

**No bot, no webhook receiver, no chat platform.** So what it gives you is the runtime itself with
everything a run does made visible: the answer as it streams, what the model is thinking, every tool
call and what it returned, a card per subagent, the live to-do list, the sources a knowledge base was
consulted for, what the turn cost, and the agent's own questions as a form you answer in the page.

Because it carries no `spring-agent-events`, the admin knowledge-base box has no source-owner
suggestions here — the servers shipped in this repository keep the webhook receiver and the browser in
separate deployments.

## Signing in

Feishu OAuth by default:

| Variable | What it is |
| --- | --- |
| `FEISHU_APP_ID`, `FEISHU_APP_SECRET` | The Feishu app's credentials. The same pair a bot uses, but only the OAuth half is used here — no SDK, no websocket, no bot |
| `FEISHU_TENANT_ID` | Whose people may use this deployment, as a tenant key. **There is no permissive default**: unset, nobody is let in. The agent acts with the logged-in person's credentials and files, so this is the line between a colleague and a stranger who happens to have installed the same app |

In the Feishu console add `http://localhost:8080/login/oauth2/code/feishu` (and the same URL under
your real host) as a redirect URI, and grant the profile scopes that return `open_id` and
`tenant_key`.

To sign in with **Slack** instead, run with `SPRING_PROFILES_ACTIVE=slack-login` and set
`SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET` and `SLACK_TEAM_ID`. A profile rather than a second
registration, because Spring Security refuses to start with a registration whose client id is empty,
so the two cannot both sit there half-configured. The Slack app needs `openid`, `profile` and `email`
user scopes and `http://localhost:8080/login/oauth2/code/slack` as a redirect URI.

## What a person meets

**A conversation reads as a conversation.** Both halves are drawn as markdown, so a message typed with
backticks, a pasted path or a bulleted list arrives looking like what was written. What the run did on
the way to the answer hangs off a hairline rail in the margin, numbered with the journal's own
sequence — which is the number the browser sends back to resume.

**The sidebar is three tabs**: the conversations, what the agent has been asked to do later, and —
wherever a deployment has one — the knowledge base. Each has an address, so `#/chat/<conversation>`,
`#/tasks/<task>` and `#/kb/<scope>/<document>` are links worth keeping and the back button works
between them. A task's ⋯ menu opens the conversation its answers go into, edits the task, or calls it
off; editing is held to exactly the rules the agent's own `UpdateScheduledTask` is. How many times a
task *has* fired is not editable anywhere: that is a record of what happened. Nothing on that screen
creates a task, because nothing can — a schedule comes from asking the agent for one.

**The knowledge base tab** appears wherever `RAG_ENABLED` and a Milvus are configured. Documents are
listed one row per document, searched from the box above, and opened to show where they came from, how
many chunks they were split into, who else can read them and what they actually say — the same stored
text the agent is handed when it retrieves the document. Adding is on the same screen: files are
uploaded, stored in your own workspace and indexed on the spot, and a note is typed straight in. It is
the same knowledge base a conversation reaches with `IndexKnowledge` and `SearchKnowledge`. Sharing,
moving and deleting always name which knowledge base the document is in: the same file filed both
privately and company-wide is two documents wearing one id.

Somebody in `ADMINS` gets `Read another person` in that menu — listing, searching and reading somebody
else's knowledge base, and nothing else, mirroring `ListOwnerKnowledgeBase` and `SearchOwnerKnowledge`.
Deleting or re-scoping somebody else's document is not offered anywhere.

**A run outlives the page it was started from.** The page subscribes to a run over a websocket, and
that subscription is a reader of a run happening on the server, never the run itself — so refreshing,
closing the tab or coming back an hour later re-attaches and redraws everything, and nothing a browser
does can cancel a run except pressing Stop. A question the agent asked is written down rather than
held open, so it survives a restart of the server too.

## Its own switches

On top of everything [the Feishu server](../spring-agent-app-feishu/README.md) takes:

| Variable | Default | What it does |
| --- | --- | --- |
| `WEB_TITLE` | translated per language | What the deployment calls itself: the browser tab, the sidebar brand, the heading before a conversation has a title. One name for every language; unset, each reader gets the shipped name in their own language |
| `WEB_LOGO` | the shipped mark | The mark beside that name: a path this deployment serves, an absolute address, or a `data:` URI. A path of its own needs a `permitAll` rule beside `/js/**` in `SecurityConfigurer` |
| `WEB_FAVICON` | `WEB_LOGO` | The tab icon, in the same forms. Worth setting apart from the logo only where that logo is a wordmark, which rarely reads at sixteen pixels |
| `WEB_MESSAGES` | none | Comma-separated basenames of message bundles consulted before the server's own, so a deployment can reword any of its text |
| `WEB_JOURNAL_RETENTION` | `30m` | How long a finished run's detail is kept for a browser that comes back to it. Past this the conversation is still there; how it was reached is not |
| `WEB_JOURNAL_MAX_RUNS` | `500` | How many runs are held in memory at all. Finished ones are dropped first; a live run never is |
| `WEB_QUESTION_TTL` | `24h` | How long an unanswered question stays answerable |
| `WEB_LOCALE` | `en` | The language for a reader whose browser asks for one nothing is written in. Theirs is detected from `Accept-Language`; the switcher in the page overrides both |

`TOOLS_SHELL_TYPE` defaults to `none` here, as on every server. This surface is reachable by everyone
in the tenant, and `local` would mean every one of them can run commands in the server process with
its filesystem and its secrets. Use `kubernetes` or `docker`, which sandbox per user.

CSRF is **on** in this application, unlike the webhook servers': a POST here makes the agent act with
the logged-in person's credentials, files and MCP servers.
