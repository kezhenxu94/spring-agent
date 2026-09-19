// Memories: the second of Customize's tabs, and what the agent has concluded rather than been told.
//
// The strongest case of the three for existing at all. A skill somebody wrote is a skill they can
// rewrite, and a knowledge document is something they filed on purpose — but a memory is what the
// agent decided about them, and the only other way to correct one is to ask the thing that wrote it
// to unwrite it, which is the one request most likely to go round in a circle.
//
// Two stores, `own` and `tenant`, the same pair the skills tab has and for the same reason: this
// surface carries no group. Who may write the company's is app.ai.non-admin-tenant-writes, reported
// by /api/me and checked again by the server — see MemoryController on why that toggle and not
// MemoryScopes.writable, which additionally wants a group chat as a witness that a browser session
// can never have.
//
// Everything that decides what is on screen is in the address bar: the store, the search and the
// memory. So a memory is a link and the back button walks out of it.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { bus, state } from './state.js';
import { attempt } from './toast.js';
import { busyButton } from './busy.js';
import { customizeRoute, go } from './route.js';
import { openCustomizeDetail } from './customize-chrome.js';
// The card grid's silhouette, borrowed from the skills tab rather than written twice: it is
// three empty cards at the real heights, and the two lists are the same grid of the same cards.
// A sibling import and not a cycle — skills.js knows nothing about this tab.
import { renderSkillSkeleton } from './skills-list.js';
import { renderMemoryList } from './memories-list.js';
import { renderMemoryDetail, renderMemoryPending } from './memories-detail.js';
import { deleteMemory, fetchMemories, fetchMemory, saveMemory } from './memories-actions.js';

/** The stores this surface has. There is no group one: a web session carries no group. */
const SCOPES = ['own', 'tenant'];

/** What a new memory is called before anybody has typed a name, and the suffix one must end in. */
const SUFFIX = '.md';

/**
 * Which answer is still wanted.
 *
 * Every fetch here is started by a route change, and somebody clicking down a list faster than the
 * network answers will have two in flight. Without this the slower one wins by arriving last, and
 * the pane shows a memory nobody is looking at any more — the counter customize's other tabs keep,
 * for the same reason.
 */
let wanted = 0;

export function initMemories() {
  state.memories = {
    scope: 'own',
    query: '',
    path: null,
    memories: [],
    file: null,
    // Which path `file` is the contents of. What is on screen has to be droppable the moment the
    // route names another one; see load().
    filePath: null,
    draft: null,
    loading: false,
    // Whether the memory's *text* is still coming, which is not the same as the list being in
    // flight — see drawDetail, where conflating the two flashes "no such memory" over a memory
    // that is simply still arriving.
    loadingFile: false,
    loaded: false,
  };

  const search = $('memories-search');
  $('memories-search-toggle').addEventListener('click', () => {
    if (searchOpen()) closeSearch();
    else openSearch();
  });
  // On Enter and on clearing, never on every keystroke: the search is in the address bar, and a
  // history entry per letter typed is a back button that has to be pressed nine times to undo one
  // search. The same rule the other two lists follow.
  search.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      narrow({ q: search.value.trim() });
    }
    if (event.key === 'Escape') closeSearch();
  });
  search.addEventListener('search', () => {
    if (!search.value.trim()) narrow({ q: '' });
  });
  drawSearch();

  $('memories-new').addEventListener('click', () => openNewForm());

  // A write anywhere — this page, or a run that remembered something while the page was open —
  // means the list is stale. Over the bus rather than by calling back, because the thing that
  // wrote it sits in an earlier layer than this and must not import it.
  bus.on('memories:changed', () => {
    const memories = state.memories;
    if (!memories || !memories.loaded) return;
    attempt(() => load(memories.scope, memories.path, true));
  });
}

/** What the route means here: which store, which memory, and what the list is filtered to. */
export function showMemories(route) {
  const memories = state.memories;
  const scope = scopeOf(route.scope);
  const path = route.id || null;
  const query = (route.narrowing && route.narrowing.q) || '';

  memories.query = query;
  $('memories-search').value = query;
  // A link that carries a search arrives with the box already open, or the list is narrowed by
  // something with nothing on screen to say so.
  if (query) $('memories-search-box').dataset.open = 'true';
  drawSearch();

  const moved = memories.scope !== scope || memories.path !== path;
  memories.scope = scope;
  memories.path = path;

  if (moved || !memories.loaded) {
    attempt(() => load(scope, path, false));
    return;
  }
  draw();
}

/**
 * Fetches whatever this route needs and nothing it does not.
 *
 * `again` is a reload of the same place, which is what a write asks for: it must not throw away the
 * list while it is being read, so the skeleton is only drawn when arriving somewhere new.
 */
async function load(scope, path, again) {
  const memories = state.memories;
  const mine = (wanted += 1);
  memories.loading = true;
  memories.loadingFile = Boolean(path);
  // The pane holds one memory's contents and `filePath` says which, so a route naming a different
  // one has to drop it here: left in place it is the memory somebody just navigated away from,
  // drawn under the new one's path. Keyed on the path rather than on `again`, so a reload of what
  // is already open — what a save asks for — does not blink it away and back.
  if (memories.filePath !== path) {
    memories.file = null;
    memories.filePath = null;
  }
  draw();

  const list = await fetchMemories(scope);
  if (mine !== wanted) return;
  memories.memories = list.memories || [];
  // Drawn before the file is asked for, so the list is there to go back to while the memory it was
  // opened with is still coming — and so the pane it is coming into says as much.
  memories.loading = false;
  draw();

  if (path) {
    memories.file = await fetchMemory(scope, path).then((body) => body.file).catch(() => null);
    if (mine !== wanted) return;
    memories.filePath = memories.file ? path : null;
  } else {
    memories.file = null;
    memories.filePath = null;
  }

  memories.loadingFile = false;
  memories.loaded = true;
  draw();
}

// ─────────────────────────────────────── drawing ───────────────────────────────────────

function draw() {
  const memories = state.memories;
  // Open as soon as a memory is named, not once its text has arrived: the panel is the memory from
  // the moment it is asked for, and waiting would show the list for as long as the request takes
  // and then replace it.
  const open = Boolean(memories.path);

  // The bar is where the open memory is named below md, and the only way back to the list from it.
  // Its own title where the list has arrived and knows one, and the path until then — which is what
  // the card itself falls back to, so nothing changes name when the list lands.
  const entry = memories.memories.find((each) => each.path === memories.path);
  openCustomizeDetail(open, open && {
    title: (entry && entry.name) || memories.path,
    label: t('memories.back'),
    onBack: () => narrow({ path: null }),
  });
  $('memories-browse').hidden = open;
  $('memory-detail').hidden = !open;

  drawScopes();
  if (open) {
    drawDetail();
    return;
  }
  drawList();
}

function drawList() {
  const memories = state.memories;
  const mayWrite = writable(memories.scope);
  $('memories-new').hidden = !mayWrite;
  if (memories.loading && !memories.loaded) {
    renderSkillSkeleton($('memories-list'));
    $('memories-note').textContent = '';
    return;
  }

  const shown = matching(memories.memories, memories.query);
  renderMemoryList($('memories-list'), shown, (path) => open(path), mayWrite ? (memory) => [
    {
      label: t('memories.delete'),
      danger: true,
      // Built per press, so a memory deleted from under the menu is not still offered by it.
      onSelect: () => deleteMemory(memories.scope, memory.path),
    },
  ] : null);

  const note = $('memories-note');
  if (shown.length) {
    note.textContent = '';
    return;
  }
  // Three different kinds of nothing, and telling them apart is the whole value of saying anything:
  // nothing matched, nobody has agreed anything company-wide, or the agent has not learnt anything
  // about you yet.
  if (memories.loading) note.textContent = '';
  else if (memories.query) note.textContent = t('memories.none.search');
  else if (memories.scope === 'tenant') note.textContent = t('memories.none.company');
  else note.textContent = t('memories.none');
}

function drawDetail() {
  const memories = state.memories;
  const entry = memories.memories.find((each) => each.path === memories.path) || null;

  // The text is not here yet. Keyed on loadingFile and not on `loading`, which goes false as soon
  // as the *list* lands — a moment before the file is asked for. Reading that as "the fetch is
  // over and there is nothing" drew "no such memory" over a memory that was still on its way, and
  // then replaced it with the memory: a flash of a wrong answer on every open.
  if (!memories.file) {
    renderMemoryPending($('memory-detail'), {
      scopeWord: t(`memories.scope.${memories.scope}`),
      path: memories.path,
      // Asked for, and did not come. Only once nothing is still in flight for it.
      failed: !memories.loadingFile,
      onBack: () => narrow({ path: null }),
    });
    return;
  }

  renderMemoryDetail($('memory-detail'), {
    scope: memories.scope,
    scopeWord: t(`memories.scope.${memories.scope}`),
    writable: writable(memories.scope),
    path: memories.path,
    file: memories.file,
    entry,
    draft: memories.draft,
    onBack: () => narrow({ path: null }),
    onGone: () => narrow({ path: null }),
    redraw: draw,
    refresh: () => load(memories.scope, memories.path, true),
    startDraft: (text) => { memories.draft = { text }; },
    updateDraft: (text) => { memories.draft = { text }; },
    clearDraft: () => { memories.draft = null; },
  });
}

function drawScopes() {
  const host = $('memories-scopes');
  if (!host) return;
  const memories = state.memories || { scope: 'own' };
  host.textContent = '';
  // Company only where the sign-in carries one. Drawn anyway, it would be a control whose only
  // possible answer is the 400 the endpoint gives a session with no tenant.
  const offered = state.me && state.me.memories && state.me.memories.tenant ? SCOPES : ['own'];
  if (offered.length < 2) {
    host.hidden = true;
    return;
  }
  host.hidden = false;
  offered.forEach((scope) => {
    const pill = document.createElement('button');
    pill.type = 'button';
    pill.className = 'scope-pill';
    pill.setAttribute('role', 'tab');
    pill.setAttribute('aria-selected', String(scope === memories.scope));
    pill.textContent = t(`memories.scope.${scope}`);
    pill.addEventListener('click', () => narrow({ scope }));
    host.append(pill);
  });
}

// ─────────────────────────────────────── the lens ───────────────────────────────────────

function searchOpen() {
  return $('memories-search-box').dataset.open === 'true';
}

function openSearch() {
  $('memories-search-box').dataset.open = 'true';
  drawSearch();
  // Two frames, for the reason skills.js gives at length: the field is `visibility: hidden` until
  // the attribute above takes effect, and an element that is not visible cannot take focus.
  requestAnimationFrame(() => requestAnimationFrame(() => $('memories-search').focus()));
}

function closeSearch() {
  const committed = state.memories.query;
  $('memories-search-box').dataset.open = 'false';
  $('memories-search').value = '';
  drawSearch();
  if (committed) narrow({ q: '' });
}

function drawSearch() {
  const toggle = $('memories-search-toggle');
  const label = t(searchOpen() ? 'memories.search.close' : 'memories.search.open');
  toggle.setAttribute('aria-expanded', String(searchOpen()));
  toggle.title = label;
  $('memories-search-label').textContent = label;
}

/**
 * What a search leaves in the list.
 *
 * Here rather than on the server, as the skills list does it: a person has dozens of memories, not
 * thousands, and a round trip to filter five rows is a round trip nobody asked for. The path is
 * searched alongside the front matter, because a memory with none has nothing else to match on.
 */
function matching(memories, query) {
  const needle = (query || '').trim().toLowerCase();
  if (!needle) return memories;
  return memories.filter((memory) => {
    const haystack = `${memory.path} ${memory.name} ${memory.description} ${memory.type}`;
    return haystack.toLowerCase().includes(needle);
  });
}

// ─────────────────────────────────────── going places ───────────────────────────────────────

/**
 * Everything that changes what is on screen goes through here, and nothing here changes it.
 *
 * It writes a hash; app.js's dispatch reads it back. That is the one-way rule route.js is built on,
 * and it is what makes the back button work.
 */
function narrow(change) {
  const memories = state.memories;
  const scope = change.scope !== undefined ? change.scope : memories.scope;
  // Changing the store or the search leaves whatever memory was open: a path is unique inside one
  // store and not across them, so carrying it across would open a different file of the same name.
  const movedOut = change.scope !== undefined || change.q !== undefined;
  const path = change.path !== undefined ? change.path : (movedOut ? null : memories.path);
  const query = change.q !== undefined ? change.q : memories.query;
  go(customizeRoute('memories', scope, path, { q: query, file: '' }));
}

function open(path) {
  state.memories.draft = null;
  narrow({ path });
}

function writable(scope) {
  return scope !== 'tenant'
    || Boolean(state.me && state.me.memories && state.me.memories.tenantWritable);
}

function scopeOf(scope) {
  if (!SCOPES.includes(scope)) return 'own';
  // A link to the company's memories, followed by somebody whose sign-in carries no company. Sent
  // to their own rather than left on a page whose every request answers 400.
  if (scope === 'tenant' && !(state.me && state.me.memories && state.me.memories.tenant)) {
    return 'own';
  }
  return scope;
}

// ─────────────────────────────────────── making one ───────────────────────────────────────

/**
 * The form for a new memory, put where the list is rather than in a dialog over it.
 *
 * One field: the file name. There is no description box, unlike the skills form, because a memory's
 * description lives in its front matter and the front matter is what the editor opens on — asking
 * for it twice would mean deciding which of the two answers wins.
 *
 * The file is written with the front matter already in it, so that what opens is the shape a memory
 * has rather than an empty box. A memory with no front matter is one the agent cannot judge the
 * relevance of, which is most of what a memory is for.
 */
function openNewForm() {
  if ($('memories-new-form')) return;
  const memories = state.memories;

  const form = document.createElement('form');
  form.id = 'memories-new-form';
  form.className = 'space-y-2 rounded-xl border border-zinc-200 p-3 dark:border-rail';

  const title = document.createElement('p');
  title.className = 'detail-label';
  title.textContent = t('memories.new.title');

  const hint = document.createElement('p');
  hint.className = 'detail-hint';
  hint.textContent = t('memories.new.body');

  const name = document.createElement('input');
  name.type = 'text';
  name.required = true;
  name.className = 'field field-mono w-full';
  name.placeholder = t('memories.new.name');

  const buttons = document.createElement('div');
  buttons.className = 'flex gap-2';
  const create = document.createElement('button');
  create.type = 'submit';
  create.className = 'panel-action panel-action-primary';
  create.textContent = t('memories.new.create');
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'panel-action';
  cancel.textContent = t('memories.cancel');
  cancel.addEventListener('click', () => form.remove());
  buttons.append(create, cancel);

  form.append(title, hint, name, buttons);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const typed = name.value.trim();
    if (!typed) return;
    // The suffix is added rather than demanded. Every memory is markdown and the agent writes them
    // all with it, so a name typed without one is a name, not a mistake worth a refusal.
    const path = typed.endsWith(SUFFIX) ? typed : typed + SUFFIX;
    const done = busyButton(create, t('memories.new.create'));
    attempt(() => saveMemory(memories.scope, path, template(path))
      .then(() => {
        form.remove();
        // Straight into it: somebody who has just named a memory wants to write it, and the
        // alternative is a list they now have to find their own new row in.
        open(path);
      })
      .finally(done));
  });

  $('memories-list').before(form);
  name.focus();
}

/**
 * What a new memory starts as: the front matter the memory prompt describes, with the name filled
 * in and the rest left for whoever is writing it.
 *
 * Written here rather than by the server for the same reason the skills page writes its SKILL.md
 * there and not here — each is written by whichever side knows the shape. This shape is the one the
 * editor immediately shows, so a mismatch would be visible in the first second.
 */
function template(path) {
  const name = path.replace(/\.md$/i, '').split('/').pop();
  return `---\nname: ${name}\ndescription: \ntype: user\n---\n\n`;
}
