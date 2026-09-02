// What the page shows while it is waiting for the server.
//
// A fetch that draws nothing until it lands looks exactly like a page that has finished and found
// nothing — the same failure the toasts exist to fix, one step earlier. So anything that clears a
// list or a panel before asking for its contents puts something in the hole first, shaped like what
// is coming, and takes it away again whether the request worked or not.
//
// Placeholders rather than a spinner wherever the shape is known: a list of rows returns rows, and
// a silhouette of them tells the reader what is arriving as well as that something is. A spinner is
// for the cases with no shape to promise — one button's work, one panel's contents.
//
// Every function here hands back the way to undo it, so a caller can put the removal in a `finally`
// and never leave a skeleton standing over a request that failed.

import { t } from './i18n.js';

/**
 * Placeholder rows in a list, replacing whatever it holds.
 *
 * The widths vary because rows of identical length read as a rendered table rather than as
 * something on its way.
 */
export function skeletonList(host, rows = 4) {
  if (!host) return () => {};
  const made = [];
  for (let index = 0; index < rows; index += 1) {
    const item = document.createElement('li');
    item.className = 'skeleton-row';
    item.setAttribute('aria-hidden', 'true');
    const bar = document.createElement('span');
    bar.className = 'skeleton';
    bar.style.width = `${[88, 62, 75, 54, 80][index % 5]}%`;
    item.append(bar);
    made.push(item);
  }
  host.replaceChildren(...made);
  host.setAttribute('aria-busy', 'true');
  return () => {
    made.forEach((item) => item.remove());
    host.removeAttribute('aria-busy');
  };
}

/**
 * Placeholder turns in the transcript, in the shape a conversation has: something short from the
 * person on the right, something long from the agent on the left.
 */
export function skeletonTranscript(host) {
  if (!host) return () => {};
  const block = document.createElement('div');
  block.className = 'mx-auto max-w-[46rem] space-y-6';
  block.setAttribute('aria-hidden', 'true');
  [['right', [40]], ['left', [92, 84, 60]]].forEach(([side, widths]) => {
    const turn = document.createElement('div');
    turn.className = side === 'right'
      ? 'flex justify-end' : 'space-y-2 pl-[3.25rem]';
    widths.forEach((width) => {
      const bar = document.createElement('span');
      bar.className = 'skeleton block h-3.5';
      bar.style.width = `${width}%`;
      turn.append(bar);
    });
    block.append(turn);
  });
  host.append(block);
  host.setAttribute('aria-busy', 'true');
  return () => {
    block.remove();
    host.removeAttribute('aria-busy');
  };
}

/** A spinner and a word, for a panel whose contents have no shape worth promising. */
export function loading(labelKey = 'busy.loading') {
  const block = document.createElement('div');
  block.className = 'busy-block';
  block.setAttribute('role', 'status');
  block.append(spinner(), document.createTextNode(t(labelKey)));
  return block;
}

/** The spinner on its own, for putting inside something that already says what it is doing. */
export function spinner() {
  const mark = document.createElement('span');
  mark.className = 'spinner';
  mark.setAttribute('aria-hidden', 'true');
  return mark;
}

/**
 * A button that is doing something: disabled, showing a spinner and — where one was given — what it
 * is doing. A button whose label is an icon is left as the spinner alone.
 *
 * What was in the button is kept as nodes and put back as nodes. Remembering the text instead is a
 * bug waiting on the first icon button: every control in the header and the composer is an svg plus
 * a screen-reader label, and restoring `textContent` would leave the label where the icon was.
 *
 * Putting it back is the returned function's job rather than the caller's, because the caller that
 * forgets leaves a button saying "Indexing…" over a list that has already redrawn.
 */
export function busyButton(button, saying) {
  if (!button) return () => {};
  const kept = [...button.childNodes];
  const wasDisabled = button.disabled;
  button.disabled = true;
  button.setAttribute('aria-busy', 'true');
  button.replaceChildren(
    ...(saying ? [spinner(), document.createTextNode(saying)] : [spinner()]),
  );
  return () => {
    button.disabled = wasDisabled;
    button.removeAttribute('aria-busy');
    button.replaceChildren(...kept);
  };
}
