// Skills: the first of Customize's three tabs, and the folders the agent loads by name.
//
// The tab strip and which tab is on screen are skills.js's; everything below is the skills tab
// alone, and it reads the route rather than holding a copy of where it is.
//
// The list lives in this panel and not in the sidebar, unlike the knowledge base's. Two reasons
// and the second is the real one: three things narrow it and the rail has room for none of them,
// and a skill is not something a person flicks between while a conversation is open — which is
// what the rail's lists are for.
//
// Everything that decides what is on screen is in the address bar: the store, the search, the
// skill, the file. So a skill is a link, a file in a skill is a link, and the back button walks
// back out of a tree the way it walks out of anything else. The one exception is which folders are
// folded, and skills-tree.js says why.

import { t } from './i18n.js';
import { $, svgIcon } from './dom.js';
import { bus, state } from './state.js';
import { attempt } from './toast.js';
import { busyButton } from './busy.js';
import { customizeRoute, go, goReplacing } from './route.js';
import { openCustomizeDetail } from './customize-chrome.js';
import { toggleMenu } from './menu.js';
import { renderSkillList, renderSkillSkeleton } from './skills-list.js';
import { renderSkillDetail, renderSkillPending } from './skills-detail.js';
import {
  createSkill, deleteSkill, fetchFile, fetchSkills, fetchTree, importSkill,
} from './skills-actions.js';

/** An arrow into a tray, and the same plus the sidebar's one action wears. */
const UPLOAD = 'M8 10.2V3.1m0 0L5.6 5.5M8 3.1l2.4 2.4M3.2 10.4v1.7c0 .6.4 1 1 1h7.6c.6 0 1-.4 1-1v-1.7';
const PLUS = 'M8 3.6v8.8M3.6 8h8.8';

/** What makes a folder a skill, and so the file a reader means when they open one. */
const MANIFEST = 'SKILL.md';

/** The stores this surface has. There is no group one: a web session carries no group. */
const SCOPES = ['own', 'tenant'];

/**
 * Which answer is still wanted.
 *
 * Every fetch here is started by a route change, and a person clicking down a tree faster than the
 * network answers will have two in flight. Without this the slower one wins by arriving last and
 * the pane shows a file nobody is looking at any more — the same counter knowledge-detail.js keeps,
 * for the same reason.
 */
let wanted = 0;

export function initSkills() {
  state.skills = {
    scope: 'own',
    query: '',
    skill: null,
    file: '',
    skills: [],
    detail: null,
    body: null,
    // Which file `body` is the contents of. What is on screen has to be droppable the moment the
    // route names another one; see load().
    bodyPath: null,
    collapsed: new Set(),
    draft: null,
    loading: false,
  };

  drawScopes();

  const search = $('skills-search');
  $('skills-search-toggle').addEventListener('click', () => {
    if (searchOpen()) closeSearch();
    else openSearch();
  });
  // On Enter and on clearing, never on every keystroke: the search is in the address bar, and a
  // history entry per letter typed is a back button that has to be pressed nine times to undo one
  // search. The same rule the knowledge base's search follows.
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

  // Asked rather than assumed: writing a skill from nothing and bringing one that already exists
  // are different jobs, and neither is enough of a default to hide the other behind it.
  $('skills-new').addEventListener('click', () => toggleMenu($('skills-new'), () => [
    {
      label: t('skills.new.upload'),
      icon: () => svgIcon(UPLOAD),
      onSelect: () => openNewForm({ withArchive: true }),
    },
    {
      label: t('skills.new.blank'),
      icon: () => svgIcon(PLUS),
      onSelect: () => openNewForm({ withArchive: false }),
    },
  ]));

  // A write anywhere — this page, or a run that wrote a skill while the page was open — means the
  // list is stale. Over the bus rather than by calling back, because the thing that wrote it sits
  // in an earlier layer than this and must not import it.
  bus.on('skills:changed', () => {
    const skills = state.skills;
    if (!skills || !skills.loaded) return;
    attempt(() => load(skills.scope, skills.skill, skills.file, true));
  });
}

/** What the route means here: which store, which skill, which file, and what the list is filtered to. */
export function showSkills(route) {
  const skills = state.skills;
  const scope = scopeOf(route.scope);
  const skill = route.id || null;
  const file = (route.narrowing && route.narrowing.file) || '';
  const query = (route.narrowing && route.narrowing.q) || '';

  skills.query = query;
  $('skills-search').value = query;
  // A link that carries a search arrives with the box already open, or the list is narrowed by
  // something with nothing on screen to say so.
  if (query) $('skills-search-box').dataset.open = 'true';
  drawSearch();

  const moved = skills.scope !== scope
    || skills.skill !== skill
    || skills.file !== file;
  // Whether this is a skill being *opened*, as against one already open whose file changed. Only
  // an opening gets a file chosen for it — see load(). Taken before the assignment below, which
  // is the last moment the previous skill is still known.
  const opening = Boolean(skill) && skills.skill !== skill;
  skills.scope = scope;
  skills.skill = skill;
  skills.file = file;

  if (moved || !skills.loaded) {
    attempt(() => load(scope, skill, file, false, opening));
    return;
  }
  draw();
}

/**
 * Fetches whatever this route needs and nothing it does not.
 *
 * `again` is a reload of the same place, which is what a write asks for: it must not throw away
 * the list while it is being read, so the skeleton is only drawn when arriving somewhere new.
 */
async function load(scope, skill, file, again, opening) {
  const skills = state.skills;
  const mine = (wanted += 1);
  skills.loading = true;
  skills.loadingFile = Boolean(file);
  // The pane holds one file's contents and `bodyPath` says which, so a route naming a different
  // one has to drop it here: left in place it is the file somebody just navigated away from, drawn
  // under the new file's path and above its actions, and the waiting state below never appears
  // because there is something to show. Keyed on the path rather than on `again`, so that a
  // reload of the file already open — what a save asks for — does not blink it away and back.
  if (skills.bodyPath !== file) {
    skills.body = null;
    skills.bodyPath = null;
  }
  if (!again) {
    // Arriving somewhere new, the old skill's tree is not this one's. Cleared so the panes draw
    // their own silhouette rather than the last skill's files under this skill's name.
    if (skills.skill !== skill) {
      skills.detail = null;
      skills.body = null;
      skills.bodyPath = null;
    }
  }
  draw();

  const list = await fetchSkills(scope);
  if (mine !== wanted) return;
  skills.skills = list.skills || [];

  if (skill) {
    // A tree, then the file in it. In that order and not together: a file named by a stale link
    // may not be in this skill, and finding that out after the tree has been drawn means the pane
    // says so beside a list of what is actually there.
    skills.detail = await fetchTree(scope, skill).catch(() => null);
    if (mine !== wanted) return;

    // A skill opened with no file named starts on its SKILL.md, which is the file somebody came to
    // read — it is what the skill *is*, and the others are what it refers to. Only on an opening:
    // stepping back to the tree on a phone also leaves no file named, and re-choosing one there
    // would make that button do nothing.
    //
    // The address bar is corrected rather than left saying something else, because the file being
    // read is part of what this page's links mean. Replacing rather than pushing, or Back would
    // return to the route that redirects and never get past it.
    if (opening && !file && hasManifest(skills.detail)) {
      file = MANIFEST;
      skills.file = file;
      goReplacing(customizeRoute('skills', scope, skill, { q: skills.query, file }));
    }
    // Drawn before the file is asked for, so the tree is there to choose from while the file it
    // was opened with is still coming — and so the pane it is coming into says as much.
    skills.loading = false;
    draw();

    skills.body = skills.detail && file
      ? await fetchFile(scope, skill, file).catch(() => null)
      : null;
    if (mine !== wanted) return;
    skills.bodyPath = skills.body ? file : null;
  } else {
    skills.detail = null;
    skills.body = null;
    skills.bodyPath = null;
  }

  skills.loading = false;
  skills.loadingFile = false;
  skills.loaded = true;
  draw();
}

// ─────────────────────────────────────── drawing ───────────────────────────────────────

function draw() {
  const skills = state.skills;
  // Open as soon as a skill is named, not once its tree has arrived: the panel is the skill from
  // the moment it is asked for, and waiting for the tree would show the list for as long as the
  // request takes and then replace it.
  const open = Boolean(skills.skill);

  // The panel becomes the skill, so the intro, the tab strip and the list all go. The first two
  // belong to Customize as a whole and are openCustomizeDetail's; the list is this tab's own.
  //
  // The bar goes with them: below md it is where this skill is named and where the way back to the
  // list lives, because neither is on screen otherwise. Same handler the panel's own back carries,
  // written here rather than in the renderer because the route is this file's business.
  openCustomizeDetail(open, open && {
    title: skills.skill,
    label: t('skills.back'),
    onBack: () => narrow({ skill: null }),
  });
  $('skills-browse').hidden = open;
  $('skill-detail').hidden = !open;

  drawScopes();
  if (open) {
    drawDetail();
    return;
  }
  drawList();
}

// ─────────────────────────────────────── the lens ───────────────────────────────────────
//
// The same switch the knowledge base's list carries, and the same reasoning: a field standing open
// is a box in a row that is mostly not being searched, so it folds behind its own lens and the
// list is what the row is for. Pressed again it closes, and closing a search that was committed
// puts the whole list back.

function searchOpen() {
  return $('skills-search-box').dataset.open === 'true';
}

function openSearch() {
  $('skills-search-box').dataset.open = 'true';
  drawSearch();
  // Two frames later, and not one, and certainly not now.
  //
  // The field is `visibility: hidden` until the attribute above takes effect, and an element that
  // is not visible cannot take focus — calling it in this task silently does nothing, leaving an
  // open box the caret never reaches and, on a phone, no keyboard. Forcing a style read does not
  // help either: `visibility` is transitioned, so until the transition has actually begun the
  // computed value is still the one it started from.
  //
  // One frame is enough only when the attribute was set in an earlier frame; set from a click
  // handler, the next callback still runs before that frame's style is recalculated. Two is
  // enough in both cases, which is the only reason to count them. See .kb-search-field in
  // knowledge.css for why `visibility` is what closes the field in the first place.
  requestAnimationFrame(() => requestAnimationFrame(() => $('skills-search').focus()));
}

function closeSearch() {
  const committed = state.skills.query;
  $('skills-search-box').dataset.open = 'false';
  $('skills-search').value = '';
  drawSearch();
  // Only where a search is actually on screen. Closing an untouched box changes nothing, and a
  // route written for it would be a history entry that goes back to where it already is.
  if (committed) narrow({ q: '' });
}

/** What the lens says it will do next, in the tooltip and to a screen reader. */
function drawSearch() {
  const toggle = $('skills-search-toggle');
  const label = t(searchOpen() ? 'skills.search.close' : 'skills.search.open');
  toggle.setAttribute('aria-expanded', String(searchOpen()));
  toggle.title = label;
  $('skills-search-label').textContent = label;
}

function drawScopes() {
  const host = $('skills-scopes');
  if (!host) return;
  const skills = state.skills || { scope: 'own' };
  host.textContent = '';
  // Company only where the sign-in carries one. Drawn anyway, it would be a control whose only
  // possible answer is the 400 the endpoint gives a session with no tenant.
  const offered = state.me && state.me.skills && state.me.skills.tenant
    ? SCOPES : ['own'];
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
    pill.setAttribute('aria-selected', String(scope === skills.scope));
    pill.textContent = t(`skills.scope.${scope}`);
    pill.addEventListener('click', () => narrow({ scope }));
    host.append(pill);
  });
}

function drawList() {
  const skills = state.skills;
  // Making one lands in the scope on screen, so the button goes with the scope rather than with
  // the page — and before the skeleton below returns, or a read-only scope would offer it for as
  // long as the first fetch takes.
  const mayWrite = writable(skills.scope);
  $('skills-new').hidden = !mayWrite;
  // Nothing has arrived yet, so the list is drawn as the shape of what is coming rather than as an
  // empty grid that will jump when it fills. Not on a reload of a list already on screen — see
  // `again` in load(): replacing what somebody is reading with a silhouette of it is worse than a
  // moment of staleness.
  if (skills.loading && !skills.loaded) {
    renderSkillSkeleton($('skills-list'));
    $('skills-note').textContent = '';
    return;
  }

  const shown = matching(skills.skills, skills.query);
  // The one thing a row offers is a delete, so where this scope is read-only the row carries no
  // menu at all rather than a menu of nothing.
  renderSkillList($('skills-list'), shown, (name) => open(name), mayWrite ? (skill) => [
    {
      label: t('skills.delete'),
      danger: true,
      // Built per press, so a skill deleted from under the menu is not still offered by it.
      onSelect: () => deleteSkill(skills.scope, skill.name),
    },
  ] : null);

  const note = $('skills-note');
  if (shown.length) {
    note.textContent = '';
    return;
  }
  // Three different kinds of nothing, and telling them apart is the whole value of saying
  // anything: nothing matched, nobody has shared one, or you have not written one yet.
  if (skills.loading) note.textContent = '';
  else if (skills.query) note.textContent = t('skills.none.search');
  else if (skills.scope === 'tenant') note.textContent = t('skills.none.company');
  else note.textContent = t('skills.none');
}

function drawDetail() {
  const skills = state.skills;
  // The tree is not here yet. Its own silhouette rather than the record card's, because what is
  // coming is a name, a description and two panes — see renderSkillPending.
  if (!skills.detail) {
    renderSkillPending($('skill-detail'), {
      scopeWord: t(`skills.scope.${skills.scope}`),
      name: skills.skill,
      failed: !skills.loading,
      onBack: () => narrow({ skill: null }),
    });
    return;
  }
  renderSkillDetail($('skill-detail'), {
    scope: skills.scope,
    writable: writable(skills.scope),
    scopeWord: t(`skills.scope.${skills.scope}`),
    detail: skills.detail,
    file: skills.body,
    filePath: skills.file,
    collapsed: skills.collapsed,
    draft: skills.draft,
    loadingFile: skills.loadingFile,
    onFile: (path) => openFile(path),
    onBack: () => narrow({ skill: null }),
    onFileGone: () => openFile(''),
    redraw: draw,
    refresh: () => load(skills.scope, skills.skill, skills.file, true),
    startDraft: (text) => { skills.draft = { text }; },
    updateDraft: (text) => { skills.draft = { text }; },
    clearDraft: () => { skills.draft = null; },
  });
}

/** What a search leaves in the list. Here rather than on the server: a person has dozens of
 *  skills, not thousands, and a round trip to filter five rows is a round trip nobody asked for. */
function matching(skills, query) {
  const needle = (query || '').trim().toLowerCase();
  if (!needle) return skills;
  return skills.filter((skill) => `${skill.name} ${skill.declaredName} ${skill.description}`
    .toLowerCase().includes(needle));
}

// ─────────────────────────────────────── going places ───────────────────────────────────────

/**
 * Everything that changes what is on screen goes through here, and nothing here changes it.
 *
 * It writes a hash; app.js's dispatch reads it back and calls showCustomize, which calls showSkills. That is the one-way
 * rule, and it is what makes the back button work — a control that changed the page and then
 * wrote the address is a control whose two halves can disagree, and the half the back button
 * reads is the address.
 */
function narrow(change) {
  const skills = state.skills;
  const scope = change.scope !== undefined ? change.scope : skills.scope;
  // Changing the store or the search leaves whatever skill was open: a name is unique inside one
  // store, so carrying it across would be opening a different skill that happens to share a name.
  const movedOut = change.scope !== undefined || change.q !== undefined;
  const skill = change.skill !== undefined ? change.skill : (movedOut ? null : skills.skill);
  const query = change.q !== undefined ? change.q : skills.query;
  const file = change.file !== undefined ? change.file : (skill === skills.skill ? skills.file : '');
  go(customizeRoute('skills', scope, skill, { q: query, file: skill ? file : '' }));
}

function open(name) {
  state.skills.collapsed = new Set();
  state.skills.draft = null;
  narrow({ skill: name, file: '' });
}

function openFile(path) {
  // A draft belongs to the file it was started on. Leaving it behind would put what was typed into
  // one file into the next one somebody opened.
  state.skills.draft = null;
  narrow({ file: path });
}

function hasManifest(detail) {
  return Boolean(detail) && (detail.entries || []).some((it) => !it.dir && it.path === MANIFEST);
}

/**
 * Whether the scope on screen is one this person may write.
 *
 * Their own always is. The company's is app.ai.non-admin-tenant-writes, which by default keeps it
 * to administrators: a skill there is instructions every colleague's agent loads and acts on. The
 * endpoints answer 403 either way — this is what stops the page offering the buttons that would
 * earn one, while everything about reading, opening and exporting a company skill stays.
 */
function writable(scope) {
  return scope !== 'tenant' || Boolean(state.me && state.me.skills && state.me.skills.tenantWritable);
}

function scopeOf(scope) {
  if (!SCOPES.includes(scope)) return 'own';
  // A link to the company's skills, followed by somebody whose sign-in carries no company. Sent to
  // their own rather than left on a page whose every request answers 400.
  if (scope === 'tenant' && !(state.me && state.me.skills && state.me.skills.tenant)) return 'own';
  return scope;
}

// ─────────────────────────────────────── making one ───────────────────────────────────────

/**
 * The form for a new skill, put where the list is rather than in a dialog over it.
 *
 * The same shape the knowledge base's note form has, and built here rather than in the markup
 * because it is the one thing on this page that is not on it most of the time. Two fields: the
 * name, which is the folder, and what it does — which goes into the SKILL.md the server writes,
 * because a skill with no description is one the agent cannot tell when to use.
 */
function openNewForm({ withArchive }) {
  if ($('skills-new-form')) return;
  const skills = state.skills;

  const form = document.createElement('form');
  form.id = 'skills-new-form';
  form.className = 'space-y-2 rounded-xl border border-zinc-200 p-3 dark:border-rail';

  const title = document.createElement('p');
  title.className = 'detail-label';
  title.textContent = t(withArchive ? 'skills.new.upload' : 'skills.new.title');

  const hint = document.createElement('p');
  hint.className = 'detail-hint';
  hint.textContent = t('skills.new.body');

  const name = document.createElement('input');
  name.type = 'text';
  name.required = true;
  name.className = 'field w-full';
  name.placeholder = t('skills.new.name');

  const description = document.createElement('input');
  description.type = 'text';
  description.className = 'field w-full';
  description.placeholder = t('skills.new.description');

  // Or a skill somebody already has. A skill is a folder, so the way to bring an existing one here
  // is to bring the folder — typing eight files in one at a time is not a way to move a skill, and
  // an empty skill is only useful for one being written from nothing.
  const picker = document.createElement('div');
  picker.className = 'flex flex-wrap items-center gap-2';
  const zipLabel = document.createElement('label');
  zipLabel.className = 'panel-action';
  zipLabel.textContent = t('skills.new.zip');
  const zip = document.createElement('input');
  zip.type = 'file';
  zip.accept = '.zip,application/zip';
  zip.className = 'hidden';
  const chosen = document.createElement('span');
  chosen.className = 'detail-hint';
  zipLabel.append(zip);
  picker.append(zipLabel, chosen);

  zip.addEventListener('change', () => {
    const file = zip.files && zip.files[0];
    chosen.textContent = file ? file.name : '';
    // The archive's own name is a good first guess at the skill's, and the field stays editable —
    // an archive called `my-skill-main.zip` should not become a skill called that.
    if (file && !name.value.trim()) {
      name.value = file.name.replace(/\.zip$/i, '').replace(/-(main|master)$/i, '');
    }
    // What a description would be is in the archive's own SKILL.md, so the field stops applying.
    description.hidden = Boolean(file);
  });

  const buttons = document.createElement('div');
  buttons.className = 'flex gap-2';
  const create = document.createElement('button');
  create.type = 'submit';
  create.className = 'panel-action panel-action-primary';
  create.textContent = t('skills.new.create');
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'panel-action';
  cancel.textContent = t('skills.cancel');
  cancel.addEventListener('click', () => form.remove());
  buttons.append(create, cancel);

  // Only the half that was asked for. Bringing a skill that already exists has no description to
  // type — it is in the archive's own SKILL.md — and writing one from nothing has no archive.
  description.hidden = withArchive;
  picker.hidden = !withArchive;
  form.append(title, hint, name, description, picker, buttons);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const wantedName = name.value.trim();
    if (!wantedName) return;
    const archive = zip.files && zip.files[0];
    const made = archive
      ? importSkill(skills.scope, wantedName, archive)
      : createSkill(skills.scope, wantedName, description.value.trim());
    const done = busyButton(create, t('skills.new.create'));
    attempt(() => made
      .then(() => {
        form.remove();
        // Straight into it: somebody who has just named a skill wants to write its SKILL.md, and
        // the alternative is a list they now have to find their own new row in.
        open(wantedName);
      })
      .finally(done));
  });

  $('skills-list').before(form);
  // Straight to the file chooser when that is what was asked for: the name can be guessed from
  // the archive, so choosing it first means one field fewer to fill in by hand.
  if (withArchive) zip.click();
  else name.focus();
}
