// What a signed-in person who this deployment does not serve sees.
//
// Without this they got a page of failed requests and a bare 403, which is indistinguishable from
// the server being broken — so the first thing anybody did was file a bug against the wrong thing.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { csrfToken } from './api.js';
import { renderStatus } from './status.js';

export function renderDenied(me) {
  $('sidebar').remove();
  document.querySelector('main .border-t')?.remove();
  const transcript = $('transcript');
  transcript.replaceChildren();

  const box = document.createElement('div');
  box.className = 'mx-auto flex h-full max-w-[34rem] flex-col justify-center gap-4 pb-16';

  const label = document.createElement('span');
  label.className = 'font-mono text-[10px] font-medium uppercase tracking-[0.14em] text-alarm';
  label.textContent = t('denied.label');

  const heading = document.createElement('h2');
  heading.className = 'font-display text-[24px] font-semibold leading-tight tracking-tight';
  heading.textContent = t('denied.title');

  const body = document.createElement('p');
  body.className = 'text-[14px] leading-relaxed text-mist';
  body.textContent = t('denied.body');

  // The tenant is the whole of the diagnosis, so it is on the page rather than only in the log.
  const facts = document.createElement('dl');
  facts.className = 'grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 font-mono text-[11px]';
  [[t('denied.you'), me.name || me.userId], [t('denied.tenant'), me.tenantId || '—']]
    .forEach(([key, value]) => {
      const dt = document.createElement('dt');
      dt.className = 'text-mist';
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value;
      facts.append(dt, dd);
    });

  const out = document.createElement('form');
  out.method = 'post';
  out.action = '/logout';
  const token = document.createElement('input');
  token.type = 'hidden';
  token.name = '_csrf';
  token.value = csrfToken();
  const button = document.createElement('button');
  button.type = 'submit';
  button.className = 'rounded-lg border border-zinc-300 px-3 py-1.5 text-[13px] '
    + 'hover:border-zinc-400 dark:border-edge dark:hover:border-mist';
  button.textContent = t('denied.signout');
  out.append(token, button);

  box.append(label, heading, body, facts, out);
  transcript.append(box);
  renderStatus('failed');
}
