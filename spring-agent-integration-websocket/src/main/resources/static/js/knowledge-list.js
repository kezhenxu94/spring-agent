// One document per row in the sidebar, the same shape a conversation has: a dot, a name that
// truncates, and a menu of what can be done to it. They are the two things this agent keeps, so
// they are read the same way.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { menuButton } from './menu.js';
import { documentActions } from './knowledge-actions.js';
import { knowledgeRoute, go } from './route.js';

/**
 * The documents, drawn.
 *
 * Told what to draw and what to do afterwards rather than importing either, so this file and the
 * one that fetches the pages do not have to import each other.
 */
export function renderKnowledgeList(entries, options) {
  const list = $('knowledge-list');
  list.replaceChildren();

  if (!entries.length) {
    const empty = document.createElement('li');
    empty.className = 'px-1 py-1.5 text-[12px] leading-relaxed text-mist';
    empty.textContent = options.searching ? t('knowledge.none.search') : t('knowledge.none');
    list.append(empty);
    return;
  }
  entries.forEach((entry) => list.append(row(entry, options)));
}

function row(entry, options) {
  const current = isSelected(entry, options);
  const item = document.createElement('li');
  item.className = 'group relative';

  const open = document.createElement('button');
  open.type = 'button';
  open.className = 'flex w-full items-center gap-2 rounded-md py-1.5 pl-2 pr-7 text-left '
    + 'text-[13px] transition '
    + (current
      ? 'bg-zinc-200/70 font-medium dark:bg-rail'
      : 'text-zinc-600 hover:bg-zinc-100 dark:text-mist dark:hover:bg-rail/60');

  // Where a conversation's dot says it is live, a document's says who else can read it — the one
  // thing about a stored document worth seeing without opening it. Hollow for your own, filled for
  // something the whole company can read.
  const dot = document.createElement('span');
  dot.className = entry.scope === 'own'
    ? 'size-1.5 shrink-0 rounded-full border border-zinc-400 dark:border-mist'
    : 'size-1.5 shrink-0 rounded-full bg-zinc-400 dark:bg-mist';
  dot.title = t(`knowledge.scope.${entry.scope}`);

  const title = document.createElement('span');
  title.className = 'min-w-0 flex-1 truncate';
  title.textContent = entry.title || entry.docId;
  open.append(dot, title);
  open.addEventListener('click', () => go(knowledgeRoute(entry.docId, entry.scope)));
  item.append(open);

  // Built when the menu is pressed rather than now, so what it offers follows the document as it
  // stands — a row whose share has just been undone offers to share it again without redrawing.
  const items = () => documentActions(entry, { ...options, selected: current });
  // Nothing to offer is nothing to press: reading somebody else's is read-only, and a ⋯ that opens
  // an empty menu says the feature is broken rather than that it is not theirs to use.
  if (items().length) {
    const actions = menuButton(t('knowledge.actions'), items);
    actions.classList.add('row-action');
    item.append(actions);
  }
  return item;
}

/**
 * Whether this row is the document that is open.
 *
 * The knowledge base counts as well as the id, because an id is unique inside one base and not
 * across them — the same file filed privately and company-wide is two rows, and matching on the id
 * alone would draw both as current and open the first for either. A route kept from before the
 * scope was in it names no base, and then the id alone is all there is to go on.
 */
export function isSelected(entry, options) {
  if (!entry || entry.docId !== options.selected) return false;
  return !options.selectedScope || entry.scope === options.selectedScope;
}
