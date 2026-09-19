// Which of the four things the main column is showing.
//
// One place decides, because the composer belongs to a conversation and not to the page: leaving it
// on screen under a list of documents or of scheduled tasks was the tell that those sections had
// been bolted on beside the chat rather than built as peers of it.
//
// The `hidden` attribute rather than a class, so nothing in the utility layer can out-specify it.

import { $ } from './dom.js';

export function showPanel(view) {
  // The bar above the column exists for two things, and only one section needs either: the
  // conversation's title, and — below md — the way back into the drawer. Everywhere else it was
  // repeating a word the panel's own heading already says, over a rule with nothing under it. So
  // it goes bare for the other sections, and chrome.css takes it away completely at the width
  // where the drawer toggle is gone too.
  $('app-header').dataset.bare = String(view !== 'chat');
  $('transcript').hidden = view !== 'chat';
  $('composer-bar').hidden = view !== 'chat';
  $('knowledge-panel').hidden = view !== 'knowledge';
  $('tasks-panel').hidden = view !== 'tasks';
  $('customize-panel').hidden = view !== 'customize';
}

/**
 * What the bar says, which is only ever the conversation.
 *
 * Kept as the one writer of that element even though only the chat uses it now — the other three
 * sections name themselves in their own panels, and a second writer here would put a title into a
 * bar that is not on screen.
 */
export function headline(text) {
  $('conversation-title').textContent = text;
}
