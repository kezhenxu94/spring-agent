// MCP servers: the third of Customize's tabs, and the tools the agent can reach beyond the ones
// this deployment ships.
//
// The strongest case for a page rather than a conversation, of everything in this section.
// Registering a server means typing a URL and a bearer token, and dictating a credential to a model
// that will echo it into a transcript is not a reasonable way to configure anything.
//
// The three groups are this tab's stores, drawn as the same pill switch over the same grid the two
// tabs beside it use. They are not homes — a server is a row owned by whoever registered it, and the
// way it reaches anybody else is a share — but for the reader they are the same gesture as a scope:
// a pair of pills deciding whose rows are in the list. Spelling them the same way is what keeps the
// three tabs one section rather than three pages that happen to share a strip.
//
// Everything that decides what is on screen is in the address bar: which group, the server being
// read, and `new` for the empty form. So a server is a link, so is a group, and the back button
// walks out of either.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { bus, state } from './state.js';
import { attempt } from './toast.js';
import { customizeRoute, go } from './route.js';
import { openCustomizeDetail } from './customize-chrome.js';
import { renderSkillSkeleton } from './skills-list.js';
import { renderServerList } from './mcp-list.js';
import { renderServerDetail, renderServerPending } from './mcp-detail.js';
import { fetchServers, removeServer, setEnabled } from './mcp-actions.js';

/**
 * The route that means "the empty form".
 *
 * A reserved name rather than a separate flag, so that registering a server is a place with a link
 * like every other place in this section — and so the back button walks out of a half-filled form
 * the way it walks out of anything else. A real server called `new` would collide; the name is
 * reserved rather than escaped because a server is named by whoever registers it, and the one they
 * cannot use is worth less than a second spelling of every route here.
 */
const NEW = 'new';

/** The groups, in the order the pills draw them. The first is the one a bare route lands on. */
const SCOPES = ['mine', 'shared', 'configured'];

/** Which answer is still wanted — see the same counter in the other two tabs. */
let wanted = 0;

export function initMcp() {
  state.mcp = {
    scope: 'mine',
    name: null,
    owned: [],
    shared: [],
    configured: [],
    loading: false,
    loaded: false,
  };

  // Registering always lands in your own group, because that is the only one it can land in.
  $('mcp-new').addEventListener('click', () => go(customizeRoute('mcp', 'mine', NEW)));

  // A write anywhere — this page, or a run that registered a server while the page was open —
  // means the list is stale.
  bus.on('mcp:changed', () => {
    const mcp = state.mcp;
    if (!mcp || !mcp.loaded) return;
    attempt(() => load(true));
  });
}

/** What the route means here: which group is listed, and which server is open. */
export function showMcp(route) {
  const mcp = state.mcp;
  const scope = SCOPES.includes(route.scope) ? route.scope : 'mine';
  const name = route.id || null;

  const moved = mcp.scope !== scope || mcp.name !== name;
  mcp.scope = scope;
  // The form is per server, so moving between two of them must not carry what was typed into one
  // into the next. Nothing is kept in the DOM across a redraw here — see mcp-detail.js — so this
  // is only about the fields being rebuilt, which draw() does.
  mcp.name = name;

  if (moved || !mcp.loaded) {
    attempt(() => load(false));
    return;
  }
  draw();
}

/**
 * Fetches the whole list, which is the only fetch this tab has.
 *
 * One request rather than a list and then a server: the three groups come together and a server's
 * own page is drawn entirely from the row already in hand. There is nothing more of a server to
 * fetch — the headers are the one thing the server would not hand over anyway.
 */
async function load(again) {
  const mcp = state.mcp;
  const mine = (wanted += 1);
  mcp.loading = true;
  if (!again) draw();

  const body = await fetchServers();
  if (mine !== wanted) return;
  mcp.owned = body.owned || [];
  mcp.shared = body.shared || [];
  mcp.configured = body.configured || [];
  mcp.loading = false;
  mcp.loaded = true;
  draw();
}

// ─────────────────────────────────────── drawing ───────────────────────────────────────

function draw() {
  const mcp = state.mcp;
  const open = Boolean(mcp.name);

  openCustomizeDetail(open);
  $('mcp-browse').hidden = open;
  $('mcp-detail').hidden = !open;

  drawScopes();
  if (open) {
    drawDetail();
    return;
  }
  drawList();
}

function drawList() {
  const mcp = state.mcp;
  if (mcp.loading && !mcp.loaded) {
    renderSkillSkeleton($('mcp-list'));
    $('mcp-note').textContent = '';
    return;
  }

  // Only your own rows open into anything, and only they carry a menu. A shared server has nothing
  // of itself to show — a share grants the tools, never the configuration — and a configured one
  // belongs to the deployment. Passing null for both is what says so, rather than drawing a
  // disabled control: disabled means "not now", and neither of those becomes openable later.
  const mine = mcp.scope === 'mine';
  renderServerList(
    $('mcp-list'),
    rows(mcp),
    mine ? (name) => go(customizeRoute('mcp', 'mine', name)) : null,
    mine ? (server) => [
      {
        label: t(server.enabled ? 'mcp.disable' : 'mcp.enable'),
        onSelect: () => attempt(() => setEnabled(server.name, !server.enabled)),
      },
      {
        label: t('mcp.delete'),
        danger: true,
        onSelect: () => removeServer(server.name),
      },
    ] : null,
  );

  // Three different kinds of nothing, and telling them apart is the whole value of saying anything:
  // you have registered none, nobody has shared one, or this deployment configures none.
  const note = $('mcp-note');
  if (rows(mcp).length || mcp.loading) note.textContent = '';
  else note.textContent = t(`mcp.none.${mcp.scope}`);
}

/** The rows of the group on screen. */
function rows(mcp) {
  if (mcp.scope === 'shared') return mcp.shared;
  if (mcp.scope === 'configured') return mcp.configured;
  return mcp.owned;
}

/**
 * The pills, always all three.
 *
 * Unlike the two tabs beside it, no group is ever hidden for being empty: those hide the company
 * scope because a sign-in carrying no company has no such store to look in, while every one of
 * these exists for everybody and being empty is a fact about it worth reading. A pill that came and
 * went as servers were shared would also move the one beside it under the cursor.
 */
function drawScopes() {
  const host = $('mcp-scopes');
  if (!host) return;
  const mcp = state.mcp || { scope: 'mine' };
  host.textContent = '';
  SCOPES.forEach((scope) => {
    const pill = document.createElement('button');
    pill.type = 'button';
    pill.className = 'scope-pill';
    pill.setAttribute('role', 'tab');
    pill.setAttribute('aria-selected', String(scope === mcp.scope));
    pill.textContent = t(`mcp.group.${scope}`);
    pill.addEventListener('click', () => go(customizeRoute('mcp', scope, null)));
    host.append(pill);
  });
}

function drawDetail() {
  const mcp = state.mcp;
  const server = mcp.name === NEW ? null : mcp.owned.find((each) => each.name === mcp.name);

  if (!server && mcp.name !== NEW) {
    // The list has not arrived yet — a pasted link, or a reload with a server open. Its own
    // outline rather than an empty card, which would read as a server with nothing in it.
    if (!mcp.loaded) {
      renderServerPending($('mcp-detail'), { name: mcp.name, onBack: back });
      return;
    }
    // The list is here and this name is not in it: a server somebody has since removed, or one
    // shared with them, which this tab has nothing of its own to show. Sent back to the list
    // rather than left on a page about nothing.
    back();
    return;
  }

  renderServerDetail($('mcp-detail'), {
    server,
    onBack: back,
    refresh: () => load(true),
    // Registering lands on the server that was just made, so somebody who has typed a URL sees the
    // row it became rather than a list they now have to find their own new server in. Replacing
    // rather than pushing would be wrong here: the empty form is a place worth going back to if
    // the save turned out to name the wrong thing.
    onSaved: (name) => go(customizeRoute('mcp', 'mine', name)),
  });
}

function back() {
  // Back to the group that was being read. Always `mine` in practice, since no other group opens
  // anything — but the route is what says where the reader was, and reading it is cheaper than
  // knowing that.
  go(customizeRoute('mcp', state.mcp.scope, null));
}
