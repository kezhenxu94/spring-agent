// The list in the sidebar, and what opening one of them does.
//
// Opening a conversation is: fetch the transcript (chat memory, survives a restart), then ask
// /state whether a run is going and whether a question is waiting, and attach to whichever is
// there. The same path on a first visit, a reload mid-answer, and a return an hour later.

import { t } from './i18n.js';
import { $, rowMeta, scrollToEnd, timeStamp } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { skeletonList, skeletonTranscript } from './busy.js';
import { confirmAction } from './confirm.js';
import { menuButton } from './menu.js';
import { renderStatus } from './status.js';
import { onNarrowScreen, sidebarOpen } from './sidebar.js';
import { chatRoute, go } from './route.js';
import { renderQuestion } from './questions.js';
import { attachRun, closeStream } from './stream.js';
import { appendTurn, renderEmptyTranscript } from './transcript.js';
import { bus, state } from './state.js';

export async function loadConversations() {
  // Only the list waits — the run beside it carries on being watched, and a page-wide veil over a
  // sidebar fetch would hide the one thing this application exists to show.
  //
  // And only where there is nothing to look at yet. This is called again every time a run ends, to
  // pick up a title the run gave the conversation and to clear its live dot; a placeholder over the
  // list somebody is already reading would make finishing a run look like losing the sidebar.
  const done = state.conversations.length ? () => {} : skeletonList($('conversation-list'), 5);
  try {
    state.conversations = await api('/api/conversations');
  } finally {
    // Either way: a failure leaves the list as it was rather than as a row of grey bars that never
    // becomes anything.
    done();
    renderConversationList();
  }
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
  open.className = 'flex w-full flex-col gap-0.5 rounded-md py-1.5 pl-2 pr-7 text-left '
    + 'text-[13px] transition '
    + (current
      ? 'bg-zinc-200/70 font-medium dark:bg-rail'
      : 'text-zinc-600 hover:bg-zinc-100 dark:text-mist dark:hover:bg-rail/60');

  const line = document.createElement('span');
  line.className = 'flex w-full items-center gap-2';
  const dot = document.createElement('span');
  dot.className = conversation.live
    ? 'size-1.5 shrink-0 rounded-full bg-signal dot-live'
    : 'size-1.5 shrink-0 rounded-full bg-transparent';
  const title = document.createElement('span');
  title.className = 'min-w-0 flex-1 truncate';
  title.textContent = conversation.title || t('nav.untitled');
  line.append(dot, title);
  open.append(line);

  // When it was last said something to, on the second line the other two lists also keep: the three
  // of them are read in one column, and the same fact in a different place in each is read as three
  // different ones. A title is what a conversation is found by, and it gets the whole of its line
  // rather than sharing it with a stamp that would clip a long one.
  const stamp = timeStamp(conversation.updatedAt);
  if (stamp) {
    const meta = rowMeta();
    meta.append(stamp);
    open.append(meta);
  }
  // Navigated to rather than opened here: the route is what decides what is on screen, and the
  // handler it reaches closes the drawer.
  open.addEventListener('click', () => go(chatRoute(conversation.id)));

  // The same ⋯ the other two lists carry. The work happens inside the dialog, so the row cannot be
  // pressed a second time while the delete is in flight.
  const actions = menuButton(t('nav.actions'), [
    {
      label: t('nav.delete'),
      danger: true,
      onSelect: () => confirmAction({
        title: t('delete.title'),
        body: t('delete.confirm'),
        action: t('delete.action'),
        run: async () => {
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
        },
      }),
    },
  ]);
  actions.classList.add('row-action');

  item.append(open, actions);
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
  // Over the bus rather than by calling the composer, which imports this module: the setting for
  // whether an answer also goes to a chat is stored per conversation, so opening one is when it has
  // to be read back. Announced rather than acted on — whether anything cares is app.js's business.
  bus.emit('conversation:opened', id);
  // Picking a conversation is what the drawer was opened for, so it gets out of the way.
  if (onNarrowScreen()) sidebarOpen(false);

  const transcript = $('transcript');
  transcript.replaceChildren();
  renderStatus('idle');
  // In the transcript alone, in the shape of a conversation: the sidebar keeps its list, the header
  // keeps its status, and only the column that has just been emptied says it is filling again.
  const drawn = skeletonTranscript(transcript);

  // The transcript first: it comes from chat memory, so it is there after a restart of the server,
  // and it is what a reload has to show even when the run detail is long gone.
  let turns;
  try {
    turns = await api(`/api/conversations/${id}/messages`);
  } finally {
    drawn();
  }
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
