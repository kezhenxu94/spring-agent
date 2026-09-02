// Which of the three things the main column is showing.
//
// One place decides, because the composer belongs to a conversation and not to the page: leaving it
// on screen under a list of documents or of scheduled tasks was the tell that those sections had
// been bolted on beside the chat rather than built as peers of it.
//
// The `hidden` attribute rather than a class, so nothing in the utility layer can out-specify it.

import { $ } from './dom.js';

export function showPanel(view) {
  $('transcript').hidden = view !== 'chat';
  $('composer-bar').hidden = view !== 'chat';
  $('knowledge-panel').hidden = view !== 'knowledge';
  $('tasks-panel').hidden = view !== 'tasks';
}

/** What the header says it is showing, which is not always a conversation. */
export function headline(text) {
  $('conversation-title').textContent = text;
}
