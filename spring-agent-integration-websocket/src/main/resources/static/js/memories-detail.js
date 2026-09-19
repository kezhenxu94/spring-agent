// One memory, opened: what it claims about itself, and the text it holds.
//
// Drawn on the shared record card in detail.js — the same head, the same facts grid, the same body
// box a knowledge document and a scheduled task open in. That is the whole design decision here:
// the three are the same kind of object to the person reading them, something the agent keeps
// between runs, so they are drawn by the same code rather than by three panels each written to look
// a bit like the others.
//
// Not the two panes the skills tab draws. A skill is a folder and needs a tree beside the file; a
// memory is one file, and a frame around it would be a box inside a card whose sections are already
// divided by a hairline.
//
// The whole file goes in the editor, front matter included, while the facts above are a *reading*
// of that front matter. A form over the three fields would be easier and would be lying: the front
// matter is prose the agent writes and may extend, so a page that could only express what its form
// knew about would quietly drop the rest on the first save. It also makes MEMORY.md, which has no
// front matter at all, the same kind of thing as everything else here rather than a special case.
//
// The unsaved buffer lives in state and not in the textarea: anything that re-dispatches the route
// redraws this, switching language included, and a draft held only in the DOM is thrown away by an
// act that looks like it changes nothing.

import { t } from './i18n.js';
import { fullTime } from './dom.js';
import { backButton, detailBody, detailFacts, detailHead } from './detail.js';
import { markdown } from './render.js';
import { spinner } from './busy.js';
import { attempt } from './toast.js';
import { deleteMemory, saveMemory } from './memories-actions.js';

/**
 * Draws the open memory into `host`.
 *
 * `view` is everything the panel shows and every way out of it — the section owns all of it, and
 * this draws it. Nothing here fetches and nothing here writes the address bar: the way out calls
 * `onBack`, which navigates, and the panel is redrawn because the route changed.
 */
export function renderMemoryDetail(host, view) {
  const { scope, scopeWord, path, file, entry, draft, writable, onBack, onGone, redraw, refresh } =
    view;
  host.textContent = '';
  host.append(backButton(t('memories.back'), onBack));

  // Everything that can be done to it, in the head's menu — where a knowledge document and a task
  // both keep theirs. A long memory would otherwise put its own actions below the fold.
  const items = () => {
    if (!writable) return [];
    const out = [];
    // Only a file that can be shown can be edited: a binary has nothing to put in a textarea, and
    // one over the cap arrived without its text, so offering Edit for either would mean saving an
    // empty box over a file somebody still has.
    if (file && !file.binary && !file.tooLarge && !draft) {
      out.push({
        label: t('memories.edit'),
        onSelect: () => { view.startDraft((file && file.text) || ''); redraw(); },
      });
    }
    out.push({
      label: t('memories.delete'),
      danger: true,
      onSelect: () => attempt(() => deleteMemory(scope, path).then((gone) => (gone ? onGone() : null))),
    });
    return out;
  };

  host.append(detailHead({
    kind: t('memories.kind'),
    pill: scopeWord,
    // Filled means shared, which is what a filled pill means everywhere else on this page.
    pillFilled: scope === 'tenant',
    name: (entry && entry.name) || path,
    actions: { label: t('memories.actions'), items },
  }));

  host.append(detailFacts([
    // The path always, even where the title is already the name: this is the identity, it is what
    // MEMORY.md indexes by, and it is what somebody would type to ask the agent about this file.
    [t('memories.path'), path, true],
    entry && entry.type && [t('memories.type'), t(`memories.type.${entry.type}`) || entry.type],
    entry && entry.description && [t('memories.description'), entry.description],
    entry && entry.updatedAt && [t('memories.updated'), fullTime(entry.updatedAt)],
  ]));

  host.append(body(view));
}

/** The memory itself: the stored text, or the textarea it is being rewritten in. */
function body(view) {
  const { scope, path, file, draft, redraw, refresh } = view;

  if (!draft) {
    return detailBody(t('memories.body'), text(file));
  }

  const field = document.createElement('textarea');
  // Fills the box, as the task editor and the skills editor do: with the card open the panel *is*
  // the memory, so the editor is the whole of it rather than a small box with the card's empty
  // space under it.
  field.className = 'field field-area field-area-fill field-mono';
  field.spellcheck = false;
  field.value = draft.text;
  field.setAttribute('aria-label', t('memories.body'));
  // Straight into state on every keystroke rather than read off the element at save time; see the
  // note at the top of this file.
  field.addEventListener('input', () => view.updateDraft(field.value));

  const buttons = document.createElement('div');
  buttons.className = 'detail-edit-buttons';
  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'panel-action panel-action-primary';
  save.textContent = t('memories.save');
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'panel-action';
  cancel.textContent = t('memories.cancel');

  save.addEventListener('click', () => {
    save.disabled = true;
    attempt(() => saveMemory(scope, path, view.draft.text)
      .then(() => { view.clearDraft(); return refresh(); })
      .finally(() => { save.disabled = false; }));
  });
  cancel.addEventListener('click', () => { view.clearDraft(); redraw(); });
  buttons.append(save, cancel);

  return detailBody(t('memories.body'), field, buttons);
}

/**
 * The stored text, as markdown — the same way a knowledge document and an answer are drawn.
 *
 * Through the same sanitiser too, and not as a nicety: a memory in a shared scope was written by
 * somebody else's agent, so it is exactly as untrusted as model output.
 *
 * The front matter is left out of the rendering. The facts above this box *are* the front matter,
 * laid out as a spec sheet; rendered again here it is on screen twice, and the second time as
 * markdown — where `name: x` and `description: y` are consecutive lines of one paragraph and run
 * together into a sentence that is not in the file. Editing still shows the whole file, because the
 * whole file is what is saved.
 */
function text(file) {
  const body = document.createElement('div');
  if (!file) {
    body.className = 'detail-text text-mist';
    body.append(spinner(), document.createTextNode(t('memories.loading')));
    return body;
  }
  if (file.binary) return note(t('memories.binary'));
  if (file.tooLarge) return note(t('memories.large', `${Math.round(file.size / 1024)}KB`));

  const prose = withoutFrontMatter(file.text || '');
  if (!prose.trim()) return note(t('memories.empty'));

  body.className = 'detail-text prose max-w-none text-[13.5px] leading-[1.7]';
  body.innerHTML = markdown(prose);
  return body;
}

function note(what) {
  const body = document.createElement('div');
  body.className = 'detail-text text-mist';
  body.textContent = what;
  return body;
}

/**
 * The memory without its front matter.
 *
 * Only a fence that opens the file, and only up to the first one that closes it. Anything else is a
 * `---` in the prose, which is a horizontal rule somebody wrote on purpose.
 */
function withoutFrontMatter(value) {
  if (!value.startsWith('---')) return value;
  const end = value.indexOf('\n---', 3);
  if (end < 0) return value;
  const after = value.indexOf('\n', end + 1);
  return after < 0 ? '' : value.slice(after + 1).replace(/^\n+/, '');
}

/**
 * A memory that has been named but whose contents are still coming — or did not come at all.
 *
 * The panel is the memory from the moment it is asked for, so this holds the shape the real one
 * will take. The path is the one real thing in it, because the route already knows it.
 */
export function renderMemoryPending(host, { scopeWord, path, failed, onBack }) {
  host.textContent = '';
  host.append(backButton(t('memories.back'), onBack));
  host.append(detailHead({ kind: t('memories.kind'), pill: scopeWord, name: path }));
  host.append(detailBody(
    t('memories.body'),
    failed
      // Asked for and did not come. Said plainly rather than left as an outline that never fills,
      // which is the one thing worse than an error: a page that looks like it is still working.
      ? note(t('memories.gone', path))
      : text(null),
  ));
}
