// What the page knows, and how one part of it tells another something happened.
//
// Two things live here because everything else imports them and nothing here imports anything: the
// state every module reads, and a bus for the few messages that would otherwise be a cycle.
//
// The import rule this file exists to make possible: modules are layered, and a module may import
// only ones earlier than itself — the core (this, i18n, dom, render, busy, api, toast, route), then
// the two pieces every section reuses to ask before it destroys something and to offer what can be
// done to a row (confirm, menu), then status, theme and sidebar, then questions, stream, conversations,
// attachments, composer, and finally tasks, knowledge, language and denied. app.js is the only file
// that imports across the whole set, and the only place the wiring is visible.
//
// An edge that would point backwards goes through the bus instead. That matters more here than the
// tidiness suggests: an ES module cycle does not fail, it resolves the binding to `undefined` and
// throws at the call, which is a bug that only shows up on the path nobody clicked.

export const state = {
  me: null,
  conversationId: null,
  // What /api/conversations last returned. Read by the list, by the title in the header, and by the
  // hash lookup at startup, so it is here rather than private to the module that fetches it.
  conversations: [],
  // What /api/tasks last returned, and which of them is open. Here rather than private to the
  // module that fetches them, because the list, the panel and the header all read the same two.
  tasks: [],
  taskId: null,
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

const listeners = new Map();

export const bus = {
  /** Subscribes to a message. Several modules may listen to the same one. */
  on(name, handler) {
    const existing = listeners.get(name);
    if (existing) existing.push(handler);
    else listeners.set(name, [handler]);
  },
  /**
   * Announces something. Nothing is delivered to a message nobody listens to, deliberately: a
   * module emits what happened, and whether anything cares is app.js's business, not its own.
   */
  emit(name, payload) {
    (listeners.get(name) || []).forEach((handler) => handler(payload));
  },
};
