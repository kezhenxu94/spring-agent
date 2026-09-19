// The list in the sidebar, and what opening one of them does.
//
// Opening a conversation is: fetch the transcript (chat memory, survives a restart), then ask
// /state whether a run is going and whether a question is waiting, and attach to whichever is
// there. The same path on a first visit, a reload mid-answer, and a return an hour later.

import { t } from './i18n.js';
import { $, glyph, scrollToEnd } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { skeletonList, skeletonTranscript } from './busy.js';
import { confirmAction } from './confirm.js';
import { menuButton } from './menu.js';
import { setStatus } from './status.js';
import { onNarrowScreen, sidebarOpen } from './sidebar.js';
import { chatRoute, go } from './route.js';
import { renderQuestion } from './questions.js';
import { attachRun, closeStream } from './stream.js';
import { appendTools, appendTurn, renderEmptyTranscript } from './transcript.js';
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

  // One line, and only a title on it. This list is the longest thing in the sidebar and the only
  // one read by scanning rather than by reading each row: a conversation is found by what it is
  // called, and the list is easier to find something in the more of it fits on screen. Unlike a
  // task's next run or a document's size, when a conversation was last spoken to answers no
  // question somebody has while looking for it — the list is already in that order.
  const open = document.createElement('button');
  open.className = 'row-open flex w-full items-center gap-[0.55rem] rounded-md py-1 pl-[0.625rem] pr-7 text-left '
    + 'text-[13px] transition '
    + (current
      ? 'row-on font-medium'
      : 'text-zinc-600 group-hover:bg-zinc-100 dark:text-mist dark:group-hover:bg-rail/60');

  // Filled and pulsing while a run is going, an empty ring otherwise. The ring rather than nothing
  // at all because the two states then differ by *fill*, which is a difference the eye reads down a
  // column without having to compare a row against the one above it — a dot that appears and
  // disappears moves nothing, but leaves the reader deciding whether a row has one at all.
  const dot = document.createElement('span');
  dot.className = conversation.live
    ? 'size-1.5 shrink-0 rounded-full bg-signal dot-live'
    : 'size-1.5 shrink-0 rounded-full border border-mist/70';
  const title = document.createElement('span');
  title.className = 'min-w-0 flex-1 truncate';
  title.textContent = conversation.title || t('nav.untitled');
  // The dot in the same box a section's icon sits in — see .side-glyph in sidebar.css. It is what
  // puts this title under the name of the section it belongs to rather than 13px to the left of it.
  open.append(glyph(dot), title);
  // Navigated to rather than opened here: the route is what decides what is on screen, and the
  // handler it reaches closes the drawer.
  open.addEventListener('click', () => go(chatRoute(conversation.id)));

  // Renaming happens in the row itself rather than in a dialog over it: the name is read here, so
  // it is corrected here. The field takes the row's own box, so opening it moves nothing.
  const field = document.createElement('input');
  field.type = 'text';
  field.className = 'row-rename';
  field.autocomplete = 'off';
  field.spellcheck = false;
  field.setAttribute('aria-label', t('nav.rename'));
  field.hidden = true;

  const closeRow = () => {
    renamingRow = null;
    field.hidden = true;
    open.hidden = false;
  };

  const commitRow = () => {
    if (!renamingRow || renamingRow.id !== conversation.id) return;
    const wanted = field.value.trim();
    closeRow();
    if (wanted === (conversation.title || '')) return;
    attempt(() => rename(conversation.id, wanted));
  };

  field.addEventListener('input', () => {
    if (renamingRow) renamingRow.text = field.value;
  });
  field.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      commitRow();
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      closeRow();
      open.focus();
    }
  });
  field.addEventListener('blur', () => {
    // A redraw of the list removes this element, and removing a focused element fires blur. That
    // is not somebody clicking away, and treating it as one commits an edit that the list merely
    // re-rendered underneath — which is exactly the case renamingRow exists to survive. Checked
    // on the next tick, by which time a removal has happened and a click-away has not.
    setTimeout(() => {
      if (field.isConnected) commitRow();
    }, 0);
  });

  // Redrawn mid-edit — see renamingRow. The row comes back already open, holding what had been
  // typed rather than what was stored.
  if (renamingRow && renamingRow.id === conversation.id) {
    open.hidden = true;
    field.hidden = false;
    field.value = renamingRow.text;
    // After this element is in the document; focus on a detached node does nothing.
    queueMicrotask(() => { field.focus(); field.setSelectionRange(field.value.length, field.value.length); });
  }

  // The same ⋯ the other two lists carry. The work happens inside the dialog, so the row cannot be
  // pressed a second time while the delete is in flight.
  const actions = menuButton(t('nav.actions'), [
    {
      label: t('nav.rename'),
      onSelect: () => {
        renamingRow = { id: conversation.id, text: conversation.title || '' };
        open.hidden = true;
        field.hidden = false;
        field.value = renamingRow.text;
        field.focus();
        field.select();
      },
    },
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
            setStatus('idle');
          }
          await loadConversations();
          toast(t('delete.done'), 'settled', 3500);
        },
      }),
    },
  ]);
  actions.classList.add('row-action');

  item.append(open, field, actions);
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
  // Not while the bar belongs to another section. The conversation list is redrawn by things that
  // happen anywhere — a run finishing, a rename — and each of those would otherwise put a
  // conversation's name back into a bar that is sitting above the knowledge base.
  if ($('app-header').dataset.bare === 'true') return;
  const conversation = state.conversations.find((it) => it.id === state.conversationId);
  const button = $('conversation-title');
  button.textContent = conversation
    ? conversation.title || t('nav.untitled')
    : t('app.title');
  // With nothing open the heading is the application's own name, which belongs to no conversation
  // and so cannot be renamed. Disabled rather than hidden, or the bar would change height between
  // the empty state and the first conversation.
  button.disabled = !conversation;
}

// ─────────────────────────────────────── renaming one ───────────────────────────────────────
//
// A conversation is called the first thing that was said in it. That is a good name for most and a
// poor one for the few somebody comes back to, so the derived name stays the default and this is
// the override — and emptying the box puts the derived one back rather than leaving a blank row.
//
// The field and the heading are the same size and in the same place (see .chat-title in
// chrome.css), so pressing the heading moves nothing: the title is corrected where it is read.

/** Whether the field is open, so a blur that follows Escape does not save what Escape discarded. */
let renaming = false;

/**
 * The row being renamed, and what has been typed into it so far.
 *
 * In module state rather than in the input, because this list is redrawn by things that have
 * nothing to do with the rename — a run finishing, a conversation being created, the language
 * changing — and an edit living only in the DOM would be silently thrown away by one of them. The
 * same lesson the skill editor's draft learned; see skills-detail.js.
 */
let renamingRow = null;

/**
 * Sets the field to the width of what is in it, up to whatever room the bar has.
 *
 * An `<input>` has no intrinsic width, so the value is drawn once into a mirror set in the same
 * type and the field is made that wide. The mirror's own padding matches the field's, so what is
 * measured is the box rather than the glyphs, and the caret has somewhere to sit at the end of the
 * last character. The cap is CSS's — `max-width: 100%` — so this never has to know how wide the
 * bar is.
 */
function fitTitle(field, mirror) {
  mirror.textContent = field.value;
  // A pixel or two past the text, or the caret at the end of the line sits on the border.
  field.style.width = `${Math.ceil(mirror.getBoundingClientRect().width) + 3}px`;
}

export function initConversationTitle() {
  const button = $('conversation-title');
  const field = $('conversation-title-input');
  const mirror = $('conversation-title-mirror');

  // Every keystroke, because the box grows with what is typed into it rather than being sized once
  // from the name it opened with.
  field.addEventListener('input', () => fitTitle(field, mirror));

  button.addEventListener('click', () => {
    if (button.disabled) return;
    const conversation = state.conversations.find((it) => it.id === state.conversationId);
    if (!conversation) return;
    renaming = true;
    // The name it has, derived or not — so renaming starts from what is on screen rather than
    // from an empty box somebody has to retype the old name into to change one word of it.
    field.value = conversation.title || '';
    button.hidden = true;
    field.hidden = false;
    // Measured while it is on screen; a hidden box has no width to read.
    fitTitle(field, mirror);
    field.focus();
    field.select();
  });

  const close = () => {
    renaming = false;
    field.hidden = true;
    button.hidden = false;
  };

  const commit = () => {
    if (!renaming) return;
    const conversationId = state.conversationId;
    const wanted = field.value.trim();
    const conversation = state.conversations.find((it) => it.id === conversationId);
    close();
    // Unchanged is not a request. Saying so here rather than letting the server decide keeps a
    // click that opened the field and changed nothing from touching the conversation at all.
    if (!conversation || wanted === (conversation.title || '')) return;
    attempt(() => rename(conversationId, wanted));
  };

  field.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      commit();
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      // Back to the heading, or focus is left on a field that is no longer there and the next
      // Tab starts from the top of the document.
      $('conversation-title').focus();
    }
  });
  // Clicking away is the same as pressing enter. A field that threw the edit away when the pointer
  // went somewhere else would lose a rename to a stray click, which is the more expensive mistake.
  field.addEventListener('blur', commit);
}

async function rename(conversationId, title) {
  const renamed = await api(`/api/conversations/${encodeURIComponent(conversationId)}`, {
    method: 'PATCH',
    body: JSON.stringify({ title }),
  });
  // What the server settled on, not what was typed — it trims and caps, and an empty one comes
  // back as whatever the conversation derives. Written into the list so the row and the heading
  // say the same thing without a second round trip.
  const conversation = state.conversations.find((it) => it.id === conversationId);
  if (conversation) conversation.title = renamed.title;
  renderConversationList();
  renderConversationTitle();
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
  setStatus('idle');
  // In the transcript alone, in the shape of a conversation: the sidebar keeps its list, the header
  // keeps its title, and only the column that has just been emptied says it is filling again.
  const drawn = skeletonTranscript(transcript);

  // The transcript first: it comes from chat memory, so it is there after a restart of the server,
  // and it is what a reload has to show even when the run detail is long gone.
  let turns;
  try {
    turns = await api(`/api/conversations/${id}/messages`);
  } finally {
    drawn();
  }
  turns.forEach((turn) => (turn.role === 'tools'
    ? appendTools(turn.tools ?? [])
    : appendTurn(turn.role, turn.text, turn.reasoningId)));
  if (!turns.length) renderEmptyTranscript();

  // Then what the transcript cannot say: is something happening, and is the agent waiting on me.
  const live = await api(`/api/conversations/${id}/state`);
  if (live.pendingQuestion) {
    renderQuestion(live.pendingQuestion);
    setStatus('waiting');
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
