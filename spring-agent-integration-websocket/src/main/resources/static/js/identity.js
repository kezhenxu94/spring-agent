// Who is signed in, at the foot of the sidebar.
//
// Three facts and one action: the avatar, the name, the id — shortened from the middle, because an
// OAuth subject is a long constant prefix and a short distinguishing tail — and the way to get the
// whole of that id back out, which is what a shortened one is otherwise no longer good for. It is
// the string somebody is asked for when a deployment has to be told who they are: the tenant it
// serves, the admin list, another person's knowledge base.

import { t } from './i18n.js';
import { $, middleTruncate } from './dom.js';
import { toast } from './toast.js';

export function renderIdentity(me) {
  const name = me.name || me.userId || '';
  $('me-name').textContent = name;
  $('me-name').title = name;
  $('me-id').textContent = middleTruncate(me.userId);
  $('me-id').title = me.userId || '';
  if (me.avatar) $('me-avatar').src = me.avatar;

  const copy = $('me-id-copy');
  copy.hidden = !me.userId;
  copy.addEventListener('click', () => put(me.userId, copy));
}

/**
 * The id on the clipboard.
 *
 * `navigator.clipboard` is not always there — it needs a secure context, and this page is
 * legitimately served over plain http on a laptop or inside a cluster. So the failure is said out
 * loud rather than swallowed: the id is on the element's title either way, and being told to copy
 * it by hand beats pressing a button that silently does nothing.
 */
async function put(value, button) {
  try {
    await navigator.clipboard.writeText(value);
  } catch (error) {
    toast(t('identity.copy.failed'));
    return;
  }
  button.dataset.copied = 'true';
  toast(t('identity.copied'), 'settled', 2000);
  window.setTimeout(() => delete button.dataset.copied, 1200);
}
