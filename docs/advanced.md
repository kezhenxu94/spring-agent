# Advanced features

Things a deployment can do that most do not need. Each is off by default, each costs something to
turn on, and none of them is on the path to using the agent normally — which is why they are here
rather than in [the README](../README.md).

## Handing a conversation between Feishu and the browser

Two surfaces, one person. If the same people sign in to the web UI with the Feishu account they
already message the bot from, a conversation belongs to *them* rather than to whichever of the two
they happened to start it in. This is what makes that true: a conversation begun in a Feishu group
can be picked up in the browser, watched there while it runs, and answered there with the answer
going back into the Feishu thread for the group that is still watching.

It needs one application, `spring-agent-app-web-feishu`, because both directions need both surfaces
in the same process: a run's detail is held in memory, so a browser can only watch a Feishu run live
if that run is in the same JVM, and putting a browser's answer back on the chat needs a Feishu
client in the process that produced the answer.

```sh
./gradlew :spring-agent-app-web-feishu:bootRun
```

Everything [the web UI](../spring-agent-app-webui/README.md) takes, plus everything
[the Feishu server](../spring-agent-app-feishu/README.md) takes — with one Feishu app serving both, which is
what `FEISHU_APP_ID` and `FEISHU_APP_SECRET` already give you.

### It refuses to start if the two halves are not the same Feishu app

A Feishu `open_id` is scoped to the app that issued it. Point the OAuth login at one app and the bot
at another and the same person has two different ids: no Feishu conversation appears in their
sidebar, no mirrored answer finds a chat, and **nothing anywhere says why** — it looks exactly like
somebody who has never messaged the bot. So it is checked at startup and refuses, naming both
values. The shipped configuration reads `FEISHU_APP_ID` for both, so this only fires for a
configuration that deliberately separated them.

For the same reason there is no `slack-login` profile here: signing in with Slack while the bot runs
on Feishu leaves nothing connected. A deployment wanting Slack login runs `spring-agent-app-webui`,
which takes no chat surface and works with either provider.

### Continuing a Feishu conversation in the browser

Nothing to turn on. A conversation started in Feishu is in the sidebar with its history, and picking
it up continues the same conversation — the agent's memory of it, the group it belongs to, and the
knowledge that group can see all come with it.

What it does *not* do by default is show you a Feishu run while it is still going. Turn that on and
opening the conversation mid-run streams it into the page like any other: the answer as it arrives,
what the model is thinking, every tool call.

| Variable | Default | What it is |
| --- | --- | --- |
| `WEB_FOLLOW_CHAT_RUNS` | `false` | Whether a run happening in Feishu is watchable in the browser as it happens, rather than only readable once finished. Off by default because it holds every Feishu run in memory for `WEB_JOURNAL_RETENTION` whether or not anybody looks |

Watching a run does not take it over. Feishu still draws its own card, the browser is only ever a
reader, and a question the run asks goes up on *both* — so the answer can come back from whichever
one you are looking at.

### Sending an answer back to the chat

The composer has a Feishu button beside the paperclip. Switched on, the answer to your next message
is also posted into the chat the conversation belongs to — the group it started in, or your own chat
with the bot — as a card **replied onto the message the conversation started from**, so it lands in
the thread the group is already following rather than loose at the bottom of the chat. It opens by
quoting what you asked:

> You sent this from [Spring Agent](https://agent.example.com/#/chat/…):
> how do I roll back the canary?

The bot is the author of that card. It is not posting as you — doing that would need a user access
token with message-sending scope, which signing in does not give — and the quote is what makes the
attribution honest: a group seeing an answer to a question it never saw asked would otherwise
reasonably conclude the agent had started talking to itself.

| Variable | Default | What it is |
| --- | --- | --- |
| `WEB_BASE_URL` | none | The address people reach the page on, e.g. `https://agent.example.com`. Used only for the link in that quote. Unset, the card says where the message came from without linking — a guessed hostname is a link that goes nowhere |

The setting is remembered per conversation, in your browser, so turning it on for a group
conversation does not carry it into a private one. The button only appears where there is a chat to
send to, so the web-only application never shows it.

Four things worth knowing before you rely on it:

- **The card is plain markdown.** Feishu's own extras — a mention, a coloured tag, a rendered
  timestamp — are not in it. One run produces one answer, and that answer is written for the page.
- **It is sent once, when the run finishes.** No streaming into Feishu, and no stop button on that
  side; stopping is done from the page.
- **A failure there never touches your answer here.** If the card cannot be sent at all — the bot
  removed from the group, the card too large — the answer in the page is unaffected and the reason
  is in the log.
- **Turning it on mid-run applies to the next answer.** A run already going was assembled before you
  pressed the button; the page says so rather than appearing to have done nothing.

Across tenants it refuses outright. A signed-in person's tenant is pinned by the login gate, so this
only arises where the bot serves more than one enterprise — and posting one enterprise's answer into
another's chat is a leak rather than a mis-delivery.

## Running more than one replica, and replacing them

Two replicas of a chat server share one thing that is not in any store: the connection the chat
platform delivers on. Feishu and Slack both hand an event to *one* of an app's connections, so
whether a message is answered during a rolling update is a question about which replica is holding a
connection at that moment — and nothing else about scaling out is unusual.

Two things make that work, and neither needs configuring:

- **A replica lets go of its connection first thing on shutdown**, before it waits for the runs
  already going ([`app.shutdown.in-flight-wait-timeout`](../spring-agent-app-feishu/src/main/resources/application.yaml),
  30 minutes by default). So the messages that arrive next go to a replica that can answer them,
  while the one on its way out finishes what it had already started and never reconnects. Pair that
  timeout with the deployment's own grace period — `terminationGracePeriodSeconds` is 30 seconds
  unless a manifest says otherwise, and past it the process is killed mid-answer whatever this says.
- **A replica with no connection says so on its readiness probe.** `/actuator/health/readiness`
  turns `OUT_OF_SERVICE` while the Feishu long connection is gone and back to `UP` once it returns,
  which is the difference between a deployment noticing and a replica sitting there healthy and
  deaf. It is worth knowing why: a Feishu handshake refused for good — the app's connection limit
  reached, which is what two replicas overlapping risks — is not an error the SDK reports or
  retries, so `FeishuLongConnection` watches the connection itself and reopens it. Slack's own
  client already does this, so nothing here duplicates it.

Readiness is one signal for the whole process, so on `spring-agent-app-web-feishu` — the application
carrying a chat *and* the browser — a lost Feishu connection takes the page out of rotation as well.
That is the right default for a replica that is half deaf, but it is a choice: a deployment that
would rather keep serving the page turns `management.health.readinessState.enabled` off and watches
the log line instead.

What is *not* shared is the run journal a browser reads: it lives in the heap of whichever replica
is running the turn, so a page can only follow a run in its own process. That is a constraint on
handing a conversation between a chat and the browser rather than on replicas as such — see above.

## Letting the agent search the web

Off unless a deployment sets `app.ai.tools.web-search.brave.api-key` (`BRAVE_API_KEY`). With a key
there is one more tool, `WebSearch`, backed by [Brave Search](https://brave.com/search/api/) — a
query in, a list of titles, URLs and snippets out. Without one there is no such tool, rather than a
tool that fails every call: the key *is* the switch, which is why it is gated with
`ConditionalOnNonBlankProperty` and why there is no `enabled` flag beside it.

What it costs:

- **Money, per search.** `result-count` (`BRAVE_RESULT_COUNT`, 10 by default, 20 is Brave's ceiling)
  decides how much one search buys.
- **Context.** Every result is read into the window whether the model uses it or not, and a turn may
  search several times.
- **Untrusted text in the middle of a run.** What comes back is pages written by strangers. The
  tool's description
  ([`core/prompts/tools/WebSearch.md`](../spring-agent-core/src/main/resources/core/prompts/tools/WebSearch.md))
  tells the model to read them as evidence about the world and never as instructions addressed to
  it, which is a mitigation and not a guarantee. Weigh it against what else that run holds — a
  deployment whose agent also has a shell, credentials and MCP servers is handing an injected page a
  larger blast radius than one answering questions. This is the same reasoning as
  [`docs/events.md`](events.md)'s on payload text, and the answer is the same: identity is the
  boundary, so do not pair it with an `app.ai.admins` identity that has nothing to gain from it.

What it cannot do: domain filtering. `allowedDomains` and `blockedDomains` are applied here, after
Brave has answered and been billed, so they narrow what the model reads and not what a search costs.
A `site:` operator written into the query itself is narrowed by Brave and is the cheaper way to say
the same thing; the tool's description says so, so the model usually reaches for it.
