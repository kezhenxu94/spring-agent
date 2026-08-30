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
[docs/contributing.md](docs/contributing.md).

Every property and environment variable is documented in place, with the reason for its default, in
[`spring-agent-app/src/main/resources/application.yaml`](spring-agent-app/src/main/resources/application.yaml).
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
| do this every Monday | `CreateScheduledTask` | a run that fires on its own |
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
  encrypted row, so a token reaches a shell as an environment variable and never a prompt.
- **The sandbox shell** is a Pod or container per user, with its own slice of the volume, torn down
  when idle and rebuilt on the next command.
- **The knowledge base** is scoped to a person, a group chat or the whole tenant, and a run only
  ever searches what its own identity may read.

A message from a group chat also reaches the group's home and the group's knowledge, which is how a
team shares skills and notes without sharing anything private. Nobody needs an administrator to set
any of this up — they ask the agent, and it registers it for them. On the command line the same
machinery serves the one person at the keyboard, out of `~/.spring-agent`.

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

What the agent should actually *do* about a source's events — what matters, what to check first, who
to tell and where — is not a setting. It is a **playbook**: documents you write into the knowledge
base and then edit like any other document, without a deployment. Each source names which of them
are its playbook and what to look them up with (`playbook.query` and `playbook.filter`), and a
triage run is given them before it decides anything. The base is always the one owned by that
source's `owner-user-id` — never a group or tenant an incoming event named — so what the agent is
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

Or from a clone: `./gradlew :spring-agent-app:bootRun`.

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

Everything else is optional and set in
[`application.yaml`](spring-agent-app/src/main/resources/application.yaml). Two switches decide what
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
  `owner-user-id` among them — the application refuses to start on that pairing, since a triage run
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
