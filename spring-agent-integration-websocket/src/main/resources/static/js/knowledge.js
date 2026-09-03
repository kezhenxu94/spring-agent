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
import { headline } from './panels.js';
import { bus, state } from './state.js';

const PAGE = 30;

/** The scopes this surface can reach. There is no group one — a web run carries no group. */
const SCOPES = ['own', 'tenant'];

export function initKnowledge() {
  state.knowledge = {
    // `scope` is what the list is filtered to; `docScope` is which knowledge base the open
    // document is in, which is half of what names it — see KnowledgeBase#delete in core.
    offset: 0, docId: null, docScope: null, owner: '', scope: '',
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

  const search = $('knowledge-search');
  search.addEventListener('keydown', (event) => {
    if (submits(event)) {
      event.preventDefault();
      attempt(() => runSearch(search.value));
    }
  });
  // A cleared box is the whole list again, which is what the little × in a search field is for.
  search.addEventListener('search', () => {
    if (!search.value.trim()) attempt(reload);
  });

  if (state.me?.knowledge?.admin) initAdmin();
  initView();
  bus.on('language:changed', () => {
    renderList();
    renderDetail();
    // The suggestions carry a sentence of the page's own beside each id, so they are drawn again
    // too — a list built in JavaScript otherwise stays in the language the page started in.
    if (state.me?.knowledge?.admin) renderOwnerOptions();
    // The note bar is a sentence this page wrote, so it is the page's job to say it again in the
    // language that was just chosen.
    describe();
  });
  bus.on('knowledge:changed', () => attempt(reload));
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
export function showKnowledge(docId, scope) {
  state.knowledge.docId = docId || null;
  state.knowledge.docScope = docId ? scope || null : null;
  renderList();
  renderDetail();
  title();
  if (!state.knowledge.entries.length && !state.knowledge.searching) attempt(reload);
}

function title() {
  const entry = selectedEntry();
  headline(entry ? entry.title || entry.docId : t('knowledge.title'));
}

async function reload() {
  const knowledge = state.knowledge;
  knowledge.offset = 0;
  knowledge.searching = false;
  $('knowledge-search').value = '';
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

/** Search replaces what the list shows, so there is one list to read rather than two. */
async function runSearch(text) {
  const query = (text || '').trim();
  if (!query) return reload();
  const knowledge = state.knowledge;
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
  describe();
  renderList();
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
    // Reading somebody else's is reading only, exactly as far as KnowledgeAdminTools goes. The
    // server refuses a write naming an owner too; this is only about not offering one.
    readOnly: Boolean(knowledge.owner),
    tenant: Boolean(state.me?.knowledge?.tenant),
    refresh: () => attempt(reload),
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
    refresh: () => attempt(reload),
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
    state.knowledge.scope = scope;
    mark();
    attempt(reload);
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
    state.knowledge.owner = owner;
    // Nothing about somebody else's knowledge base is writable, so the pane that writes goes. The
    // server refuses a write naming an owner as well; this is only about not offering one.
    $('knowledge-add').hidden = true;
    $('knowledge-owner-clear').hidden = false;
    mark();
    go(knowledgeRoute());
    attempt(reload);
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
  state.knowledge.owner = '';
  $('knowledge-owner').value = '';
  $('knowledge-owner-row').hidden = true;
  $('knowledge-owner-clear').hidden = true;
  $('knowledge-add').hidden = false;
  mark();
  go(knowledgeRoute());
  attempt(reload);
}
