// Light, dark, or whatever the system says.

import { t } from './i18n.js';
import { $, icon } from './dom.js';

/** Set by initTheme, so a language switch can relabel the theme buttons without rebuilding them. */
let relabel = () => {};

export function initTheme() {
  const group = $('theme-switch');
  let stored = 'auto';
  try { stored = localStorage.getItem('spring-agent-theme') || 'auto'; } catch (e) { /* private mode */ }

  const apply = (choice) => {
    const dark = choice === 'dark'
      || (choice === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.toggle('dark', dark);
  };

  const buttons = ['auto', 'light', 'dark'].map((choice) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.theme = choice;
    button.className = 'seg';
    button.innerHTML = icon(choice);
    // Toggle buttons with aria-pressed rather than a radiogroup: a radiogroup promises arrow-key
    // navigation, and three tab stops is both simpler and no worse to use.
    button.addEventListener('click', () => {
      try { localStorage.setItem('spring-agent-theme', choice); } catch (e) { /* private mode */ }
      apply(choice);
      select(choice);
    });
    group.append(button);
    return button;
  });

  const select = (choice) => {
    buttons.forEach((button) => {
      const on = button.dataset.theme === choice;
      button.classList.toggle('seg-on', on);
      button.setAttribute('aria-pressed', String(on));
      button.title = t(`theme.${button.dataset.theme}`);
      button.setAttribute('aria-label', t(`theme.${button.dataset.theme}`));
    });
    group.dataset.theme = choice;
  };

  apply(stored);
  select(stored);

  // Following the system while set to auto, so a desktop that switches at sunset takes the page
  // with it without a reload.
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if ((group.dataset.theme || 'auto') === 'auto') apply('auto');
  });

  // Re-labelled when the language changes; the icons stay, their names do not.
  relabel = () => select(group.dataset.theme || 'auto');
}

/** Re-reads the button labels out of the current language. */
export function relabelTheme() {
  relabel();
}
