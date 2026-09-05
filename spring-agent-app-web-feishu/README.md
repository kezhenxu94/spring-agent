# spring-agent-app-web-feishu

> **Audience:** whoever deploys this. It is
> [`spring-agent-app-webui`](../spring-agent-app-webui/README.md) and the Feishu bot in one process, so
> a conversation can be handed between them. The feature itself is described in
> [advanced.md](../docs/advanced.md#handing-a-conversation-between-feishu-and-the-browser); this page
> is how to run it. The configuration reference is
> [`src/main/resources/application.yaml`](src/main/resources/application.yaml), derived from the web
> UI's in turn.

```sh
./gradlew :spring-agent-app-web-feishu:bootRun
```

Everything [the web UI](../spring-agent-app-webui/README.md) takes, plus everything
[the Feishu server](../spring-agent-app-feishu/README.md) takes — with **one Feishu app serving both**,
which is what `FEISHU_APP_ID` and `FEISHU_APP_SECRET` already give you.

## Why it is an application rather than a configuration

A handoff cannot be done from two processes. A `RunJournal` is held in memory, so a browser can only
watch a chat run live if that run is in the same JVM; and putting a browser's answer back on the chat
needs a Feishu client in the process that produced it.

This is the only application here carrying two surfaces, and `chatType` is the whole of what makes it
safe — the websocket listener claims a run only when it is `web`, the Feishu listener only when it is
not. `OneChatSurfacePlusWebTest` asserts the three singletons and that the two listeners' claims are
disjoint. Anything added here has to say which runs it answers for.

## It refuses to start if the two halves are not the same Feishu app

A Feishu `open_id` is scoped to the app that issued it. Point the OAuth login at one app and the bot
at another and the same person has two different ids: no Feishu conversation appears in their sidebar,
no mirrored answer finds a chat, and **nothing anywhere says why** — it looks exactly like somebody who
has never messaged the bot. So it is checked at startup and refused, naming both values. The shipped
configuration reads `FEISHU_APP_ID` for both, so this only fires for a configuration that deliberately
separated them.

For the same reason there is **no `slack-login` profile here**: signing in with Slack while the bot
runs on Feishu leaves nothing connected. `FeishuIdentityCheck` refuses to start on any other provider.
A deployment wanting Slack login runs `spring-agent-app-webui`, which takes no chat surface and works
with either.

## Its own two switches

| Variable | Default | What it is |
| --- | --- | --- |
| `WEB_FOLLOW_CHAT_RUNS` | `false` | Whether a run happening in Feishu is watchable in the browser as it happens, rather than only readable once finished. Off by default because it holds every Feishu run in memory for `WEB_JOURNAL_RETENTION` whether or not anybody looks |
| `WEB_BASE_URL` | none | The address people reach the page on, e.g. `https://agent.example.com`. Used only for the link in the quote at the top of a mirrored card. Unset, the card says where the message came from without linking — a guessed hostname is a link that goes nowhere |

## Readiness

Readiness is one signal for the whole process, so a lost Feishu connection takes the page out of
rotation as well. That is the right default for a replica that is half deaf, but it is a choice: a
deployment that would rather keep serving the page turns `management.health.readinessState.enabled`
off and watches the log line instead. See
[advanced.md § Running more than one replica](../docs/advanced.md#running-more-than-one-replica-and-replacing-them).
