// The preferences menu at the foot of the sidebar: the theme, and the language.
//
// Both used to stand open down there — a segmented control for one, a menu button for the other —
// which spent the sidebar's last inch on two decisions that are made once and then never again. One
// button holds both, and the row it leaves behind is the thing that actually belongs there: who is
// signed in, and the way out.
//
// The two sets share a menu but not a heading, because a run of ticked rows with nothing saying
// which tick answers which question is unreadable. See openMenu for what a heading is.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { toggleMenu } from './menu.js';
import { setTheme, theme, themeChoices } from './theme.js';
import { chooseLanguage, language, languageName, languageTags } from './language.js';

export function initSettings() {
  const button = $('settings-button');
  // Built on each press rather than once, so the labels are in whichever language was last chosen
  // and the ticks are on whatever is in force — including a theme the system changed underneath.
  button.addEventListener('click', () => toggleMenu(button, items));
}

function items() {
  const out = [{ heading: t('theme') }];
  themeChoices().forEach((choice) => out.push({
    label: t(`theme.${choice}`),
    checked: theme() === choice,
    onSelect: () => setTheme(choice),
  }));

  // Only where there is a choice to make. A deployment serving one language would otherwise get a
  // heading and a single row that is already ticked, which says the switcher is broken.
  if (languageTags().length > 1) {
    out.push({ separator: true }, { heading: t('language') });
    languageTags().forEach((tag) => out.push({
      label: languageName(tag),
      code: tag,
      checked: language() === tag,
      onSelect: () => chooseLanguage(tag),
    }));
  }
  return out;
}
