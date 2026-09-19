// Everything on the memories page that changes something, in one place.
//
// The same shape skills-actions.js has, and for the same reason: an action sits earlier in the
// layering than the section that owns the list, so it announces `memories:changed` rather than
// redrawing anything itself. What a delete leaves behind depends on where the reader is, which is
// the section's business and not the delete's.

import { t } from './i18n.js';
import { api } from './api.js';
import { bus } from './state.js';
import { toast } from './toast.js';
import { confirmAction } from './confirm.js';

/** The query string every one of these endpoints is addressed by. */
function where(scope, path) {
  const params = new URLSearchParams({ scope });
  if (path) params.set('path', path);
  return params.toString();
}

export function fetchMemories(scope) {
  return api(`/api/memories?${where(scope)}`);
}

export function fetchMemory(scope, path) {
  return api(`/api/memories/file?${where(scope, path)}`);
}

export async function saveMemory(scope, path, text) {
  const saved = await api('/api/memories/file', {
    method: 'PUT',
    body: JSON.stringify({ scope, path, text }),
  });
  toast(t('memories.saved', path), 'settled');
  bus.emit('memories:changed');
  return saved;
}

/**
 * Deletes one memory, having asked.
 *
 * Answers whether it went ahead, because the caller has to know whether what it was showing is
 * still there. Confirmed rather than undoable: nothing here keeps a copy, and a memory is prose
 * somebody may have been building on for months.
 */
export function deleteMemory(scope, path) {
  return confirmAction({
    title: t('memories.delete.confirm', path),
    body: t('memories.delete.body'),
    action: t('memories.delete'),
    run: async () => {
      await api(`/api/memories/file?${where(scope, path)}`, { method: 'DELETE' });
      toast(t('memories.deleted', path), 'settled');
      bus.emit('memories:changed');
    },
  });
}
