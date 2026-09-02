// The connection that reads a run.
//
// The one idea to hold on to: a subscription here is a *reader* of a run that is happening on the
// server whether or not this page exists. Nothing on this side starts a run except POSTing a
// message, and nothing on this side stops one except pressing Stop. Closing the tab, reloading,
// losing the network — all of them only end a connection.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { toast } from './toast.js';
import { RunView } from './render.js';
import { renderStatus } from './status.js';
import { onRunEvent, runHandlers } from './run-events.js';
import { bus, state } from './state.js';

export function closeStream() {
  if (state.stream) {
    // deactivate rather than a bare close: it also stops the client's own reconnect timer, which a
    // close on its own would leave to fire and reopen a connection for a run we have finished with.
    state.stream.deactivate();
    state.stream = null;
  }
  setRunning(false);
}

/** Announced rather than done here: which buttons a running run shows is the composer's business. */
function setRunning(running) {
  state.running = running;
  bus.emit('run:running', running);
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
export function attachRun(requestId, from) {
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

  const controls = { close: closeStream };
  const handlers = runHandlers(controls);

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
      onRunEvent(event, handlers, controls);
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
