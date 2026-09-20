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

**Scenario memos.** A message carrying `/kb`, `/knowledge-base` or `/knowledge_base` runs that turn
in `BuiltInScenarios.KNOWLEDGE_BASE` — the knowledge base, the memories and the vision tools, and
nothing else; `/mini`, `/one-off` or `/one_off` runs it in `ONE_OFF`, which is the model alone and
writes nothing to the conversation. `ChatController.send` has the text in hand, so it parses and
strips in one step, and the mirror and the queue both take the stripped text: what the chat surface shows is the words the
agent was given. `core/agent/ScenarioMemos` owns the rule — whole token, case-insensitive, anywhere
in the message, including at the end of it — and a slash word it does not know reaches the model
untouched.

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

That bound is why `ChatSessions.transcript` draws tool calls too, and not only what was said. A
journal is the live view and is gone after a restart or an eviction; chat memory is what survives,
so a reloaded conversation reads the calls back out of it. It is drawn **through `RunView` itself**
rather than by markup that resembles it, so the two cannot drift: `appendTools` in `transcript.js`
feeds that class the same event shapes the stream feeds it, and one tools row per *turn* is what
matches the one fold a run draws for itself. What a replay lacks is only the journal, so the outcome
is set at once: a replayed round is over before it is drawn.

Only a backend that stores tool calls has any to draw: `redis` and `mongodb` do, `jpa` does not, so
a conversation with no tools row is the ordinary case there rather than a fault.

**What a round thought is drawn the same way, and fetched only if somebody asks for it.** Core keeps
one row per round (see `ChatReasoning`), and `transcript` pairs a row to a round by the digest of the
answer it ended on — chat memory holds no run id and no backend can be made to hold one. The id of
the matching run travels on the **user** row that opened the round, because that is where a live run
draws its fold; a round that matches nothing carries null and no fold is drawn, which is the right
answer both to "this round produced none" and to "memory no longer holds the answer it was digested
from". Two rounds that answered identically match nothing, deliberately: showing one of them the
other's reasoning would be wrong and convincing.

The text itself never rides along with the transcript — a round's thinking is routinely longer than
the whole of the rest of the conversation, and most rounds are read without anybody wanting it.
`appendReasoning` in `transcript.js` builds the fold empty and asks
`GET /api/conversations/{id}/reasoning/{requestId}` the first time it is opened. That endpoint checks
two things and the second is not redundant: the conversation is the caller's, *and* the row belongs
to that conversation — without it, one's own conversation id plus somebody else's run id reads their
thinking.

## Reaching a store without a run in between

This is the one surface that does, and it does it four times — for the knowledge base, for skills,
for memories and for MCP servers. All four are things a user owns, and all four were previously
reachable only by asking the model to operate on them, which is a poor way to check a list or correct
one line: the model has to pick the tool, guess the id and report back, and any of the three can go
wrong quietly.

### The knowledge base

`KnowledgeController` puts core's `KnowledgeBase` SPI behind
`/api/knowledge` so a person can list, search, read, add to and correct what the agent remembers
rather than asking the model to do it with the knowledge tools. Reading one document's stored text is
what `KnowledgeBase.read` exists for — no vector store can be asked for a document's own content
without a query, which is why enumeration and reading both live on the SPI.

Rules that hold there and must keep holding:

- **The scope is derived from the session, never from the request**, exactly as `ChatController`
  derives a run's. The one exception is that an `app.ai.admins` member may name an `owner` on the
  *read* endpoints and on `DELETE`, mirroring what `KnowledgeAdminTools` and `PlaybookTools` allow
  between them: a source's playbooks are written into an identity nobody logs in as, so without a
  delete that names one they could be overwritten and never removed. No other write accepts an
  owner, and the one that does reaches only that identity's *own* knowledge base — naming an owner
  and `tenant` together is refused rather than deleting from the admin's company base.
- **Writing the company knowledge base is `app.ai.non-admin-tenant-writes`**, off by default, so it
  is an `app.ai.admins` member's to change and everybody else's to read and search. Core's
  `TenantWrites` again, the same rule the knowledge tools keep; `/api/me` reports
  `knowledge.tenantWritable` so the page draws neither the Company scope on the add form nor the
  share and delete actions on a company document.
- **Every endpoint answers 404 where no `KnowledgeBase` bean exists**, and `/api/me` reports that
  first so the page never offers the section.
- **A document id travels in the query string or the body, never in the path.** A document indexed
  from a file is identified by its absolute path, whose slashes are rejected encoded and are extra
  segments unencoded.

### Skills

`SkillController` puts core's `SkillFiles` behind `/api/skills`, which is what the **Customize**
section of the page reads and writes. A skill is a folder holding a `SKILL.md` plus whatever else it
needs, so unlike a knowledge document it is not one record — the page lists skills, opens one into a
file tree beside the file being read, and creates, edits, adds to and deletes.

What is different here from the knowledge base, and why:

- **No `owner` parameter on any endpoint.** The knowledge base has one because a source's playbooks
  live under an identity nobody logs in as. There is no such case for skills, and a skill is
  *instructions the agent will load and act on* — there is no view of somebody else's worth the door
  it would open.
- **Who may write company skills is `app.ai.non-admin-tenant-writes`**, and by default that is
  `app.ai.admins` and nobody else: a skill in the tenant's directory is instructions every
  colleague's agent loads and acts on. The same check `SkillManagementTools` makes, through core's
  `TenantWrites`, so asking the agent is not the way round the page — a stricter rule on one side
  alone would not protect the directory, only make the page the slow way round. Reading, opening and
  exporting a company skill are untouched.
- **Nothing here is optional**, so no endpoint answers 404 for absence the way the knowledge base's
  do. `/api/me` reports `skills.tenant`, which is what decides whether the page draws the Company
  scope at all, and `skills.tenantWritable`, which decides whether it draws that scope's write
  controls; the endpoints answer 403 regardless, and that is the check that counts.
- **A skill name and a file path travel in the query string**, never in the path — the same rule, for
  the same reason.
- **The list reports `shadowed`.** `HomeDir.dirs` answers nearest scope first and `SkillsTool` keeps
  the first of a duplicate name, so a company skill whose name the reader also has privately is
  installed and never loaded. Nothing else tells anybody that, and a skill that quietly does nothing
  is worse than none.

**`SkillFiles` is the one guard, and it is in core because both callers have to reach it** — the
model-facing `SkillManagementTools` and this controller. Two copies of a path check are one copy
fixed. Three things it does that are easy to leave out:

- it resolves the deepest existing part of a path to its **real** location and re-checks containment,
  because lexical normalizing is symlink-blind and the sandbox shell can create one inside a skills
  folder;
- it resolves both sides of that comparison, because comparing a real path against a *declared*
  directory refuses every write on any host whose storage root is itself a link — which on macOS it
  always is;
- it refuses to delete `SKILL.md` as a file, because without it the folder stops being a skill, so it
  would leave the list and become unreachable by every endpoint that names one.

The controller builds a **single-scope** `HomeDir` per request (`forOwner` or `forTenant`, never
`forRequest`): the guard asks the home it is given whether a path is inside it, and a composite home
spans both stores, so a `scope=own` request could resolve its way into the company's.

`GET /api/skills/export` sends a skill back as a zip and `POST /api/skills/import` takes one, and
the two are exact counterparts — what comes down goes back up unchanged, because both speak the
skill's own folder with no wrapper directory around it. Export streams and has **no size cap** where
import has one: the cap exists so a small archive cannot expand into a large amount of our disk, and
nothing about sending somebody their own files has that shape — capping it would mean a skill that
grew past the limit could never be got out again. Packing puts every file through the same path
guard, because `Files.walk` does not follow links but `Files.copy` does, and a download is the
easiest way to carry something off.

The import unpacks a whole skill at once. It reads and validates the
entire archive **before writing anything** — an archive that fails half way through would otherwise
leave a folder holding the entries that happened to come first. Entry names go through the same path
guard, which is what answers zip slip; entries are read to a budget rather than with `readAllBytes`,
which is what answers a zip bomb; a single top-level folder is stripped, because that is the shape a
downloaded repository has and unpacked as-is its `SKILL.md` is one level too deep to be a skill.

### Memories

`MemoryController` puts core's `MemoryStore` behind `/api/memories`, which is the **Customize**
section's *Memories* tab. It lists a scope's `memories/` root recursively with each file's front
matter read onto its row, and reads, writes and deletes one file at a time.

The case for it is the strongest of the four. A skill is something somebody wrote and a knowledge
document is something they filed; a memory is what the agent *concluded* about them, and the only
other way to correct one is to ask the thing that wrote it to unwrite it — the one request most
likely to go round in a circle.

`core/memory/MemoryStore` is where the work is, and it is in core for the reason `SkillFiles` is:
both callers need the same guard. `MemoryFiles.resolveSafe` is what stops a symlink planted in a
shared `memories/` turning a read of a group's memories into a read of one member's home, and a
guard that exists twice is one that will eventually only be half fixed. The tools call it with a
scope word a model wrote and answer in localized prose; this calls it with a scope and a path and
answers in JSON.

Four things differ from the skills page:

- **Scope is `own` or `tenant` and there is no `owner` parameter**, so not even an administrator
  reaches another person's memories here. That is the skills rule rather than the knowledge base's:
  reading somebody's notes is a moderation question, reading what the agent decided about them is
  not a question anybody should be able to ask.
- **Who may write company memories is `app.ai.non-admin-tenant-writes`**, asked through
  `TenantWrites` exactly as the skills page and the knowledge base ask it — and deliberately *not*
  through `MemoryScopes.writable`, which is the same question plus one more: that a shared write
  happen in a group chat, in front of the people it affects. A browser session has no group at all,
  so reusing that answer would leave the company scope permanently read-only here whatever a
  deployment configured, which is a control no setting could ever open. The witness rule is a rule
  about chats and stays in the chat tools; what this page grants is what it already grants over
  company *skills*, which are instructions the agent executes and so strictly the more dangerous.
- **Create and overwrite are one endpoint.** `MemoryCreate` refuses a path that exists, which is
  right for a model that cannot see the file and would otherwise replace something it never read,
  and wrong for a person who is looking at it in an editor.
- **Nothing here maintains `MEMORY.md`.** The index is prose, one line per memory, and what belongs
  on that line is a judgement — so it is a file on this page like any other. A page that appended a
  line of its own devising would be fighting the agent over the shape of a file they both write.

A memory opens in the **shared record card** — `detailHead`, `detailFacts`, `detailBody` — which is
the card a knowledge document and a scheduled task open in, with the actions in the head's menu and
the text as `.detail-text prose`. Not the skills tab's two panes: a skill is a folder and needs a
tree beside the file, while a memory is one file, and the pane's frame and inset only make sense
against a tree that is not there. The front matter is read into the facts and left out of the
rendered body, because otherwise it is on screen twice — and the second time as markdown, where
consecutive `key: value` lines run together into a sentence that is not in the file. Editing still
opens the whole file.

`MemoryEntry` is the wire shape and is handed straight to Jackson, which it can be only because it
carries no `Path`. `SkillSummary` carries the directory its skill is in and therefore needs a
controller to strip it on the way out; anything added to `MemoryEntry` is something every browser
that can open the page will see.

### MCP servers

`McpController` puts core's `McpServerRegistry` behind `/api/mcp`, which is the *MCP servers* tab.
Registering a server means typing a URL and a bearer token, and dictating a credential to a model
that will echo it into a transcript is not a reasonable way to configure anything.

`core/tools/mcp/McpServerRegistry` is the extraction that made this possible, and it is the same
split again: registering a server is URL validation, a tool-prefix collision check across everything
the caller can reach, a live probe of the endpoint and only then a save. `McpServerManagementTools`
is now a translator from an `McpRegistryException` reason to `CoreMessages`, and this controller from
the same reason to an HTTP status plus `WebMessages` — which is why the registry throws a reason
rather than a sentence: a URL this runtime will not take and a server that did not answer look alike
and are not, and a page reporting the second as a bad request has somebody editing a URL that was
right all along.

Three things about it are load-bearing:

- **Headers never come back.** A row's headers are exactly where the token lives. A response carries
  their *names* only, and a save that omits the field keeps what is stored while an empty object
  clears it. Without that distinction, opening a server to correct its URL and pressing save would
  quietly drop its credential, and the next run would get a 401 from a server that had been working.
  Getting it backwards would make `GET /api/mcp` the shortest path between a stolen session and
  every token the person has pasted in.
- **Three groups, and only one of them is the caller's.** What others have shared carries a name and
  an owner and nothing about the connection, which is what `ListMcpServers` already refuses to leak;
  what the application configures under `spring.ai.mcp.client.*` is read-only because nobody owns it.
  The wire shapes are records rather than maps precisely because *what is not on them* is the point:
  a record has no field that could carry a header, while one more `put` on a map is one line. On the
  page the three are the tab's **stores** — the same pill switch over the same grid skills and
  memories use — so each is a link, rather than three headings stacked down one column.
- **Saving connects.** Nothing is stored unless the endpoint answered and listed its tools, and the
  response carries those names — the only evidence anybody gets that the credential is right. A row
  that was never reached is a row whose tools a later run fails to assemble, and the failure lands on
  a conversation rather than on whoever typed the URL.

There is no scope here: a server is not a file under a home but a row owned by whoever registered it,
and the way it reaches anybody else is a share. The browser is given no chat id, so only servers
shared with the person directly or with everyone reach them — a server shared with a Feishu group
they are in is theirs to use *in that group*, and this session is not in it. `enabled` is the one
operation the chat tools cannot do at all: the field has always been stored and honoured and nothing
ever set it, which left removing a misbehaving server as the only way to quieten it — throwing away
a URL, a prefix the model has learnt and a credential somebody has to find again.

CSRF is on in the applications carrying this module, unlike the webhook servers': a POST here makes
the agent act with the logged-in person's credentials, files and MCP servers.

### One field vocabulary, and one frame

Three things about the record card were settled while the two tabs above were added, and each is a
rule for whatever comes next rather than a detail of those two.

**Every field is `css/fields.css`.** There used to be six vocabularies — `.detail-input` and
`.detail-edit` on the record cards, the knowledge note form spelt in utilities in the markup,
`.file-edit` in the skills pane, `.row-rename` in the rail, `.chat-title-input` in the header and
`.composer-box` around the composer — and four of them already carried the same four declarations,
each restated in full. There are two shapes now: `.field`, where the element draws its own frame,
and `.field-box`, where a wrapper draws it and a `.field-bare` child scrolls inside. The second is
what you need when the frame holds something besides the text — the composer's send button, the
skills pane's path. Sans by default; `.field-mono` where the content is data rather than prose.

The **search lens is deliberately neither**, and there is a note in `fields.css` saying so: it
frames itself with an `outline` rather than a border, because a border would make the closed box 2px
wider than the buttons beside it and shift the glyph, and its frame goes amber while it is being
typed into. Folding it in draws a second rectangle around it.

**An open detail card has no frame of its own**, which `detail.css` has always said and, until now,
only half did: the rule matched `.detail-body > .detail-card`, and Customize puts a tabpanel in
between, so the skills tab drew the frame the rule exists to remove and every tab added beside it
inherited that. It matches at any depth now, and the tabpanel passes the height through — which is
also what finally lets the skills tab's two panes fill the panel and scroll inside it.

## The page

Plain ES modules under `src/main/resources/static/js`, no bundler. Three rules there are load-bearing.

**Modules are layered.** The core first (`state`, `dom`, `i18n`, `render`, `api`, `toast`, `route`),
then `status`/`theme`/`sidebar`, then the features, then `app.js` as the only file importing across
all of them. A module imports only ones earlier than itself, and a backward edge goes over the `bus`
in `state.js`. A cycle does not fail loudly: it resolves a binding to `undefined` and throws on
whichever path nobody clicked.

**The column is a rail with a readout.** The sidebar's left 3.25rem is the instrument — the mark,
the three sections, the one action, what the agent is doing, who is signed in — and the rest of it is
whatever the selected section is listing. The fold in its header takes the readout away and leaves
the rail, so a folded sidebar is still navigable rather than gone, and the choice is remembered in
`localStorage`. Every glyph in the column sits in a box of one width on one axis, which is what makes
a fold look like a fold rather than a redraw; the geometry and the reasons are in `css/sidebar.css`,
and changing one of the three rows that state it means changing all three.

**Navigation is one-way.** `route.js` owns the hash (`#/chat/<id>`, `#/tasks/<id>`,
`#/kb/<scope>/<docId>?q=&owner=&scope=`, `#/customize/<tab>[/<scope>]/<id>?q=&file=`), a click calls `go`, and `app.js`'s `dispatch` decides what
is on screen. Nothing opens a thing and then writes the hash. That holds for a *list* as well as for
a panel: everything that narrows the knowledge base — the search, the scope, the identity an admin is
reading — is in the query string and nowhere else, so each of those lists is reachable by a link, a
reload and the back button. The controls hold no state of their own; `showKnowledge` writes them from
the route on the way in, and re-fetches only when the narrowing actually changed. Customize follows
the same rule down to the file: which tab, which store, which skill, which file and the search are
all in the hash, so a file inside a skill is a link, and so is one memory and one MCP server. The
store segment is **per tab** — `TAB_SCOPES` in `route.js` — because skills and memories are files
under a home while an MCP server is not, so its route carries no store and a server called `own` is
read as the name it is. Which folders of the tree are folded is the one thing that
is not, because a folded folder shows nothing different — it is a setting, like the sidebar's own
fold. `panels.js` is the only thing that hides and shows the main column's four panels, so
a section cannot forget to put the composer back.

Every section's content is read in one column of one width, `--page-width` in `base.css`, applied by
`.page-column`. The number is written once: two of them would have a person moving between sections
read the shift as the page jumping, with nothing on screen to explain it.

**A conversation is renamed from its own heading, or from its row in the sidebar.** It is called the first thing that was said in
it — derived on read, so it cannot go stale against a conversation that was cleared or trimmed —
and `PATCH /api/conversations/{id}` stores an override that wins. Emptying the field clears the
override rather than leaving a blank row, so "call it nothing" puts the derived name back. The
stored name is the one thing about a conversation `ChatSession` holds that is *not* derivable from
it, which is why the model carries it where it deliberately carries no preview and no message
count; `AbstractPersistenceBackendTest` covers the round trip on all three backends, because a
rename that worked on jpa and silently did nothing on redis would look like the page being slow.
Renaming does not `touch` the session: the list is ordered by when something last happened in the
conversation, and a rename is not a turn.

Both fields open in place — the heading's grows to fit what is typed into it, measured against a
mirror set in the same type because an `<input>` has no intrinsic width — and both keep their draft
outside the DOM. The row's is the one that has to: the conversation list is redrawn by things that
have nothing to do with a rename (a run finishing, the language changing), and **removing a focused
element fires `blur`**, so a blur handler that committed straight away would save an edit the list
merely re-rendered underneath. It commits on the next tick and only if the field is still connected;
`renamingRow` is what brings the half-typed name back into the redrawn row.

**The bar above that column is one title bar, and every section's.** It holds the same three things
in the same order — the way out of what is on screen, the name of what is on screen, and on a
conversation the rename behind that name — and it has exactly one writer at a time: `sectionBar` in
`panels.js` for the three sections that are not a conversation, `renderConversationTitle` for the
one that is, which refuses to write while the bar is marked `data-bare` because the conversation
list is redrawn by things that happen anywhere and each would otherwise put a conversation's name
above the knowledge base. A section's name goes in `panels.js`'s `SECTION` table rather than in the
section's own code, for the same reason.

Which of the three it draws is a fact about the **width**, so it is `chrome.css`'s and not the
markup's. At md and up the bar is the conversation's alone — `showPanel` marks the others
`data-bare` and the rule takes it away, because there each panel names itself in its own heading and
the drawer toggle is gone with the drawer. Below md it is drawn on all four and the panel's heading
is the one that goes (`.section-name`), since it would be the bar's own word again at twice the
size; the drawer toggle wears the same panel glyph the fold button does, because what is behind it
is not a menu but the column the fold hides and shows at a wider window; and it gives up its slot
to the way back whenever one is set, so inside a thing the bar offers the way out of it and the
drawer is one press further away.

**That way back is one control in two places.** `.detail-back` in `css/detail.css` is its shape,
`backButton` in `detail.js` builds the copy a record card carries, and `#header-back` is the bar's.
A section whose list is in the rail — the knowledge base, the schedule — passes no `back` at all,
because the list is one press away in the drawer; the three Customize tabs each pass one through
`openCustomizeDetail`, since opening a skill, a memory or a server takes the whole column and their
lists are *in* it. Below md only the bar's copy is drawn, and that is the point of it: the card's
scrolls away with the card.

**An opened record scrolls as one thing in Customize, and pins its head in the other two.**
`css/detail.css` gives a panel that has become the thing it shows a head that stays put and a body
that scrolls under it, which is right for a knowledge document and a scheduled task: one card,
read top to bottom, whose menu of actions has to stay reachable. All three Customize tabs undo that
chain — see `#customize-panel.detail-open` in `css/customize.css`. A skill is a name, a description,
a shadowing warning and *then* the two panes; a memory and an MCP server each carry a spec sheet
above their body. Pinning all of that gave the thing itself whatever was left of a phone screen, so
the panel scrolls the way every list on this page scrolls and what stays put is the title bar, which
is why that bar carries the open thing's name. A panel with an editor open in it scrolls that way
too, wherever it is: `detail.css` unpins the head of a panel it finds a `.field-area-fill` in,
because the two actions a writer needs are under the box rather than in the menu the pinning kept
reachable.

What is read or written at length then takes the height left under the facts about it, and is
floored at `--editor-lines` — twenty lines of its own type, since `.file-edit`'s 12.5px on a 1.7
line is not `.field`'s 13px on a 1.5. Below that floor the panel scrolls rather than squeezing the
box, which is what a phone gets and what a desktop window never sees. `.skill-panes` is the same
rule with `.skill-file-head` added to its floor, and it is the one link of the chain with a
flex-basis of zero: the others take their content's height, and a frame sized to a two-thousand-line
file is not a frame. Below md `.detail-facts` and `.detail-form` stack key over value — a 6.5rem
key column of a 20rem screen leaves a path wrapping to four lines beside a two-word label.

**Below md every navigation closes the drawer**, not only one that names an item. It used to stay
open when a section was pressed, on the argument that the list it switches to is what you opened
the drawer to read — true of the two sections whose list is in the rail, false of Customize, whose
list is the page. One rule rather than a rule per section: the drawer is modal, and a modal that
outlives the choice made in it is one you have to dismiss twice. The cost is that picking the
knowledge base or the schedule now closes the rail their list is in, so choosing a document is two
presses.

**The column moves on one timing, `--sidebar-motion`.** It has two motions — the fold animates a
width at md and up, the drawer a transform below it — and they are the same column going away and
coming back, so they share a duration and a curve. Both are declared on `#sidebar` in
`sidebar.css` rather than one in each file: they were two rules at equal specificity and this file
is imported after `chrome.css`, so `width` won and the drawer had no animation at all while the
rule meant to give it one looked correct where it was written. The backdrop fades on the same
timing, switched by a class rather than `hidden` — an element with `display: none` has nothing to
fade from.

Below md it takes the **whole width**. At that size it is not a column beside anything — it is a
screen you are on, and the sliver of the run left past a 270px panel was too little to read and not
something you could press, so all it did was make the drawer look as though it had stopped half way
open. The fold button is already hidden at that width (folding a drawer to a rail would leave the
run covered by 3.25rem of nothing), so the way out is the close button, the backdrop and Escape.

That close button stands **exactly where the button that opened it stands** — first in the column's
head, before the brand, on the same 0.75rem/0.55rem the header's toggle sits on, which is what the
mobile-only padding on `.side-head` is for. The header's toggle is directly behind the drawer once
it is open, so landing on the same spot makes the two read as one control that opens and closes.
It wears the same panel glyph with the arrow turned round; an × would have said "dismiss this
thing", and the column is not a dialog. The padding is mobile-only because at md the head's inset
is what puts the brand on the rail's centre line, which the fold depends on.

`styles.css` is a single linked entry that `@import`s `css/*`, and that order is load-bearing: rules
there tie on specificity with Tailwind's utilities and with each other, so a rule that must hold
regardless of file order buys specificity and says why (see `.drawer-only`).

Two rules there are worth knowing before writing a third. **A class that sets `display` or
`position` must also say `[hidden] { display: none }`**, because the only thing making the attribute
mean invisible is the browser's own stylesheet, which every author rule beats — the composer's two
actions share a corner, so the one hidden that way would print over the other rather than merely
stay on screen. And **an icon that rides at the *end* of a row wears `.row-icon`** (base.css), which
is one box, one glyph size and flush right: the copy button beside the user id and the chevron into
a submenu are read as one column, so neither picks a size of its own.

A markup file is a state too. `setRunning` is called when a run starts and when one ends and so says
nothing about a page that has not yet had one, which is why `#stop` carries `hidden` in
`index.html`; the same goes for the drawn stand-in under the avatar, which stands until an actual
picture has loaded rather than until `/api/me` has answered — a provider that carries no avatar, and
a src that 404s, are both ordinary.

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
