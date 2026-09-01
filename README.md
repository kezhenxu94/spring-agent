# spring-agent

[![Maven Central](https://img.shields.io/maven-central/v/me.kezhenxu94/spring-agent-core?label=Maven%20Central)](https://central.sonatype.com/artifact/me.kezhenxu94/spring-agent-core)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](#)

A tool-using agent you can run as it is: a server whose surface is a Feishu/Lark bot, and a command
line for your own machine. Both give the agent a shell sandbox, MCP servers, skills, memories,
credentials, scheduled tasks, subagents, a knowledge base and file publishing — and let it pick up
new abilities in conversation, without a redeploy.

It is also a library. If you are building your own agent on Spring Boot 4 and Spring AI, read
[docs/sdk.md](docs/sdk.md); if you want to change this repository, read
[docs/contributing.md](docs/contributing.md). For how the pieces fit together — the surfaces, the
event sources, what a run is offered and where state lives — [docs/architecture.md](docs/architecture.md)
draws it.

Every property and environment variable is documented in place, with the reason for its default, in
[`spring-agent-app-feishu/src/main/resources/application.yaml`](spring-agent-app-feishu/src/main/resources/application.yaml).
That file, not this README, is the configuration reference.

## (Nearly) everything is a tool

The agent is not a fixed feature list. Almost everything it can do arrives as a tool, and the
registries that decide what tools exist are themselves tools — so the agent extends itself, in
conversation:

| Ask it to… | …and it calls | …which gives the next run |
| --- | --- | --- |
| use an MCP server you name | `AddMcpServer` | every tool that server offers |
| learn a procedure | `WriteSkillFile` | a skill, loaded on demand |
| remember something about you | the memory tools | notes it reads back before replying |
| hold a token for later | `SetCredential` | the secret in its sandbox, never in a prompt |
| do this every Monday | `CreateScheduledTask` | a run that fires on its own, within `app.scheduling.sweep-interval` of the moment, and still fired if the agent was down when it came round |
| every 10 minutes until it is fixed, or 10 times | `CreateScheduledTask` with `maxRuns`, `StopThisScheduledTask` | a task that counts its own runs, and ends itself when what it watched for happens |
| take on something long | `StartSubagent` | a second run doing the work, reporting back |
| write this down for the team | `IndexKnowledge` | an answer that consults it from then on |

Nothing is registered up front. The tool set is assembled once per request out of the built-in
tools, the MCP servers that user can reach, that user's skills and whatever the deployment
configured — so a set that grew a minute ago is offered to the next turn. Because it is open-ended,
both applications turn on tool search, which retrieves the few tools a turn actually needs instead
of sending the model all of them.

## Every user gets their own agent

Every run carries a user id, and that id — not the process — owns the state. Under
`app.storage.location` each user has a home with `memories/`, `skills/`, `workspace/` and
`artifacts/`, and the filesystem tools are confined to that root, so one user's agent cannot read
another's files. The same line runs through everything else:

- **MCP servers** are registered by their owner. `ShareMcpServer` grants use to another person, a
  group chat, or everyone, while editing, removing and re-sharing stay with the owner — the
  recipient never sees the URL or headers. Servers the deployment configures for everybody under
  `spring.ai.mcp.client.*` are listed alongside them and belong to nobody.
- **Skills** are folders with a `SKILL.md` in the user's own skills directory. The agent writes and
  deletes them on request; paths outside that directory are refused. After a turn that has cost a
  great many tool calls it also offers one unprompted — it finishes the answer, then asks whether to
  keep the method it worked out, and writes the skill only if you say yes. Turn the offer off with
  `SKILLS_OFFER_AFTER_EXPENSIVE_RUNS=false`, or move the bar with `SKILLS_TOOL_CALL_THRESHOLD`.
- **Memories** are files in the user's memories directory, written and read back by the agent
  itself.
- **Credentials** are per-user: a Kubernetes Secret mounted into that user's sandbox, or an
  encrypted row, so a token reaches a shell as an environment variable and never a prompt. On
  Kubernetes an operator can also share Secrets they provisioned themselves — with a group, a
  tenant, or one named person — by labelling them to match a selector under
  `app.ai.tools.shell.kubernetes.credentials.shared`; a credential the user set for themselves
  still wins the name.
- **The sandbox shell** is a Pod or container per user, with its own slice of the volume, torn down
  when idle and rebuilt on the next command.
- **The knowledge base** is scoped to a person, a group chat or the whole tenant, and a run only
  ever searches what its own identity may read.
- **The chat model itself**, where the deployment allows it: a user can register their own
  OpenAI-compatible endpoints and have their conversations answered by one of them, leaving
  everybody else on the application's. See [Bring your own model](#bring-your-own-model).

A message from a group chat also reaches the group's home and the group's knowledge, which is how a
team shares skills and notes without sharing anything private. Nobody needs an administrator to set
any of this up — they ask the agent, and it registers it for them. On the command line the same
machinery serves the one person at the keyboard, out of `~/.spring-agent`.

## Bring your own model

Every run goes through the application's model unless the person asking has chosen another. Off
unless `USER_MODELS_ENCRYPTION_KEY` is set — the tokens people register are bearer credentials for
somebody else's paid endpoint, and the only alternative to storing them sealed is storing them in
the clear, so a deployment that cannot do the first does not offer the feature at all:

```sh
export USER_MODELS_ENCRYPTION_KEY=$(openssl rand -base64 32)
```

Keep it out of the database and out of version control. Rotating it does not re-seal what is
already stored: those rows stop being readable and say so, rather than quietly behaving as though
nobody had registered anything.

With it set, a user can ask the agent — `add my Kimi endpoint`, `what models do I have`, `switch me
back to the default` — through the `AddChatModel`, `ListChatModels`, `UseChatModel` and
`DeleteChatModel` tools. An endpoint is connection-tested before it is stored: if it cannot be
reached, the token is refused or the model name is unknown, nothing is saved and the reason comes
back. Tokens are never shown again, to anyone, including the person who set them.

There is also a way in that does not involve the agent, and it is the important one. A model that
stops answering would otherwise break the very run needed to undo it, so **`/config` never touches
the LLM**:

| Surface | How |
| --- | --- |
| Feishu | Send `/config`. A card opens with a dropdown of what you could be on and fields for adding an endpoint. |
| Slack | Type `/config`. A modal opens, private to you, so the API token never enters channel history. The command has to be declared on the Slack app — see below. If nobody did, send ` /config` with a leading space instead: Slack sends that verbatim rather than looking for a command, and the same form arrives as a message. |
| Command line | `/config` lists your models, `/config <name>` switches, `/config default` returns to the built-in one, and `/config <name> <effort>` or `/config default <effort>` sets how hard it thinks. |

The dropdown also lists what the application's own endpoint reports it can serve, so choosing among
the models the deployment already pays for needs no token of your own. That listing is best-effort:
an endpoint that does not answer `GET /models` simply shows the one built-in entry. What it does
answer with is usually more than chat models — the embedding model this deployment uses, a
reranker, a speech or image model — and those are filtered out by name, since `GET /models` says
nothing about what a model is for. Where the endpoint serves more chat models than a card can hold
the list is cut short. Either way, fill in the **Model** field alone, leaving name, base URL and
token empty, to name any model the endpoint serves directly.

### How hard it thinks

The same form carries the reasoning effort, chosen from a list rather than typed — `none`, `minimal`,
`low`, `medium`, `high`, `xhigh`, `max`, as the OpenAI API spells them — plus two entries that are
not values:

- **whatever this deployment is set to**, which is `OPENAI_REASONING_EFFORT` and what every model
  answers with until somebody chooses otherwise;
- **not sent at all**, for a gateway that rejects `reasoning_effort` outright rather than ignoring
  it. `none` is a real value the newer models act on, so it is not a way of leaving the parameter
  out.

It applies to whichever model the form leaves you on, the built-in ones included, and is remembered
per model: switching away and back keeps it. The effort shown on the form is the one in force, and
the thinking panel on a Feishu reply reports the effort that run was actually made with rather than
the deployment's.

An effort is part of what gets connection-tested when an endpoint is registered, so a gateway that
refuses one says so before anything is stored.

The **embedding** model is deliberately not configurable this way. The knowledge base is shared
between users and its collections are built with one embedding model, so letting one person change
theirs would invalidate vectors that are not theirs.

Other knobs, all optional: `USER_MODELS_MAX_PER_USER` (default 10), `USER_MODELS_CACHE_SIZE`
(default 50 live endpoints) and `USER_MODELS_PROBE_TIMEOUT` (default 30s).

## It can also speak first

Given `app.events.enabled`, the agent watches instead of waiting: alerts and code-hosting webhooks
arrive at `/events/webhooks/<source>`, group chat messages it was not addressed in arrive through
the Feishu integration. Related events are correlated into one *situation* and left to settle — a
thousand alerts from one outage become one run, not a thousand — and only then is the agent woken to
decide whether it has anything worth saying. Silence is a normal answer.

GitHub, GitLab and Grafana ship as sources. Each authenticates its own deliveries, and a source
nobody configured a secret for refuses everything, so the endpoint is safe to expose but useless
until somebody sets it up. Timings — how long to wait, how often at most, when to close a situation
— are per source, because an alert and a chat want very different manners. See the `app.events`
block in `application.yaml`.

Who a source *runs as* is `app.events.sources.<name>.owner`. Its `user-id` must be an identity of
the agent's own and never a person's, since a triage run assumes it along with that identity's files,
credentials and MCP servers. `group-id` and `tenant-id` beside it are optional and say what else that
identity belongs to, which is what gives the run the group's and the tenant's shared workspaces as
well as its own — configured rather than taken from whatever the event named, so a surface that
reports a tenant cannot pick the shared workspace an unattended run writes into.

Who a source will listen to is `app.events.sources.<name>.trusted-actors`: a list of regular
expressions, matched whole and without regard to case against an identity the source authenticated —
a GitHub login inside a body the signature already covered, say. Anything else is dropped before it
is recorded, and the sender is told nothing, since an answer that distinguished the two would let
anybody read the list back. Leave it out and everybody is heard, which is what an existing
deployment keeps on upgrading; every source without one is named in the log at startup. A private
repository or an internal GitLab where everybody is already trusted says so as `['.*']`.

It is worth being plain about what this does and does not buy. Everything an event carries was
written by whoever caused it, and the triage prompts say so at length — but a model has no privilege
separation between the parts of what it is shown, so that framing raises the cost of an attack
rather than making it impossible. Bounding who can send at all is the part that can actually be
decided, and this is where it is decided.

Mail is a source too, and the only one that dials out rather than being dialled: `app.email.*`
watches an IMAP mailbox and reads what arrives as observations. It is the strictest of the sources
and refuses to start unless told whose mail to accept, because unlike a signed webhook a mailbox is
reachable by anybody who learns the address. A sender counts as somebody only where DKIM verified
their domain, which this reads from the `Authentication-Results` header your own mail server writes
— so it works only where the mailbox is fed by a server you control that verifies DKIM and strips
inbound forgeries of that header. Nothing ever replies to a sender.

What the agent should actually *do* about a source's events — what matters, what to check first, who
to tell and where — is not a setting. It is a **playbook**: documents you write into the knowledge
base and then edit like any other document, without a deployment. Each source names which of them
are its playbook and what to look them up with (`playbook.query` and `playbook.filter`), and a
triage run is given them before it decides anything. The base is always the one owned by that
source's `owner.user-id` — never a group or tenant, whether an incoming event named it or the owner
was configured with it — so what the agent is
told to do can never be chosen by whoever sent the event. A source with no playbook triages on the
shipped prompt alone, as before.

Nothing routes a run's output any more: `app.events.sources.<name>.route` is now only where a triage
run's **failure** is reported. Those runs are unattended, so nothing else would ever mention one that
broke; the notice is sent by the application rather than by the agent, since the failure most worth
hearing about is the one where the model is what broke. It needs a surface that can send — the
Feishu integration can — and is otherwise logged.

## Run the server

```sh
docker run --env-file .env -p 8080:8080 ghcr.io/kezhenxu94/spring-agent:latest
```

Or from a clone: `./gradlew :spring-agent-app-feishu:bootRun`.

These have no defaults and the application will not start without them — `OPENAI_BASE_URL`,
`OPENAI_API_KEY`, `OPENAI_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL`. Any
OpenAI-compatible endpoint will do; the embedding model is needed even if you index nothing, since
tool search is built by embedding tool descriptions.

The server ships with the Feishu/Lark integration wired up, so a bot in a chat is an agent surface
with no code written. It expects the Feishu block too — `FEISHU_APP_ID`, `FEISHU_APP_SECRET`,
`FEISHU_ENCRYPT_KEY`, `FEISHU_TENANT_ID`, `FEISHU_TENANT_DOMAIN`, `FEISHU_BOT_OPEN_ID` — which also
back the login on published-file pages. Set `APP_FEISHU_ENABLED=false` to leave the whole
integration out; the shipped `application.yaml` still names the app id and secret for that login, so
override the `spring.security.oauth2` block as well if you run without Feishu at all.

Opening a chat with the bot for the first time is answered with a welcome card: what the agent is,
and a few things you can tap to ask it rather than having to work out what to type. After that,
opening the chat says nothing — unless the agent has learned to do something you have not been told
about, in which case you get one card listing exactly what changed since your last visit, and
nothing you have already read.

That "since" is the notes under
[`feishu/updates/`](spring-agent-integration-feishu/src/main/resources/feishu/updates), one markdown
file per version, named `1.md`, `2.md` and so on; the greeting itself is
[`feishu/welcome.md`](spring-agent-integration-feishu/src/main/resources/feishu/welcome.md). The
agent records the number of the last note each person was shown, which is what lets it tell them
only the new ones. To write your own, point `FEISHU_WELCOME` and `FEISHU_UPDATES` at files of your
own — see `app.feishu` in
[`application.yaml`](spring-agent-app-feishu/src/main/resources/application.yaml) for the rules, including
what a gap in the numbering does.

To receive any of this the app's event subscription needs **用户和机器人的会话首次被创建**
(`p2p_chat_create`) and **用户进入与机器人的会话**
(`im.chat.access_event.bot_p2p_chat_entered_v1`) added in the Feishu console. Only the second is
required — the first is delivered over webhooks only, so a deployment on the long connection is
greeted by the second either way.

A turn is answered in a card that is written as the run goes: the answer as it streams, what the
model thought, every tool call and what it returned, a panel per subagent, the to-do list, and what
the turn cost. Feishu allows a card 30KB and 200 elements, and a long turn can outgrow both — so
when a card fills up the agent finishes it and replies another onto the same message, carrying on
where it left off, as many times as the turn needs. A very long answer arrives as a run of cards
rather than stopping partway; the stop button is always on the one still being written.

Everything else is optional and set in
[`application.yaml`](spring-agent-app-feishu/src/main/resources/application.yaml). Two switches decide what
the deployment actually is:

| Property (env var) | Values | Default |
| --- | --- | --- |
| `app.persistence.type` (`PERSISTENCE_TYPE`) | `jpa` (SQLite, no server needed), `mongodb`, `redis` | `jpa` |
| `app.ai.tools.shell.type` (`TOOLS_SHELL_TYPE`) | `none`, `kubernetes`, `docker`, `local` | `none` |

The shell defaults to `none` because it runs commands the model wrote; turn it on deliberately, and
prefer `kubernetes` or `docker`, which give each user a disposable sandbox, over `local`, which does
not.

One more knob is worth knowing about before a tool surprises you with a wall of text.
`app.ai.tools.max-result-chars` (`TOOLS_MAX_RESULT_CHARS`, default `30000`) is how long *any*
tool's result may be — the shell, an MCP server, a webhook reader alike — before it is written to
your workspace and the agent handed the path instead of the text. Nothing is lost: the agent reads
the file, greps it, or sends it to you. It is counted in characters rather than tokens, so the same
number is far more text in English than in Chinese; lower it if your tools answer in Chinese and
runs feel like they run out of room.

The agent does not have to read such a file back to use it, either. Where a tool parameter says it
takes a file reference, the agent can give it `@file:<path>` — or `@file:<path>#/pointer/into/the/json`
for one part — and the saved result goes into the call without passing through the model a second
time. `app.ai.tools.max-inlined-input-chars` (`TOOLS_MAX_INLINED_INPUT_CHARS`, default `300000`)
bounds how much one such reference may carry. Which parameters accept one is fixed in the code
rather than configurable, deliberately: a parameter that reads a file is a parameter a tool call
can be steered into reading a file with, and some runs act on text written by strangers.

Two notes for a deployment running more than one replica. These files live under
`app.storage.location`, so unless that is shared storage a follow-up turn served by another replica
cannot see them; and a path the agent noted in an earlier conversation may since have been cleaned
up, in which case it is told so and re-runs the tool.

Scheduled tasks are safe across replicas: the schedule is a column on the task rather than a timer
in one process, and each occurrence is won by exactly one replica before it fires. The one thing
they must agree on is `TZ`, since a cron expression is resolved against the JVM's default zone.

The default pair needs nothing running alongside it. For the others, `docker-compose.yaml` has a
profile per value so the containers cannot drift from the application's own choice:

```sh
PERSISTENCE_TYPE=redis VECTORSTORE_TYPE=milvus \
  COMPOSE_PROFILES=$PERSISTENCE_TYPE,$VECTORSTORE_TYPE docker compose up   # backends only
```

Add `app` to `COMPOSE_PROFILES` to run everything in containers. The knowledge base has a profile of
its own, `rag`, and a switch that goes with it — the profile starts Milvus, the variable is what
makes the application use it:

```sh
RAG_ENABLED=true COMPOSE_PROFILES=rag docker compose up
```

Other things worth knowing before a real deployment:

- `ADMINS` lists the people this deployment trusts with everybody else's work, by user id (a Feishu
  open id). An admin's agent reads and posts in chats they are not a member of; they can answer a
  question the agent put to somebody else and speak into a run already going for somebody else; and
  they get the admin-only tools, which today are the ones that write the triage playbooks
  (`ListPlaybooks`, `WritePlaybook`) and the ones that read back a knowledge base nobody logs in as
  (`ListOwnerKnowledgeBase`, `SearchOwnerKnowledge`) — without those a playbook could be written
  into a source owner's knowledge base and never read again. A run keeps the identity it started
  with, so an admin causes things to happen *as* the person being helped — grant it only to people
  you would trust with those files and credentials directly. Never list an events source's
  `owner.user-id` among them — the application refuses to start on that pairing, since a triage run
  assuming an admin identity would hand the admin-only tools to whoever wrote the event it is
  triaging.
- `SPRING_AGENT_LOCALE` chooses the language the agent's own text — and the tool descriptions the
  model reads — are written in. `en` and `zh_CN` ship.
- `app.ai.system-prompt` is where a persona and house rules go. It replaces a five-thousand-character
  default, so read the note above it before overriding.
- On `redis`, use Redis 8 or Redis Stack **configured to keep what it is given** (`maxmemory-policy
  noeviction`, plus AOF or RDB). These are the agent's records, not a cache: a Redis provisioned for
  caching will quietly evict a stored credential or an unfired task.
- `/actuator/health` and `/actuator/prometheus` are exposed for probes and metrics.

### Run the Slack server

The same runtime with Slack as its surface: `./gradlew :spring-agent-app-slack:bootRun`. It takes
the same `OPENAI_*`/`EMBEDDING_*` variables as the Feishu server, plus four of its own.

| Variable | Where it comes from |
| --- | --- |
| `SLACK_BOT_TOKEN` | **OAuth & Permissions** in your app's settings, after installing the app to the workspace. Starts `xoxb-`. |
| `SLACK_APP_TOKEN` | **Basic Information → App-Level Tokens**, generate one with the `connections:write` scope. Starts `xapp-`. This is what opens the Socket Mode connection, and it is a different credential from the bot token. |
| `SLACK_BOT_USER_ID` | **OAuth & Permissions**, or `curl -H "Authorization: Bearer $SLACK_BOT_TOKEN" https://slack.com/api/auth.test` and read `user_id`. Starts `U`. |
| `SLACK_TEAM_ID` | The same `auth.test` call, field `team_id`. Starts `T`. |

`.env` is read by `docker compose` and by `docker run --env-file`, but **not** by
`./gradlew bootRun` — Gradle passes on the environment it was started with and nothing more. So
export it into the shell first:

```sh
set -a; . ./.env; set +a
./gradlew :spring-agent-app-slack:bootRun
```

One variable name to watch: **`SLACK_CLIENT_ID` and `SLACK_CLIENT_SECRET` belong to the browser
surface's Sign in with Slack, not to the bot.** Bolt's own `AppConfig` reads those two from the
environment and treats an app that has them as a multi-workspace OAuth app, which authorizes each
event against an installation store the bot does not have — so exporting them alongside the bot's
own settings used to refuse every event with `401 "a request for an unknown workspace detected"`
while the bot token was perfectly valid. The Slack integration now pins itself to single-workspace
mode regardless, so the two can share a `.env` safely.

An empty `SLACK_BOT_TOKEN=` in `.env` is worse than a missing one, because it resolves: the
application starts, and every event is then answered with `401 "a request for an unknown workspace
detected"` — Bolt calling `auth.test` with an empty token, once per delivery. `SlackIdentity`
refuses to start on that now and says which variable it is, but the shape of the mistake is worth
recognising.

Creating the app, once, at <https://api.slack.com/apps>:

1. **Socket Mode → Enable Socket Mode.** No public URL is needed, and no request signing.
2. **OAuth & Permissions → Bot Token Scopes**: `chat:write`, `im:history`, `channels:history`,
   `groups:history`, `mpim:history`, `files:read`, `files:write`, `reactions:write`, `users:read`.
3. **Event Subscriptions → Subscribe to bot events**: `message.im`, `message.channels`,
   `message.groups`, `message.mpim`, `app_home_opened`. Deliberately **not** `app_mention` — Slack
   delivers a message that mentions the bot under both events with different ids, so subscribing to
   both means answering every mention twice.
4. **Interactivity & Shortcuts → Enable.** With Socket Mode on there is no Request URL to fill in;
   this is what makes the stop button and the question form work.
5. **Slash Commands → Create New Command**, `/config`, description "Choose which chat model answers
   you". Only needed where `USER_MODELS_ENCRYPTION_KEY` is set, and again no Request URL under
   Socket Mode. This one is not plumbing the application can do for itself: Bolt only routes
   commands Slack decides to send, and Slack sends none it has not been told about. Creating it
   adds the `commands` scope, so the app has to be reinstalled afterwards. Skipping this step
   costs the modal but not the feature — a message reading ` /config`, with a leading space, opens
   the same form in the channel.
6. **Install to Workspace**, then invite the bot to any channel you want it to answer in.

Opening a direct message with the bot for the first time is answered with a welcome note, and
afterwards with whatever has changed since — the same `welcome.md` and `updates/N.md` arrangement the
Feishu server uses, under `slack/` and pointed at by `SLACK_WELCOME` and `SLACK_UPDATES`.

A turn is answered in a message that is rewritten as the run goes: the answer as it is written, the
tools it is calling, its task list, and what it cost. Slack allows a message 50 blocks, and a long
turn outgrows that — so when one fills up the agent finishes it and posts another into the same
thread, carrying on where it left off.

## Run the web UI

A third application, `spring-agent-app-webui`: the agent and a browser, and nothing else. No bot, no
webhook receiver, no chat platform — so what it gives you is the runtime itself with everything a run
does made visible: the answer as it streams, what the model is thinking, every tool call and what it
returned, a card per subagent, the live to-do list, the sources a knowledge base was consulted for,
what the turn cost, and the agent's own questions as a form you answer in the page.

```sh
./gradlew :spring-agent-app-webui:bootRun   # the same OPENAI_*/EMBEDDING_* variables, plus the three below
```

Then open <http://localhost:8080>. Sign-in is Feishu OAuth by default, so three more variables are needed. To sign in with Slack
instead, run with `SPRING_PROFILES_ACTIVE=slack-login` and set `SLACK_CLIENT_ID`,
`SLACK_CLIENT_SECRET` and `SLACK_TEAM_ID` — a profile rather than a second registration, because
Spring Security refuses to start with a registration whose client id is empty, so the two cannot
both sit there half-configured. The Slack app needs `openid`, `profile` and `email` user scopes and
`http://localhost:8080/login/oauth2/code/slack` as a redirect URI.

| Variable | What it is |
| --- | --- |
| `FEISHU_APP_ID`, `FEISHU_APP_SECRET` | The Feishu app's credentials. The same pair the bot uses, but only the OAuth half is used here — no SDK, no websocket, no bot |
| `FEISHU_TENANT_ID` | Whose people may use this deployment, as a tenant key. **There is no permissive default**: unset, nobody is let in. The agent acts with the logged-in person's credentials and files, so this is the line between a colleague and a stranger who happens to have installed the same app |

In the Feishu app's console, add `http://localhost:8080/login/oauth2/code/feishu` (and the same URL
under your real host) as a redirect URI, and grant the app the profile scopes it needs to return
`open_id` and `tenant_key`.

**A run outlives the page it was started from.** The page subscribes to a run over a websocket, and
that subscription is a reader of a run that is happening on the server, never the run itself — so refreshing, closing the tab, or coming back an
hour later re-attaches and redraws everything, and nothing a browser does can cancel a run except
pressing Stop. A question the agent asked is written down rather than held open, so it survives a
restart of the server too, and is still there to answer when you come back.

Its own switches, on top of the ones in the table above:

| Variable | Default | What it does |
| --- | --- | --- |
| `WEB_TITLE` | translated per language | What the deployment calls itself: the browser tab, the sidebar brand, the heading before a conversation has a title. One name for every language; unset, each reader gets the shipped name in their own language |
| `WEB_MESSAGES` | none | Comma-separated basenames of message bundles consulted before the server's own, so a deployment can reword any of its text — including `app-title`, which is how it gives itself a different name in each language |
| `WEB_JOURNAL_RETENTION` | `30m` | How long a finished run's detail — its tool calls, its subagents, its thinking — is kept for a browser that comes back to it. Past this the conversation is still there; how it was reached is not |
| `WEB_JOURNAL_MAX_RUNS` | `500` | How many runs are held in memory at all. Finished ones are dropped first; a live run never is |
| `WEB_QUESTION_TTL` | `24h` | How long an unanswered question stays answerable |
| `WEB_LOCALE` | `en` | The language for a reader whose browser asks for one nothing is written in. Theirs is detected from `Accept-Language`, and the switcher in the page overrides both |

`TOOLS_SHELL_TYPE` defaults to `none` here, unlike the command line. This surface is reachable by
everyone in the tenant, and `local` would mean every one of them can run commands in the server
process with its filesystem and its secrets; use `kubernetes` or `docker`, which sandbox per user.

## Run the command line

There is no prebuilt binary; build it from a clone.

```sh
./gradlew :spring-agent-app-cli:bootRun                    # needs the same OPENAI_*/EMBEDDING_* variables
# or a native binary:
./gradlew :spring-agent-app-cli:nativeCompile -Pnative
```

Everything lives in SQLite under `~/.spring-agent`. Type a sentence to talk to the agent; anything
starting with `/` is a command — `/help`, `/clear`, `/session`, `/model`, `/tools`, `/stop`,
`/exit`. Unlike the server it answers the agent's questions inline, so a run continues instead of
ending when it asks; Ctrl-C cancels the run in progress rather than the session. Its shell tool runs
on your own machine (`TOOLS_SHELL_TYPE=local` by default), which is the point of a laptop tool and
worth knowing before you let it run something.

## Build from source

```sh
make          # ./gradlew build
make test     # needs a running Docker daemon: the tests start MongoDB and Redis via Testcontainers
make lint     # spotlessApply
```

Java bytecode targets 21, built with a GraalVM 25 toolchain because `native-image` ships with it.
There is no CI that builds or tests — the workflows only publish, so run `make` before pushing. See
[docs/contributing.md](docs/contributing.md) for the rest.

## License

[Apache 2.0](LICENSE).
