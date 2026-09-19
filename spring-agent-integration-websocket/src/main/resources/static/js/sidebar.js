// The column: a drawer below md, a rail with a readout beside it at md and up.
//
// Two things live here, and they are two because they are the two states this column has: which of
// its lists is showing (selectTab), and whether the readout is folded away (the fold).
//
// Opening the drawer is easy to get right and closing it is what gets forgotten: the header's own
// toggle is behind the drawer once it is open, so there has to be a way out from inside it — a close
// button, the backdrop, and Escape.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { chatRoute, customizeRoute, go, knowledgeRoute, tasksRoute } from './route.js';
import { bus, state } from './state.js';

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

  initFold();
}

// ─────────────────────────────────────── the fold ───────────────────────────────────────
//
// Folded, the column is the rail alone: the sections, the one action and the avatar, all still on
// their own centre line — see sidebar.css, which is where the geometry that
// makes a fold look like a fold rather than a redraw is written down.
//
// The choice is stored, because a fold that has to be redone on every reload is a fold nobody uses.
// It is stored for the reader and not for the deployment: this is the same kind of setting the theme
// is, so it is kept the same way, in the same guarded try — private browsing refuses localStorage
// outright and an unfolded sidebar is a fine answer.

const FOLD_KEY = 'spring-agent-sidebar-folded';

function folded() {
  return $('sidebar').classList.contains('is-collapsed');
}

function fold(on) {
  $('sidebar').classList.toggle('is-collapsed', on);
  try { localStorage.setItem(FOLD_KEY, on ? '1' : '0'); } catch (e) { /* private mode */ }
  drawFold();
}

/**
 * What the fold button says it will do next.
 *
 * `aria-expanded` is on the button and names the column it controls, which is what a screen reader
 * reads it as: the sidebar is expanded, and this collapses it. The visible name and the tooltip say
 * the same thing in words, and both are written here rather than from a data-i18n key in the markup,
 * because which of the two strings is right depends on the state.
 */
function drawFold() {
  const button = $('fold-sidebar');
  const label = t(folded() ? 'nav.unfold' : 'nav.fold');
  button.setAttribute('aria-expanded', String(!folded()));
  button.title = label;
  $('fold-label').textContent = label;
}

function initFold() {
  let stored = null;
  try { stored = localStorage.getItem(FOLD_KEY); } catch (e) { /* private mode */ }
  $('sidebar').classList.toggle('is-collapsed', stored === '1');
  drawFold();
  $('fold-sidebar').addEventListener('click', () => fold(!folded()));
  bus.on('language:changed', drawFold);
}

/**
 * The sections the sidebar can lead to.
 *
 * A row does not switch the sidebar by itself — it navigates, and showing the right list is what
 * the route handler does on the way past. So arriving at a document by a pasted link selects the
 * right row too, which a row that flipped its own panels would not.
 *
 * Conversations, the schedule and Customize always exist. The knowledge base is the one that comes
 * and goes: a row leading to a section this deployment does not have would say the feature is
 * broken when the truth is that it was never configured. Customize needs no such gate — a skill is
 * a folder on the filesystem core always has.
 */
export function initTabs({ knowledge }) {
  $('sidebar-sections').hidden = false;
  $('tab-conversations-button').addEventListener('click', () => go(chatRoute(state.conversationId)));
  $('tab-tasks-button').addEventListener('click', () => go(tasksRoute()));
  $('tab-customize-button').addEventListener('click', () => go(customizeRoute()));
  if (!knowledge) return;
  $('tab-knowledge-row').hidden = false;
  $('tab-knowledge-button').addEventListener('click', () => go(knowledgeRoute()));
}

/**
 * Every section that can be the one you are in.
 *
 * Not the same list as the one below it, and that is the point of there being two: Customize is a
 * section of this column without being a list *in* it. What it holds — the scopes, the search, the
 * skills — needs the width of the page and would be unreadable in a 270px rail, so the rail's job
 * for that section ends at saying you are in it.
 */
const SECTIONS = ['conversations', 'tasks', 'knowledge', 'customize'];

/** The sections that do hold a list, which is what the scrolling area below the rows shows. */
const LISTS = ['conversations', 'tasks', 'knowledge'];

/** Puts the rows and the panels under them in step with wherever the page now is. */
export function selectTab(view) {
  SECTIONS.forEach((name) => {
    // The tick and the weight come from the attribute itself — see .side-row in sidebar.css — so
    // there is one fact here rather than a class that has to be kept in step with it.
    $(`tab-${name}-button`).setAttribute('aria-selected', String(name === view));
  });
  LISTS.forEach((name) => {
    $(`tab-${name}`).hidden = name !== view;
  });
  // The one action belongs to the conversations and only to them. It sits in the block that says
  // which section you are in, so leaving it there for the other two reads as an action of theirs —
  // and the other two make their own things in their own panels anyway: a task by asking the agent
  // for one, a document from the controls above the knowledge list. The rule above it stays put, so
  // what moves when this goes is one row and not the separator as well.
  $('new-conversation').hidden = view !== 'conversations';
}
