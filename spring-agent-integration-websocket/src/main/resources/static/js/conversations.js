// The list in the sidebar, and what opening one of them does.
//
// Opening a conversation is: fetch the transcript (chat memory, survives a restart), then ask
// /state whether a run is going and whether a question is waiting, and attach to whichever is
// there. The same path on a first visit, a reload mid-answer, and a return an hour later.

import { t } from './i18n.js';
import { $, scrollToEnd } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { renderStatus } from './status.js';
import { onNarrowScreen, sidebarOpen } from './sidebar.js';
import { chatRoute, go } from './route.js';
import { renderQuestion } from './questions.js';
import { attachRun, closeStream } from './stream.js';
import { appendTurn, renderEmptyTranscript } from './transcript.js';
import { state } from './state.js';

export async function loadConversations() {
  state.conversations = await api('/api/conversations');
  renderConversationList();
}

export function renderConversationList() {
  const list = $('conversation-list');
  list.replaceChildren();
  if (!state.conversations.length) {
    const empty = document.createElement('li');
    empty.className = 'px-1 py-1.5 text-[12px] leading-relaxed text-mist';
    empty.textContent = t('nav.empty');
    list.append(empty);
    return;
  }
  state.conversations.forEach((conversation) => list.append(row(conversation)));
}

function row(conversation) {
  const current = conversation.id === state.conversationId;
  const item = document.createElement('li');
  item.className = 'group relative';

  const open = document.createElement('button');
  open.className = 'flex w-full items-center gap-2 rounded-md py-1.5 pl-2 pr-7 text-left '
    + 'text-[13px] transition '
    + (current
      ? 'bg-zinc-200/70 font-medium dark:bg-rail'
      : 'text-zinc-600 hover:bg-zinc-100 dark:text-mist dark:hover:bg-rail/60');
  const dot = document.createElement('span');
  dot.className = conversation.live
    ? 'size-1.5 shrink-0 rounded-full bg-signal dot-live'
    : 'size-1.5 shrink-0 rounded-full bg-transparent';
  const title = document.createElement('span');
  title.className = 'min-w-0 flex-1 truncate';
  title.textContent = conversation.title || t('nav.untitled');
  open.append(dot, title);
  // Navigated to rather than opened here: the route is what decides what is on screen, and the
  // handler it reaches closes the drawer.
  open.addEventListener('click', () => go(chatRoute(conversation.id)));

  const remove = document.createElement('button');
  remove.className = 'absolute right-1 top-1/2 -translate-y-1/2 rounded px-1 text-[13px] '
    + 'text-mist opacity-0 transition hover:text-alarm focus-visible:opacity-100 '
    + 'group-hover:opacity-100';
  remove.textContent = '×';
  remove.setAttribute('aria-label', t('nav.delete'));
  remove.addEventListener('click', (event) => {
    event.stopPropagation();
    if (!window.confirm(t('delete.confirm'))) return;
    attempt(async () => {
      await api(`/api/conversations/${conversation.id}`, { method: 'DELETE' });
      if (state.conversationId === conversation.id) {
        closeStream();
        state.conversationId = null;
        state.runView = null;
        $('transcript').replaceChildren();
        renderEmptyTranscript();
        renderStatus('idle');
      }
      await loadConversations();
      toast(t('delete.done'), 'settled', 3500);
    });
  });

  item.append(open, remove);
  return item;
}

export async function newConversation() {
  const created = await api('/api/conversations', { method: 'POST' });
  await loadConversations();
  go(chatRoute(created.id));
  $('composer').focus();
}

/** What the header says about the conversation on screen; also what leaving the knowledge base restores. */
export function renderConversationTitle() {
  const conversation = state.conversations.find((it) => it.id === state.conversationId);
  $('conversation-title').textContent = conversation
    ? conversation.title || t('nav.untitled')
    : t('app.title');
}

export async function openConversation(id) {
  closeStream();
  state.conversationId = id;
  state.runView = null;
  state.lastSeq = 0;
  // Picking a conversation is what the drawer was opened for, so it gets out of the way.
  if (onNarrowScreen()) sidebarOpen(false);

  const transcript = $('transcript');
  transcript.replaceChildren();
  renderStatus('idle');

  // The transcript first: it comes from chat memory, so it is there after a restart of the server,
  // and it is what a reload has to show even when the run detail is long gone.
  const turns = await api(`/api/conversations/${id}/messages`);
  turns.forEach((turn) => appendTurn(turn.role, turn.text));
  if (!turns.length) renderEmptyTranscript();

  // Then what the transcript cannot say: is something happening, and is the agent waiting on me.
  const live = await api(`/api/conversations/${id}/state`);
  if (live.pendingQuestion) {
    renderQuestion(live.pendingQuestion);
    renderStatus('waiting');
  }
  if (live.liveRequestId) {
    // A run was already going before this page existed. Attaching from 0 replays everything it has
    // emitted, which is what makes a refresh mid-answer look like nothing happened.
    attachRun(live.liveRequestId, 0);
  }
  renderConversationList();
  renderConversationTitle();
  scrollToEnd(true);
}
