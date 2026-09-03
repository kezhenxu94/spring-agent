// The card the two non-conversation sections both open into.
//
// A stored document and a scheduled task are the same kind of object to the person reading them —
// something the agent keeps between runs — so they are drawn by the same code rather than by two
// panels that were each written to look a bit like the other. What differs is what goes in the
// slots; see detail.css for the shape and for why the name line is not the same fact in both.

import { $ } from './dom.js';
import { menuButton } from './menu.js';

/**
 * The head: an eyebrow saying what sort of record this is, the pill that classifies it, the menu of
 * everything that can be done to it, and the one line that names it.
 *
 * `actions` is the label and the item builder for the menu, or nothing where there is nothing this
 * reader may do — an admin reading somebody else's knowledge base, say.
 */
export function detailHead({ kind, pill, pillFilled, name, actions }) {
  const host = document.createElement('div');
  host.className = 'detail-head';

  const line = document.createElement('div');
  line.className = 'detail-kind';
  const what = document.createElement('span');
  what.className = 'detail-label';
  what.textContent = kind;
  line.append(what);

  if (pill) {
    const badge = document.createElement('span');
    badge.className = 'detail-pill' + (pillFilled ? ' detail-pill-filled' : '');
    badge.textContent = pill;
    line.append(badge);
  }
  // Built on each press, so the menu follows the state the card is in — whether it is being edited,
  // whether the reader owns what they are looking at.
  if (actions && actions.items().length) line.append(menuButton(actions.label, actions.items));

  const title = document.createElement('h2');
  title.className = 'detail-name';
  title.textContent = name;
  host.append(line, title);
  return host;
}

/**
 * The spec sheet. Rows are `[key, value, mono]`, and anything falsy is dropped so a caller can
 * write a conditional row inline.
 */
export function detailFacts(rows) {
  const host = document.createElement('dl');
  host.className = 'detail-facts';
  rows.filter(Boolean).forEach(([key, value, mono]) => {
    const term = document.createElement('dt');
    term.className = 'detail-label';
    term.textContent = key;
    const said = document.createElement('dd');
    if (mono) said.className = 'detail-fact-mono';
    said.textContent = value;
    host.append(term, said);
  });
  return host;
}

/** The body, under its own caption. `content` is whatever the section draws into it. */
export function detailBody(caption, ...content) {
  const box = document.createElement('section');
  box.className = 'detail-text-box';
  const label = document.createElement('p');
  label.className = 'detail-label';
  label.textContent = caption;
  box.append(label, ...content);
  return box;
}

/**
 * Puts a panel into the state where it *is* the record it is showing, or back out of it.
 *
 * One call rather than three lines repeated in each section, because getting one of the three wrong
 * is not a visible mistake until somebody opens the section a second time.
 */
export function openDetail(panel, intro, open) {
  $(panel).classList.toggle('detail-open', open);
  $(intro).hidden = open;
}
