// What a deployment that runs this page as its own product looks like: the mark beside the name in
// the sidebar, and the icon on the browser tab.
//
// Its name is a translated string and lives in the bundles (see i18n.js); an image is not, so it
// comes from configuration alone and arrives with /api/me — the first response this page ever
// renders from, since somebody with no session is redirected to the identity provider rather than
// shown a login screen of ours. Both values are what the deployment configured for itself, never
// anything a user typed, and both go somewhere an image is fetched from and nothing is executed.
//
// Blank means nobody replaced it: the shipped mark stays rather than being emptied, which is what
// makes this one rule for both values and no special case at the server.

import { $ } from './dom.js';

export function applyBrand(brand) {
  if (!brand) return;

  const logo = String(brand.logo || '').trim();
  if (logo) {
    const shipped = $('brand-mark');
    const mark = document.createElement('img');
    mark.id = 'brand-mark';
    mark.src = logo;
    // The name is right next to it and says the same thing, so the image is decoration and a
    // screen reader is better off skipping it than reading a filename.
    mark.alt = '';
    mark.setAttribute('aria-hidden', 'true');
    // The shipped mark's box, and object-contain on top of it: a logo nobody cropped to a square
    // is letterboxed into that box rather than stretched, and the row keeps its height either way.
    mark.className = 'size-6 shrink-0 object-contain';
    shipped.replaceWith(mark);
  }

  const favicon = String(brand.favicon || '').trim();
  if (favicon) $('favicon').href = favicon;
}
