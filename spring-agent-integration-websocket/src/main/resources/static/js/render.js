// Turning run events into DOM.
//
// The shape is a spine: a hairline rail in the gutter to the left of the column, with the journal's
// own sequence number beside each thing that happened. The numbers are not decoration — that
// sequence is the cursor the browser sends back as Last-Event-ID, so what the rail draws is
// literally how far this page has got through the run. On a reattach you watch it rebuild to where
// you were. The answer itself is not on that spine but flush in the column, level with the person's
// own message; see run.css.
//
// One RunView per run. Panels are created on first use, so a plain answer stays a plain answer.
// Every event carries a subagentId, null for the run itself, which routes a delta to the right card.

import { t } from './i18n.js';

// Model output is untrusted. It is markdown written by something that has just read tool results,
// web pages and files, any of which can carry markup — so it goes through DOMPurify on the way to
// innerHTML, every time, with no exception for "our own" text.
export function markdown(text) {
  const html = window.marked.parse(text ?? '', { breaks: true, gfm: true });
  return window.DOMPurify.sanitize(html, { USE_PROFILES: { html: true } });
}

function el(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  // textContent, not innerHTML: everything through here is a label or a tool argument, and neither
  // is ours to trust either.
  if (text !== undefined) node.textContent = text;
  return node;
}

/**
 * A tool's arguments or its result, as something a person can read.
 *
 * Both arrive as JSON: the arguments are what the model wrote, and a result is whatever the tool
 * returned, serialized on its way back to the model. So a tool that returns a string arrives as a
 * quoted literal with its newlines escaped — `"bash_id: shell_1\n\nroot\n"` — which is the least
 * readable form of the most-read thing in a run. Parsed back and re-rendered: a string becomes its
 * own text, an object or a list becomes indented JSON, and anything that is not JSON at all is left
 * exactly as it came.
 *
 * A result the server truncated at `MAX_TOOL_RESULT_CHARACTERS` (see WebRunRenderer) is no longer
 * valid JSON, and long shell output is exactly what gets truncated — so leaving that case alone
 * would leave the commonest result the one still full of `\n`. A cut string literal is recoverable:
 * close the quote the cut took away and parse that instead, keeping the ellipsis that says there
 * was more. Anything the cut left unrecoverable — it landed mid-escape, or the value was an object
 * rather than a string — is shown exactly as it came, which is the honest outcome.
 *
 * A number or a boolean is left as its own text too: `42` is already what it says, and unwrapping
 * it would only risk turning a tool's exact output into a rounded one.
 */
function readable(text) {
  if (typeof text !== 'string' || !text) return text;
  try {
    const value = JSON.parse(text);
    if (typeof value === 'string') return value;
    if (value !== null && typeof value === 'object') return JSON.stringify(value, null, 2);
    return text;
  } catch {
    return truncatedString(text);
  }
}

/** The server's marker for a result it cut short, which is a cut it made in the JSON. */
const TRUNCATED = '\n…';

function truncatedString(text) {
  if (!text.startsWith('"')) return text;
  const cut = text.endsWith(TRUNCATED);
  try {
    return JSON.parse(`${cut ? text.slice(0, -TRUNCATED.length) : text}"`) + (cut ? TRUNCATED : '');
  } catch {
    return text;
  }
}

const PROSE = 'prose max-w-none text-[14.5px] leading-[1.7]';
const LABEL = 'font-mono text-[10px] font-medium uppercase tracking-[0.14em]';

export class RunView {
  constructor(container) {
    this.root = el('div', 'run mx-auto max-w-[46rem]');

    this.body = el('div', 'run-body');
    this.root.append(this.body);
    container.append(this.root);

    // Accumulated rather than appended as HTML: markdown is not a stream format, and re-parsing the
    // whole answer each tick is the only way a list or a fence that is half-arrived renders as what
    // it will become.
    this.answerText = '';
    this.reasoningText = '';

    this.nodes = {};
    this.subagents = new Map();
    this.toolCalls = new Map();
    this.seq = 0;
    this.finished = false;
  }

  /** The number in the gutter. Set from the SSE event id, so it is the journal's, not a count. */
  at(seq) {
    if (seq) this.seq = seq;
    return this;
  }

  /**
   * One station on the spine: a sequence number in the gutter, and content beside it.
   *
   * Rows are appended in the order things actually happened rather than sorted into fixed slots.
   * A run is a sequence, and rearranging it would make the rail lie about the order.
   */
  row(kind, node) {
    const row = el('div', `run-row run-row-${kind}`);
    // Empty rather than 000 where there is no number to show. A conversation replayed out of chat
    // memory is drawn through this same view — see appendTools in transcript.js — and chat memory
    // holds no journal, so there is no cursor to be honest about. The span stays either way,
    // because it is what reserves the gutter the rail is drawn in.
    const seq = this.seq ? String(this.seq).padStart(3, '0') : '';
    row.append(el('span', 'run-seq', seq), node);
    this.body.append(row);
    return row;
  }

  /** A collapsible block, with a coloured dot on the rail saying what kind of thing it is. */
  fold(kind, label, tone, open = false) {
    const details = el('details', `fold fold-${tone}`);
    details.open = open;
    const summary = el('summary', 'fold-summary');
    summary.append(el('span', `fold-dot bg-${tone}`), el('span', `${LABEL} fold-label`, label));
    const count = el('span', 'fold-count font-mono text-[10px] text-mist');
    summary.append(count);
    const inner = el('div', 'fold-body');
    details.append(summary, inner);
    const row = this.row(kind, details);
    return { row, details, summary, body: inner, count };
  }

  slot(name, build) {
    if (!this.nodes[name]) this.nodes[name] = build();
    return this.nodes[name];
  }

  // ── the answer ────────────────────────────────────────────────────────────────────────────
  onContent(data) {
    if (!data.delta) return;
    this.answerText += data.delta;
    const node = this.slot('answer', () => {
      const prose = el('div', PROSE);
      this.row('answer', prose);
      return prose;
    });
    node.innerHTML = markdown(this.answerText);
  }

  // ── what it thought on the way ────────────────────────────────────────────────────────────
  onReasoning(data) {
    if (!data.delta) return;
    this.reasoningText += data.delta;
    const panel = this.slot('reasoning', () => this.fold('reasoning', t('run.thinking'), 'mist'));
    panel.body.innerHTML = markdown(this.reasoningText);
  }

  // ── tool calls ────────────────────────────────────────────────────────────────────────────
  toolsPanel() {
    return this.slot('tools', () => {
      const panel = this.fold('tools', t('run.tools'), 'settled');
      panel.list = el('ol', 'space-y-2.5');
      panel.body.append(panel.list);
      return panel;
    });
  }

  onTool(data) {
    const panel = this.toolsPanel();
    const item = el('li', 'tool');
    const head = el('div', 'flex items-baseline gap-2');
    head.append(el('code', 'font-mono text-[12px] font-medium', data.name));
    const state = el('span', 'tool-state text-mist', '·');
    head.append(state);
    item.append(head);
    if (data.input) item.append(el('pre', 'tool-io', readable(data.input)));
    panel.list.append(item);
    this.toolCalls.set(data.id, { item, state });
    panel.count.textContent = this.toolCalls.size;
  }

  onToolResult(data) {
    const call = this.toolCalls.get(data.id);
    if (!call) return;
    call.state.textContent = '✓';
    call.state.className = 'tool-state text-settled';
    if (data.result) call.item.append(el('pre', 'tool-io tool-out', readable(data.result)));
  }

  // ── subagents ─────────────────────────────────────────────────────────────────────────────
  subagent(id) {
    if (!this.subagents.has(id)) {
      const card = el('div', 'subagent');
      const head = el('div', 'flex items-center gap-2');
      const title = el('span', 'text-[13px] font-medium', t('run.subagent'));
      const state = el('span', `${LABEL} text-signal`, t('run.subagent.running'));
      head.append(el('span', 'subagent-fork font-mono text-mist', '⑂'), title, state);
      const brief = el('div', 'mt-1 text-[12px] leading-relaxed text-mist');
      const body = el('div', `${PROSE} mt-2 text-[13.5px]`);
      const tools = el('div', 'mt-2 flex flex-wrap gap-1');
      card.append(head, brief, body, tools);
      this.row('subagent', card);
      this.subagents.set(id, { card, title, state, brief, body, tools, text: '' });
    }
    return this.subagents.get(id);
  }

  onSubagent(data) {
    const view = this.subagent(data.subagentId);
    if (data.state === 'started') {
      if (data.description) view.title.textContent = data.description;
      if (data.brief) view.brief.textContent = data.brief;
      return;
    }
    if (data.state !== 'ended') return;
    const done = data.outcome === 'COMPLETED';
    view.state.textContent = t(done ? 'run.subagent.done'
      : data.outcome === 'CANCELLED' ? 'run.subagent.stopped' : 'run.subagent.failed');
    view.state.className = `${LABEL} ${done ? 'text-settled'
      : data.outcome === 'CANCELLED' ? 'text-mist' : 'text-alarm'}`;
    view.card.classList.add('subagent-done');
  }

  onSubagentContent(id, data) {
    const view = this.subagent(id);
    view.text += data.delta ?? '';
    view.body.innerHTML = markdown(view.text);
  }

  onSubagentTool(id, data) {
    const view = this.subagent(id);
    view.tools.append(el('code', 'subagent-tool font-mono', data.name));
  }

  // ── the to-do list ────────────────────────────────────────────────────────────────────────
  onTodos(data) {
    const items = data.items ?? [];
    const panel = this.slot('todo', () => {
      const built = this.fold('todo', t('run.todo'), 'waiting', true);
      built.list = el('ul', 'space-y-1');
      built.body.append(built.list);
      return built;
    });
    panel.list.replaceChildren();
    items.forEach((item) => {
      const done = item.status === 'completed';
      const active = item.status === 'in_progress';
      const line = el('li', `todo ${done ? 'todo-done' : active ? 'todo-active' : ''}`);
      line.append(el('span', 'todo-mark', done ? '✓' : active ? '▸' : ''));
      // activeForm is the present-participle label the tool supplies for whatever is under way;
      // "Reading the config" reads better than the noun while it is happening.
      line.append(el('span', '', active && item.activeForm ? item.activeForm : item.content));
      panel.list.append(line);
    });
    panel.count.textContent = `${items.filter((i) => i.status === 'completed').length}/${items.length}`;
  }

  // ── what the answer was built on ──────────────────────────────────────────────────────────
  onReferences(data) {
    const sources = data.sources ?? [];
    if (!sources.length) return;
    const panel = this.slot('sources', () => {
      const built = this.fold('sources', t('run.sources'), 'settled');
      built.list = el('ul', 'space-y-1');
      built.body.append(built.list);
      return built;
    });
    panel.list.replaceChildren();
    sources.forEach((source) => {
      const line = el('li', 'flex items-baseline gap-2 text-[12.5px]');
      line.append(el('span', '', source.title || '—'),
        el('span', `${LABEL} text-mist`, source.scope));
      panel.list.append(line);
    });
    panel.count.textContent = sources.length;
  }

  // ── what it cost ──────────────────────────────────────────────────────────────────────────
  onUsage(data) {
    const node = this.slot('usage', () => {
      const usage = el('div', `${LABEL} text-mist`);
      this.row('usage', usage);
      return usage;
    });
    if (data.totalTokens) node.textContent = t('run.usage', data.model ?? '', data.totalTokens);
    else if (data.model && !node.textContent) node.textContent = data.model;
  }

  // ── a message that joined the run mid-turn ────────────────────────────────────────────────
  onQueued(data) {
    const node = this.slot('queued', () => {
      const list = el('div', 'space-y-1');
      this.row('queued', list);
      return list;
    });
    const line = el('div', 'queued', t('run.queued', data.message ?? ''));
    line.dataset.requestId = data.requestId;
    node.append(line);
  }

  onQueuedRead(data) {
    const node = this.nodes.queued;
    if (!node) return;
    (data.requestIds ?? []).forEach((id) => {
      const line = node.querySelector(`[data-request-id="${CSS.escape(id)}"]`);
      if (line) {
        line.classList.add('queued-read');
        line.append(el('span', `${LABEL} ml-2 text-settled`, t('run.queued.read')));
      }
    });
  }

  onError(data) {
    const node = this.slot('error', () => {
      const box = el('div', 'failure');
      this.row('error', box);
      return box;
    });
    node.replaceChildren(
      el('span', `${LABEL} text-alarm`, t('run.failed')),
      el('div', 'mt-1 text-[13px]', data.message || ''));
  }

  onFinished(data) {
    this.finished = true;
    this.root.dataset.outcome = data.outcome;
    if (data.outcome === 'CANCELLED') {
      this.row('stopped', el('div', `${LABEL} text-mist`, t('run.stopped')));
    }
  }
}
