// One skill, opened: its files on the left and the one being read on the right.
//
// This is the only place on this page where anything is spent. The frame, the hairline, the tree's
// rows and the file's type are all borrowed from somewhere else in the product — see customize.css
// for which and why — so what is new here is the arrangement rather than a new set of surfaces.
//
// Two things in it are worth knowing before changing it.
//
// The file is set in exactly the type the editor is: mono 12.5px on a 1.7 line, which is what
// .detail-edit has always used. Pressing Edit swaps the rendered lines for a textarea and nothing
// moves, so correcting a file happens where it was being read rather than in a form about it. If
// either side of that pair is restyled, both are.
//
// And the unsaved buffer lives in state, not in the textarea. Anything that re-dispatches the
// route redraws this panel — switching language does, see app.js — and a draft held only in the
// DOM would be silently thrown away by an act that looks like it changes nothing.

import { t } from './i18n.js';
import { detailHead } from './detail.js';
import { busyButton } from './busy.js';
import { renderTree } from './skills-tree.js';
import { deleteFile, deleteSkill, downloadSkill, saveFile, uploadInto } from './skills-actions.js';
import { attempt } from './toast.js';

/** How wide the tree is, remembered for the reader the way the sidebar's fold is. */
const WIDTH_KEY = 'spring-agent-skill-tree-width';
const DEFAULT_WIDTH = 240;
const MIN_WIDTH = 144;

function storedWidth() {
  try {
    const stored = Number(localStorage.getItem(WIDTH_KEY));
    return Number.isFinite(stored) && stored >= MIN_WIDTH ? stored : DEFAULT_WIDTH;
  } catch (e) {
    return DEFAULT_WIDTH; // private browsing refuses the store; the default is a fine answer
  }
}

function rememberWidth(px) {
  try { localStorage.setItem(WIDTH_KEY, String(Math.round(px))); } catch (e) { /* private mode */ }
}

/**
 * Draws the open skill into `host`.
 *
 * `view` is everything the panel shows and every way out of it — the section owns all of it, and
 * this draws it. Nothing here fetches, and nothing here writes the address bar: choosing a file
 * calls `onFile`, which navigates, and the panel is redrawn because the route changed. That is the
 * one-way rule route.js is built on, kept at the level of a pane.
 */
export function renderSkillDetail(host, view) {
  const {
    scope, detail, file, filePath, collapsed, draft, scopeWord,
    onFile, onBack, redraw, refresh,
  } = view;
  host.textContent = '';

  const back = document.createElement('button');
  back.type = 'button';
  back.className = 'skill-back';
  back.append(arrow(), document.createTextNode(t('skills.back')));
  back.addEventListener('click', onBack);
  host.append(back);

  host.append(detailHead({
    kind: t('skills.kind'),
    pill: scopeWord,
    // Filled means shared with more people than the reader — the same thing it means about a
    // knowledge document, which is why the pill is borrowed rather than a new badge invented.
    pillFilled: scope === 'tenant',
    name: detail.name,
    actions: {
      label: t('skills.actions'),
      items: () => [
        { label: t('skills.upload'), onSelect: () => pickFiles(view) },
        { label: t('skills.download'), onSelect: () => downloadSkill(scope, detail.name) },
        {
          label: t('skills.delete'),
          danger: true,
          onSelect: () => deleteSkill(scope, detail.name).then((gone) => { if (gone) onBack(); }),
        },
      ],
    },
  }));

  if (detail.description) {
    const why = document.createElement('p');
    why.className = 'skill-why';
    why.textContent = detail.description;
    host.append(why);
  }
  // The same warning the list row carries, repeated here because this is where somebody comes to
  // fix it — and the fix is one line of the file already on screen.
  if (!detail.declaredName) {
    const warn = document.createElement('p');
    warn.className = 'detail-hint';
    warn.textContent = t('skills.unnamed.why');
    host.append(warn);
  }

  const panes = document.createElement('div');
  panes.className = 'skill-panes';
  // Below md the two panes are one at a time, and which one is decided by the route rather than by
  // a toggle — a route naming a file shows the file. See the media query in customize.css.
  panes.dataset.on = filePath ? 'file' : 'tree';

  const tree = document.createElement('div');
  tree.className = 'skill-tree';
  tree.style.setProperty('--tree-width', `${storedWidth()}px`);
  renderTree(tree, detail.entries || [], filePath, collapsed, onFile, redraw);

  panes.append(tree, resizer(tree), filePane(view, file));
  host.append(panes);
}

/** The pane on the right: which file, what can be done to it, and the file. */
function filePane(view, file) {
  const { scope, detail, filePath, draft, redraw, refresh } = view;
  const pane = document.createElement('div');
  pane.className = 'skill-file';

  const head = document.createElement('div');
  head.className = 'skill-file-head';

  // Below md the two panes are one at a time, so with a file open the tree is off screen and this
  // is the only way back to it. Drawn always and hidden by the media query rather than built
  // conditionally: a control that exists only under a width is a control that goes missing when
  // the window is resized, and nothing here redraws on resize.
  const toTree = document.createElement('button');
  toTree.type = 'button';
  toTree.className = 'panel-action pane-only';
  toTree.textContent = t('skills.tree.show');
  toTree.addEventListener('click', () => view.onFileGone());
  head.append(toTree);

  const path = document.createElement('span');
  path.className = 'skill-file-path';
  path.textContent = filePath ? `/${filePath}` : '';
  head.append(path);

  const body = document.createElement('div');
  body.className = 'skill-file-body';

  // With nothing open there is no head at all — not an empty one. The head carries a path and the
  // things that can be done to a file, so with no file it has nothing in it but its own bottom
  // rule, and a rule across an empty pane reads as a heading whose text failed to load.
  if (!filePath || !file) {
    body.append(note(t('skills.file.none')));
    pane.append(body);
    return pane;
  }

  const actions = document.createElement('div');
  actions.className = 'skill-file-actions';

  // Only a file that can be shown can be edited. A binary has nothing to put in a textarea, and
  // one over the cap arrived without its text — offering Edit for either would mean saving an
  // empty box over a file somebody still has.
  const editable = !file.binary && !file.tooLarge;

  if (draft) {
    const save = document.createElement('button');
    save.type = 'button';
    save.className = 'panel-action panel-action-primary';
    save.textContent = t('skills.save');
    save.addEventListener('click', () => {
      const done = busyButton(save, t('skills.save'));
      attempt(() => saveFile(scope, detail.name, filePath, draft.text)
        .then(() => { view.clearDraft(); return refresh(); })
        .finally(done));
    });
    const cancel = document.createElement('button');
    cancel.type = 'button';
    cancel.className = 'panel-action';
    cancel.textContent = t('skills.cancel');
    cancel.addEventListener('click', () => { view.clearDraft(); redraw(); });
    actions.append(save, cancel);
  } else {
    if (editable) {
      const edit = document.createElement('button');
      edit.type = 'button';
      edit.className = 'panel-action';
      edit.textContent = t('skills.edit');
      edit.addEventListener('click', () => { view.startDraft(file.text || ''); redraw(); });
      actions.append(edit);
    }
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'panel-action';
    remove.textContent = t('skills.file.delete');
    remove.addEventListener('click', () => {
      attempt(() => deleteFile(scope, detail.name, filePath)
        .then((gone) => (gone ? view.onFileGone() : null)));
    });
    actions.append(remove);
  }
  head.append(actions);

  if (draft) {
    const area = document.createElement('textarea');
    area.className = 'file-edit';
    area.spellcheck = false;
    area.value = draft.text;
    // Straight into state on every keystroke, not read back off the element at save time — this
    // panel is redrawn by things that are not this panel, and a value only in the DOM is a draft
    // that disappears when somebody changes the language.
    area.addEventListener('input', () => view.updateDraft(area.value));
    body.append(area);
  } else if (file.binary) {
    body.append(note(t('skills.file.binary')));
  } else if (file.tooLarge) {
    body.append(note(t('skills.file.large', `${Math.round(file.size / 1024)}KB`)));
  } else if (!file.text) {
    body.append(note(t('skills.file.empty')));
  } else {
    body.append(lines(file.text));
  }

  pane.append(head, body);
  return pane;
}

/**
 * The file, numbered.
 *
 * A grid of two columns rather than a number glued to the front of each line: the numbers have to
 * line up in their own column whatever the lines do, and a long line wraps inside its own cell
 * instead of pushing the pane sideways — a row that scrolled horizontally would scroll away from
 * the number that names it.
 */
function lines(text) {
  const host = document.createElement('div');
  host.className = 'file-lines';
  // A trailing newline is the end of the last line, not the start of an empty one after it.
  const all = text.replace(/\n$/, '').split('\n');
  all.forEach((line, index) => {
    const no = document.createElement('span');
    no.className = 'file-no';
    // An attribute and not a text node: see .file-no in customize.css. A number that is text ends
    // up inside a selection dragged across the file, and copying three lines gives back three
    // lines with their numbers glued to the front.
    no.dataset.line = String(index + 1);
    const code = document.createElement('code');
    code.className = 'file-line';
    code.textContent = line;
    host.append(no, code);
  });
  return host;
}

function note(text) {
  const p = document.createElement('p');
  p.className = 'file-note';
  p.textContent = text;
  return p;
}

/**
 * The divider.
 *
 * Pointer events with capture, so a drag that leaves the handle — which every drag does — keeps
 * being delivered to it. Arrow keys move it too: it is a separator with a tabindex, so somebody
 * who never touches a pointer can still get at the half of the pane they want.
 */
function resizer(tree) {
  const handle = document.createElement('div');
  handle.className = 'pane-resize';
  handle.setAttribute('role', 'separator');
  handle.setAttribute('aria-orientation', 'vertical');
  handle.setAttribute('aria-label', t('skills.resize'));
  handle.tabIndex = 0;

  const setWidth = (px) => {
    const width = Math.max(MIN_WIDTH, px);
    tree.style.setProperty('--tree-width', `${width}px`);
    rememberWidth(width);
  };

  handle.addEventListener('pointerdown', (event) => {
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    const left = tree.getBoundingClientRect().left;
    const move = (moved) => setWidth(moved.clientX - left);
    const up = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
  });

  handle.addEventListener('keydown', (event) => {
    const step = event.key === 'ArrowLeft' ? -16 : event.key === 'ArrowRight' ? 16 : 0;
    if (!step) return;
    event.preventDefault();
    setWidth(tree.getBoundingClientRect().width + step);
  });

  return handle;
}

/** Adding files to the skill: the picker, then the upload, then whatever the caller does next. */
function pickFiles(view) {
  const input = document.createElement('input');
  input.type = 'file';
  input.multiple = true;
  input.addEventListener('change', () => {
    if (!input.files || !input.files.length) return;
    attempt(() => uploadInto(view.scope, view.detail.name, '', input.files)
      .then(() => view.refresh()));
  });
  input.click();
}

function arrow() {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('width', '13');
  svg.setAttribute('height', '13');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', 'M9.5 3.5 5 8l4.5 4.5');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.5');
  path.setAttribute('stroke-linecap', 'round');
  path.setAttribute('stroke-linejoin', 'round');
  svg.append(path);
  return svg;
}
