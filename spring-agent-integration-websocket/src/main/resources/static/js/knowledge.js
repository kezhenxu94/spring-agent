// What the agent has been told to remember, listed where the conversations are listed.
//
// Everything here is the same operation the knowledge tools perform in a conversation, on the same
// documents — this section exists because checking a list or correcting one entry through the model
// means it has to pick the tool, guess the id and report back, and any of the three can go wrong
// without saying so.
//
// Whose knowledge base is never sent from here: the server reads it from the session. The one
// exception is an admin naming an owner, which is read-only and which the server checks again.

import { t } from './i18n.js';
import { $, submits } from './dom.js';
import { api } from './api.js';
import { attempt } from './toast.js';
import { busyButton, skeletonList } from './busy.js';
import { openMenu } from './menu.js';
import { knowledgeRoute, go } from './route.js';
import { isSelected, renderKnowledgeList } from './knowledge-list.js';
import { forgetDocumentText, renderKnowledgeDetail } from './knowledge-detail.js';
import { bus, state } from './state.js';

const PAGE = 30;

/** The scopes this surface can reach. There is no group one — a web run carries no group. */
const SCOPES = ['own', 'tenant'];

export function initKnowledge() {
  state.knowledge = {
    // `scope` is what the list is filtered to; `docScope` is which knowledge base the open
    // document is in, which is half of what names it — see KnowledgeBase#delete in core.
    // The three that narrow the list are a copy of what the route says, never the other way
    // round: they are written only by showKnowledge, off a hash that has already changed. `query`
    // is the committed search — the box's own value is a draft until Enter puts it in the address
    // bar.
    offset: 0, docId: null, docScope: null, owner: '', scope: '', query: '',
    entries: [], hasMore: false, searching: false,
  };

  $('knowledge-add-button').addEventListener('click', () => {
    // The route first, then the form: it lives inside #knowledge-add, which the detail renderer
    // only un-hides once no document is selected, so revealing it before navigating would put it
    // inside a hidden container.
    go(knowledgeRoute());
    $('knowledge-note-form').hidden = false;
    $('knowledge-note-title').focus();
  });
  $('knowledge-more').addEventListener('click', () => attempt(loadMore));

  initSearch();
  if (state.me?.knowledge?.admin) initAdmin();
  initView();
  bus.on('language:changed', () => {
    renderList();
    renderDetail();
    // What the lens says it will do next is written here rather than from a key in the markup,
    // because which of the two strings is right depends on whether the field is open — the same
    // reason drawFold writes the sidebar's fold button.
    drawSearch();
    // The suggestions carry a sentence of the page's own beside each id, so they are drawn again
    // too — a list built in JavaScript otherwise stays in the language the page started in.
    if (state.me?.knowledge?.admin) renderOwnerOptions();
    // The note bar is a sentence this page wrote, so it is the page's job to say it again in the
    // language that was just chosen.
    describe();
  });
  bus.on('knowledge:changed', () => attempt(refetch));
}

/**
 * The search, which is a switch rather than a box standing open.
 *
 * Closed it is one of the three icons over the list; open it is a field, and the list is a search
 * of itself. Closing therefore means *stop searching* and puts the whole list back — a field that
 * closed over a query somebody had typed would leave the list narrowed with nothing on screen
 * saying by what.
 *
 * Losing focus deliberately does not close it. The results *are* the list, so clicking one is the
 * ordinary next thing to do with a search, and a blur that closed would throw the search away at
 * exactly the moment it worked. Escape closes, which is what Escape means everywhere else here.
 */
function initSearch() {
  const search = $('knowledge-search');
  $('knowledge-search-toggle').addEventListener('click', () => {
    if (searchOpen()) closeSearch();
    else openSearch();
  });
  search.addEventListener('keydown', (event) => {
    if (submits(event)) {
      event.preventDefault();
      // The address bar, not the server: a search is a place this page can be, so it is navigated
      // to and the fetch happens on the way back through dispatch. That is what puts a search in
      // the history, in a reload and in a link somebody pastes to a colleague.
      narrow({ q: search.value.trim() });
    }
    if (event.key === 'Escape') closeSearch();
  });
  // A cleared box is the whole list again, which is what the little × in a search field is for.
  // The box stays open: clearing is not closing, and somebody who pressed it is still typing.
  search.addEventListener('search', () => {
    if (!search.value.trim()) narrow({ q: '' });
  });
  drawSearch();
}

// Named for the search rather than just `open`, because initAdmin below has an `open` of its own
// for the owner box and two things called the same in one file is one rename away from a bug.
function searchOpen() {
  return $('knowledge-search-box').dataset.open === 'true';
}

function openSearch() {
  $('knowledge-search-box').dataset.open = 'true';
  drawSearch();
  $('knowledge-search').focus();
}

function closeSearch() {
  const committed = state.knowledge.query;
  $('knowledge-search-box').dataset.open = 'false';
  $('knowledge-search').value = '';
  drawSearch();
  // Only where a search is actually on screen. Closing an untouched box is a change of nothing, and
  // a route written for it would be a history entry that goes back to where it already is.
  if (committed) narrow({ q: '' });
}

/** What the lens says it will do next, in the tooltip and to a screen reader. */
function drawSearch() {
  const toggle = $('knowledge-search-toggle');
  const label = t(searchOpen() ? 'knowledge.search.close' : 'knowledge.search');
  toggle.setAttribute('aria-expanded', String(searchOpen()));
  toggle.title = label;
  $('knowledge-search-label').textContent = label;
}

/**
 * Narrows the list, by going somewhere.
 *
 * Every control that changes what this list holds ends up here, and none of them touches
 * `state.knowledge` or fetches anything: they name what should be different, the hash changes, and
 * dispatch comes back through {@link showKnowledge} with the whole narrowing to apply. That is the
 * page's one-way rule kept for a list as well as for a panel — the alternative is the list and the
 * address bar each holding a version of what is on screen, and the back button reading the one
 * that is wrong.
 *
 * No document, deliberately: a list that has just been narrowed may not contain the document that
 * was open, and leaving it named would put a title over a panel the new list cannot select.
 */
function narrow(change) {
  const knowledge = state.knowledge;
  go(knowledgeRoute(null, null, {
    q: knowledge.query, owner: knowledge.owner, scope: knowledge.scope, ...change,
  }));
}

/**
 * Makes the controls say what the route says.
 *
 * Called on the way in, so a pasted link arrives with its search in the box, its funnel marked and
 * — for an admin — the owner row open on the identity being read. Without this a link would fetch
 * the right list and draw a header claiming it was the whole of one.
 */
function applyNarrowing() {
  const knowledge = state.knowledge;
  $('knowledge-search').value = knowledge.query;
  // Opened for a search that is on, never closed for one that is not: the box is also opened by
  // pressing the lens, and that press has no route of its own to be undone by the next one.
  if (knowledge.query) $('knowledge-search-box').dataset.open = 'true';
  drawSearch();

  if (state.me?.knowledge?.admin) {
    $('knowledge-owner').value = knowledge.owner;
    $('knowledge-owner-row').hidden = !knowledge.owner;
    $('knowledge-owner-clear').hidden = !knowledge.owner;
  }
  // Not the pane that writes: renderKnowledgeDetail owns that, and already hides it both for a
  // document being open and for somebody else's knowledge base being read. A second line saying
  // half of it here would un-hide it over an open document for as long as the fetch takes.
  mark();
}

/** Whether the section should be offered at all, and the scopes a person may file into. */
export function knowledgeAvailable() {
  return Boolean(state.me?.knowledge?.enabled);
}

export function scopesAvailable() {
  return SCOPES.filter((scope) => scope !== 'tenant' || state.me?.knowledge?.tenant);
}

/**
 * Shows the knowledge base, with the document `docId` in `scope` selected if one was named.
 *
 * Reached only from the route handler. The list is fetched the first time and then kept, so moving
 * between documents is not a page of requests — {@link reload} is what a write asks for.
 */
export function showKnowledge(docId, scope, narrowing = {}) {
  const knowledge = state.knowledge;
  knowledge.docId = docId || null;
  knowledge.docScope = docId ? scope || null : null;

  // Only when the route asks for a different list. Moving between documents is the common case and
  // carries the same narrowing every time, and re-fetching for it would redraw the list under the
  // cursor of somebody reading their way down it.
  const wanted = { q: narrowing.q || '', owner: narrowing.owner || '', scope: narrowing.scope || '' };
  if (wanted.q !== knowledge.query || wanted.owner !== knowledge.owner
      || wanted.scope !== knowledge.scope) {
    Object.assign(knowledge, { query: wanted.q, owner: wanted.owner, scope: wanted.scope });
    applyNarrowing();
    attempt(refetch);
    return;
  }

  renderList();
  renderDetail();
  title();
  if (!knowledge.entries.length && !knowledge.searching) attempt(refetch);
}

function title() {
  const entry = selectedEntry();
}

/**
 * Fetches the list again, as whatever it currently is.
 *
 * Everything that changes a document — a delete, a move, a write from a run — asks for this rather
 * than for a listing, because a narrowed list is still the list: reloading a search as a plain
 * listing leaves the box and the address bar saying a search is on over results that are the whole
 * knowledge base, and the only way back is to type the query again.
 */
function refetch() {
  return state.knowledge.query ? runSearch() : reload();
}

async function reload() {
  const knowledge = state.knowledge;
  knowledge.offset = 0;
  knowledge.searching = false;
  // Only the list in the sidebar, and only while it is empty: the panel beside it keeps the document
  // that is open, which after a write is usually the one that was just changed, and a reload is
  // mostly a write's own refresh rather than somebody waiting to see the list at all.
  const done = knowledge.entries.length ? () => {} : skeletonList($('knowledge-list'), 6);
  let page;
  try {
    page = await list(0);
  } finally {
    done();
  }
  knowledge.entries = page.entries;
  knowledge.hasMore = page.hasMore;
  // A reload follows a write, and a write can have changed the text of the document on screen
  // without changing its id — which is the only thing the panel would otherwise notice.
  forgetDocumentText();
  describe();
  renderList();
  renderDetail();
  title();
}

async function loadMore() {
  const knowledge = state.knowledge;
  // On the button that was pressed, because that is the only part of the page that is waiting: the
  // rows already fetched stay where they are and the next ones arrive under them.
  const done = busyButton($('knowledge-more'), t('busy.loading'));
  let page;
  try {
    page = await list(knowledge.offset + PAGE);
  } finally {
    done();
  }
  // Moved only once the page is in hand, or a failed request would leave the offset past rows
  // nobody ever fetched and skip them on the next press.
  knowledge.offset += PAGE;
  knowledge.entries = knowledge.entries.concat(page.entries);
  knowledge.hasMore = page.hasMore;
  renderList();
}

function list(offset) {
  const params = new URLSearchParams({ offset: String(offset), limit: String(PAGE) });
  if (state.knowledge.owner) params.set('owner', state.knowledge.owner);
  if (state.knowledge.scope) params.set('scope', state.knowledge.scope);
  return api(`/api/knowledge?${params}`);
}

/**
 * Search replaces what the list shows, so there is one list to read rather than two.
 *
 * Reads the committed query off the state rather than the box, because by here the query is the
 * one the route named — which on a pasted link or a back button is a search nobody has typed into
 * this box at all.
 */
async function runSearch() {
  const knowledge = state.knowledge;
  const query = knowledge.query;
  if (!query) return reload();
  const params = new URLSearchParams({ q: query });
  if (knowledge.owner) params.set('owner', knowledge.owner);
  // Narrowed the same way the listing is, or a filter would silently stop applying the moment
  // somebody typed into the box above it.
  if (knowledge.scope) params.set('scope', knowledge.scope);
  const done = skeletonList($('knowledge-list'), 4);
  let result;
  try {
    result = await api(`/api/knowledge/search?${params}`);
  } finally {
    done();
  }
  knowledge.searching = true;
  knowledge.entries = result.hits;
  knowledge.hasMore = false;
  // The same reason a reload does it: a search is also how the list comes back after a write, and
  // a write can change a document's text without changing its id — which is the only thing the
  // panel would otherwise notice.
  forgetDocumentText();
  describe();
  renderList();
  // The panel as well as the list, the same as a reload draws. A search is arrived at by narrowing,
  // which names no document, so leaving the panel alone would keep the last one open under a list
  // that can no longer select it — and on a pasted link that names both, this is what opens the
  // document the link asked for.
  renderDetail();
  title();
  return undefined;
}

/**
 * What this list is, in one sentence.
 *
 * Three things can narrow it and any of them can hold at once, so they are said in one place in
 * priority order rather than as three badges over the list. Whose knowledge base it is comes first:
 * it is the only one of the three that changes what the rows *mean*, and it is the one worth
 * seeing while reading somebody else's documents.
 */
function describe() {
  const knowledge = state.knowledge;
  const bar = $('knowledge-note-bar');
  let text = '';
  if (knowledge.owner) text = t('knowledge.owner.reading', knowledge.owner);
  else if (knowledge.searching) text = t('knowledge.results', knowledge.entries.length);
  else if (knowledge.scope) text = t('knowledge.filter.showing', t(`knowledge.scope.${knowledge.scope}`));
  bar.textContent = text;
  bar.hidden = !text;
}

/** The open document, matched on its knowledge base as well as its id. */
function selectedEntry() {
  const knowledge = state.knowledge;
  const options = { selected: knowledge.docId, selectedScope: knowledge.docScope };
  return knowledge.entries.find((it) => isSelected(it, options));
}

function renderList() {
  const knowledge = state.knowledge;
  renderKnowledgeList(knowledge.entries, {
    selected: knowledge.docId,
    selectedScope: knowledge.docScope,
    searching: knowledge.searching,
    // Somebody else's knowledge base cannot be written into from here — no document is filed into
    // it and none is moved between its scopes — but it can be deleted from, which is the only way
    // a document stored under an identity nobody logs in as ever comes out again. The server draws
    // that line as well; see documentActions.
    readOnly: Boolean(knowledge.owner),
    owner: knowledge.owner,
    tenant: Boolean(state.me?.knowledge?.tenant),
    refresh: () => attempt(refetch),
  });
  $('knowledge-more').hidden = !knowledge.hasMore;
}

function renderDetail() {
  const knowledge = state.knowledge;
  const entry = selectedEntry();
  renderKnowledgeDetail(entry, {
    readOnly: Boolean(knowledge.owner),
    tenant: Boolean(state.me?.knowledge?.tenant),
    // Whose knowledge base is being read, for the one request that fetches a document's text. Read
    // endpoints only, and the server checks it again.
    owner: knowledge.owner,
    refresh: () => attempt(refetch),
  });
}

/**
 * The menu of what this list shows: which scope, and — for an admin — whose.
 *
 * Drawn only where it would hold something. Without a company knowledge base there is nothing to
 * narrow to, since "everything" and "only you" would be the same list, and without admin there is
 * nobody else to read; a trigger opening a menu of one row says the feature is broken rather than
 * that it is not this person's to use.
 *
 * The rows are built on each press, so they follow the current state rather than a redraw somebody
 * has to remember.
 */
function initView() {
  const trigger = $('knowledge-view');
  const tenant = Boolean(state.me?.knowledge?.tenant);
  const admin = Boolean(state.me?.knowledge?.admin);
  if (!tenant && !admin) return;
  trigger.hidden = false;

  const choose = (scope) => {
    if (scope === state.knowledge.scope) return;
    narrow({ scope });
  };

  const items = () => {
    const knowledge = state.knowledge;
    // Not while reading somebody else's: the scope this surface may narrow to is a tenant, that
    // scope carries none, and offering the choice would be offering a list that cannot exist.
    const scopes = tenant && !knowledge.owner
      ? [
        { label: t('knowledge.filter.all'), checked: !knowledge.scope, onSelect: () => choose('') },
        ...scopesAvailable().map((scope) => ({
          label: t(`knowledge.scope.${scope}`),
          checked: knowledge.scope === scope,
          onSelect: () => choose(scope),
        })),
      ]
      : [];
    return [
      ...scopes,
      admin && !knowledge.owner && {
        label: t('knowledge.owner'),
        onSelect: () => {
          $('knowledge-owner-row').hidden = false;
          $('knowledge-owner').focus();
        },
      },
      admin && knowledge.owner && { label: t('knowledge.owner.mine'), onSelect: leaveOwner },
    ];
  };

  trigger.addEventListener('click', (event) => {
    event.stopPropagation();
    openMenu(trigger, items());
  });
  mark();
}

/** Whether the list on screen is narrowed, on the trigger, so it need not be opened to be read. */
function mark() {
  const knowledge = state.knowledge;
  $('knowledge-view').dataset.on = String(Boolean(knowledge.scope || knowledge.owner));
}

function initAdmin() {
  const input = $('knowledge-owner');
  renderOwnerOptions();

  const open = () => {
    const owner = input.value.trim();
    if (!owner) return;
    // The scope goes with it. This surface narrows to a tenant and somebody else's knowledge base
    // carries none, so the two together name a list that cannot exist — which is the same reason
    // the menu stops offering the choice while an owner is being read.
    narrow({ owner, scope: '' });
  };

  $('knowledge-owner-open').addEventListener('click', open);
  input.addEventListener('keydown', (event) => {
    if (submits(event)) {
      event.preventDefault();
      open();
    }
  });
  $('knowledge-owner-clear').addEventListener('click', leaveOwner);
}

/**
 * The ids worth suggesting in that box: the identities this deployment runs unattended work as,
 * reported by `/api/me`.
 *
 * They are suggestions and never the whole of what may be typed — most of what an admin reads is
 * an ordinary person's knowledge base, and no server-side list can enumerate those — which is why
 * this is a datalist behind a plain input rather than a picker. What each identity is for is said
 * beside it, because an id like `agent-triage` says nothing on its own about which source's events
 * it has been remembering.
 */
function renderOwnerOptions() {
  const list = $('knowledge-owner-options');
  const owners = state.me?.knowledge?.owners || [];
  list.replaceChildren(
    ...owners.map((owner) => {
      const option = document.createElement('option');
      option.value = owner.userId;
      const sources = (owner.sources || []).join(', ');
      if (sources) option.label = t('knowledge.owner.triage', sources);
      return option;
    }),
  );
}

/** Back to your own, from either the × on the row or the menu entry. */
function leaveOwner() {
  narrow({ owner: '' });
}
