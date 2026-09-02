// A drawer below md, a column at md and up. Opening it is easy to get right and closing it is what
// gets forgotten: the header's own toggle is behind the drawer once it is open, so there has to be
// a way out from inside it — a close button, the backdrop, and Escape.

import { $ } from './dom.js';
import { chatRoute, go, knowledgeRoute, tasksRoute } from './route.js';
import { state } from './state.js';

export function sidebarOpen(open) {
  const sidebar = $('sidebar');
  const backdrop = $('sidebar-backdrop');
  if (!sidebar) return; // removed on the no-access screen
  sidebar.classList.toggle('sidebar-open', open);
  backdrop.hidden = !open;
  $('toggle-sidebar').setAttribute('aria-expanded', String(open));
  // The page behind a modal drawer must not scroll under it.
  document.body.classList.toggle('drawer-open', open);
}

export function sidebarIsOpen() {
  return $('sidebar')?.classList.contains('sidebar-open');
}

/** True only where the sidebar is a drawer; at md and up it is always on screen. */
export function onNarrowScreen() {
  return window.matchMedia('(max-width: 767.98px)').matches;
}

export function initSidebar() {
  $('toggle-sidebar').addEventListener('click', () => sidebarOpen(!sidebarIsOpen()));
  $('close-sidebar').addEventListener('click', () => sidebarOpen(false));
  $('sidebar-backdrop').addEventListener('click', () => sidebarOpen(false));
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && sidebarIsOpen()) sidebarOpen(false);
  });
  // Widening past md leaves the drawer state behind, or the backdrop would sit over the column.
  window.matchMedia('(max-width: 767.98px)').addEventListener('change', (event) => {
    if (!event.matches) sidebarOpen(false);
  });
}

/**
 * The three lists the sidebar can show.
 *
 * A tab does not switch the sidebar by itself — it navigates, and showing the right list is what
 * the route handler does on the way past. So arriving at a document by a pasted link selects the
 * right tab too, which a tab that flipped its own panels would not.
 *
 * The strip is always there, because conversations and scheduled tasks always both exist. The
 * knowledge tab is the one that comes and goes: a tab leading to a section this deployment does not
 * have would say the feature is broken when the truth is that it was never configured.
 */
export function initTabs({ knowledge }) {
  $('sidebar-tabs').hidden = false;
  $('tab-conversations-button').addEventListener('click', () => go(chatRoute(state.conversationId)));
  $('tab-tasks-button').addEventListener('click', () => go(tasksRoute()));
  if (!knowledge) return;
  $('tab-knowledge-button').hidden = false;
  $('tab-knowledge-button').addEventListener('click', () => go(knowledgeRoute()));
}

/** Puts the strip and the panels under it in step with wherever the page now is. */
export function selectTab(view) {
  ['conversations', 'tasks', 'knowledge'].forEach((name) => {
    const on = name === view;
    const button = $(`tab-${name}-button`);
    button.classList.toggle('seg-on', on);
    button.setAttribute('aria-selected', String(on));
    $(`tab-${name}`).hidden = !on;
  });
  // The primary action belongs to the conversations, and only to them. The other two sections make
  // their own things in their own panels — a task by asking the agent for one, a document from the
  // controls above the knowledge list — so the slot goes rather than holding a button that would
  // have to be explained.
  $('primary-action').hidden = view !== 'conversations';
}
