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
import { $ } from './dom.js';
import { api } from './api.js';
import { attempt } from './toast.js';
import { busyButton, skeletonList } from './busy.js';
import { knowledgeRoute, go } from './route.js';
import { renderKnowledgeList } from './knowledge-list.js';
import { forgetDocumentText, renderKnowledgeDetail } from './knowledge-detail.js';
import { headline } from './panels.js';
import { bus, state } from './state.js';

const PAGE = 30;

/** The scopes this surface can reach. There is no group one — a web run carries no group. */
const SCOPES = ['own', 'tenant'];

export function initKnowledge() {
  state.knowledge = {
    offset: 0, docId: null, owner: '', entries: [], hasMore: false, searching: false,
  };

  $('new-knowledge').addEventListener('click', () => {
    go(knowledgeRoute());
    $('knowledge-note-form').hidden = false;
    $('knowledge-note-title').focus();
  });
  $('knowledge-more').addEventListener('click', () => attempt(loadMore));

  const search = $('knowledge-search');
  search.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      attempt(() => runSearch(search.value));
    }
  });
  // A cleared box is the whole list again, which is what the little × in a search field is for.
  search.addEventListener('search', () => {
    if (!search.value.trim()) attempt(reload);
  });

  if (state.me?.knowledge?.admin) initAdmin();
  bus.on('language:changed', () => {
    renderList();
    renderDetail();
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
 * Shows the knowledge base, with `docId` selected if one was named.
 *
 * Reached only from the route handler. The list is fetched the first time and then kept, so moving
 * between documents is not a page of requests — {@link reload} is what a write asks for.
 */
export function showKnowledge(docId) {
  state.knowledge.docId = docId || null;
  renderList();
  renderDetail();
  title();
  if (!state.knowledge.entries.length && !state.knowledge.searching) attempt(reload);
}

function title() {
  const entry = state.knowledge.entries.find((it) => it.docId === state.knowledge.docId);
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
  note('');
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
  return api(`/api/knowledge?${params}`);
}

/** Search replaces what the list shows, so there is one list to read rather than two. */
async function runSearch(text) {
  const query = (text || '').trim();
  if (!query) return reload();
  const knowledge = state.knowledge;
  const params = new URLSearchParams({ q: query });
  if (knowledge.owner) params.set('owner', knowledge.owner);
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
  note(t('knowledge.results', result.hits.length));
  renderList();
  return undefined;
}

function note(text) {
  const bar = $('knowledge-note-bar');
  bar.textContent = text;
  bar.hidden = !text;
}

function renderList() {
  const knowledge = state.knowledge;
  renderKnowledgeList(knowledge.entries, {
    selected: knowledge.docId,
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
  const entry = knowledge.entries.find((it) => it.docId === knowledge.docId);
  renderKnowledgeDetail(entry, {
    readOnly: Boolean(knowledge.owner),
    tenant: Boolean(state.me?.knowledge?.tenant),
    // Whose knowledge base is being read, for the one request that fetches a document's text. Read
    // endpoints only, and the server checks it again.
    owner: knowledge.owner,
    refresh: () => attempt(reload),
  });
}

function initAdmin() {
  const box = $('knowledge-admin');
  const input = $('knowledge-owner');
  const clear = $('knowledge-owner-clear');
  box.hidden = false;

  const open = () => {
    const owner = input.value.trim();
    if (!owner) return;
    state.knowledge.owner = owner;
    clear.hidden = false;
    $('knowledge-add').hidden = true;
    go(knowledgeRoute());
    attempt(async () => {
      await reload();
      note(t('knowledge.owner.reading', owner));
    });
  };

  $('knowledge-owner-open').addEventListener('click', open);
  input.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      open();
    }
  });
  clear.addEventListener('click', () => {
    state.knowledge.owner = '';
    input.value = '';
    clear.hidden = true;
    $('knowledge-add').hidden = false;
    go(knowledgeRoute());
    attempt(reload);
  });
}
