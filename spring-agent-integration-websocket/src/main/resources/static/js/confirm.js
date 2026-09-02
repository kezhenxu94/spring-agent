// Asking before something cannot be undone.
//
// `window.confirm` was doing this job and doing it badly: it is not translated, it cannot say which
// of the four dangerous things on this page is about to happen in the page's own voice, and it
// blocks the whole tab — including the run this page exists to keep watching.
//
// The work is done *inside* the dialog rather than after it. A confirmation that closes and leaves
// the caller to disable its own button is where the loading state goes missing: the row is still
// there, the button still looks pressable, and pressing it again sends the delete twice. Here the
// button that was pressed is the thing that spins, and a refusal is written where it was read
// rather than in a toast over a dialog that has already gone.

import { t } from './i18n.js';
import { busyButton } from './busy.js';

/**
 * Asks, and — if a `run` was given — carries the action out while the dialog is still up.
 *
 * @param title   what is about to happen, as a question
 * @param body    what it costs, in a sentence
 * @param action  the label on the button that does it
 * @param run     optional async work; the dialog stays open, busy, until it settles
 * @returns whether it went ahead
 */
export function confirmAction({ title, body, action, run }) {
  const dialog = document.createElement('dialog');
  dialog.className = 'dialog';

  const heading = document.createElement('h2');
  heading.className = 'dialog-title';
  heading.textContent = title;

  const text = document.createElement('p');
  text.className = 'dialog-body';
  text.textContent = body || '';

  const failure = document.createElement('p');
  failure.className = 'dialog-failure';
  failure.hidden = true;

  const buttons = document.createElement('div');
  buttons.className = 'dialog-buttons';
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'panel-action';
  cancel.textContent = t('confirm.cancel');
  const confirm = document.createElement('button');
  confirm.type = 'button';
  confirm.className = 'panel-action panel-action-danger';
  confirm.textContent = action || t('confirm.ok');
  buttons.append(cancel, confirm);

  dialog.append(heading, text, failure, buttons);
  document.body.append(dialog);

  return new Promise((resolve) => {
    let working = false;
    const close = (answer) => {
      dialog.close();
      dialog.remove();
      resolve(answer);
    };

    // Escape closes a dialog by itself, which is right until something is in flight — cancelling
    // then would leave the request running with nothing on screen saying so.
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      if (!working) close(false);
    });
    cancel.addEventListener('click', () => close(false));

    confirm.addEventListener('click', async () => {
      if (!run) {
        close(true);
        return;
      }
      working = true;
      failure.hidden = true;
      cancel.disabled = true;
      const done = busyButton(confirm, t('confirm.working'));
      try {
        await run();
        close(true);
      } catch (error) {
        // Written where it was read. api() has already redirected on a lost session, and there is
        // nothing to say about that one.
        if (error && error.handled) {
          close(false);
          return;
        }
        failure.textContent = error?.message || t('error.generic');
        failure.hidden = false;
        working = false;
        cancel.disabled = false;
        done();
      }
    });

    dialog.showModal();
    // The safe one, so a held Return does not confirm what it was never shown.
    cancel.focus();
  });
}
