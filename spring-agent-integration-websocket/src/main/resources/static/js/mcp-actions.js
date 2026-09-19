// Everything on the MCP page that changes something, in one place.
//
// The same shape the other two tabs' action modules have: each ends by announcing `mcp:changed`
// rather than redrawing anything, because an action sits earlier in the layering than the section
// that owns the list.
//
// One of these is not like the others. `saveServer` is slow — the server validates the URL, checks
// the tool prefix against everything this person can reach, and then actually connects and asks the
// endpoint what it offers, all before storing a row. That is deliberate and is the whole value of
// the call: a server that was never reached is a row whose tools a later run fails to assemble, and
// the failure lands on a conversation rather than on whoever typed the URL. So the caller is
// expected to show a waiting state, and the answer carries the tool names the probe found.

import { t } from './i18n.js';
import { api } from './api.js';
import { bus } from './state.js';
import { toast } from './toast.js';
import { confirmAction } from './confirm.js';

/** The sentinel a share uses to mean everybody, rather than one open_id or chat_id. */
export const EVERYONE = '*';

export function fetchServers() {
  return api('/api/mcp');
}

/**
 * Registers a server, or replaces one of the same name.
 *
 * `headers` left out entirely means "keep whatever is stored", which is what the form sends when
 * nobody touched the authentication — it never had the values to send back, because the server
 * hands out header *names* and never their contents. An empty object is how they are cleared.
 * Without that distinction, opening a server to correct its URL and pressing save would quietly
 * drop its credential, and the next run would get a 401 from a server that had been working.
 */
export async function saveServer(server) {
  const saved = await api('/api/mcp', { method: 'POST', body: JSON.stringify(server) });
  const tools = saved.tools || [];
  toast(tools.length
    ? t('mcp.saved.tools', server.name, tools.length)
    : t('mcp.saved', server.name), 'settled');
  bus.emit('mcp:changed');
  return saved;
}

export async function setEnabled(name, enabled) {
  const changed = await api('/api/mcp/enabled', {
    method: 'PATCH',
    body: JSON.stringify({ name, enabled }),
  });
  toast(t(enabled ? 'mcp.enabled' : 'mcp.disabled', name), 'settled');
  bus.emit('mcp:changed');
  return changed;
}

/**
 * Shares a server, having asked first where the target is everyone.
 *
 * Only there. Sharing with one colleague is a thing somebody can undo by looking at the list they
 * just changed; `*` hands a credentialed connection to every account this deployment serves, and
 * nothing on the list afterwards says how many people that is.
 */
export async function shareServer(name, target) {
  if (target === EVERYONE) {
    return confirmAction({
      title: t('mcp.share.everyone.confirm', name),
      body: t('mcp.share.everyone.body'),
      action: t('mcp.share.everyone.do'),
      run: () => share(name, target),
    });
  }
  await share(name, target);
  return true;
}

async function share(name, target) {
  await api('/api/mcp/share', { method: 'POST', body: JSON.stringify({ name, target }) });
  toast(t('mcp.shared', name, target === EVERYONE ? t('mcp.share.everyone') : target), 'settled');
  bus.emit('mcp:changed');
}

export async function unshareServer(name, target) {
  const where = new URLSearchParams({ name, target });
  await api(`/api/mcp/share?${where}`, { method: 'DELETE' });
  toast(t('mcp.unshared', name), 'settled');
  bus.emit('mcp:changed');
}

export function removeServer(name) {
  return confirmAction({
    title: t('mcp.delete.confirm', name),
    body: t('mcp.delete.body'),
    action: t('mcp.delete'),
    run: async () => {
      await api(`/api/mcp?${new URLSearchParams({ name })}`, { method: 'DELETE' });
      toast(t('mcp.deleted', name), 'settled');
      bus.emit('mcp:changed');
    },
  });
}
