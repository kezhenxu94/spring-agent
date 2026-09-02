// The small things every part of the page uses to draw itself.

import { locale } from './i18n.js';

/** By id, because that is how this page addresses everything it did not just create. */
export const $ = (id) => document.getElementById(id);

// Icons, at 16 and stroked in currentColor so they take the button's own state.
const ICONS = {
  auto: '<rect x="2.4" y="2.8" width="11.2" height="8.2" rx="1.5"/><path d="M6 13.6h4"/>',
  light: '<circle cx="8" cy="8" r="2.9"/><path d="M8 1.6v1.4M8 13v1.4M3.5 3.5l1 1M11.5 11.5l1 1'
    + 'M1.6 8H3M13 8h1.4M3.5 12.5l1-1M11.5 4.5l1-1"/>',
  dark: '<path d="M13.4 9.7A5.8 5.8 0 0 1 6.3 2.6a5.8 5.8 0 1 0 7.1 7.1Z"/>',
};

export function icon(name, size = 15) {
  return `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"
    stroke-linecap="round" stroke-linejoin="round" class="size-[${size}px]"
    aria-hidden="true">${ICONS[name]}</svg>`;
}

/** Long enough to cover a commit's own keydown arriving after it, short enough to feel absent. */
const COMPOSITION_TAIL = 80;

let composedAt = -Infinity;

/**
 * Whether a Return press is the one that acts, or one an input method is still using.
 *
 * Typing Chinese, Japanese or Korean means typing latin letters into the input method and pressing
 * Return to accept what it made of them — often the latin letters themselves, when none of the
 * candidates is what was wanted. That Return reaches the page as a keydown like any other, so a
 * handler that only asks for `Enter` sends `doc` the moment somebody meant to keep typing.
 *
 * Asking `isComposing` is not enough on its own, and this is the part that bites on macOS. The
 * committing key ends the composition and fires Return, but the order of the two is the browser's
 * business: WebKit ends the composition first, so by the time the keydown arrives `isComposing` is
 * already false and the press looks exactly like a deliberate one. So a composition that has only
 * just ended counts as still going. Nobody commits an input method's text and means to send within
 * the same handful of milliseconds — the commit was itself a keypress, and a second one cannot
 * follow it that fast.
 *
 * Every Return this page acts on has to ask, which is why it is one function here rather than a
 * condition remembered in three places.
 */
export const submits = (event) => event.key === 'Enter'
  && !event.isComposing
  && performance.now() - composedAt > COMPOSITION_TAIL;

// One listener for every input on the page, registered once and in the capture phase so nothing on
// the way up can stop this from seeing a composition end.
document.addEventListener('compositionend', () => {
  composedAt = performance.now();
}, true);

export function humanSize(bytes) {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

/**
 * A moment, as short as it can be and still be unambiguous: the clock for something today, the day
 * for something this year, the date for anything older.
 *
 * Relative wording — "2h ago" — reads better for the top of a list and worse everywhere else: it
 * needs a phrase per language and per unit, and it goes stale in a tab left open, which for a list
 * whose whole point is that a run outlives the page is the wrong way round. The browser's own
 * formatting is localised already, so this only has to choose how much of it to show.
 *
 * The full moment goes in the title, because the short form is a hint and somebody looking twice
 * wants the answer.
 */
export function shortTime(value) {
  const when = moment(value);
  if (!when) return '';
  const tag = locale() === 'zh' ? 'zh-CN' : 'en';
  const now = new Date();
  const sameDay = when.getFullYear() === now.getFullYear()
    && when.getMonth() === now.getMonth()
    && when.getDate() === now.getDate();
  // `numeric` on the hour rather than `2-digit`: en renders 12-hour with a meridiem, so a leading
  // zero is a character of width spent on nothing in a row this narrow.
  if (sameDay) return when.toLocaleTimeString(tag, { hour: 'numeric', minute: '2-digit' });
  if (when.getFullYear() === now.getFullYear()) {
    return when.toLocaleDateString(tag, { month: 'short', day: 'numeric' });
  }
  return when.toLocaleDateString(tag, { year: 'numeric', month: 'numeric', day: 'numeric' });
}

export function fullTime(value) {
  const when = moment(value);
  return when ? when.toLocaleString(locale() === 'zh' ? 'zh-CN' : 'en') : '';
}

/**
 * A date, or nothing.
 *
 * A row may legitimately carry no time — the server sends null for a conversation that has none —
 * and `new Date` turns anything it cannot read into an Invalid Date whose `toLocaleString` is the
 * words "Invalid Date". In a sidebar row that is worse than an empty space, so both become one.
 */
function moment(value) {
  if (!value) return null;
  const when = new Date(value);
  return Number.isNaN(when.getTime()) ? null : when;
}

export function scrollToEnd(force) {
  const transcript = $('transcript');
  // Only when the reader is already at the bottom, so scrolling up to read something earlier is not
  // undone by the next delta.
  const atBottom = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 140;
  if (force || atBottom) transcript.scrollTop = transcript.scrollHeight;
}
