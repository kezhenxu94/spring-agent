// Which language the page and the server both answer in.
//
// The choice is stored and announced here; what it looks like to choose is the preferences menu's
// business — see settings.js.
//
// What has to be redrawn when the choice changes is not this module's business either: it announces
// the change and app.js decides who hears it, which is what keeps a new panel from having to be
// remembered here.

import { applyTranslations, LANGUAGE_NAMES, locale, setLocale } from './i18n.js';
import { bus } from './state.js';

let tags = ['en'];
let served = 'en';

export function initLanguage(me) {
  // The server already resolved this from the cookie or Accept-Language. Following it rather than
  // deciding again keeps the page and the server's own messages in one language.
  setLocale(me.locale);
  tags = me.locales && me.locales.length ? me.locales : ['en'];
  served = me.locale || tags[0];
}

/** Every language this deployment serves, as the tags the server named them by. */
export function languageTags() {
  return tags;
}

/** The tag currently in force, chosen from those — `locale()` is only its language. */
export function language() {
  return tags.find((tag) => tag.split('-')[0] === locale()) || served || tags[0];
}

export function languageName(tag) {
  return LANGUAGE_NAMES[tag.split('-')[0]] || tag;
}

export function chooseLanguage(tag) {
  // The same cookie Spring's CookieLocaleResolver reads, so the choice is what the *server* uses
  // for its own messages too — setting only a JavaScript variable would leave the page translated
  // and its error messages not.
  const oneYear = 365 * 24 * 60 * 60;
  document.cookie = `SPRING_AGENT_LOCALE=${encodeURIComponent(tag)};path=/;max-age=${oneYear};samesite=lax`;
  setLocale(tag);
  applyTranslations();
  bus.emit('language:changed');
}
