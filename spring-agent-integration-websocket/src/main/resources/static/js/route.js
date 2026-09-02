// Where the page is, kept in the address bar.
//
// The hash is the whole of the navigation state, so the back button, a reload and a pasted link all
// land in the same place — which for a run in progress is the difference between watching it carry
// on and having to find it again.
//
// Navigation is one-way: something clicked calls `go`, the hash changes, and app.js reacts to the
// change by opening whatever the route names. Nothing opens a conversation and *then* writes the
// hash, because that leaves the two able to disagree — and the one that disagrees is the one the
// back button reads.

const CHAT = '#/chat/';
const KNOWLEDGE = '#/kb';
const TASKS = '#/tasks';

/** The knowledge bases a document can be in, for reading one back out of a route. */
const SCOPES = ['own', 'group', 'tenant'];

/** The hash that opens a conversation, or the empty conversation list when there is none. */
export function chatRoute(conversationId) {
  return conversationId ? CHAT + encodeURIComponent(conversationId) : '#/chat';
}

/**
 * The hash that opens the knowledge base, or one document in it.
 *
 * The knowledge base the document is in is part of the route, because an id is unique inside one
 * base and not across them: the same file filed privately and company-wide is two documents
 * wearing one id, and a route naming only the id would open whichever of them the list happened to
 * hold first — then offer a delete for it under the other one's title.
 *
 * The id is encoded because a document indexed from a file is identified by its absolute path, so
 * it contains slashes — which would otherwise read as more of the route. The scope is not, and is
 * what lets the two be told apart on the way back: it is one of a closed set of words with no
 * slash in any of them, so the segment before the first slash is the scope whenever it is one.
 */
export function knowledgeRoute(docId, scope) {
  if (!docId) return KNOWLEDGE;
  const id = encodeURIComponent(docId);
  return scope ? `${KNOWLEDGE}/${scope}/${id}` : `${KNOWLEDGE}/${id}`;
}

/** The hash that shows what the agent has been asked to do later, or one of those tasks. */
export function tasksRoute(taskId) {
  return taskId ? `${TASKS}/${encodeURIComponent(taskId)}` : TASKS;
}

/** What a hash means: which section, and what is selected in it. */
export function parse(hash) {
  const raw = (hash || '').replace(/^#/, '');
  if (raw.startsWith('/kb')) {
    const rest = raw.slice('/kb'.length).replace(/^\//, '');
    if (!rest) return { view: 'knowledge', id: null, scope: null };
    const cut = rest.indexOf('/');
    const head = cut < 0 ? rest : rest.slice(0, cut);
    // A link kept from before the scope was in the route names only the id. Still opened, with
    // whichever copy of it the list holds — the same document as it used to open.
    if (cut < 0 || !SCOPES.includes(head)) {
      return { view: 'knowledge', id: decode(rest), scope: null };
    }
    return { view: 'knowledge', id: decode(rest.slice(cut + 1)), scope: head };
  }
  if (raw.startsWith('/tasks')) {
    const rest = raw.slice('/tasks'.length).replace(/^\//, '');
    return { view: 'tasks', id: rest ? decode(rest) : null };
  }
  if (raw.startsWith('/chat')) {
    const rest = raw.slice('/chat'.length).replace(/^\//, '');
    return { view: 'chat', id: rest ? decode(rest) : null };
  }
  // A bare id, which is what this page's links were before there was more than one section to be
  // in. Still read as a conversation, so a link somebody kept still opens what it used to.
  return { view: 'chat', id: raw || null };
}

export function current() {
  return parse(window.location.hash);
}

/**
 * Goes somewhere.
 *
 * Assigning the same hash fires no event, so a caller that is already there is answered by the
 * handler directly rather than silently doing nothing — selecting the conversation you are in
 * should still close the drawer.
 */
export function go(hash) {
  if (window.location.hash === hash) {
    onSame(parse(hash));
    return;
  }
  window.location.hash = hash;
}

let onSame = () => {};

/** Registers the one handler that acts on a route, whether it was navigated to or re-selected. */
export function onRoute(handler) {
  onSame = handler;
  window.addEventListener('hashchange', () => handler(current()));
}

function decode(value) {
  try {
    return decodeURIComponent(value);
  } catch (e) {
    return value; // a hand-typed hash with a stray % is still worth trying to open
  }
}
