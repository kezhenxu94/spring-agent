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
import { bus } from './state.js';

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
  const view = new RunView(transcript);
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

export function appendTurn(role, text) {
  const transcript = $('transcript');
  transcript.querySelector('.empty-state')?.remove();
  const wrapper = document.createElement('div');
  if (role === 'user') {
    wrapper.className = 'mx-auto mt-7 flex max-w-[46rem] justify-end first:mt-0';
    const bubble = document.createElement('div');
    // Markdown, as the answer is — see .turn-user in run.css for why, and note that `breaks: true`
    // keeps a plain multi-line message looking exactly as it was typed.
    bubble.className = 'turn-user prose';
    bubble.innerHTML = markdown(text ?? '');
    wrapper.append(bubble);
  } else {
    wrapper.className = 'mx-auto mt-4 max-w-[46rem]';
    const body = document.createElement('div');
    body.className = 'prose max-w-none text-[14.5px] leading-[1.7]';
    body.innerHTML = markdown(text);
    wrapper.append(body);
  }
  transcript.append(wrapper);
  return wrapper;
}
