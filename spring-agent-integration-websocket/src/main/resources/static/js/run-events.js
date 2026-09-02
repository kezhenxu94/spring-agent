// What each frame a run streams does to the page.
//
// Separate from the connection that carries them so that neither has to be read to understand the
// other: this file knows the vocabulary, stream.js knows the socket. What a handler needs from the
// connection is passed in rather than imported, which is also what keeps the two acyclic.

import { t } from './i18n.js';
import { scrollToEnd } from './dom.js';
import { toast } from './toast.js';
import { renderStatus } from './status.js';
import { renderQuestion } from './questions.js';
import { bus, state } from './state.js';

/**
 * The handler table for one attachment.
 *
 * `close` is the connection's own teardown: a run that has finished has nothing more to send, and
 * leaving the socket up would leave its reconnect timer up with it.
 */
export function runHandlers({ close }) {
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
    close();
    state.runView = null;
    const pending = document.querySelector('[data-question]');
    renderStatus(pending ? 'waiting'
      : d.outcome === 'CANCELLED' ? 'stopped'
      : d.outcome === 'FAILED' ? 'failed' : 'done');
    bus.emit('conversations:changed');
  });

  return handlers;
}

/** One frame, routed by the type the server put in it. */
export function onRunEvent(event, handlers, { close }) {
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
    close();
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
