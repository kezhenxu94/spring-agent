// The one thing Customize's three tabs share: what happens to the panel, and to the bar above it,
// when one of them opens something.
//
// Its own module rather than a function in customize.js because of the layering rule at the top of
// state.js. customize.js imports the three tabs, so a tab cannot import customize.js back — the
// cycle would not fail, it would resolve this binding to `undefined` and throw on whichever tab
// somebody clicked first. This sits earlier than all of them instead, which is what a shared
// backward edge looks like when it is not worth a bus event.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { openDetail } from './detail.js';
import { sectionBar } from './panels.js';

/**
 * Puts the panel into the state where it *is* the thing being read, or back out of it.
 *
 * The intro and the tab strip both go, because a skill's file tree, a memory's text and a server's
 * form each want the whole column: they are what the person came for, and a strip offering to
 * leave sits above them saying nothing about where they are. Coming back out is one press on the
 * tab's own back control, which is the same gesture in all three.
 *
 * `bar` is what the title bar says while it is open — `{ title, label, onBack }`, the thing's own
 * name and the way back to the list it came from. Below md that bar is the only chrome this panel
 * has: the tab strip is gone, the sidebar is not holding this list, and the card's own head scrolls
 * away with the rest of it. Passing nothing is what closing looks like, and the bar goes back to
 * naming the section.
 */
export function openCustomizeDetail(open, bar) {
  openDetail('customize-panel', 'customize-intro', open);
  $('customize-tabs').hidden = open;
  if (open && bar) sectionBar(bar.title, { label: bar.label, onBack: bar.onBack });
  else sectionBar(t('customize.title'));
}
