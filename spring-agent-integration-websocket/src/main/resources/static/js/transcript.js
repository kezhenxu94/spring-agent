// What a conversation looks like when nothing is running: the turns chat memory kept, the tool calls
// it kept beside them, and the invitation shown where there are none.
//
// The two kinds of turn are drawn as markdown, through the same sanitiser. That the person wrote one
// of them is not a reason to trust it: a replayed transcript is whatever chat memory holds, which
// includes turns another surface wrote. A tool call is not markdown at all — name, arguments and
// result go in through textContent, having come from whatever the tool read.

import { t } from './i18n.js';
import { $, scrollToEnd, transcriptAtEnd } from './dom.js';
import { RunView, markdown } from './render.js';
import { api } from './api.js';
import { skeletonProse } from './busy.js';
import { bus, state } from './state.js';

/**
 * The way back to the end of a conversation somebody has scrolled up out of.
 *
 * Two things can change the answer and only one of them is a scroll: the reader moving, and the
 * transcript being emptied and refilled for another conversation, which leaves scrollTop at zero
 * and so fires no scroll event at all. `scrollToEnd` says when the second has happened — see
 * dom.js — and everything that appends to the transcript already calls it.
 */
export function initScrollToEnd() {
  const transcript = $('transcript');
  const button = $('scroll-end');
  // Reading scroll offsets is a layout read, and a streaming answer emits a delta a token; on a
  // frame rather than on the event, so a long answer costs one read a frame instead of hundreds.
  let queued = false;
  const check = () => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(() => {
      queued = false;
      button.hidden = transcriptAtEnd();
    });
  };
  transcript.addEventListener('scroll', check, { passive: true });
  bus.on('transcript:scrolled', check);
  // The transcript is `scroll-smooth`, so setting the offset is the whole of it. Focus is left
  // where it was rather than moved to the composer: this is a request to *read* the end of the
  // conversation, and on a phone taking the caret would open the keyboard over what was asked for.
  button.addEventListener('click', () => scrollToEnd(true));
  check();
}

export function renderEmptyTranscript() {
  const transcript = $('transcript');
  const empty = document.createElement('div');
  // At the top, where the schedule's and the knowledge base's own openings are. It was centred in
  // the height of the panel, which put the one screen a person sees most in a different place from
  // the two beside it — and moved it every time the window was resized, while neither of the others
  // budged. A first turn is drawn from the top, so this is also where the answer to it will appear.
  // `empty-state` is not styling: it is the handle appendTurn and appendTools take this block away
  // by when the first message of a conversation arrives. Both have always looked for it and it has
  // never been here, so the opening stayed on screen above the turn that answered it.
  //
  // The three openings are the same block at the same size — #tasks-intro and #knowledge-intro in
  // index.html are its markup, and this is the one drawn in JavaScript. A person moving between the
  // sections is looking at one page, so a heading two steps larger here would read as a different
  // page rather than as the same one with nothing in it yet.
  empty.className = 'empty-state page-column flex flex-col gap-1';
  const heading = document.createElement('p');
  heading.className = 'font-display text-[20px] font-semibold tracking-tight';
  heading.textContent = t('empty.title');
  const body = document.createElement('p');
  body.className = 'max-w-[38rem] text-[12.5px] leading-relaxed text-mist';
  body.textContent = t('empty.body');
  empty.append(heading, body);
  transcript.append(empty);
}

/**
 * The rail of the round being drawn, so that everything one round did shares one spine.
 *
 * A live run is a single `RunView`: its thinking, its tool calls and its answer are stations on one
 * rail. A replay builds the same round from separate rows, and a `RunView` each would draw two
 * rails — which `.run + .run` in run.css then separates by 2rem, because two `.run` blocks mean two
 * *runs* there. So the fold under a user message and the tool calls of the same round go into one
 * view, and a new user message is what starts the next.
 *
 * Reset per user turn rather than per conversation, plus the `isConnected` check where it is read:
 * opening another conversation empties the transcript, and appending to a view whose root is no
 * longer in the document would draw nothing at all.
 */
let roundView = null;

/**
 * The tool calls one assistant message made, as a replayed conversation shows them.
 *
 * Drawn through `RunView` rather than rebuilt here, which is the whole point: a person looking at a
 * conversation they reloaded is looking at the same run they watched, and a second set of markup
 * that merely resembled the first would drift away from it the next time render.js changed. So the
 * fold, the rail, the dot, the tick and the `.tool-io` blocks are not copied — they are the same
 * code, fed the same event shapes the stream feeds it.
 *
 * Two things differ, and both are the absence of a journal rather than a choice of style. The
 * gutter carries no sequence number, because chat memory has no cursor — see `row` in render.js.
 * And the outcome is set straight away, so the rail does not run its travelling highlight: that
 * animation means "this is still moving", and nothing replayed is.
 */
export function appendTools(tools) {
  const transcript = $('transcript');
  transcript.querySelector('.empty-state')?.remove();
  const view = roundView?.root.isConnected ? roundView : new RunView(transcript);
  roundView = view;
  // Before the rows rather than after, so the rail is never live even for a frame. COMPLETED is
  // not a claim about how the run ended — chat memory does not keep that — it is only what says
  // the run is over; the two outcomes that are drawn differently are set by a live run alone.
  view.onFinished({ outcome: 'COMPLETED' });
  tools.forEach((tool) => {
    view.onTool({ id: tool.id, name: tool.name, input: tool.input });
    // A call with no result is one the conversation holds no answer to — the run was stopped, or
    // memory was trimmed between the two — and it keeps the dot a running call has rather than
    // being drawn as though it had come back.
    if (tool.result !== null && tool.result !== undefined) {
      view.onToolResult({ id: tool.id, result: tool.result });
    }
  });
  return view.root;
}

/**
 * What one round thought, as a fold under the message that started it — and empty until somebody
 * opens it.
 *
 * Drawn through `RunView` for the reason `appendTools` gives: a person looking at a conversation
 * they reloaded is looking at the run they watched, so this is the same fold, dot and rail a live
 * run streams its thinking into rather than a second set of markup that would drift from it.
 *
 * The text is not here and is not in the transcript either: a round's thinking is routinely longer
 * than the whole of the rest of the conversation, and most rounds are read without anybody wanting
 * it. So the transcript carries only the id of the run that produced it, and this asks for the text
 * the first time the fold is opened.
 *
 * The conversation is captured here rather than read when the fetch happens: by then the reader may
 * have opened another one, and `state.conversationId` would name that one instead.
 */
function appendReasoning(requestId) {
  const conversationId = state.conversationId;
  const transcript = $('transcript');
  const view = new RunView(transcript);
  // The round's rail from here on: its tool calls join this one rather than starting a second.
  roundView = view;
  // Before the row rather than after, so the rail is never live even for a frame — the travelling
  // highlight means "this is still moving", and nothing replayed is. Same as `appendTools`.
  view.onFinished({ outcome: 'COMPLETED' });
  const panel = view.fold('reasoning', t('run.thinking'), 'mist');

  let asked = false;
  panel.details.addEventListener('toggle', async () => {
    if (!panel.details.open || asked) return;
    asked = true;
    // In the fold rather than over the page: the reader opened this one block and nothing else is
    // waiting, and the lines are the shape of what is coming. Taken away on both paths below,
    // which is why the helper hands back the way to undo it.
    const settle = skeletonProse(panel.body);
    try {
      const got = await api(`/api/conversations/${conversationId}/reasoning/${requestId}`);
      settle();
      // The same sanitiser every other replayed turn goes through. This text was written by a
      // model, which is reason enough on its own.
      panel.body.innerHTML = markdown(got.text ?? '');
    } catch (e) {
      settle();
      // Gone — evicted with the conversation, or a request that never arrived. Said in the fold
      // rather than as a toast, because that is where the reader is looking; and `asked` goes back
      // so that closing and opening it again retries, which is the whole recovery a dropped
      // connection needs.
      asked = false;
      panel.body.textContent = t('run.thinking.gone');
    }
  });
  return view.root;
}

export function appendTurn(role, text, reasoningId) {
  const transcript = $('transcript');
  transcript.querySelector('.empty-state')?.remove();
  const wrapper = document.createElement('div');
  if (role === 'user') {
    // A new round, so whatever rail the last one was drawn on is finished with.
    roundView = null;
    wrapper.className = 'page-column mt-7 flex justify-end first:mt-0';
    const bubble = document.createElement('div');
    // Markdown, as the answer is — see .turn-user in run.css for why, and note that `breaks: true`
    // keeps a plain multi-line message looking exactly as it was typed.
    bubble.className = 'turn-user prose';
    bubble.innerHTML = markdown(text ?? '');
    wrapper.append(bubble);
  } else {
    wrapper.className = 'page-column mt-4';
    const body = document.createElement('div');
    body.className = 'prose max-w-none text-[14.5px] leading-[1.7]';
    body.innerHTML = markdown(text);
    wrapper.append(body);
  }
  transcript.append(wrapper);
  // Under the message that caused it, which is where a live run puts its own thinking. Only a user
  // turn ever carries one — see `ChatSessions.Turn`.
  if (reasoningId) appendReasoning(reasoningId);
  return wrapper;
}
