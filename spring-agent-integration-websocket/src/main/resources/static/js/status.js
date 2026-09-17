// What the run is doing, remembered.
//
// This drew a strip in the header until the conversation row's live dot made it the same fact
// twice. What is left is the value itself, which is not dead with the strip: stream.js asks whether
// it was already reattaching before it says "reattached", because a reconnect that nobody noticed
// is not worth a toast. Everything else about a run is said where it happens — a question in the
// transcript, a failure in a toast — so there is nothing here to redraw on a language change.

import { state } from './state.js';

export function setStatus(kind) {
  state.status = kind;
}
