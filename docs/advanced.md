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

Everything [the web UI](../README.md#run-the-web-ui) takes, plus everything
[the Feishu server](../README.md#run-the-server) takes — with one Feishu app serving both, which is
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
