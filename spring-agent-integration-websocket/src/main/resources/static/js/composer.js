// The box at the bottom: what it can send, and what it looks like while a run is going.

import { t } from './i18n.js';
import { $, scrollToEnd } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { busyButton } from './busy.js';
import { loadConversations, newConversation } from './conversations.js';
import { appendTurn } from './transcript.js';
import { removeQuestion } from './questions.js';
import { attachRun } from './stream.js';
import { renderAttachments } from './attachments.js';
import { state } from './state.js';

/** Send is inert until there is something to send — a message, or a file to talk about. */
export function refreshSendState() {
  const send = $('send');
  if (!send) return;
  send.disabled = !$('composer').value.trim() && !state.attachments.length;
}

export function setRunning(running) {
  // Explicit display rather than toggling a `hidden` class: both buttons carry a display of their
  // own from .composer-action, and which of the two utilities wins would depend on the order
  // Tailwind happened to emit them in.
  $('send').style.display = running ? 'none' : 'grid';
  $('stop').style.display = running ? 'grid' : 'none';
  $('composer').placeholder = running
    ? t('composer.placeholder.running') : t('composer.placeholder');
  refreshSendState();
}

export async function send() {
  const composer = $('composer');
  const typed = composer.value.trim();
  // A message that is only files is a legitimate one — "here, look at this".
  if (!typed && !state.attachments.length) return;
  const attached = state.attachments.slice();
  // Named in the message rather than passed beside it, so what the model reads is exactly what the
  // person sees was sent. The directory is stated because that is where the agent's file tools look.
  const text = attached.length
    ? [typed, t('composer.attach.note', attached.map((f) => f.name).join(', '))]
      .filter(Boolean).join('\n\n')
    : typed;

  await attempt(async () => {
    if (!state.conversationId) {
      const created = await api('/api/conversations', { method: 'POST' });
      state.conversationId = created.id;
      await loadConversations();
    }
    // Cleared only once the request is on its way, so a failure does not also lose what they typed.
    const pending = text;
    composer.value = '';
    composer.style.height = 'auto';
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
        body: JSON.stringify({ text: pending }),
      });
    } catch (error) {
      // Give it back rather than swallow it: retyping a long message because the network blinked is
      // the worst thing this page could do to somebody. The files are already uploaded, so the
      // chips come back too rather than needing to be chosen again.
      composer.value = typed;
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
  composer.addEventListener('input', () => {
    composer.style.height = 'auto';
    composer.style.height = `${Math.min(composer.scrollHeight, 208)}px`;
    refreshSendState();
  });
  composer.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send();
    }
  });
  $('send').addEventListener('click', send);
  $('stop').addEventListener('click', stop);
  setRunning(false);
  $('new-conversation').addEventListener('click', () => attempt(newConversation));
}
