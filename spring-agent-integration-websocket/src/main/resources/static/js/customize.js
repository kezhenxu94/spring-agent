// Customize: what this agent has been taught, as against what it has been told.
//
// One sub-tab today, and the section is built as though there were three, because the cost of that
// is a list of tab names and the cost of not doing it is rewriting the shell the first time a
// second tab is wanted. `TABS` in route.js is the list; everything below reads the route rather
// than holding a copy of where it is.
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
import { $ } from './dom.js';
import { bus, state } from './state.js';
import { attempt } from './toast.js';
import { busyButton } from './busy.js';
import { customizeRoute, go, goReplacing } from './route.js';
import { openDetail } from './detail.js';
import { renderSkillList, renderSkillSkeleton } from './skills-list.js';
import { renderSkillDetail, renderSkillPending } from './skills-detail.js';
import {
  createSkill, deleteSkill, fetchFile, fetchSkills, fetchTree, importSkill,
} from './skills-actions.js';

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

export function initCustomize() {
  state.customize = {
    scope: 'own',
    query: '',
    skill: null,
    file: '',
    skills: [],
    detail: null,
    body: null,
    collapsed: new Set(),
    draft: null,
    loading: false,
  };

  drawScopes();

  const search = $('skills-search');
  // On Enter and on clearing, never on every keystroke: the search is in the address bar, and a
  // history entry per letter typed is a back button that has to be pressed nine times to undo one
  // search. The same rule the knowledge base's search follows.
  search.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      narrow({ q: search.value.trim() });
    }
  });
  search.addEventListener('search', () => {
    if (!search.value.trim()) narrow({ q: '' });
  });

  $('skills-new').addEventListener('click', openNewForm);

  // A write anywhere — this page, or a run that wrote a skill while the page was open — means the
  // list is stale. Over the bus rather than by calling back, because the thing that wrote it sits
  // in an earlier layer than this and must not import it.
  bus.on('skills:changed', () => {
    const customize = state.customize;
    if (!customize || !customize.loaded) return;
    attempt(() => load(customize.scope, customize.skill, customize.file, true));
  });
}

/** What the route means here: which store, which skill, which file, and what the list is filtered to. */
export function showCustomize(route) {
  const customize = state.customize;
  const scope = scopeOf(route.scope);
  const skill = route.id || null;
  const file = (route.narrowing && route.narrowing.file) || '';
  const query = (route.narrowing && route.narrowing.q) || '';

  customize.query = query;
  $('skills-search').value = query;

  const moved = customize.scope !== scope
    || customize.skill !== skill
    || customize.file !== file;
  // Whether this is a skill being *opened*, as against one already open whose file changed. Only
  // an opening gets a file chosen for it — see load(). Taken before the assignment below, which
  // is the last moment the previous skill is still known.
  const opening = Boolean(skill) && customize.skill !== skill;
  customize.scope = scope;
  customize.skill = skill;
  customize.file = file;

  if (moved || !customize.loaded) {
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
  const customize = state.customize;
  const mine = (wanted += 1);
  customize.loading = true;
  customize.loadingFile = Boolean(file);
  if (!again) {
    // Arriving somewhere new, the old skill's tree is not this one's. Cleared so the panes draw
    // their own silhouette rather than the last skill's files under this skill's name.
    if (customize.skill !== skill) {
      customize.detail = null;
      customize.body = null;
    }
  }
  draw();

  const list = await fetchSkills(scope);
  if (mine !== wanted) return;
  customize.skills = list.skills || [];

  if (skill) {
    // A tree, then the file in it. In that order and not together: a file named by a stale link
    // may not be in this skill, and finding that out after the tree has been drawn means the pane
    // says so beside a list of what is actually there.
    customize.detail = await fetchTree(scope, skill).catch(() => null);
    if (mine !== wanted) return;

    // A skill opened with no file named starts on its SKILL.md, which is the file somebody came to
    // read — it is what the skill *is*, and the others are what it refers to. Only on an opening:
    // stepping back to the tree on a phone also leaves no file named, and re-choosing one there
    // would make that button do nothing.
    //
    // The address bar is corrected rather than left saying something else, because the file being
    // read is part of what this page's links mean. Replacing rather than pushing, or Back would
    // return to the route that redirects and never get past it.
    if (opening && !file && hasManifest(customize.detail)) {
      file = MANIFEST;
      customize.file = file;
      goReplacing(customizeRoute('skills', scope, skill, { q: customize.query, file }));
    }
    // Drawn before the file is asked for, so the tree is there to choose from while the file it
    // was opened with is still coming — and so the pane it is coming into says as much.
    customize.loading = false;
    draw();

    customize.body = customize.detail && file
      ? await fetchFile(scope, skill, file).catch(() => null)
      : null;
    if (mine !== wanted) return;
  } else {
    customize.detail = null;
    customize.body = null;
  }

  customize.loading = false;
  customize.loadingFile = false;
  customize.loaded = true;
  draw();
}

// ─────────────────────────────────────── drawing ───────────────────────────────────────

function draw() {
  const customize = state.customize;
  // Open as soon as a skill is named, not once its tree has arrived: the panel is the skill from
  // the moment it is asked for, and waiting for the tree would show the list for as long as the
  // request takes and then replace it.
  const open = Boolean(customize.skill);

  // The panel becomes the skill, so the intro, the tab strip and the list all go. openDetail does
  // the first of those and the panel's own shape; the other two are this section's to hide,
  // because they belong to the list rather than to Customize as a whole.
  openDetail('customize-panel', 'customize-intro', open);
  $('customize-tabs').hidden = open;
  $('skills-browse').hidden = open;
  $('skill-detail').hidden = !open;

  drawScopes();
  if (open) {
    drawDetail();
    return;
  }
  drawList();
}

function drawScopes() {
  const host = $('skills-scopes');
  if (!host) return;
  const customize = state.customize || { scope: 'own' };
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
    pill.setAttribute('aria-selected', String(scope === customize.scope));
    pill.textContent = t(`skills.scope.${scope}`);
    pill.addEventListener('click', () => narrow({ scope }));
    host.append(pill);
  });
}

function drawList() {
  const customize = state.customize;
  // Nothing has arrived yet, so the list is drawn as the shape of what is coming rather than as an
  // empty grid that will jump when it fills. Not on a reload of a list already on screen — see
  // `again` in load(): replacing what somebody is reading with a silhouette of it is worse than a
  // moment of staleness.
  if (customize.loading && !customize.loaded) {
    renderSkillSkeleton($('skills-list'));
    $('skills-note').textContent = '';
    return;
  }

  const shown = matching(customize.skills, customize.query);
  renderSkillList($('skills-list'), shown, (name) => open(name), (skill) => [
    {
      label: t('skills.delete'),
      danger: true,
      // Built per press, so a skill deleted from under the menu is not still offered by it.
      onSelect: () => deleteSkill(customize.scope, skill.name),
    },
  ]);

  const note = $('skills-note');
  if (shown.length) {
    note.textContent = '';
    return;
  }
  // Three different kinds of nothing, and telling them apart is the whole value of saying
  // anything: nothing matched, nobody has shared one, or you have not written one yet.
  if (customize.loading) note.textContent = '';
  else if (customize.query) note.textContent = t('skills.none.search');
  else if (customize.scope === 'tenant') note.textContent = t('skills.none.company');
  else note.textContent = t('skills.none');
}

function drawDetail() {
  const customize = state.customize;
  // The tree is not here yet. Its own silhouette rather than the record card's, because what is
  // coming is a name, a description and two panes — see renderSkillPending.
  if (!customize.detail) {
    renderSkillPending($('skill-detail'), {
      scopeWord: t(`skills.scope.${customize.scope}`),
      name: customize.skill,
      failed: !customize.loading,
      onBack: () => narrow({ skill: null }),
    });
    return;
  }
  renderSkillDetail($('skill-detail'), {
    scope: customize.scope,
    scopeWord: t(`skills.scope.${customize.scope}`),
    detail: customize.detail,
    file: customize.body,
    filePath: customize.file,
    collapsed: customize.collapsed,
    draft: customize.draft,
    loadingFile: customize.loadingFile,
    onFile: (path) => openFile(path),
    onBack: () => narrow({ skill: null }),
    onFileGone: () => openFile(''),
    redraw: draw,
    refresh: () => load(customize.scope, customize.skill, customize.file, true),
    startDraft: (text) => { customize.draft = { text }; },
    updateDraft: (text) => { customize.draft = { text }; },
    clearDraft: () => { customize.draft = null; },
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
 * It writes a hash; app.js's dispatch reads it back and calls showCustomize. That is the one-way
 * rule, and it is what makes the back button work — a control that changed the page and then
 * wrote the address is a control whose two halves can disagree, and the half the back button
 * reads is the address.
 */
function narrow(change) {
  const customize = state.customize;
  const scope = change.scope !== undefined ? change.scope : customize.scope;
  // Changing the store or the search leaves whatever skill was open: a name is unique inside one
  // store, so carrying it across would be opening a different skill that happens to share a name.
  const movedOut = change.scope !== undefined || change.q !== undefined;
  const skill = change.skill !== undefined ? change.skill : (movedOut ? null : customize.skill);
  const query = change.q !== undefined ? change.q : customize.query;
  const file = change.file !== undefined ? change.file : (skill === customize.skill ? customize.file : '');
  go(customizeRoute('skills', scope, skill, { q: query, file: skill ? file : '' }));
}

function open(name) {
  state.customize.collapsed = new Set();
  state.customize.draft = null;
  narrow({ skill: name, file: '' });
}

function openFile(path) {
  // A draft belongs to the file it was started on. Leaving it behind would put what was typed into
  // one file into the next one somebody opened.
  state.customize.draft = null;
  narrow({ file: path });
}

function hasManifest(detail) {
  return Boolean(detail) && (detail.entries || []).some((it) => !it.dir && it.path === MANIFEST);
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
function openNewForm() {
  if ($('skills-new-form')) return;
  const customize = state.customize;

  const form = document.createElement('form');
  form.id = 'skills-new-form';
  form.className = 'space-y-2 rounded-xl border border-zinc-200 p-3 dark:border-rail';

  const title = document.createElement('p');
  title.className = 'detail-label';
  title.textContent = t('skills.new.title');

  const hint = document.createElement('p');
  hint.className = 'detail-hint';
  hint.textContent = t('skills.new.body');

  const name = document.createElement('input');
  name.type = 'text';
  name.required = true;
  name.className = 'detail-input w-full';
  name.placeholder = t('skills.new.name');

  const description = document.createElement('input');
  description.type = 'text';
  description.className = 'detail-input w-full';
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

  form.append(title, hint, name, description, picker, buttons);
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const wantedName = name.value.trim();
    if (!wantedName) return;
    const archive = zip.files && zip.files[0];
    const made = archive
      ? importSkill(customize.scope, wantedName, archive)
      : createSkill(customize.scope, wantedName, description.value.trim());
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
  name.focus();
}
