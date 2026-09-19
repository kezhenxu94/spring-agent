// Which of the four things the main column is showing, and what the bar above it says about that.
//
// One place decides, because the composer belongs to a conversation and not to the page: leaving it
// on screen under a list of documents or of scheduled tasks was the tell that those sections had
// been bolted on beside the chat rather than built as peers of it.
//
// The `hidden` attribute rather than a class, so nothing in the utility layer can out-specify it.

import { $ } from './dom.js';
import { t } from './i18n.js';

/**
 * What the bar calls each section that is not the conversation.
 *
 * A table rather than a line in each section's own code, because the bar is one element: two
 * writers of it disagree the moment one of them is reached by a path the other did not expect,
 * and the one that is wrong is whichever ran second.
 */
const SECTION = {
  knowledge: 'knowledge.title',
  tasks: 'tasks.title',
  customize: 'customize.title',
};

export function showPanel(view) {
  // The bar is the conversation's at md and up and everybody's below it: there the panels name
  // themselves in their own headings and the drawer toggle is gone, so a bar over them would be a
  // word repeated above a rule with nothing under it. chrome.css reads this attribute; what the
  // bar says while it is on screen is sectionBar's.
  const bare = view !== 'chat';
  $('app-header').dataset.bare = String(bare);
  if (bare) sectionBar(t(SECTION[view]));
  // The conversation writes its own name — see renderConversationTitle, which is also what puts
  // the rename back. What has to go either way is the way back to the list of whatever was open
  // before this, which belongs to a section this one is not.
  else barBack(null);
  $('transcript').hidden = view !== 'chat';
  $('composer-bar').hidden = view !== 'chat';
  $('knowledge-panel').hidden = view !== 'knowledge';
  $('tasks-panel').hidden = view !== 'tasks';
  $('customize-panel').hidden = view !== 'customize';
}

/**
 * The bar, on a section that is not the conversation: what it says, and the way out it offers.
 *
 * `back` is `{ label, onBack }` where the thing on screen came from a list this bar is the only
 * way back to — a skill, a memory, an MCP server — and nothing where the sidebar is still holding
 * that list, as it is for the knowledge base and the schedule.
 */
export function sectionBar(title, back) {
  const heading = $('conversation-title');
  heading.textContent = title;
  // Nothing here is a conversation, so nothing here is renamed: the field behind this heading
  // belongs to the chat alone, and pressing a section's name should do nothing at all.
  heading.disabled = true;
  barBack(back);
}

/**
 * The bar's way back, or nothing.
 *
 * It and the drawer toggle are one slot: with something open, the way out of it is what the bar
 * offers and the drawer is one press further away. Both carry .drawer-only, so at md and up this
 * decides nothing — neither is drawn.
 */
function barBack(back) {
  const out = $('header-back');
  out.hidden = !back;
  $('toggle-sidebar').hidden = Boolean(back);
  if (!back) return;
  $('header-back-label').textContent = back.label;
  // Assigned rather than added: this button is drawn once and re-aimed at whatever is open, and a
  // listener per open would leave every previous section's way out still on it.
  out.onclick = back.onBack;
}
