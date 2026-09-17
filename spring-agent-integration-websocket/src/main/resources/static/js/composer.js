// The box at the bottom: what it can send, and what it looks like while a run is going.

import { placeholder, t } from './i18n.js';
import { $, scrollToEnd, submits } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { busyButton } from './busy.js';
import { loadConversations, newConversation } from './conversations.js';
import { appendTurn } from './transcript.js';
import { removeQuestion } from './questions.js';
import { attachRun } from './stream.js';
import { renderAttachments } from './attachments.js';
import { state } from './state.js';

// Which conversation's toggle is stored where. Per conversation rather than one setting for the
// page: whether an answer belongs in a group chat is a fact about that conversation, and carrying
// one conversation's choice into the next would put somebody's private question in front of a
// group. Nothing on the server remembers it — see ChatController.Send.
const MIRROR_KEY = 'spring-agent-mirror';

function storedMirror(id) {
  if (!id) return false;
  try { return localStorage.getItem(`${MIRROR_KEY}:${id}`) === 'on'; } catch (e) { return false; }
}

function storeMirror(id, on) {
  if (!id) return;
  try { localStorage.setItem(`${MIRROR_KEY}:${id}`, on ? 'on' : 'off'); } catch (e) { /* private mode */ }
}

/** Whether the button can be offered at all, and what the chat it sends to is called. */
function mirrorSurface() {
  const mirror = state.me?.mirror;
  if (!mirror?.enabled) return null;
  // A surface this page has no mark for is not offered. The button *is* the platform's logo, so
  // there is nothing to draw for a name we do not recognise — and a generic icon standing in for
  // "some chat platform" would tell the reader less than no button at all. Adding one is adding
  // its mark to index.html and its name here.
  const name = t(`composer.mirror.surface.${mirror.surface}`);
  return name === `composer.mirror.surface.${mirror.surface}` ? null : name;
}

function paintMirror(surface) {
  const button = $('mirror');
  button.setAttribute('aria-pressed', state.mirroring ? 'true' : 'false');
  button.classList.toggle('tool-button-on', state.mirroring);
  button.title = t(state.mirroring ? 'composer.mirror.on' : 'composer.mirror.off', surface);
}

/**
 * Draws the toggle for the conversation now on screen.
 *
 * Called when the conversation changes, when /api/me lands, and when the language changes — the
 * title names the chat platform, so it has to be rewritten like any other label.
 */
export function refreshMirror() {
  const button = $('mirror');
  if (!button) return;
  const surface = mirrorSurface();
  button.hidden = !surface;
  // Off, not merely undrawn. Whatever is in state is what gets sent, so a page with no button must
  // not be able to leave a stale true behind it.
  state.mirroring = surface ? storedMirror(state.conversationId) : false;
  if (surface) paintMirror(surface);
}

function toggleMirror() {
  state.mirroring = !state.mirroring;
  storeMirror(state.conversationId, state.mirroring);
  paintMirror(mirrorSurface());
  // Turned on while a run is going, the run already going cannot be mirrored: a listener belongs to
  // a request, and that request has already been assembled. Said plainly rather than left to look
  // like a control that did nothing.
  if (state.mirroring && state.running) toast(t('run.mirror.next'), 'waiting', 4000);
}

/**
 * What is typed, as the person sees it.
 *
 * `innerText` rather than `textContent`, because the field is a contenteditable: a newline may be a
 * character in a text node or a <br> a browser left behind, and only innerText renders the second
 * as the line break it looks like. Read as text either way — nothing here ever touches innerHTML,
 * so the box cannot carry markup out of itself.
 */
function typed() {
  return $('composer').innerText;
}

/**
 * Puts text in the field: what a failed send gives back, and what clearing it is.
 *
 * `textContent` rather than innerHTML for the reason above, and it is enough — the field is
 * `white-space: pre-wrap`, so the newlines in a restored message are drawn as lines without any
 * markup to represent them.
 */
function setTyped(text) {
  const field = $('composer');
  if (text) field.textContent = text;
  else field.replaceChildren();
  refreshEmptiness();
  refreshSendState();
}

/**
 * Whether the placeholder is drawn. See the note on .composer-field[data-empty] for why this is an
 * attribute this sets rather than the `:empty` a stylesheet could ask about on its own.
 *
 * An element left behind counts as content — press Enter in an empty field and the caret is on a
 * second line, which is not a field to offer a placeholder over.
 */
function refreshEmptiness() {
  const field = $('composer');
  field.toggleAttribute('data-empty', !field.textContent.length && !field.firstElementChild);
}

/** Send is inert until there is something to send — a message, or a file to talk about. */
export function refreshSendState() {
  const send = $('send');
  if (!send) return;
  send.disabled = !typed().trim() && !state.attachments.length;
}

export function setRunning(running) {
  // Explicit display rather than toggling a `hidden` class: both buttons carry a display of their
  // own from .composer-action, and which of the two utilities wins would depend on the order
  // Tailwind happened to emit them in.
  $('send').style.display = running ? 'none' : 'grid';
  $('stop').style.display = running ? 'grid' : 'none';
  placeholder($('composer'), t(running ? 'composer.placeholder.running' : 'composer.placeholder'));
  refreshSendState();
}

export async function send() {
  const message = typed().trim();
  // A message that is only files is a legitimate one — "here, look at this".
  if (!message && !state.attachments.length) return;
  const attached = state.attachments.slice();
  // Named in the message rather than passed beside it, so what the model reads is exactly what the
  // person sees was sent. The directory is stated because that is where the agent's file tools look.
  const text = attached.length
    ? [message, t('composer.attach.note', attached.map((f) => f.name).join(', '))]
      .filter(Boolean).join('\n\n')
    : message;

  await attempt(async () => {
    if (!state.conversationId) {
      const created = await api('/api/conversations', { method: 'POST' });
      state.conversationId = created.id;
      // The toggle was set before this conversation existed, so it has nowhere stored yet. Written
      // now rather than left for the next click, or a reload would forget it.
      storeMirror(created.id, state.mirroring);
      await loadConversations();
    }
    // Cleared only once the request is on its way, so a failure does not also lose what they typed.
    const pending = text;
    setTyped('');
    state.attachments = [];
    renderAttachments();
    $('transcript').querySelector('.mx-auto.flex.h-full')?.remove();
    appendTurn('user', pending);
    removeQuestion();
    scrollToEnd(true);

    let result;
    try {
      result = await api(`/api/conversations/${state.conversationId}/messages`, {
        method: 'POST',
        body: JSON.stringify({ text: pending, mirror: state.mirroring }),
      });
    } catch (error) {
      // Give it back rather than swallow it: retyping a long message because the network blinked is
      // the worst thing this page could do to somebody. The files are already uploaded, so the
      // chips come back too rather than needing to be chosen again.
      setTyped(message);
      state.attachments = attached;
      renderAttachments();
      throw error;
    }

    // Queued means it joined the run already going, which is already being streamed — attaching a
    // second time would draw the same run twice.
    if (result.queued) toast(t('run.queued.sent'), 'settled', 3000);
    else {
      state.runView = null;
      attachRun(result.requestId, 0);
    }
    await loadConversations();
  });
}

export async function stop() {
  if (!state.requestId) return;
  // On the Stop button itself, until the server has taken the cancellation. What follows is the run
  // ending, which arrives as an event and is drawn by the status strip — but between the press and
  // that event there is a round trip, and a button that looks pressable through it gets pressed
  // again.
  const done = busyButton($('stop'));
  await attempt(async () => {
    await api(`/api/runs/${state.requestId}/cancel`, { method: 'POST' });
  });
  done();
}

export function initComposer() {
  const composer = $('composer');
  // No height to maintain — the field is a div and grows on its own, up to the max-height that
  // turns it into a scroller. Only what the box says about itself is left.
  composer.addEventListener('input', () => {
    refreshEmptiness();
    refreshSendState();
  });
  composer.addEventListener('keydown', (event) => {
    if (submits(event) && !event.shiftKey) {
      event.preventDefault();
      send();
    }
  });
  // A field, not a document. `plaintext-only` already says so to a browser that honours it, and
  // this is what a browser that treats the value as plain `true` would otherwise do: paste the
  // clipboard's *markup*, styles and all. execCommand is deprecated and still the only insertion
  // that a browser records in its own undo stack, which a field somebody is typing a message into
  // cannot do without.
  //
  // Files are somebody else's business: attachments.js turns a pasted screenshot into an upload,
  // and it is the handler that has already claimed the event when it did.
  composer.addEventListener('paste', (event) => {
    if (event.defaultPrevented || event.clipboardData?.files?.length) return;
    const text = event.clipboardData?.getData('text/plain');
    if (text === undefined) return;
    event.preventDefault();
    document.execCommand('insertText', false, text);
  });
  // The frame around the field, which a one-line composer has plenty of. The frame is not the
  // field, so without this a click aimed at the composer but landing beside its text does nothing
  // at all — see .composer-box, which is where the cursor saying it is clickable lives.
  $('composer-box').addEventListener('mousedown', (event) => {
    if (event.target !== event.currentTarget) return;
    event.preventDefault();
    composer.focus();
    // At the end of what is there, which is where a click on the frame past the text means.
    const range = document.createRange();
    range.selectNodeContents(composer);
    range.collapse(false);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
  });
  $('send').addEventListener('click', send);
  $('stop').addEventListener('click', stop);
  $('mirror').addEventListener('click', toggleMirror);
  refreshMirror();
  setRunning(false);
  $('new-conversation').addEventListener('click', () => attempt(newConversation));
}
