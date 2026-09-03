// Light, dark, or whatever the system says.
//
// The choice is stored and applied here; what it looks like to choose is the preferences menu's
// business — see settings.js. Keeping the two apart is what lets the same three values be offered
// from a menu now and from something else later without touching the rule that decides `dark`,
// which the inline script in index.html also has to agree with before first paint.

const KEY = 'spring-agent-theme';

const CHOICES = ['auto', 'light', 'dark'];

let chosen = 'auto';

function apply(choice) {
  const dark = choice === 'dark'
    || (choice === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  document.documentElement.classList.toggle('dark', dark);
}

export function themeChoices() {
  return CHOICES;
}

export function theme() {
  return chosen;
}

export function setTheme(choice) {
  chosen = CHOICES.includes(choice) ? choice : 'auto';
  try { localStorage.setItem(KEY, chosen); } catch (e) { /* private mode */ }
  apply(chosen);
}

export function initTheme() {
  try { chosen = localStorage.getItem(KEY) || 'auto'; } catch (e) { /* private mode */ }
  if (!CHOICES.includes(chosen)) chosen = 'auto';
  apply(chosen);

  // Following the system while set to auto, so a desktop that switches at sunset takes the page
  // with it without a reload.
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (chosen === 'auto') apply('auto');
  });
}
