// The account menu at the foot of the sidebar: who is signed in, the two preferences that belong to
// them, and the way out.
//
// The line at the foot of the sidebar is a row like the ones above it — an avatar and a name — and
// the whole of it opens this menu. Nothing about an account stands open down there: the id, the
// theme, the language and sign out are all in here, and each of them is either a machine handle
// wanted while filling in a deployment's configuration or a decision made once and then never
// again. A strip of those spends the sidebar's last inch on something nobody is doing.
//
// The theme and the language are submenus rather than two sets of ticked rows in this one. They are
// not what the menu is for — it is opened to sign out or to copy an id far more often than to change
// a language — so each is one line saying what it currently is, and only the one the pointer reaches
// opens into its choices. The rows are built on each press, so those lines are in whichever language
// was last chosen and the ticks are on whatever is in force, including a theme the system changed
// underneath.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { toggleMenu } from './menu.js';
import { identityRow } from './identity.js';
import { setTheme, theme, themeChoices } from './theme.js';
import { chooseLanguage, language, languageName, languageTags } from './language.js';

export function initAccount() {
  const button = $('account-button');
  button.addEventListener('click', () => toggleMenu(button, items));
}

function items() {
  const who = identityRow();
  return [
    who && { node: who },
    who && { separator: true },
    { label: t('theme'), value: t(`theme.${theme()}`), submenu: themes },
    // Only where there is a choice to make. A deployment serving one language would otherwise get a
    // row that opens onto a single entry that is already ticked, which says the switcher is broken.
    languageTags().length > 1
      && { label: t('language'), value: languageName(language()), submenu: languages },
    { separator: true },
    // The one alarm colour in this menu, and the reason the colour exists: everything else here is
    // reversible by pressing it again. The form is in the markup so the POST carries the CSRF token
    // Spring's logout endpoint requires — see index.html.
    { label: t('nav.signout'), danger: true, onSelect: () => $('logout-form').submit() },
  ];
}

function themes() {
  return themeChoices().map((choice) => ({
    label: t(`theme.${choice}`),
    checked: theme() === choice,
    onSelect: () => setTheme(choice),
  }));
}

function languages() {
  return languageTags().map((tag) => ({
    label: languageName(tag),
    code: tag,
    checked: language() === tag,
    onSelect: () => chooseLanguage(tag),
  }));
}
