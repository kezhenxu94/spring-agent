// Customize: what this agent has been taught, as against what it has been told.
//
// Three tabs, and this file is only the strip across the top of them and the switch underneath:
// skills are folders of instructions the agent loads by name, memories are what it has concluded
// about the person it was talking to, and MCP servers are the tools it can reach beyond the ones
// this deployment ships. Three different stores with nothing in common except that all three are
// things somebody configured rather than said — which is what the section is for.
//
// `TABS` in route.js is the list and the order; the strip is drawn from it rather than from markup,
// so adding a fourth tab is a word there, a case here and a section of its own.
//
// Everything that decides what is on screen is in the address bar: the tab, the store where the tab
// has one, the search, and the thing being read. So a skill is a link, a memory is a link, and the
// back button walks out of any of them the way it walks out of anything else.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { bus, state } from './state.js';
import { customizeRoute, go } from './route.js';
import { initSkills, showSkills } from './skills.js';
import { initMemories, showMemories } from './memories.js';
import { initMcp, showMcp } from './mcp.js';

/**
 * The tabs, in the order the strip draws them, each with the section that owns it.
 *
 * A table rather than a switch in two places, because the strip and the dispatch have to agree
 * about the set — a tab drawn with nothing behind it is a button that empties the panel, and a
 * section with no tab is unreachable except by typing the hash.
 */
const TABS = [
  { id: 'skills', label: 'customize.skills', show: showSkills },
  { id: 'memories', label: 'customize.memories', show: showMemories },
  { id: 'mcp', label: 'customize.mcp', show: showMcp },
];

export function initCustomize() {
  drawTabs();
  initSkills();
  initMemories();
  initMcp();
  // The strip is written in JavaScript, so it stays in whatever language the page started in
  // unless it is told otherwise — the rule every list drawn here follows.
  bus.on('language:changed', drawTabs);
}

/** What the route means here: which tab, and then that tab's own reading of the rest of it. */
export function showCustomize(route) {
  const tab = TABS.find((each) => each.id === route.tab) || TABS[0];
  state.customize = { tab: tab.id };
  // Every tab's panel is hidden and then one is shown, rather than each tab hiding the others: a
  // section that forgot one of its siblings would leave two lists stacked in the same column, and
  // the one that forgot would not be the one that looked broken.
  TABS.forEach((each) => { $(`customize-${each.id}`).hidden = each.id !== tab.id; });
  drawTabs();
  tab.show(route);
}

/**
 * The strip, with the tab the route names marked.
 *
 * Marked off `aria-selected`, which is how every other selected thing on this page is marked, so
 * the styling and what a screen reader is told are one fact rather than two that can disagree.
 */
function drawTabs() {
  const host = $('customize-tabs');
  const current = (state.customize && state.customize.tab) || TABS[0].id;
  host.textContent = '';
  TABS.forEach((tab) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.id = `customize-tab-${tab.id}`;
    button.className = 'page-tab';
    button.setAttribute('role', 'tab');
    button.setAttribute('aria-controls', `customize-${tab.id}`);
    button.setAttribute('aria-selected', String(tab.id === current));
    button.textContent = t(tab.label);
    // Nothing here opens the tab. It writes the hash and app.js's dispatch brings it back — the
    // one-way rule route.js states, and what makes the back button walk between tabs.
    button.addEventListener('click', () => go(customizeRoute(tab.id)));
    host.append(button);
  });
}
