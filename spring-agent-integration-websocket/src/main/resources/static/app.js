// The page.
//
// The one idea to hold on to: a subscription here is a *reader* of a run that is happening on the
// server whether or not this page exists. Nothing on this side starts a run except POSTing a
// message, and nothing on this side stops one except pressing Stop. Closing the tab, reloading,
// losing the network — all of them only end a connection.
//
// So loading a conversation is: fetch the transcript (chat memory, survives a restart), then ask
// /state whether a run is going and whether a question is waiting, and attach to whichever is
// there. The same path on a first visit, a reload mid-answer, and a return an hour later.

import { applyTranslations, LANGUAGE_NAMES, locale, setAppName, setLocale, t } from './i18n.js';
import { markdown, RunView } from './render.js';

const $ = (id) => document.getElementById(id);

const state = {
  me: null,
  conversationId: null,
  requestId: null,
  stream: null,
  runView: null,
  // Where the current stream got to, so a reconnect resumes rather than repeats. Sent as the
  // `from` header on every subscribe, including the ones the STOMP client makes after a drop —
  // unlike SSE, nothing in the protocol carries a cursor on our behalf.
  lastSeq: 0,
  // The last sequence number that existed when we subscribed, from the server's `replay` event.
  // Everything up to it is backlog; everything past it is happening now.
  replayThrough: 0,
  // True while replaying a backlog, so a hundred rows do not each animate in.
  replaying: false,
  running: false,
  // Uploaded and waiting to be named in the next message. Cleared once that message is sent.
  attachments: [],
};

// ─────────────────────────────────────────── toasts ───────────────────────────────────────────
//
// Anything that fails has to be visible. Before this, a rejected request threw into a promise
// nobody caught and the page simply did nothing — which looks exactly like a request that worked.

function toast(message, tone = 'alarm', ttl = 8000) {
  let host = $('toasts');
  if (!host) {
    host = document.createElement('div');
    host.id = 'toasts';
    document.body.append(host);
  }
  const node = document.createElement('div');
  node.className = `toast toast-${tone}`;
  node.setAttribute('role', tone === 'alarm' ? 'alert' : 'status');

  const mark = document.createElement('span');
  mark.className = 'toast-mark';
  mark.textContent = tone === 'alarm' ? '!' : '✓';

  const text = document.createElement('span');
  text.textContent = message;

  const close = document.createElement('button');
  close.className = 'toast-close';
  close.type = 'button';
  close.setAttribute('aria-label', t('toast.dismiss'));
  close.textContent = '×';

  const dismiss = () => {
    node.classList.add('toast-leaving');
    node.addEventListener('animationend', () => node.remove(), { once: true });
  };
  close.addEventListener('click', dismiss);
  node.append(mark, text, close);
  host.append(node);
  if (ttl) setTimeout(dismiss, ttl);
  return node;
}

/** Runs an action and surfaces whatever it throws, rather than losing it to an unhandled rejection. */
async function attempt(action, fallback) {
  try {
    return await action();
  } catch (error) {
    if (error && error.handled) return undefined;
    toast(error?.message || fallback || t('error.generic'));
    return undefined;
  }
}

// ──────────────────────────────────────────── HTTP ────────────────────────────────────────────

function csrfToken() {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : '';
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  // FormData sets its own Content-Type, with the multipart boundary in it. Setting one here would
  // replace that boundary with nothing and the server would fail to parse the body.
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }
  // Every state-changing request echoes the cookie back in a header. The cookie is readable and the
  // header is not settable cross-origin, which is what makes the pair proof of same-origin.
  if (options.method && options.method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken();

  let response;
  try {
    response = await fetch(path, { ...options, headers });
  } catch (networkError) {
    throw new Error(t('error.offline'));
  }

  if (response.status === 401) {
    // The session is gone. Back through the front door rather than an error they can do nothing with.
    window.location.href = '/oauth2/authorization/feishu';
    const handled = new Error('unauthenticated');
    handled.handled = true;
    throw handled;
  }
  if (response.status === 403) {
    // Either this deployment does not serve them, or the CSRF cookie is missing. Both are worth
    // saying out loud; /api/me is what distinguishes them, and start() has already asked it.
    throw new Error(state.me && state.me.allowed === false
      ? t('denied.short') : t('error.forbidden'));
  }
  if (!response.ok) {
    let message = `${t('error.generic')} (${response.status})`;
    try {
      const body = await response.json();
      // Spring's ProblemDetail puts the reason in `detail`; ResponseStatusException in `message`.
      if (body && (body.detail || body.message)) message = body.detail || body.message;
    } catch (e) { /* a non-JSON error body is still an error */ }
    throw new Error(message);
  }
  return response.status === 204 ? null : response.json();
}

// ─────────────────────────────────────── theme and language ───────────────────────────────────────

// Icons, at 16 and stroked in currentColor so they take the button's own state.
const ICONS = {
  auto: '<rect x="2.4" y="2.8" width="11.2" height="8.2" rx="1.5"/><path d="M6 13.6h4"/>',
  light: '<circle cx="8" cy="8" r="2.9"/><path d="M8 1.6v1.4M8 13v1.4M3.5 3.5l1 1M11.5 11.5l1 1'
    + 'M1.6 8H3M13 8h1.4M3.5 12.5l1-1M11.5 4.5l1-1"/>',
  dark: '<path d="M13.4 9.7A5.8 5.8 0 0 1 6.3 2.6a5.8 5.8 0 1 0 7.1 7.1Z"/>',
};

function icon(name, size = 15) {
  return `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"
    stroke-linecap="round" stroke-linejoin="round" class="size-[${size}px]"
    aria-hidden="true">${ICONS[name]}</svg>`;
}

function initTheme() {
  const group = $('theme-switch');
  let stored = 'auto';
  try { stored = localStorage.getItem('spring-agent-theme') || 'auto'; } catch (e) { /* private mode */ }

  const apply = (choice) => {
    const dark = choice === 'dark'
      || (choice === 'auto' && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.toggle('dark', dark);
  };

  const buttons = ['auto', 'light', 'dark'].map((choice) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.dataset.theme = choice;
    button.className = 'seg';
    button.innerHTML = icon(choice);
    // Toggle buttons with aria-pressed rather than a radiogroup: a radiogroup promises arrow-key
    // navigation, and three tab stops is both simpler and no worse to use.
    button.addEventListener('click', () => {
      try { localStorage.setItem('spring-agent-theme', choice); } catch (e) { /* private mode */ }
      apply(choice);
      select(choice);
    });
    group.append(button);
    return button;
  });

  const select = (choice) => {
    buttons.forEach((button) => {
      const on = button.dataset.theme === choice;
      button.classList.toggle('seg-on', on);
      button.setAttribute('aria-pressed', String(on));
      button.title = t(`theme.${button.dataset.theme}`);
      button.setAttribute('aria-label', t(`theme.${button.dataset.theme}`));
    });
    group.dataset.theme = choice;
  };

  apply(stored);
  select(stored);

  // Following the system while set to auto, so a desktop that switches at sunset takes the page
  // with it without a reload.
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if ((group.dataset.theme || 'auto') === 'auto') apply('auto');
  });

  // Re-labelled when the language changes; the icons stay, their names do not.
  themeRelabel = () => select(group.dataset.theme || 'auto');
}

/** Set by initTheme, so a language switch can relabel the theme buttons without rebuilding them. */
let themeRelabel = () => {};

function initLanguage(me) {
  // The server already resolved this from the cookie or Accept-Language. Following it rather than
  // deciding again keeps the page and the server's own messages in one language.
  setLocale(me.locale);

  const button = $('language-button');
  const menu = $('language-menu');
  const current = $('language-current');
  const tags = me.locales && me.locales.length ? me.locales : ['en'];

  const chosen = () => tags.find((tag) => tag.split('-')[0] === locale()) || me.locale || tags[0];

  const close = () => {
    menu.classList.add('hidden');
    button.setAttribute('aria-expanded', 'false');
  };
  const open = () => {
    menu.classList.remove('hidden');
    button.setAttribute('aria-expanded', 'true');
    menu.querySelector('button')?.focus();
  };

  const choose = (tag) => {
    // The same cookie Spring's CookieLocaleResolver reads, so the choice is what the *server* uses
    // for its own messages too — setting only a JavaScript variable would leave the page translated
    // and its error messages not.
    const oneYear = 365 * 24 * 60 * 60;
    document.cookie = `SPRING_AGENT_LOCALE=${encodeURIComponent(tag)};path=/;max-age=${oneYear};samesite=lax`;
    setLocale(tag);
    applyTranslations();
    themeRelabel();
    draw();
    renderConversationList();
    renderStatus();
    attempt(loadTasks);
    close();
    button.focus();
  };

  const draw = () => {
    current.textContent = locale().toUpperCase();
    menu.replaceChildren();
    tags.forEach((tag) => {
      const item = document.createElement('li');
      item.setAttribute('role', 'none');
      const entry = document.createElement('button');
      entry.type = 'button';
      entry.setAttribute('role', 'menuitemradio');
      const on = tag === chosen();
      entry.setAttribute('aria-checked', String(on));
      entry.className = 'menu-item';
      const tick = document.createElement('span');
      tick.className = 'menu-tick';
      tick.textContent = on ? '✓' : '';
      const label = document.createElement('span');
      label.textContent = LANGUAGE_NAMES[tag.split('-')[0]] || tag;
      const code = document.createElement('span');
      code.className = 'menu-code';
      code.textContent = tag;
      entry.append(tick, label, code);
      entry.addEventListener('click', () => choose(tag));
      item.append(entry);
      menu.append(item);
    });
  };

  button.addEventListener('click', (event) => {
    event.stopPropagation();
    if (menu.classList.contains('hidden')) open(); else close();
  });
  // Dismissed the two ways every menu is expected to be.
  document.addEventListener('click', (event) => {
    if (!menu.classList.contains('hidden') && !menu.contains(event.target)) close();
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !menu.classList.contains('hidden')) {
      close();
      button.focus();
    }
  });

  draw();
  applyTranslations();
  themeRelabel();
}

// ──────────────────────────────────────── the status strip ────────────────────────────────────────

function renderStatus(kind) {
  if (kind) state.status = kind;
  const status = state.status || 'idle';
  const dot = $('status-dot');
  const text = $('status-text');
  const tone = {
    idle: ['bg-zinc-300 dark:bg-edge', 'status.idle'],
    attached: ['bg-signal dot-live', 'status.attached'],
    reattaching: ['bg-signal', 'status.reattaching'],
    waiting: ['bg-waiting dot-live', 'status.waiting'],
    done: ['bg-settled', 'status.done'],
    stopped: ['bg-zinc-400 dark:bg-mist', 'status.stopped'],
    failed: ['bg-alarm', 'status.failed'],
  }[status] || ['bg-zinc-300 dark:bg-edge', 'status.idle'];
  dot.className = `size-1.5 rounded-full ${tone[0]}`;
  text.textContent = t(tone[1]);
  text.dataset.i18n = tone[1];
}

// ───────────────────────────────────────── conversations ─────────────────────────────────────────

let conversations = [];

async function loadConversations() {
  conversations = await api('/api/conversations');
  renderConversationList();
}

function renderConversationList() {
  const list = $('conversation-list');
  list.replaceChildren();
  if (!conversations.length) {
    const empty = document.createElement('li');
    empty.className = 'px-1 py-1.5 text-[12px] leading-relaxed text-mist';
    empty.textContent = t('nav.empty');
    list.append(empty);
    return;
  }
  conversations.forEach((conversation) => {
    const current = conversation.id === state.conversationId;
    const item = document.createElement('li');
    item.className = 'group relative';

    const open = document.createElement('button');
    open.className = 'flex w-full items-center gap-2 rounded-md py-1.5 pl-2 pr-7 text-left '
      + 'text-[13px] transition '
      + (current
        ? 'bg-zinc-200/70 font-medium dark:bg-rail'
        : 'text-zinc-600 hover:bg-zinc-100 dark:text-mist dark:hover:bg-rail/60');
    const dot = document.createElement('span');
    dot.className = conversation.live
      ? 'size-1.5 shrink-0 rounded-full bg-signal dot-live'
      : 'size-1.5 shrink-0 rounded-full bg-transparent';
    const title = document.createElement('span');
    title.className = 'min-w-0 flex-1 truncate';
    title.textContent = conversation.title || t('nav.untitled');
    open.append(dot, title);
    open.addEventListener('click', () => attempt(async () => {
      await openConversation(conversation.id);
      // Picking a conversation is what the drawer was opened for, so it gets out of the way.
      if (onNarrowScreen()) sidebarOpen(false);
    }));

    const remove = document.createElement('button');
    remove.className = 'absolute right-1 top-1/2 -translate-y-1/2 rounded px-1 text-[13px] '
      + 'text-mist opacity-0 transition hover:text-alarm focus-visible:opacity-100 '
      + 'group-hover:opacity-100';
    remove.textContent = '×';
    remove.setAttribute('aria-label', t('nav.delete'));
    remove.addEventListener('click', (event) => {
      event.stopPropagation();
      if (!window.confirm(t('delete.confirm'))) return;
      attempt(async () => {
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
      });
    });

    item.append(open, remove);
    list.append(item);
  });
}

async function newConversation() {
  const created = await api('/api/conversations', { method: 'POST' });
  await loadConversations();
  await openConversation(created.id);
  if (onNarrowScreen()) sidebarOpen(false);
  $('composer').focus();
}

function renderEmptyTranscript() {
  const transcript = $('transcript');
  const empty = document.createElement('div');
  empty.className = 'mx-auto flex h-full max-w-[46rem] flex-col justify-center gap-3 pb-16';
  const heading = document.createElement('p');
  heading.className = 'font-display text-[26px] font-semibold leading-tight tracking-tight';
  heading.textContent = t('empty.title');
  const body = document.createElement('p');
  body.className = 'max-w-[34rem] text-[14px] leading-relaxed text-mist';
  body.textContent = t('empty.body');
  empty.append(heading, body);
  transcript.append(empty);
}

async function openConversation(id) {
  closeStream();
  state.conversationId = id;
  state.runView = null;
  state.lastSeq = 0;
  window.location.hash = id;

  const transcript = $('transcript');
  transcript.replaceChildren();
  renderStatus('idle');

  // The transcript first: it comes from chat memory, so it is there after a restart of the server,
  // and it is what a reload has to show even when the run detail is long gone.
  const turns = await api(`/api/conversations/${id}/messages`);
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
  const conversation = conversations.find((it) => it.id === id);
  $('conversation-title').textContent = conversation?.title || t('nav.untitled');
  scrollToEnd(true);
}

function appendTurn(role, text) {
  const transcript = $('transcript');
  transcript.querySelector('.empty-state')?.remove();
  const wrapper = document.createElement('div');
  if (role === 'user') {
    wrapper.className = 'mx-auto mt-7 flex max-w-[46rem] justify-end first:mt-0';
    const bubble = document.createElement('div');
    bubble.className = 'max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-zinc-100 '
      + 'px-3.5 py-2.5 text-[14px] leading-relaxed dark:bg-rail';
    bubble.textContent = text ?? '';
    wrapper.append(bubble);
  } else {
    wrapper.className = 'mx-auto mt-4 max-w-[46rem] pl-[3.25rem]';
    const body = document.createElement('div');
    body.className = 'prose max-w-none text-[14.5px] leading-[1.7]';
    body.innerHTML = markdown(text);
    wrapper.append(body);
  }
  transcript.append(wrapper);
  return wrapper;
}

// ───────────────────────────────────────── the stream ─────────────────────────────────────────

function closeStream() {
  if (state.stream) {
    // deactivate rather than a bare close: it also stops the client's own reconnect timer, which a
    // close on its own would leave to fire and reopen a connection for a run we have finished with.
    state.stream.deactivate();
    state.stream = null;
  }
  setRunning(false);
}

function setRunning(running) {
  state.running = running;
  // Explicit display rather than toggling a `hidden` class: both buttons carry a display of their
  // own from .composer-action, and which of the two utilities wins would depend on the order
  // Tailwind happened to emit them in.
  $('send').style.display = running ? 'none' : 'grid';
  $('stop').style.display = running ? 'grid' : 'none';
  $('composer').placeholder = running
    ? t('composer.placeholder.running') : t('composer.placeholder');
  refreshSendState();
}

/** Send is inert until there is something to send — a message, or a file to talk about. */
function refreshSendState() {
  const send = $('send');
  if (!send) return;
  send.disabled = !$('composer').value.trim() && !state.attachments.length;
}

/**
 * Attaches to a run.
 *
 * `from` is what this page already has. It goes out as a `from` header on the subscribe, and again
 * on every subscribe the STOMP client makes after a dropped connection — with `state.lastSeq` by
 * then, since the point of a cursor is that it moves. That explicitness is the one real difference
 * from the SSE endpoint this replaced, where the browser resent `Last-Event-ID` unasked.
 *
 * A connection per run rather than one for the page: a subscription and the run it reads have the
 * same lifetime, so there is no state to keep straight between them, and this is what closing a tab
 * mid-answer already did.
 */
function attachRun(requestId, from) {
  closeStream();
  state.lastSeq = from ?? 0;
  state.replayThrough = 0;
  state.requestId = requestId;
  state.runView = state.runView ?? new RunView($('transcript'));
  setRunning(true);
  renderStatus('attached');

  // A backlog arrives in one burst; live events arrive one at a time. Only the latter animate — a
  // hundred rows sliding in on reattach would be a slot machine, and the point of reattaching is
  // that it looks like nothing happened.
  state.replaying = true;

  const handlers = {};
  const on = (name, handler) => { handlers[name] = handler; };
  const view = () => state.runView;

  on('content', (d) => (d.subagentId
    ? view().onSubagentContent(d.subagentId, d) : view().onContent(d)));
  on('reasoning', (d) => (d.subagentId ? undefined : view().onReasoning(d)));
  on('tool', (d) => (d.subagentId ? view().onSubagentTool(d.subagentId, d) : view().onTool(d)));
  on('tool-result', (d) => (d.subagentId ? undefined : view().onToolResult(d)));
  on('subagent', (d) => view().onSubagent(d));
  on('todos', (d) => (d.subagentId ? undefined : view().onTodos(d)));
  on('usage', (d) => (d.subagentId ? undefined : view().onUsage(d)));
  on('references', (d) => view().onReferences(d));
  on('queued', (d) => view().onQueued(d));
  on('queued-read', (d) => view().onQueuedRead(d));

  on('error', (d) => {
    view().onError(d);
    // In the run *and* as a toast: the run keeps the record where it happened, the toast is what
    // reaches somebody who has scrolled away or switched tabs.
    toast(d.message || t('run.failed'));
  });

  on('question', (d) => {
    // Drawn from the event while the page is open, and from /state after a reload. Same shape
    // either way, because both come from the same PendingQuestion row.
    renderQuestion({ pendingQuestionId: d.pendingQuestionId, questions: d.questions });
    renderStatus('waiting');
  });

  on('finished', (d) => {
    view().onFinished(d);
    closeStream();
    state.runView = null;
    const pending = document.querySelector('[data-question]');
    renderStatus(pending ? 'waiting'
      : d.outcome === 'CANCELLED' ? 'stopped'
      : d.outcome === 'FAILED' ? 'failed' : 'done');
    attempt(loadConversations);
  });

  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const client = new window.StompJs.Client({
    brokerURL: `${scheme}//${window.location.host}/ws/runs`,
    // Left to the client, and the reason a dropped network is a gap rather than a lost run: it
    // reconnects, we resubscribe from state.lastSeq, and the journal replays what we missed.
    reconnectDelay: 2000,
    // Off. The server sends no heartbeats of its own and reads none, and a client that expects them
    // tears down a perfectly good connection during a long tool call, when nothing is being said.
    heartbeatIncoming: 0,
    heartbeatOutgoing: 0,
  });
  state.stream = client;

  client.onConnect = () => {
    if (state.status === 'reattaching') toast(t('run.reattached'), 'settled', 2500);
    renderStatus(state.running ? 'attached' : state.status);
    client.subscribe(`/app/runs/${requestId}`, (frame) => {
      let event;
      try {
        event = JSON.parse(frame.body);
      } catch (e) {
        return; // a frame we cannot read is not worth tearing the stream down over
      }
      onRunEvent(event, handlers);
    }, { from: String(state.lastSeq) });
  };

  // A protocol-level ERROR frame, which closes the connection: the server refuses a subscribe it
  // cannot answer with a `gone` event instead, so reaching here means something else went wrong.
  client.onStompError = (frame) => {
    toast(frame.headers?.message || t('run.failed'));
    closeStream();
    renderStatus('idle');
  };
  client.onWebSocketClose = () => {
    // The client reconnects by itself and we resubscribe from the cursor. This is only to say so —
    // the run is unaffected either way. `active` is false once deactivate() has been called, which
    // is how a close we asked for is told apart from one we did not.
    if (client.active) {
      state.replaying = true;
      renderStatus('reattaching');
    }
  };

  client.activate();
}

/** One frame, routed by the type the server put in it. */
function onRunEvent(event, handlers) {
  if (event.type === 'replay') {
    // Where the backlog ends, so the rows that follow can be told apart from the ones happening
    // now. Sent before the first of them, and the reason this page no longer has to guess from the
    // shape of the traffic.
    state.replayThrough = Number(event.data?.through ?? 0);
    state.replaying = state.lastSeq < state.replayThrough;
    return;
  }
  if (event.type === 'gone') {
    // No journal for this run — evicted, or never ours. Either way the transcript is what is left,
    // and it is already on the page.
    closeStream();
    renderStatus('idle');
    return;
  }

  const handler = handlers[event.type];
  if (!handler) return;

  state.lastSeq = Number(event.seq || state.lastSeq);
  state.replaying = state.lastSeq <= state.replayThrough;

  const before = state.runView.body.childElementCount;
  state.runView.at(state.lastSeq);
  try {
    handler(event.data ?? {});
  } catch (e) {
    toast(t('error.render'));
    return;
  }
  if (!state.replaying) {
    for (let i = before; i < state.runView.body.childElementCount; i++) {
      state.runView.body.children[i].classList.add('run-row-live');
    }
  }
  scrollToEnd();
}


// ───────────────────────────────────────── attachments ─────────────────────────────────────────
//
// A file goes to the caller's artifacts directory — the same place a file sent to the bot on Feishu
// lands — and the message that follows names it, so the run knows there is something to look at.
// The agent reads it with the file and shell tools it already has; nothing is parsed here.

function renderAttachments() {
  const list = $('attachments');
  list.replaceChildren();
  list.classList.toggle('hidden', !state.attachments.length);
  list.classList.toggle('flex', state.attachments.length > 0);
  state.attachments.forEach((file, index) => {
    const chip = document.createElement('li');
    chip.className = 'flex items-center gap-1.5 rounded-md border border-zinc-200 bg-zinc-50 '
      + 'py-1 pl-2 pr-1 font-mono text-[11px] dark:border-rail dark:bg-panel';
    const name = document.createElement('span');
    name.className = 'max-w-[16rem] truncate';
    name.textContent = file.name;
    const size = document.createElement('span');
    size.className = 'text-mist';
    size.textContent = humanSize(file.size);
    const drop = document.createElement('button');
    drop.type = 'button';
    drop.className = 'px-1 text-mist transition hover:text-alarm';
    drop.textContent = '×';
    drop.setAttribute('aria-label', t('composer.attach.remove'));
    drop.addEventListener('click', () => {
      // Only from the next message. The file itself stays in their artifacts — it is theirs now,
      // and silently deleting somebody's file because they closed a chip would be a surprise.
      state.attachments.splice(index, 1);
      renderAttachments();
    });
    chip.append(name, size, drop);
    list.append(chip);
  });
  refreshSendState();
}

function humanSize(bytes) {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

async function uploadFiles(fileList) {
  const chosen = [...fileList];
  if (!chosen.length) return;

  await attempt(async () => {
    if (!state.conversationId) {
      const created = await api('/api/conversations', { method: 'POST' });
      state.conversationId = created.id;
      await loadConversations();
    }
    const form = new FormData();
    chosen.forEach((file) => form.append('files', file));
    const result = await api(`/api/conversations/${state.conversationId}/files`, {
      method: 'POST',
      body: form,
    });
    // The server's names, not the browser's: a collision is resolved on the way in, so the name the
    // message quotes has to be the one actually on disk or the agent looks for a file that is not
    // there.
    state.attachments.push(...(result.files || []));
    renderAttachments();
    toast(t('composer.attach.done', result.files.length), 'settled', 3000);
  });
}

function initAttachments() {
  const input = $('file-input');
  $('attach').addEventListener('click', () => input.click());
  input.addEventListener('change', () => {
    uploadFiles(input.files);
    // Reset, so choosing the same file twice in a row still fires a change event.
    input.value = '';
  });

  // Drag onto the page, which is what anybody tries first.
  const dropped = (event) => [...(event.dataTransfer?.files || [])];
  document.addEventListener('dragover', (event) => {
    if (event.dataTransfer?.types?.includes('Files')) {
      event.preventDefault();
      document.body.classList.add('dropping');
    }
  });
  document.addEventListener('dragleave', (event) => {
    if (!event.relatedTarget) document.body.classList.remove('dropping');
  });
  document.addEventListener('drop', (event) => {
    const files = dropped(event);
    if (!files.length) return;
    event.preventDefault();
    document.body.classList.remove('dropping');
    uploadFiles(files);
  });

  // Paste a screenshot straight in.
  $('composer').addEventListener('paste', (event) => {
    const files = [...(event.clipboardData?.files || [])];
    if (files.length) {
      event.preventDefault();
      uploadFiles(files);
    }
  });
}

// ────────────────────────────────────────── sending ──────────────────────────────────────────

async function send() {
  const composer = $('composer');
  const typed = composer.value.trim();
  // A message that is only files is a legitimate one — "here, look at this".
  if (!typed && !state.attachments.length) return;
  const attached = state.attachments.slice();
  // Named in the message rather than passed beside it, so what the model reads is exactly what the
  // person sees was sent. The directory is stated because that is where the agent's file tools look.
  const text = attached.length
    ? [typed, t('composer.attach.note', attached.map((f) => f.name).join(', '))]
      .filter(Boolean).join('\n\n')
    : typed;

  await attempt(async () => {
    if (!state.conversationId) {
      const created = await api('/api/conversations', { method: 'POST' });
      state.conversationId = created.id;
      await loadConversations();
    }
    // Cleared only once the request is on its way, so a failure does not also lose what they typed.
    const pending = text;
    composer.value = '';
    composer.style.height = 'auto';
    state.attachments = [];
    renderAttachments();
    $('transcript').querySelector('.mx-auto.flex.h-full')?.remove();
    appendTurn('user', pending);
    removeQuestion();
    scrollToEnd(true);

    let result;
    try {
      result = await api(`/api/conversations/${state.conversationId}/messages`, {
        method: 'POST',
        body: JSON.stringify({ text: pending }),
      });
    } catch (error) {
      // Give it back rather than swallow it: retyping a long message because the network blinked is
      // the worst thing this page could do to somebody. The files are already uploaded, so the
      // chips come back too rather than needing to be chosen again.
      composer.value = typed;
      state.attachments = attached;
      renderAttachments();
      throw error;
    }

    // Queued means it joined the run already going, which is already being streamed — attaching a
    // second time would draw the same run twice.
    if (result.queued) toast(t('run.queued.sent'), 'settled', 3000);
    else {
      state.runView = null;
      attachRun(result.requestId, 0);
    }
    await loadConversations();
  });
}

async function stop() {
  if (!state.requestId) return;
  await attempt(async () => {
    await api(`/api/runs/${state.requestId}/cancel`, { method: 'POST' });
  });
}

// ───────────────────────────────────────── questions ─────────────────────────────────────────

function removeQuestion() {
  document.querySelectorAll('[data-question]').forEach((node) => node.remove());
}

function renderQuestion(pending) {
  removeQuestion();
  const form = document.createElement('form');
  form.dataset.question = pending.pendingQuestionId;
  form.className = 'question mx-auto mt-5 max-w-[46rem] space-y-3';

  const head = document.createElement('div');
  head.className = 'flex items-center gap-2';
  const dot = document.createElement('span');
  dot.className = 'size-1.5 rounded-full bg-waiting dot-live';
  const title = document.createElement('span');
  title.className = 'font-mono text-[10px] font-medium uppercase tracking-[0.14em] text-waiting';
  title.textContent = t('question.title');
  head.append(dot, title);
  form.append(head);

  pending.questions.forEach((question) => {
    const block = document.createElement('fieldset');
    block.className = 'space-y-0.5';

    const legend = document.createElement('legend');
    legend.className = 'mb-1.5 text-[14px] font-medium';
    legend.textContent = question.question;
    block.append(legend);

    const type = question.multiSelect ? 'checkbox' : 'radio';
    question.options.forEach((option, index) => {
      const label = document.createElement('label');
      label.className = 'question-option';
      const input = document.createElement('input');
      input.type = type;
      input.name = `q${question.index}`;
      input.value = String(index);
      const text = document.createElement('span');
      const strong = document.createElement('span');
      strong.className = 'text-[13.5px] font-medium';
      strong.textContent = option.label;
      text.append(strong);
      if (option.description) {
        const description = document.createElement('span');
        description.className = 'block text-[11.5px] leading-snug text-mist';
        description.textContent = option.description;
        text.append(description);
      }
      label.append(input, text);
      block.append(label);
    });

    // Free text alongside the options, not instead of them: the tool's contract allows either, and
    // the answer somebody actually wants to give is often none of the four offered.
    const other = document.createElement('input');
    other.type = 'text';
    other.name = `q${question.index}-other`;
    other.placeholder = t('question.other');
    other.className = 'mt-1.5 ml-[1.55rem] w-[calc(100%-1.55rem)] rounded-lg border '
      + 'border-zinc-300 bg-white/70 px-2.5 py-1 text-[12.5px] outline-none '
      + 'focus:border-waiting dark:border-edge dark:bg-panel';
    block.append(other);

    form.append(block);
  });

  const submit = document.createElement('button');
  submit.type = 'submit';
  submit.className = 'rounded-lg bg-ink px-3.5 py-2 text-[13px] font-medium text-white '
    + 'transition hover:bg-zinc-700 disabled:opacity-40 dark:bg-paper dark:text-ink';
  submit.textContent = t('question.submit');
  form.append(submit);

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    submit.disabled = true;
    const label = submit.textContent;
    submit.textContent = t('question.sending');
    try {
      const answers = pending.questions.map((question) => ({
        index: question.index,
        optionIndexes: [...form.querySelectorAll(`input[name="q${question.index}"]:checked`)]
          .map((input) => Number(input.value)),
        text: (form.querySelector(`input[name="q${question.index}-other"]`) || {}).value || '',
      }));
      const result = await api(`/api/questions/${pending.pendingQuestionId}/answers`, {
        method: 'POST',
        body: JSON.stringify({ answers }),
      });
      form.remove();
      state.runView = null;
      attachRun(result.requestId, 0);
    } catch (error) {
      // The reasons this fails — somebody else answered, a later message replaced it, it expired —
      // are all things the person needs to read, so they go in the form as well as in a toast.
      let failure = form.querySelector('.question-failure');
      if (!failure) {
        failure = document.createElement('p');
        failure.className = 'question-failure text-[12.5px] text-alarm';
        form.append(failure);
      }
      failure.textContent = error.message;
      toast(error.message);
      submit.disabled = false;
      submit.textContent = label;
    }
  });

  $('transcript').append(form);
  scrollToEnd(true);
}

// ─────────────────────────────────────── scheduled tasks ───────────────────────────────────────

async function loadTasks() {
  const tasks = await api('/api/tasks');
  const list = $('task-list');
  list.replaceChildren();
  if (!tasks.length) {
    const empty = document.createElement('li');
    empty.className = 'px-1 text-[12px] text-mist';
    empty.textContent = t('task.none');
    list.append(empty);
    return;
  }
  tasks.forEach((task) => {
    const item = document.createElement('li');
    item.className = 'group rounded-lg border border-zinc-200 px-2.5 py-2 transition '
      + 'hover:border-zinc-300 dark:border-rail dark:hover:border-edge';

    const when = document.createElement('div');
    when.className = 'flex items-center gap-1.5';
    const clock = document.createElement('span');
    clock.className = 'size-1.5 rounded-full bg-waiting';
    const cron = document.createElement('code');
    cron.className = 'font-mono text-[11px] text-waiting';
    cron.textContent = task.cron || task.scheduledAt;
    when.append(clock, cron);
    item.append(when);

    const text = document.createElement('p');
    text.className = 'line-clamp-2 mt-1 text-[12px] leading-relaxed text-zinc-600 dark:text-mist';
    text.textContent = task.text;
    item.append(text);

    const meta = document.createElement('div');
    meta.className = 'mt-1.5 flex items-center gap-2 font-mono text-[10px] text-mist';
    if (task.maxRuns) {
      meta.append(document.createTextNode(t('task.runs', task.runCount ?? 0, task.maxRuns)));
    }
    if (task.background) meta.append(document.createTextNode(t('task.background')));
    item.append(meta);

    const actions = document.createElement('div');
    actions.className = 'mt-1.5 flex gap-2.5 opacity-0 transition group-hover:opacity-100 '
      + 'focus-within:opacity-100';
    if (task.conversationId) {
      const open = document.createElement('button');
      open.className = 'font-mono text-[10px] uppercase tracking-wider text-mist hover:text-signal';
      open.textContent = t('task.open');
      open.addEventListener('click', () => attempt(async () => {
        await openConversation(task.conversationId);
        if (onNarrowScreen()) sidebarOpen(false);
      }));
      actions.append(open);
    }
    const cancel = document.createElement('button');
    cancel.className = 'font-mono text-[10px] uppercase tracking-wider text-mist hover:text-alarm';
    cancel.textContent = t('task.cancel');
    cancel.addEventListener('click', () => attempt(async () => {
      await api(`/api/tasks/${task.id}`, { method: 'DELETE' });
      await loadTasks();
      toast(t('task.cancelled'), 'settled', 3000);
    }));
    actions.append(cancel);
    item.append(actions);

    list.append(item);
  });
}

// ───────────────────────────────────── refused, and why ─────────────────────────────────────

/**
 * What a signed-in person who this deployment does not serve sees.
 *
 * Without this they got a page of failed requests and a bare 403, which is indistinguishable from
 * the server being broken — so the first thing anybody did was file a bug against the wrong thing.
 */
function renderDenied(me) {
  $('sidebar').remove();
  document.querySelector('main .border-t')?.remove();
  const transcript = $('transcript');
  transcript.replaceChildren();

  const box = document.createElement('div');
  box.className = 'mx-auto flex h-full max-w-[34rem] flex-col justify-center gap-4 pb-16';

  const label = document.createElement('span');
  label.className = 'font-mono text-[10px] font-medium uppercase tracking-[0.14em] text-alarm';
  label.textContent = t('denied.label');

  const heading = document.createElement('h2');
  heading.className = 'font-display text-[24px] font-semibold leading-tight tracking-tight';
  heading.textContent = t('denied.title');

  const body = document.createElement('p');
  body.className = 'text-[14px] leading-relaxed text-mist';
  body.textContent = t('denied.body');

  // The tenant is the whole of the diagnosis, so it is on the page rather than only in the log.
  const facts = document.createElement('dl');
  facts.className = 'grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 font-mono text-[11px]';
  [[t('denied.you'), me.name || me.userId], [t('denied.tenant'), me.tenantId || '—']]
    .forEach(([key, value]) => {
      const dt = document.createElement('dt');
      dt.className = 'text-mist';
      dt.textContent = key;
      const dd = document.createElement('dd');
      dd.textContent = value;
      facts.append(dt, dd);
    });

  const out = document.createElement('form');
  out.method = 'post';
  out.action = '/logout';
  const token = document.createElement('input');
  token.type = 'hidden';
  token.name = '_csrf';
  token.value = csrfToken();
  const button = document.createElement('button');
  button.type = 'submit';
  button.className = 'rounded-lg border border-zinc-300 px-3 py-1.5 text-[13px] '
    + 'hover:border-zinc-400 dark:border-edge dark:hover:border-mist';
  button.textContent = t('denied.signout');
  out.append(token, button);

  box.append(label, heading, body, facts, out);
  transcript.append(box);
  renderStatus('failed');
}


// ─────────────────────────────────────── the sidebar drawer ───────────────────────────────────────
//
// A drawer below md, a column at md and up. Opening it is easy to get right and closing it is what
// gets forgotten: the header's own toggle is behind the drawer once it is open, so there has to be
// a way out from inside it — a close button, the backdrop, and Escape.

function sidebarOpen(open) {
  const sidebar = $('sidebar');
  const backdrop = $('sidebar-backdrop');
  if (!sidebar) return; // removed on the no-access screen
  sidebar.classList.toggle('sidebar-open', open);
  backdrop.hidden = !open;
  $('toggle-sidebar').setAttribute('aria-expanded', String(open));
  // The page behind a modal drawer must not scroll under it.
  document.body.classList.toggle('drawer-open', open);
}

function sidebarIsOpen() {
  return $('sidebar')?.classList.contains('sidebar-open');
}

/** True only where the sidebar is a drawer; at md and up it is always on screen. */
function onNarrowScreen() {
  return window.matchMedia('(max-width: 767.98px)').matches;
}

function initSidebar() {
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

// ────────────────────────────────────────── wiring ──────────────────────────────────────────

function scrollToEnd(force) {
  const transcript = $('transcript');
  // Only when the reader is already at the bottom, so scrolling up to read something earlier is not
  // undone by the next delta.
  const atBottom = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 140;
  if (force || atBottom) transcript.scrollTop = transcript.scrollHeight;
}

function initComposer() {
  const composer = $('composer');
  composer.addEventListener('input', () => {
    composer.style.height = 'auto';
    composer.style.height = `${Math.min(composer.scrollHeight, 208)}px`;
    refreshSendState();
  });
  composer.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      send();
    }
  });
  $('send').addEventListener('click', send);
  $('stop').addEventListener('click', stop);
  setRunning(false);
  $('new-conversation').addEventListener('click', () => attempt(newConversation));
  initAttachments();
  initSidebar();
}

async function start() {
  initTheme();

  try {
    state.me = await api('/api/me');
  } catch (error) {
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
    renderDenied(state.me);
    return;
  }

  initComposer();
  initLanguage(state.me);
  renderStatus('idle');
  $('me-name').textContent = state.me.name || state.me.userId;
  if (state.me.avatar) $('me-avatar').src = state.me.avatar;
  $('logout-csrf').value = csrfToken();

  await attempt(async () => {
    await loadConversations();
    await loadTasks();

    // Deep-linking on the hash, so a reload lands back in the conversation that was open rather
    // than at the top of the list — which for a run in progress is the difference between watching
    // it continue and having to find it again.
    const wanted = window.location.hash.slice(1);
    const target = conversations.find((it) => it.id === wanted) || conversations[0];
    if (target) await openConversation(target.id);
    else renderEmptyTranscript();
  });
}

start();
