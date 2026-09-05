# spring-agent

[![Build](https://github.com/kezhenxu94/spring-agent/actions/workflows/build.yaml/badge.svg)](https://github.com/kezhenxu94/spring-agent/actions/workflows/build.yaml)
[![Maven package](https://github.com/kezhenxu94/spring-agent/actions/workflows/maven.yaml/badge.svg)](https://github.com/kezhenxu94/spring-agent/actions/workflows/maven.yaml)
[![Docker](https://github.com/kezhenxu94/spring-agent/actions/workflows/docker.yaml/badge.svg)](https://github.com/kezhenxu94/spring-agent/actions/workflows/docker.yaml)
[![Shell runner](https://github.com/kezhenxu94/spring-agent/actions/workflows/shell-runner.yaml/badge.svg)](https://github.com/kezhenxu94/spring-agent/actions/workflows/shell-runner.yaml)
[![Maven Central](https://img.shields.io/maven-central/v/me.kezhenxu94/spring-agent-core?label=Maven%20Central)](https://central.sonatype.com/artifact/me.kezhenxu94/spring-agent-core)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-orange)](#)

A tool-using agent you can run as it is: one runtime behind a Feishu/Lark bot, a Slack bot, a
browser, or a command line for your own machine. Each gives the agent a shell sandbox, MCP servers,
skills, memories, credentials, scheduled tasks, subagents, a knowledge base and file publishing — and
lets it pick up new abilities in conversation, without a redeploy.

It is also a library. If you are building your own agent on Spring Boot 4 and Spring AI, read
[docs/sdk.md](docs/sdk.md); if you want to change this repository, read
[docs/contributing.md](docs/contributing.md). For how the pieces fit together — the surfaces, the
event sources, what a run is offered and where state lives — [docs/architecture.md](docs/architecture.md)
draws it. [docs/integrations.md](docs/integrations.md) indexes every module and its own README,
[docs/events.md](docs/events.md) covers the agent watching rather than waiting, and
[docs/advanced.md](docs/advanced.md) what a deployment can turn on that most do not need.

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
arrive at `/events/webhooks/<source>`, mail arrives in a watched mailbox, group chat messages it was
not addressed in arrive through the chat integration. Related events are correlated into one
*situation* and left to settle — a thousand alerts from one outage become one run, not a thousand —
and only then is the agent woken to decide whether it has anything worth saying. Silence is a normal
answer.

GitHub, GitLab, Grafana and a mailbox ship as sources. Each authenticates its own deliveries, and a
source nobody configured a secret for refuses everything, so the endpoint is safe to expose but
useless until somebody sets it up. What the agent should actually *do* about a source's events is not
a setting but a **playbook**: documents you write into the knowledge base and edit like any other,
without a deployment.

It is all off by default, and there is more to decide here than anywhere else in the agent — who a
source runs as, who it will listen to, and what any of that is worth against text a stranger wrote.
**[docs/events.md](docs/events.md) is the whole of it.**

## Run it

Five applications, one runtime. Pick the surface you want; each has its own page with the setup steps,
its variables and what it carries.

| | | |
| --- | --- | --- |
| [`spring-agent-app-feishu`](spring-agent-app-feishu/README.md) | A Feishu/Lark bot | The published image; carries every optional module |
| [`spring-agent-app-slack`](spring-agent-app-slack/README.md) | A Slack bot | The same server, Slack instead |
| [`spring-agent-app-webui`](spring-agent-app-webui/README.md) | A browser | The runtime with everything a run does made visible, and no chat platform |
| [`spring-agent-app-web-feishu`](spring-agent-app-web-feishu/README.md) | Both at once | So a conversation can be handed between a Feishu chat and the browser |
| [`spring-agent-app-cli`](spring-agent-app-cli/README.md) | Your own terminal | SQLite under `~/.spring-agent`, and a shell on your own machine |

The quickest of them:

```sh
docker run --env-file .env -p 8080:8080 ghcr.io/kezhenxu94/spring-agent:latest
```

Six variables have no defaults and nothing starts without them — `OPENAI_BASE_URL`, `OPENAI_API_KEY`,
`OPENAI_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL`. Any OpenAI-compatible
endpoint will do; the embedding model is needed even if you index nothing, since tool search is built
by embedding tool descriptions. Each application's page lists what else it needs.

Two switches decide what a deployment actually is, and they mean the same thing in every application:

| Property (env var) | Values | Default |
| --- | --- | --- |
| `app.persistence.type` (`PERSISTENCE_TYPE`) | `jpa` (SQLite, no server needed), `mongodb`, `redis` | `jpa` |
| `app.ai.tools.shell.type` (`TOOLS_SHELL_TYPE`) | `none`, `kubernetes`, `docker`, `local` | `none` |

The shell defaults to `none` because it runs commands the model wrote. Turn it on deliberately, and
prefer `kubernetes` or `docker`, which give each user a disposable sandbox, over `local`, which does
not. `docker-compose.yaml` has a compose profile per value of both switches, so the containers and the
application's own choice cannot drift apart:

```sh
PERSISTENCE_TYPE=redis VECTORSTORE_TYPE=milvus \
  COMPOSE_PROFILES=$PERSISTENCE_TYPE,$VECTORSTORE_TYPE docker compose up   # backends only
```

Add `app` to `COMPOSE_PROFILES` to run everything in containers. The default pair needs no server at
all.

## The modules

Every module in the repository has a README of its own saying what it is, what it needs and what to
know before changing it — [docs/integrations.md](docs/integrations.md) is the index, and the place
where what they all have in common is written down.

## Build from source

```sh
make          # ./gradlew build
make test     # needs a running Docker daemon: the tests start MongoDB and Redis via Testcontainers
make lint     # spotlessApply
```

Java bytecode targets 21, built with a GraalVM 25 toolchain because `native-image` ships with it.
CI builds and tests every push to `main` and every pull request; run `make` before pushing anyway. See
[docs/contributing.md](docs/contributing.md) for the rest.

## License

[Apache 2.0](LICENSE).
