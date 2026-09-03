// The page: what it does when it loads, and which module hears what.
//
// Everything else here is a module that draws one thing. This file is the only one that imports
// across all of them, so the order the page comes up in — and the few places one part has to tell
// another something happened — are readable in one screen rather than inferred from a graph of
// imports. See state.js for the layering rule that keeps that graph acyclic.

import { applyTranslations, setAppName, setLocale, t } from './i18n.js';
import { $ } from './dom.js';
import { api, csrfToken } from './api.js';
import { attempt, toast } from './toast.js';
import { skeletonList, skeletonTranscript } from './busy.js';
import { bus, state } from './state.js';
import { initTheme } from './theme.js';
import { renderStatus } from './status.js';
import { initSidebar, initTabs, onNarrowScreen, selectTab, sidebarOpen } from './sidebar.js';
import { chatRoute, current, go, onRoute } from './route.js';
import { attachRun } from './stream.js';
import {
  loadConversations, openConversation, renderConversationList, renderConversationTitle,
} from './conversations.js';
import { initScrollToEnd, renderEmptyTranscript } from './transcript.js';
import { initAttachments } from './attachments.js';
import { initComposer, refreshMirror, refreshSendState, setRunning } from './composer.js';
import { loadTasks, showTasks } from './tasks.js';
import { initKnowledge, knowledgeAvailable, scopesAvailable, showKnowledge } from './knowledge.js';
import { showPanel } from './panels.js';
import { initKnowledgeAdd } from './knowledge-upload.js';
import { initLanguage } from './language.js';
import { initSettings } from './settings.js';
import { renderIdentity } from './identity.js';
import { renderDenied } from './denied.js';

function wire() {
  // A run starting or ending decides which of send and stop the composer shows. The stream says
  // that it happened; what it looks like is the composer's business.
  bus.on('run:running', setRunning);
  bus.on('composer:refresh', refreshSendState);
  // Answering a question starts a new run on the same conversation, and the answer form has no
  // business knowing how one is attached to.
  bus.on('run:attach', ({ requestId, from }) => attachRun(requestId, from));
  // A finished run may have given the conversation its title, and clears its live dot.
  bus.on('conversations:changed', () => attempt(loadConversations));
  // Whether the answer also goes to a chat is remembered per conversation, so opening one is when
  // the toggle has to be redrawn from what was stored for it.
  bus.on('conversation:opened', refreshMirror);
  // Everything already on screen, in the language just chosen. Anything drawn from a template in
  // the markup is handled by applyTranslations; these are the lists built in JavaScript.
  bus.on('language:changed', () => {
    renderConversationList();
    renderStatus();
    // Its title names the chat platform, so it is a label like any other.
    refreshMirror();
    // And whatever the main column is showing, by asking the route what that is — which is cheaper
    // to keep right than a list of every panel that would otherwise have to be remembered here.
    dispatch(current());
  });
  onRoute(dispatch);
}

/**
 * What a route means on screen: which sidebar tab, which panel, and what is open in it.
 *
 * The single place that decides, so the address bar and the page cannot disagree — every click that
 * navigates goes through the router and arrives back here, and so does the back button, a reload
 * and a pasted link.
 */
function dispatch(route) {
  if (route.view === 'knowledge' && !knowledgeAvailable()) {
    // A link to a section this deployment does not have. Sent to the conversation rather than left
    // with an address that says one thing while the page shows another.
    go(chatRoute(state.conversationId));
    return;
  }
  selectTab(route.view === 'chat' ? 'conversations' : route.view);
  showPanel(route.view);
  if (route.view === 'knowledge') {
    showKnowledge(route.id, route.scope);
    getOutOfTheWay(route);
    return;
  }
  if (route.view === 'tasks') {
    showTasks(route.id);
    getOutOfTheWay(route);
    return;
  }
  renderConversationTitle();
  // Re-selecting the conversation already open is not a reason to tear its stream down and draw it
  // again; the drawer closing is the whole of what it should do.
  if (route.id && route.id !== state.conversationId) {
    attempt(() => openConversation(route.id));
  } else if (onNarrowScreen()) {
    sidebarOpen(false);
  }
}

/**
 * The drawer, once it has done what it was opened for.
 *
 * Below md the sidebar covers the column it selects into, so picking a document or a task has to
 * close it — otherwise the thing you chose is drawn behind the list you chose it from, and it looks
 * as though nothing happened. `openConversation` does this for itself, which is why the chat branch
 * does not come through here.
 *
 * Only where the route names something. Pressing a tab is not picking a thing: the list it switches
 * to is what you opened the drawer to read.
 */
function getOutOfTheWay(route) {
  if (route.id && onNarrowScreen()) sidebarOpen(false);
}

async function start() {
  initTheme();

  // The two places something is about to appear, said before the first request goes out. Not a veil
  // over the page: the chrome around them is already drawn and correct, and covering it would hide
  // the theme and the sign-in this page can show before it has asked the server anything.
  const settleList = skeletonList($('conversation-list'), 5);
  const settleTranscript = skeletonTranscript($('transcript'));
  const settle = () => {
    settleList();
    settleTranscript();
  };

  try {
    state.me = await api('/api/me');
  } catch (error) {
    settle();
    if (!error?.handled) {
      // api() has not redirected, so this is a server that is up but unhappy. Say so on the page —
      // there is no sidebar to fall back to yet.
      toast(error?.message || t('error.generic'), 'alarm', 0);
    }
    return;
  }

  setLocale(state.me.locale);
  // Before applyTranslations, which is what puts the name on the page: the tab, the sidebar brand
  // and the heading a conversation title later replaces all read the same key.
  setAppName(state.me.title);
  applyTranslations();

  if (state.me.allowed === false) {
    settle();
    renderDenied(state.me);
    return;
  }

  wire();
  initComposer();
  initAttachments();
  initScrollToEnd();
  initSidebar();
  initLanguage(state.me);
  initSettings();
  // Only where this deployment has one at all — see the knowledge block in /api/me.
  if (knowledgeAvailable()) {
    initKnowledge();
    initKnowledgeAdd(scopesAvailable());
  }
  initTabs({ knowledge: knowledgeAvailable() });
  renderStatus('idle');
  renderIdentity(state.me);
  $('logout-csrf').value = csrfToken();

  await attempt(async () => {
    await loadConversations();
    await loadTasks();

    // Where the hash says, so a reload lands back where the reader was rather than at the top of
    // the list — which for a run in progress is the difference between watching it continue and
    // having to find it again. A conversation it does not name is the most recent one, and the
    // knowledge base is drawn over whichever that is, so leaving it puts something back.
    const route = current();
    const wanted = route.view === 'chat' ? route.id : null;
    const target = state.conversations.find((it) => it.id === wanted) || state.conversations[0];
    // openConversation empties the transcript itself; the other branch has to, or the placeholder
    // turns would sit above an invitation to start the first conversation.
    if (target) await openConversation(target.id);
    else {
      settleTranscript();
      renderEmptyTranscript();
    }

    // The section the hash named, now that there is a conversation under it to go back to.
    if (route.view === 'chat') go(chatRoute(target ? target.id : null));
    else dispatch(route);
  });
  // Whatever happened above, nothing is still on its way. A placeholder left standing over a
  // request that failed is the one thing worse than no placeholder at all.
  settle();
}

start();
