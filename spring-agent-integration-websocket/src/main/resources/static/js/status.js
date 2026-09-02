// The strip in the header that says what the run is doing.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { state } from './state.js';

export function renderStatus(kind) {
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
