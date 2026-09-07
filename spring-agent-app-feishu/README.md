# spring-agent-app-feishu

> **Audience:** whoever deploys this. It is the runtime with a Feishu/Lark bot as its surface, and it
> carries every optional module in the repository. Every property and environment variable is
> documented in place, with the reason for its default, in
> [`src/main/resources/application.yaml`](src/main/resources/application.yaml) — that file, not this
> page, is the configuration reference.

```sh
docker run --env-file .env -p 8080:8080 ghcr.io/kezhenxu94/spring-agent:latest
```

Or from a clone: `./gradlew :spring-agent-app-feishu:bootRun`.

## What it carries

[core](../spring-agent-core/README.md) · [feishu](../spring-agent-integration-feishu/README.md) ·
[events](../spring-agent-events/README.md) with the
[github](../spring-agent-integration-github/README.md),
[gitlab](../spring-agent-integration-gitlab/README.md),
[grafana](../spring-agent-integration-grafana/README.md) and
[email](../spring-agent-integration-email/README.md) sources ·
[jpa](../spring-agent-persistence-jpa/README.md) /
[mongodb](../spring-agent-persistence-mongodb/README.md) /
[redis](../spring-agent-persistence-redis/README.md) ·
[kubernetes](../spring-agent-tools-shell-kubernetes/README.md) /
[docker](../spring-agent-tools-shell-docker/README.md) shell ·
[rag-milvus](../spring-agent-rag-milvus/README.md) ·
[provider-openai](../spring-agent-provider-openai/README.md) /
[provider-dashscope](../spring-agent-provider-dashscope/README.md).

Carrying a module is not turning it on. What each one needs is on its own page.

## The variables with no defaults

The application will not start without a model to talk to, and there are two ways to give it one.

**Either** the OpenAI-compatible set — `OPENAI_BASE_URL`, `OPENAI_API_KEY`, `OPENAI_MODEL`,
`EMBEDDING_BASE_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_MODEL` — which any gateway or self-hosted
server takes.

**Or** the DashScope set: `DASHSCOPE_API_KEY`, `DASHSCOPE_CHAT_MODEL` and
`DASHSCOPE_EMBEDDING_MODEL`. One credential covers every DashScope endpoint, and
[spring-agent-provider-dashscope](../spring-agent-provider-dashscope/README.md) expands it into the
`spring.ai.openai.*` connection, since DashScope's chat and embedding endpoints are that same
protocol. The two model names still have to be given: no endpoint has a default model, and an empty
one is refused by all of them. `DASHSCOPE_BASE_URL` is optional and is a **host with no path** —
`https://dashscope-intl.aliyuncs.com`, or your workspace's
`https://ws-<id>.<region>.maas.aliyuncs.com` — with the paths appended for you.

An explicit `OPENAI_*` wins over the DashScope one, so moving a single model at a time works.

The application refuses to start when a connection or a model name is missing, naming the variable to
set, rather than starting and failing on the first run. The embedding model is needed even if you
index nothing, since tool search is built by embedding tool descriptions.

Two tools are off until a model is named for them, and are absent rather than broken when they are
not — a tool the model can see is a tool it will try:

- **`GenerateImage`** needs `IMAGE_MODEL_PROVIDER` set to `openai` or `dashscope`. It defaults to
  `none`, because an image API is a paid third party this deployment would start calling on the
  model's say-so, and the two providers' image APIs genuinely differ.
- **`RecognizeImage`** needs a vision model: `DASHSCOPE_VISION_MODEL` (a `qwen3-vl-*` one, on its own
  host if Model Studio gave it one), or `OPENAI_VISION_MODEL` on an OpenAI-compatible endpoint —
  which may be the same model `OPENAI_MODEL` names, if that one is multimodal. It is not assumed,
  because whether the model behind a gateway can see images is something only you know.

Then the Feishu block: `FEISHU_APP_ID`, `FEISHU_APP_SECRET`, `FEISHU_ENCRYPT_KEY`, `FEISHU_TENANT_ID`,
`FEISHU_TENANT_DOMAIN`, `FEISHU_BOT_OPEN_ID`. These also back the login on published-file pages. Set
`APP_FEISHU_ENABLED=false` to leave the whole integration out — but the shipped `application.yaml`
still names the app id and secret for that login, so override the `spring.security.oauth2` block as
well if you run without Feishu at all.

`.env` is read by `docker compose` and by `docker run --env-file`, but **not** by `./gradlew bootRun`,
which passes on the environment it was started with and nothing more:

```sh
set -a; . ./.env; set +a
./gradlew :spring-agent-app-feishu:bootRun
```

## In the Feishu console

- Add `http://localhost:8080/login/oauth2/code/feishu` (and the same URL under your real host) as a
  redirect URI, and grant the app the profile scopes that return `open_id` and `tenant_key`.
- Under the event subscription, add **用户和机器人的会话首次被创建** (`p2p_chat_create`) and **用户进入与机器人的会话**
  (`im.chat.access_event.bot_p2p_chat_entered_v1`). Only the second is required — the first is
  delivered over webhooks only, so a deployment on the long connection is greeted by the second either
  way.

Opening a chat with the bot for the first time is answered with a welcome card. After that, opening
the chat says nothing — unless the agent has learned something the person has not been told about, in
which case they get one card listing exactly what changed since their last visit. That "since" is
[`feishu/updates/`](../spring-agent-integration-feishu/src/main/resources/feishu/updates), one markdown
file per version; the greeting itself is
[`feishu/welcome.md`](../spring-agent-integration-feishu/src/main/resources/feishu/welcome.md). Point
`FEISHU_WELCOME` and `FEISHU_UPDATES` at files of your own to replace them — see `app.feishu` in
`application.yaml` for the rules, including what a gap in the numbering does.

## The two switches that decide what the deployment is

| Property (env var) | Values | Default |
| --- | --- | --- |
| `app.persistence.type` (`PERSISTENCE_TYPE`) | `jpa` (SQLite, no server needed), `mongodb`, `redis` | `jpa` |
| `app.ai.tools.shell.type` (`TOOLS_SHELL_TYPE`) | `none`, `kubernetes`, `docker`, `local` | `none` |

The shell defaults to `none` because it runs commands the model wrote. Turn it on deliberately, and
prefer `kubernetes` or `docker`, which give each user a disposable sandbox, over `local`, which does
not.

Both are evaluated during AOT, so in a **native image they are build-time decisions** baked by
`-PnativeBackends`; the environment variable is inert at runtime and must agree with what was baked.

`docker-compose.yaml` has a profile per value, so the containers and the application's own choice
cannot drift apart:

```sh
PERSISTENCE_TYPE=redis VECTORSTORE_TYPE=milvus \
  COMPOSE_PROFILES=$PERSISTENCE_TYPE,$VECTORSTORE_TYPE docker compose up   # backends only
```

Add `app` to `COMPOSE_PROFILES` to run everything in containers. The knowledge base has a profile of
its own, `rag`, and a switch that goes with it — the profile starts Milvus, the variable makes the
application use it:

```sh
RAG_ENABLED=true COMPOSE_PROFILES=rag docker compose up
```

## Before a real deployment

- **`ADMINS`** lists the people this deployment trusts with everybody else's work, by Feishu open id.
  An admin's agent reads and posts in chats they are not a member of; they can answer a question the
  agent put to somebody else and speak into a run already going for somebody else; and they get the
  admin-only tools — today the ones that write triage playbooks (`ListPlaybooks`, `WritePlaybook`) and
  the ones that read back a knowledge base nobody logs in as (`ListOwnerKnowledgeBase`,
  `SearchOwnerKnowledge`). A run keeps the identity it started with, so an admin causes things to
  happen *as* the person being helped. Grant it only to people you would trust with those files and
  credentials directly. **Never list an events source's `owner.user-id` among them** — the application
  refuses to start on that pairing.
- **`USER_MODELS_ENCRYPTION_KEY`** turns on bring-your-own-model. Off unless set, because the tokens
  people register are bearer credentials for somebody else's paid endpoint. `openssl rand -base64 32`;
  keep it out of the database and out of version control. Rotating it does not re-seal what is already
  stored: those rows stop being readable and say so.
- **`TOOLS_MAX_RESULT_CHARS`** (default 30000) is how long *any* tool's result may be — the shell, an
  MCP server, a webhook reader alike — before it is written to the user's workspace and the agent
  handed the path instead of the text. Nothing is lost. It is counted in characters rather than
  tokens, so the same number is far more text in English than in Chinese; lower it if your tools answer
  in Chinese and runs feel like they run out of room. `TOOLS_MAX_INLINED_INPUT_CHARS` (300000) bounds
  how much one `@file:` reference may carry back in.
- **`SPRING_AGENT_LOCALE`** chooses the language the agent's own text — and the tool descriptions the
  model reads — are written in. `en` and `zh_CN` ship.
- **`app.ai.system-prompt`** is where a persona and house rules go. It replaces a five-thousand-character
  default; read the note above it in `application.yaml` before overriding.
- **`/actuator/health` and `/actuator/prometheus`** are exposed for probes and metrics.

## More than one replica

Files written by a tool live under `app.storage.location`, so unless that is shared storage a
follow-up turn served by another replica cannot see them. A path noted in an earlier conversation may
since have been cleaned up, in which case the agent is told so and re-runs the tool.

Scheduled tasks are safe across replicas: the schedule is a column on the task rather than a timer in
one process, and each occurrence is won by exactly one replica before it fires. The one thing replicas
must agree on is `TZ`, since a cron expression is resolved against the JVM's default zone.

The chat connection is the part that needs care during a rolling update —
[advanced.md § Running more than one replica](../docs/advanced.md#running-more-than-one-replica-and-replacing-them)
covers what this application already does about it.
