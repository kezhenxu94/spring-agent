// Who is signed in, at the foot of the sidebar.
//
// Three facts and one action: the avatar, the name, the id — shortened from the middle, because an
// OAuth subject is a long constant prefix and a short distinguishing tail — and the way to get the
// whole of that id back out, which is what a shortened one is otherwise no longer good for. It is
// the string somebody is asked for when a deployment has to be told who they are: the tenant it
// serves, the admin list, another person's knowledge base.
//
// The avatar and the name are the line at the foot of the sidebar; the id is the first row of the
// account menu — see account.js. It is a machine handle, wanted when somebody is filling in a
// deployment's configuration and at no other time, so the sidebar's last inch goes to the name.

import { t } from './i18n.js';
import { $, middleTruncate } from './dom.js';
import { toast } from './toast.js';

const SVG = 'http://www.w3.org/2000/svg';

let id = '';

export function renderIdentity(me) {
  const name = me.name || me.userId || '';
  $('me-name').textContent = name;
  $('me-name').title = name;
  if (me.avatar) $('me-avatar').src = me.avatar;
  id = me.userId || '';
}

/**
 * The id and the button that copies it, as a row for the account menu — or nothing, where the
 * server reported no id at all and there is nothing to show or to copy.
 *
 * Built on each press rather than held: the menu is removed when it closes, so a cached element
 * would carry whichever language the page was in when it was first drawn.
 */
export function identityRow() {
  if (!id) return null;

  const row = document.createElement('div');
  row.className = 'menu-identity';

  const shown = document.createElement('span');
  // Elided in the middle rather than at the end, so what survives is the tail — the part that
  // actually distinguishes one subject from another. The whole of it is on the title.
  shown.className = 'menu-identity-id';
  shown.textContent = middleTruncate(id);
  shown.title = id;

  const copy = document.createElement('button');
  copy.type = 'button';
  copy.className = 'id-copy';
  copy.title = t('identity.copy');
  copy.setAttribute('aria-label', t('identity.copy'));
  copy.append(clipboard());
  // The menu stays open on a copy, unlike every command in it: the tick this control puts on itself
  // is half of what says it worked, and a menu that shut would take that with it.
  copy.addEventListener('click', () => put(id, copy));

  row.append(shown, copy);
  return row;
}

function clipboard() {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.3');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'size-[11px]');
  const sheet = document.createElementNS(SVG, 'rect');
  sheet.setAttribute('x', '5.6');
  sheet.setAttribute('y', '5.6');
  sheet.setAttribute('width', '7.4');
  sheet.setAttribute('height', '7.4');
  sheet.setAttribute('rx', '1.6');
  const behind = document.createElementNS(SVG, 'path');
  behind.setAttribute(
    'd',
    'M10.4 3.6a1.6 1.6 0 0 0-1.6-1.6H4.6A1.6 1.6 0 0 0 3 3.6v4.2a1.6 1.6 0 0 0 1.6 1.6',
  );
  svg.append(sheet, behind);
  return svg;
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
