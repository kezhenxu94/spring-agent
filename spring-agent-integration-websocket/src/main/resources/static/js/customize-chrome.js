// The one thing Customize's three tabs share: what happens to the panel when one of them opens
// something.
//
// Its own module rather than a function in customize.js because of the layering rule at the top of
// state.js. customize.js imports the three tabs, so a tab cannot import customize.js back — the
// cycle would not fail, it would resolve this binding to `undefined` and throw on whichever tab
// somebody clicked first. This sits earlier than all of them instead, which is what a shared
// backward edge looks like when it is not worth a bus event.

import { $ } from './dom.js';
import { openDetail } from './detail.js';

/**
 * Puts the panel into the state where it *is* the thing being read, or back out of it.
 *
 * The intro and the tab strip both go, because a skill's file tree, a memory's text and a server's
 * form each want the whole column: they are what the person came for, and a strip offering to
 * leave sits above them saying nothing about where they are. Coming back out is one press on the
 * tab's own back control, which is the same gesture in all three.
 */
export function openCustomizeDetail(open) {
  openDetail('customize-panel', 'customize-intro', open);
  $('customize-tabs').hidden = open;
}
