// The language menu.
//
// What has to be redrawn when the choice changes is not this module's business — it announces the
// change and app.js decides who hears it, which is what keeps a new panel from having to be
// remembered here.

import { applyTranslations, LANGUAGE_NAMES, locale, setLocale } from './i18n.js';
import { $ } from './dom.js';
import { relabelTheme } from './theme.js';
import { bus } from './state.js';

export function initLanguage(me) {
  // The server already resolved this from the cookie or Accept-Language. Following it rather than
  // deciding again keeps the page and the server's own messages in one language.
  setLocale(me.locale);

  const button = $('language-button');
  const menu = $('language-menu');
  const current = $('language-current');
  const tags = me.locales && me.locales.length ? me.locales : ['en'];

  const chosen = () => tags.find((tag) => tag.split('-')[0] === locale()) || me.locale || tags[0];

  const close = () => {
    menu.classList.add('hidden');
    button.setAttribute('aria-expanded', 'false');
  };
  const open = () => {
    menu.classList.remove('hidden');
    button.setAttribute('aria-expanded', 'true');
    menu.querySelector('button')?.focus();
  };

  const choose = (tag) => {
    // The same cookie Spring's CookieLocaleResolver reads, so the choice is what the *server* uses
    // for its own messages too — setting only a JavaScript variable would leave the page translated
    // and its error messages not.
    const oneYear = 365 * 24 * 60 * 60;
    document.cookie = `SPRING_AGENT_LOCALE=${encodeURIComponent(tag)};path=/;max-age=${oneYear};samesite=lax`;
    setLocale(tag);
    applyTranslations();
    draw();
    bus.emit('language:changed');
    close();
    button.focus();
  };

  const draw = () => {
    current.textContent = locale().toUpperCase();
    menu.replaceChildren();
    tags.forEach((tag) => {
      const item = document.createElement('li');
      item.setAttribute('role', 'none');
      const entry = document.createElement('button');
      entry.type = 'button';
      entry.setAttribute('role', 'menuitemradio');
      const on = tag === chosen();
      entry.setAttribute('aria-checked', String(on));
      entry.className = 'menu-item';
      const tick = document.createElement('span');
      tick.className = 'menu-tick';
      tick.textContent = on ? '✓' : '';
      const label = document.createElement('span');
      label.textContent = LANGUAGE_NAMES[tag.split('-')[0]] || tag;
      const code = document.createElement('span');
      code.className = 'menu-code';
      code.textContent = tag;
      entry.append(tick, label, code);
      entry.addEventListener('click', () => choose(tag));
      item.append(entry);
      menu.append(item);
    });
  };

  button.addEventListener('click', (event) => {
    event.stopPropagation();
    if (menu.classList.contains('hidden')) open(); else close();
  });
  // Dismissed the two ways every menu is expected to be.
  document.addEventListener('click', (event) => {
    if (!menu.classList.contains('hidden') && !menu.contains(event.target)) close();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !menu.classList.contains('hidden')) {
      close();
      button.focus();
    }
  });

  draw();
  applyTranslations();
  // Directly rather than over the bus: at startup nothing else is drawn yet, and announcing a
  // change nobody made would have the lists fetched a second time before the first has landed.
  relabelTheme();
}
