// One skill, opened: its files on the left and the one being read on the right.
//
// This is the only place on this page where anything is spent. The frame, the hairline, the tree's
// rows and the file's type are all borrowed from somewhere else in the product — see customize.css
// for which and why — so what is new here is the arrangement rather than a new set of surfaces.
//
// Two things in it are worth knowing before changing it.
//
// The file is set in exactly the type the editor is: mono 12.5px on a 1.7 line, which is what
// .file-edit is set to. Pressing Edit swaps the rendered lines for a textarea and nothing
// moves, so correcting a file happens where it was being read rather than in a form about it. If
// either side of that pair is restyled, both are.
//
// And the unsaved buffer lives in state, not in the textarea. Anything that re-dispatches the
// route redraws this panel — switching language does, see app.js — and a draft held only in the
// DOM would be silently thrown away by an act that looks like it changes nothing.

import { t } from './i18n.js';
import { svgIcon } from './dom.js';
import { detailHead } from './detail.js';
import { markdown } from './render.js';
import { busyButton, spinner } from './busy.js';
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
    scope, detail, file, filePath, collapsed, draft, scopeWord, writable,
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
      // Download is offered whatever the scope: taking a copy of a company skill is reading it,
      // and it is what somebody does with one they may not change. The other two are writes.
      items: () => [
        writable && { label: t('skills.upload'), onSelect: () => pickFiles(view) },
        { label: t('skills.download'), onSelect: () => downloadSkill(scope, detail.name) },
        writable && {
          label: t('skills.delete'),
          danger: true,
          onSelect: () => deleteSkill(scope, detail.name).then((gone) => { if (gone) onBack(); }),
        },
      ].filter(Boolean),
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
  // Asked for and not here yet. Its own state rather than the "choose a file" note, because those
  // two say opposite things — one is an invitation to press something, the other is the answer to
  // having pressed it, and showing the invitation while the answer is on its way reads as the
  // press having missed.
  if (filePath && !file && view.loadingFile) {
    const waiting = document.createElement('p');
    waiting.className = 'file-loading';
    waiting.append(spinner(), document.createTextNode(t('skills.file.loading')));
    head.append(actionsPlaceholder());
    pane.append(head, body);
    body.append(waiting);
    return pane;
  }

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
    // Icons here too, so the strip does not change shape between reading and writing — four
    // widths of button swapping for two words would move the path beside them every time somebody
    // pressed Edit. The spinner takes the tick's place while the save is in flight, with no label
    // beside it for the same reason.
    const save = iconAction(TICK, t('skills.save'), () => {
      const done = busyButton(save);
      attempt(() => saveFile(scope, detail.name, filePath, draft.text)
        .then(() => { view.clearDraft(); return refresh(); })
        .finally(done));
    });
    const cancel = iconAction(CROSS, t('skills.cancel'), () => { view.clearDraft(); redraw(); });
    actions.append(save, cancel);
  } else if (view.writable) {
    if (editable) {
      actions.append(iconAction(PENCIL, t('skills.edit'), () => {
        view.startDraft(file.text || '');
        redraw();
      }));
    }
    actions.append(iconAction(TRASH, t('skills.file.delete'), () => {
      attempt(() => deleteFile(scope, detail.name, filePath)
        .then((gone) => (gone ? view.onFileGone() : null)));
    }));
  }
  head.append(actions);

  if (draft) {
    const area = document.createElement('textarea');
    area.className = 'field-bare field-area-fill field-mono file-edit';
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
    body.append(fileContent(file.text, filePath));
  }

  pane.append(head, body);
  return pane;
}

/** An empty stand-in for the file's actions, so the head does not change height as they arrive. */
function actionsPlaceholder() {
  const host = document.createElement('div');
  host.className = 'skill-file-actions';
  const ghost = document.createElement('span');
  ghost.className = 'panel-action invisible';
  ghost.textContent = '\u00a0';
  host.append(ghost);
  return host;
}

/**
 * What a file's name says it is.
 *
 * By extension, because that is all there is to go on — the server says whether the bytes are text
 * and nothing about what kind. Anything not named here is drawn as plain text with a gutter, which
 * is the right answer for a file nobody can identify: still readable, still numbered, just not
 * coloured in.
 */
const LANGUAGES = {
  py: 'python', sh: 'bash', bash: 'bash', zsh: 'bash', java: 'java', json: 'json',
  js: 'javascript', mjs: 'javascript', cjs: 'javascript', ts: 'typescript',
  yml: 'yaml', yaml: 'yaml', xml: 'xml', html: 'xml', css: 'css', scss: 'scss',
  sql: 'sql', go: 'go', rs: 'rust', rb: 'ruby', php: 'php', kt: 'kotlin', swift: 'swift',
  c: 'c', h: 'c', cpp: 'cpp', hpp: 'cpp', cs: 'csharp', lua: 'lua', pl: 'perl', r: 'r',
  toml: 'ini', ini: 'ini', cfg: 'ini', conf: 'ini', diff: 'diff', patch: 'diff',
  make: 'makefile', mk: 'makefile', graphql: 'graphql', gql: 'graphql',
};

function extensionOf(path) {
  const name = path.split('/').pop();
  const dot = name.lastIndexOf('.');
  return dot <= 0 ? '' : name.slice(dot + 1).toLowerCase();
}

/** The file, drawn as what it is. */
function fileContent(text, path) {
  const extension = extensionOf(path);
  if (extension === 'md' || extension === 'markdown') {
    const prose = document.createElement('div');
    prose.className = 'prose file-prose';
    // The same renderer and the same sanitiser the transcript uses. A skill's files are written by
    // the person reading them or by the agent on their behalf, but they also arrive in a zip
    // somebody was sent, so this is not a place to trust the markup in a file.
    prose.innerHTML = markdown(text);
    return prose;
  }
  return codeBlock(text, LANGUAGES[extension]);
}

/**
 * A file with a gutter beside it.
 *
 * One <pre> for the whole file rather than a row per line, because highlighting works on the file:
 * a string or a comment that spans two lines is one construct, and marking up each line on its own
 * would cut it in half and colour the remainder wrong. The gutter is the other grid column, with
 * the same line-height, so the numbers line up without knowing anything about the code.
 */
function codeBlock(text, language) {
  const host = document.createElement('div');
  host.className = 'file-code';

  // A trailing newline ends the last line; it does not start an empty one after it.
  const body = text.replace(/\n$/, '');
  const count = body.split('\n').length;

  const gutter = document.createElement('div');
  gutter.className = 'file-gutter';
  gutter.setAttribute('aria-hidden', 'true');
  for (let line = 1; line <= count; line += 1) {
    const no = document.createElement('span');
    no.className = 'file-no';
    no.dataset.line = String(line);
    gutter.append(no);
  }

  const pre = document.createElement('pre');
  pre.className = 'file-text';
  const code = document.createElement('code');
  const highlighted = highlight(body, language);
  if (highlighted === null) code.textContent = body;
  else {
    code.className = 'hljs';
    code.innerHTML = highlighted;
  }
  pre.append(code);

  host.append(gutter, pre);
  return host;
}

/**
 * The file marked up, or null to draw it as it is.
 *
 * Null for an unknown extension and null when anything goes wrong: highlight.js throws on a
 * language it does not have registered, and a file that cannot be coloured in is still a file
 * somebody wants to read. The alternative — letting it throw — takes the whole pane down over a
 * cosmetic step.
 */
function highlight(text, language) {
  if (!language || !window.hljs || !window.hljs.getLanguage(language)) return null;
  try {
    return window.hljs.highlight(text, { language, ignoreIllegals: true }).value;
  } catch (e) {
    return null;
  }
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

const PENCIL = 'M10.4 2.9a1.3 1.3 0 0 1 1.9 0l.8.8a1.3 1.3 0 0 1 0 1.9l-6.3 6.3-3 .8.8-3Z';
const TRASH = 'M2.9 4.4h10.2M6.2 4.4V3.1h3.6v1.3M4.2 4.4l.6 8.2h6.4l.6-8.2M6.6 6.8v3.6M9.4 6.8v3.6';
const TICK = 'm3.6 8.4 3 3 5.8-6.8';
const CROSS = 'm4.6 4.6 6.8 6.8m0-6.8-6.8 6.8';

/**
 * One of the file's actions, as an icon with its name in a tooltip.
 *
 * Icons rather than words because there are two of them in a strip that also has to hold a path,
 * and on a narrow pane the path is the thing that matters — it says which file you are about to
 * act on. The name is still there for a pointer that rests and for a screen reader; what is lost
 * is only the reader who wants to scan them, and there are two.
 */
function iconAction(d, label, onClick) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'tool-button tool-button-sm';
  button.title = label;
  button.setAttribute('aria-label', label);
  button.append(svgIcon(d));
  button.addEventListener('click', onClick);
  return button;
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

/**
 * A skill that has been named but whose tree is still coming — or did not come at all.
 *
 * The panel is the skill from the moment it is asked for, so this holds the shape the real one
 * will take: the way back, the eyebrow, the name it was opened by, and the two panes as outlines.
 * The name is the one real thing in it, because the route already knows it and drawing a bar where
 * a name is known would be pretending to know less than we do.
 */
export function renderSkillPending(host, { scopeWord, name, failed, onBack }) {
  host.textContent = '';

  const back = document.createElement('button');
  back.type = 'button';
  back.className = 'skill-back';
  back.append(arrow(), document.createTextNode(t('skills.back')));
  back.addEventListener('click', onBack);
  host.append(back);

  host.append(detailHead({ kind: t('skills.kind'), pill: scopeWord, name }));

  if (failed) {
    // The tree was asked for and did not come. Said plainly rather than left as an outline that
    // never fills, which is the one thing worse than an error: a page that looks like it is still
    // working when nothing is.
    const gone = document.createElement('p');
    gone.className = 'file-note';
    gone.textContent = t('skills.gone', name);
    host.append(gone);
    return;
  }

  const panes = document.createElement('div');
  panes.className = 'skill-panes';
  panes.setAttribute('aria-busy', 'true');

  const tree = document.createElement('div');
  tree.className = 'skill-tree';
  for (let row = 0; row < 5; row += 1) {
    const line = document.createElement('div');
    line.className = 'tree-skeleton';
    const bar = document.createElement('span');
    bar.className = 'skeleton';
    bar.style.width = `${[70, 52, 84, 60, 45][row]}%`;
    line.append(bar);
    tree.append(line);
  }

  const file = document.createElement('div');
  file.className = 'skill-file';
  const waiting = document.createElement('p');
  waiting.className = 'file-loading';
  waiting.append(spinner(), document.createTextNode(t('skills.loading')));
  file.append(waiting);

  panes.append(tree, file);
  host.append(panes);
}
