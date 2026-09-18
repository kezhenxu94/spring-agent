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

/**
 * What can narrow the knowledge base's list, in the order a route spells it.
 *
 * All three live in the address bar rather than in the page, and that is the point: a list that has
 * been searched, filtered to a scope or pointed at somebody else's knowledge base is a different
 * list, and it was not reachable by a link, a reload or the back button until it had a spelling of
 * its own. `q` is the committed search — what was typed and entered, not what is in the box.
 */
const NARROWING = ['q', 'owner', 'scope'];

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
export function knowledgeRoute(docId, scope, narrowing) {
  const path = !docId ? KNOWLEDGE
    : scope ? `${KNOWLEDGE}/${scope}/${encodeURIComponent(docId)}`
      : `${KNOWLEDGE}/${encodeURIComponent(docId)}`;
  // Left out, the narrowing is whatever the address bar already says. That default is what keeps
  // every existing caller right: opening a document from a list that is a search, a scope or
  // somebody else's knowledge base means opening it *in* that list, and a route that quietly
  // dropped the narrowing would answer the click by throwing away what was being read. Clearing is
  // therefore something a caller has to ask for, by passing one.
  return path + query(narrowing === undefined ? current().narrowing : narrowing);
}

/**
 * What the list is narrowed to, as a query string.
 *
 * The keys are written in a fixed order rather than in whatever order they were set, because `go`
 * decides whether this is a move at all by comparing the hash against the one in the address bar —
 * and two spellings of one place would each read as a move to the other, which is a history entry
 * per press and a back button that goes nowhere.
 */
function query(narrowing) {
  const params = new URLSearchParams();
  NARROWING.forEach((key) => {
    const value = ((narrowing || {})[key] || '').trim();
    if (value) params.set(key, value);
  });
  const text = params.toString();
  return text ? `?${text}` : '';
}

/** The hash that shows what the agent has been asked to do later, or one of those tasks. */
export function tasksRoute(taskId) {
  return taskId ? `${TASKS}/${encodeURIComponent(taskId)}` : TASKS;
}

/** What a hash means: which section, and what is selected in it. */
export function parse(hash) {
  const whole = (hash || '').replace(/^#/, '');
  // The narrowing is split off before anything else, so that a `?` can never be read as part of a
  // document id — which, being a file's absolute path, is the one segment here allowed odd
  // characters in it.
  const mark = whole.indexOf('?');
  const raw = mark < 0 ? whole : whole.slice(0, mark);
  const narrowing = readNarrowing(mark < 0 ? '' : whole.slice(mark + 1));
  if (raw.startsWith('/kb')) {
    const rest = raw.slice('/kb'.length).replace(/^\//, '');
    if (!rest) return { view: 'knowledge', id: null, scope: null, narrowing };
    const cut = rest.indexOf('/');
    const head = cut < 0 ? rest : rest.slice(0, cut);
    // A link kept from before the scope was in the route names only the id. Still opened, with
    // whichever copy of it the list holds — the same document as it used to open.
    if (cut < 0 || !SCOPES.includes(head)) {
      return { view: 'knowledge', id: decode(rest), scope: null, narrowing };
    }
    return { view: 'knowledge', id: decode(rest.slice(cut + 1)), scope: head, narrowing };
  }
  if (raw.startsWith('/tasks')) {
    const rest = raw.slice('/tasks'.length).replace(/^\//, '');
    return { view: 'tasks', id: rest ? decode(rest) : null, narrowing };
  }
  if (raw.startsWith('/chat')) {
    const rest = raw.slice('/chat'.length).replace(/^\//, '');
    return { view: 'chat', id: rest ? decode(rest) : null, narrowing };
  }
  // A bare id, which is what this page's links were before there was more than one section to be
  // in. Still read as a conversation, so a link somebody kept still opens what it used to.
  return { view: 'chat', id: raw || null, narrowing };
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

/**
 * The narrowing a hash carries, as `{ q, owner, scope }` with a blank for each one absent.
 *
 * An unknown scope is dropped rather than carried through. It can only arrive by a hand-typed or
 * stale link, and passing it on would put the page in a state no control can undo — the menu offers
 * the scopes this surface has, and none of them is the one the list would then be filtered to.
 */
function readNarrowing(text) {
  const params = new URLSearchParams(text);
  const scope = params.get('scope') || '';
  return {
    q: params.get('q') || '',
    owner: params.get('owner') || '',
    scope: SCOPES.includes(scope) ? scope : '',
  };
}

function decode(value) {
  try {
    return decodeURIComponent(value);
  } catch (e) {
    return value; // a hand-typed hash with a stray % is still worth trying to open
  }
}
