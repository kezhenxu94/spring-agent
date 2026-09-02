// Anything that fails has to be visible. Before this, a rejected request threw into a promise
// nobody caught and the page simply did nothing — which looks exactly like a request that worked.

import { t } from './i18n.js';
import { $ } from './dom.js';

export function toast(message, tone = 'alarm', ttl = 8000) {
  let host = $('toasts');
  if (!host) {
    host = document.createElement('div');
    host.id = 'toasts';
    document.body.append(host);
  }
  const node = document.createElement('div');
  node.className = `toast toast-${tone}`;
  node.setAttribute('role', tone === 'alarm' ? 'alert' : 'status');

  const mark = document.createElement('span');
  mark.className = 'toast-mark';
  mark.textContent = tone === 'alarm' ? '!' : '✓';

  const text = document.createElement('span');
  text.textContent = message;

  const close = document.createElement('button');
  close.className = 'toast-close';
  close.type = 'button';
  close.setAttribute('aria-label', t('toast.dismiss'));
  close.textContent = '×';

  const dismiss = () => {
    node.classList.add('toast-leaving');
    node.addEventListener('animationend', () => node.remove(), { once: true });
  };
  close.addEventListener('click', dismiss);
  node.append(mark, text, close);
  host.append(node);
  if (ttl) setTimeout(dismiss, ttl);
  return node;
}

/** Runs an action and surfaces whatever it throws, rather than losing it to an unhandled rejection. */
export async function attempt(action, fallback) {
  try {
    return await action();
  } catch (error) {
    if (error && error.handled) return undefined;
    toast(error?.message || fallback || t('error.generic'));
    return undefined;
  }
}
