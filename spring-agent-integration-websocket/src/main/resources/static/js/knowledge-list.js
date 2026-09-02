// One document per row in the sidebar, the same shape a conversation has: a dot, a name that
// truncates, and a delete that appears when the row does. They are the two things this agent keeps,
// so they are read the same way.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
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
  const current = entry.docId === options.selected;
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
  open.addEventListener('click', () => go(knowledgeRoute(entry.docId)));
  item.append(open);

  if (!options.readOnly) {
    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'absolute right-1 top-1/2 -translate-y-1/2 rounded px-1 text-[13px] '
      + 'text-mist opacity-0 transition hover:text-alarm focus-visible:opacity-100 '
      + 'group-hover:opacity-100';
    remove.textContent = '×';
    remove.setAttribute('aria-label', t('knowledge.delete'));
    remove.addEventListener('click', (event) => {
      event.stopPropagation();
      if (!window.confirm(t('knowledge.delete.confirm'))) return;
      attempt(async () => {
        await api(`/api/knowledge?docId=${encodeURIComponent(entry.docId)}`, { method: 'DELETE' });
        // Off the document that no longer exists before the list is fetched again, or the detail
        // would redraw against an entry that is on its way out.
        if (current) go(knowledgeRoute());
        await options.refresh();
        toast(t('knowledge.deleted'), 'settled', 2500);
      });
    });
    item.append(remove);
  }
  return item;
}
