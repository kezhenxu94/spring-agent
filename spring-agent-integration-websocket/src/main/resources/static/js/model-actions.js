// Everything on the Model tab that changes something, in one place.
//
// The same shape the other tabs' action modules have: each ends by announcing `model:changed`
// rather than redrawing anything, because an action sits earlier in the layering than the section
// that owns the list.
//
// `saveModel` is slow, for the same reason `saveServer` is on the MCP tab: the server tests the
// endpoint with the token and headers given before storing anything, so a bad token or URL is
// reported as a refusal rather than a row that fails on the first real message.

import { t } from './i18n.js';
import { api } from './api.js';
import { bus } from './state.js';
import { toast } from './toast.js';
import { confirmAction } from './confirm.js';

/** The name meaning "switch back to the application's own model". */
export const DEFAULT = 'default';

export function fetchModels() {
  return api('/api/models');
}

/**
 * Registers a model, or replaces one of the same name.
 *
 * `headers` left out entirely means "keep whatever is stored" — the same tri-state the MCP tab's
 * authentication field uses, and for the same reason: the server hands out header *names* and never
 * their values, so an untouched headers field must send nothing at all rather than clear the row's
 * headers on the next unrelated edit. The token has no such state: it is always required, and always
 * reseals the row, the same as re-registering a model over chat.
 */
export async function saveModel(model) {
  const saved = await api('/api/models', { method: 'POST', body: JSON.stringify(model) });
  toast(t('model.saved', model.name), 'settled');
  bus.emit('model:changed');
  return saved;
}

export async function activateModel(name) {
  const activated = await api(`/api/models/${encodeURIComponent(name)}/activate`, {
    method: 'PATCH',
  });
  toast(t(name === DEFAULT ? 'model.switched.default' : 'model.switched', name), 'settled');
  bus.emit('model:changed');
  return activated;
}

export function removeModel(name) {
  return confirmAction({
    title: t('model.delete.confirm', name),
    body: t('model.delete.body'),
    action: t('model.delete'),
    run: async () => {
      await api(`/api/models/${encodeURIComponent(name)}`, { method: 'DELETE' });
      toast(t('model.deleted', name), 'settled');
      bus.emit('model:changed');
    },
  });
}
