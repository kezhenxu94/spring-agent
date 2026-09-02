// What can be done to one stored document, in one place.
//
// The row in the sidebar and the panel it opens into offer the same things, so they are written
// once here and drawn twice — a delete that asks in the list and a delete that does not ask in the
// panel is the kind of difference nobody decides on and everybody has to remember.
//
// Every one of them is scoped by the server to whoever is signed in; nothing here sends an identity.

import { t } from './i18n.js';
import { api } from './api.js';
import { attempt, toast, working } from './toast.js';
import { confirmAction } from './confirm.js';
import { knowledgeRoute, go } from './route.js';

/**
 * The menu for a document: where it can be read, and whether it survives.
 *
 * Opening it is not in here — pressing the row does that, and the panel is already showing it.
 * Reading somebody else's knowledge base offers nothing at all: it is read-only exactly as far as
 * `KnowledgeAdminTools` goes, and the server refuses a write naming an owner as well, so this is
 * only about not offering one.
 */
export function documentActions(entry, options) {
  if (options.readOnly) return [];
  return [
    options.tenant && {
      label: t(entry.scope === 'tenant' ? 'knowledge.unshare' : 'knowledge.share'),
      onSelect: () => moveDocument(entry, options),
    },
    {
      label: t('knowledge.delete'),
      danger: true,
      onSelect: () => deleteDocument(entry, options),
    },
  ].filter(Boolean);
}

/**
 * Between the caller's own knowledge base and the company's.
 *
 * Re-embeds every chunk on the way, so it is not instant on a large document. The menu it was
 * chosen from has closed by now and there is no button left to spin, so what says it is happening
 * is a toast that stays up until it is not.
 */
export function moveDocument(entry, options) {
  const target = entry.scope === 'tenant' ? 'own' : 'tenant';
  const done = working(t('knowledge.moving'));
  return attempt(async () => {
    // The id travels in the body, never in the path: a document indexed from a file is identified
    // by its absolute path, and a path inside a path is not a route.
    await api('/api/knowledge', {
      method: 'PATCH',
      body: JSON.stringify({ docId: entry.docId, scope: target }),
    });
    await options.refresh();
    toast(t('knowledge.moved'), 'settled', 2500);
  }).finally(done);
}

export function deleteDocument(entry, options) {
  return confirmAction({
    title: t('knowledge.delete.title'),
    body: t('knowledge.delete.confirm'),
    action: t('knowledge.delete.action'),
    run: async () => {
      await api(`/api/knowledge?docId=${encodeURIComponent(entry.docId)}`, { method: 'DELETE' });
      // Off the document that no longer exists before the list is fetched again, or the panel
      // would redraw against an entry that is on its way out.
      if (options.selected) go(knowledgeRoute());
      await options.refresh();
      toast(t('knowledge.deleted'), 'settled', 2500);
    },
  });
}
