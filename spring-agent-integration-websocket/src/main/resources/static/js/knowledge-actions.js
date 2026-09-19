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
 *
 * Somebody else's knowledge base offers one thing rather than everything or nothing: it can be
 * deleted from, and it cannot be written into. An event source's owner is an identity nobody logs
 * in as, so the playbooks a run stored under it have no other way out — where moving one would
 * only ever mean moving it into the admin's own company knowledge base, which is not what "share
 * this" means about a document that is not theirs. The server draws the same line; this is only
 * about not offering what it would refuse.
 *
 * A company document is the other case of the same thing: where this deployment keeps the company
 * knowledge base to its administrators, sharing into it and deleting out of it are both refused,
 * so neither is drawn — while everything about reading it stays exactly as it was.
 */
export function documentActions(entry, options) {
  const company = entry.scope === 'tenant';
  // Both directions, because both ends of a move are writes: taking a document out of the company
  // base removes it from everybody exactly as a delete would.
  const mayShare = options.tenant && options.tenantWritable;
  return [
    !options.readOnly && mayShare && {
      label: t(company ? 'knowledge.unshare' : 'knowledge.share'),
      onSelect: () => moveDocument(entry, options),
    },
    (!company || options.tenantWritable) && {
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
    // by its absolute path, and a path inside a path is not a route. `from` goes with it because
    // an id names a document only together with the knowledge base holding it — the same file can
    // be in both of these.
    await api('/api/knowledge', {
      method: 'PATCH',
      body: JSON.stringify({ docId: entry.docId, from: entry.scope, scope: target }),
    });
    await options.refresh();
    toast(t('knowledge.moved'), 'settled', 2500);
  }).finally(done);
}

export function deleteDocument(entry, options) {
  return confirmAction({
    title: t('knowledge.delete.title'),
    // Whose it is, where it is not the reader's own. An admin deleting from somebody else's
    // knowledge base is the one thing on this page that acts on a stranger's documents, and the
    // list it was pressed from says whose only in a sentence above it.
    body: options.owner
      ? t('knowledge.delete.confirm.owner', options.owner)
      : t('knowledge.delete.confirm'),
    action: t('knowledge.delete.action'),
    run: async () => {
      // Scoped for the same reason the move is: deleting by id alone could not say which of two
      // knowledge bases holding that id was meant.
      const params = new URLSearchParams({ docId: entry.docId, scope: entry.scope });
      // The one write on this page that names somebody: the knowledge base being read belongs to
      // an identity nobody logs in as, and the server allows it only for an admin — and only into
      // that identity's own base, never a company one.
      if (options.owner) params.set('owner', options.owner);
      await api(`/api/knowledge?${params}`, { method: 'DELETE' });
      // Off the document that no longer exists before the list is fetched again, or the panel
      // would redraw against an entry that is on its way out.
      if (options.selected) go(knowledgeRoute());
      await options.refresh();
      toast(t('knowledge.deleted'), 'settled', 2500);
    },
  });
}
