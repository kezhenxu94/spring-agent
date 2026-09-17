// The column: a drawer below md, a rail with a readout beside it at md and up.
//
// Three things live here, and they are three because they are the three states this column has.
// Which of its lists is showing (selectTab), whether the readout is folded away (the fold), and
// what the agent is doing (drawActivity) — the one line in the sidebar that is about the run rather
// than about what the column holds.
//
// Opening the drawer is easy to get right and closing it is what gets forgotten: the header's own
// toggle is behind the drawer once it is open, so there has to be a way out from inside it — a close
// button, the backdrop, and Escape.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { chatRoute, go, knowledgeRoute, tasksRoute } from './route.js';
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
  initActivity();
}

// ─────────────────────────────────────── the fold ───────────────────────────────────────
//
// Folded, the column is the rail alone: the sections, the one action, the activity dot and the
// avatar, all still on their own centre line — see sidebar.css, which is where the geometry that
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

// ─────────────────────────────── what the agent is doing ───────────────────────────────
//
// One line, and only when there is something to say. A conversation's own row carries a live dot,
// but only about itself and only while that row is in view — so somebody who has scrolled the list,
// or who is reading a document, has no way of knowing that a run is going on behind them. This is
// that way, and it is the only place in the column allowed a state colour.
//
// Every number it reports comes from what the page already has: `live` on the conversations the
// server last listed, which is reloaded when a run starts and again when one ends, and the status of
// the run on screen. So there is nothing to poll and nothing that can drift — if the line is wrong,
// the list beside it is wrong in the same way.

function reading() {
  // A question first. Both cannot really be true of one conversation — a run that stops to ask has
  // ended — but a question is the state that needs a person, and a run in progress is not.
  if (state.status === 'waiting') return { tone: 'bg-waiting', text: t('nav.activity.waiting') };
  const running = state.conversations.filter((conversation) => conversation.live).length;
  if (running) return { tone: 'bg-signal', text: t('nav.activity.running', running) };
  return null;
}

function drawActivity() {
  const line = $('activity');
  const now = reading();
  line.hidden = !now;
  if (!now) return;
  // The same two classes a conversation row's dot and a pending question's dot carry, so that the
  // rail and the list say a thing is happening in one vocabulary rather than two.
  $('activity-dot').className = `size-1.5 rounded-full dot-live ${now.tone}`;
  $('activity-text').textContent = now.text;
}

function initActivity() {
  // Over the bus rather than by being called: conversations.js is a later layer than this file, so
  // it cannot be imported here, and what it announces is exactly the moment this has to redraw —
  // the list it reports on has just been replaced.
  bus.on('conversations:loaded', drawActivity);
  // The local run, straight away rather than after the reload that follows it, so pressing Send
  // lights the rail in the same frame it lights the composer.
  bus.on('run:running', drawActivity);
  bus.on('language:changed', drawActivity);
  drawActivity();
}

/**
 * The three lists the sidebar can show.
 *
 * A row does not switch the sidebar by itself — it navigates, and showing the right list is what
 * the route handler does on the way past. So arriving at a document by a pasted link selects the
 * right row too, which a row that flipped its own panels would not.
 *
 * Conversations and the schedule always both exist. The knowledge base is the one that comes and
 * goes: a row leading to a section this deployment does not have would say the feature is broken
 * when the truth is that it was never configured.
 */
export function initTabs({ knowledge }) {
  $('sidebar-sections').hidden = false;
  $('tab-conversations-button').addEventListener('click', () => go(chatRoute(state.conversationId)));
  $('tab-tasks-button').addEventListener('click', () => go(tasksRoute()));
  if (!knowledge) return;
  $('tab-knowledge-row').hidden = false;
  $('tab-knowledge-button').addEventListener('click', () => go(knowledgeRoute()));
}

/** Puts the rows and the panels under them in step with wherever the page now is. */
export function selectTab(view) {
  ['conversations', 'tasks', 'knowledge'].forEach((name) => {
    const on = name === view;
    // The tick and the weight come from the attribute itself — see .side-row in sidebar.css — so
    // there is one fact here rather than a class that has to be kept in step with it.
    $(`tab-${name}-button`).setAttribute('aria-selected', String(on));
    $(`tab-${name}`).hidden = !on;
  });
  // The one action belongs to the conversations and only to them. It sits in the block that says
  // which section you are in, so leaving it there for the other two reads as an action of theirs —
  // and the other two make their own things in their own panels anyway: a task by asking the agent
  // for one, a document from the controls above the knowledge list. The rule above it stays put, so
  // what moves when this goes is one row and not the separator as well.
  $('new-conversation').hidden = view !== 'conversations';
}
