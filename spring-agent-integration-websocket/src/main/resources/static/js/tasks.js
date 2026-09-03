// What the agent has been asked to do later.
//
// A list in the sidebar read the way the conversations are, and one task opened in the main column.
// Nothing here creates one: a scheduled task comes from asking the agent for it in a conversation,
// which is why this section offers no "new" button and says so instead.

import { t } from './i18n.js';
import { $, rowMeta } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { skeletonList } from './busy.js';
import { confirmAction } from './confirm.js';
import { menuButton } from './menu.js';
import { chatRoute, tasksRoute, go } from './route.js';
import { headline } from './panels.js';
import { editTask, renderTaskDetail, taskName } from './tasks-detail.js';
import { state } from './state.js';

const SVG = 'http://www.w3.org/2000/svg';

export async function loadTasks() {
  // The list alone, and only while it is empty — as in the conversations, and for the same reason:
  // this is called again after every cancellation.
  const done = state.tasks.length ? () => {} : skeletonList($('task-list'), 3);
  try {
    state.tasks = await api('/api/tasks');
  } finally {
    done();
    renderTaskList();
    // The one on screen may have just been cancelled, or may have run since.
    if (state.taskId) renderDetail();
  }
}

export function renderTaskList() {
  const list = $('task-list');
  list.replaceChildren();
  if (!state.tasks.length) {
    const empty = document.createElement('li');
    empty.className = 'px-1 py-1.5 text-[12px] leading-relaxed text-mist';
    empty.textContent = t('task.none');
    list.append(empty);
    return;
  }
  state.tasks.forEach((task) => list.append(row(task)));
}

/** Shows the schedule, with `taskId` opened if one was named. */
export function showTasks(taskId) {
  state.taskId = taskId || null;
  renderTaskList();
  renderDetail();
}

function renderDetail() {
  const task = state.tasks.find((it) => it.id === state.taskId);
  // The panel is handed what cancelling and reloading mean rather than importing them: this module
  // already owns the dialog and the fetch, and a detail that imported them back would close the
  // loop. `redraw` is how the panel switches itself between reading and editing without owning the
  // question of which task is open.
  renderTaskDetail(task, {
    cancel: () => cancelTask(task, true),
    redraw: renderDetail,
    saved: loadTasks,
  });
  headline(task ? taskName(task) : t('tasks.title'));
}

function row(task) {
  const current = task.id === state.taskId;
  const item = document.createElement('li');
  item.className = 'group relative';

  const open = document.createElement('button');
  open.type = 'button';
  // The two-line row every sidebar list uses: the name on one line, what is true about it on the
  // next. This list is the one that has the most to say there — a task's name is its prompt, and
  // what tells two of them apart at a glance is when they come round and how often.
  open.className = 'flex w-full flex-col gap-0.5 rounded-md py-1.5 pl-2 pr-7 text-left '
    + 'text-[13px] transition '
    + (current
      ? 'bg-zinc-200/70 font-medium dark:bg-rail'
      : 'text-zinc-600 hover:bg-zinc-100 dark:text-mist dark:hover:bg-rail/60');

  const line = document.createElement('span');
  line.className = 'flex w-full items-center gap-2';
  // Where a conversation's dot says it is live, a task's says it is waiting — violet, the colour
  // this page uses for the agent waiting on something rather than working.
  const dot = document.createElement('span');
  dot.className = 'size-1.5 shrink-0 rounded-full bg-waiting';
  const text = document.createElement('span');
  text.className = 'min-w-0 flex-1 truncate';
  text.textContent = taskName(task);
  line.append(dot, text);

  open.append(line, meta(task));
  open.addEventListener('click', () => go(tasksRoute(task.id)));
  item.append(open);

  const actions = menuButton(t('task.actions'), [
    // The row's own copy of what the open card offers, so correcting a task does not mean opening
    // it first and then finding the menu again. The edit itself belongs to the panel, so this
    // leaves word that the draw is to open a form and then navigates as any other row does — the
    // task being edited is the task on screen, whether or not it was before the press.
    {
      label: t('task.edit'),
      onSelect: () => {
        editTask(task.id);
        go(tasksRoute(task.id));
      },
    },
    task.conversationId && {
      label: t('task.open'),
      onSelect: () => go(chatRoute(task.conversationId)),
    },
    {
      label: t('task.cancel'),
      danger: true,
      onSelect: () => cancelTask(task, current),
    },
  ]);
  actions.classList.add('row-action');
  item.append(actions);
  return item;
}

/**
 * The second line: on what schedule, how many times it has gone, and whether anybody is expected to
 * be there when it does.
 *
 * The expression leads the line, where a conversation keeps the moment it was last spoken to: what
 * a person scans this list for is when each task comes round, and in both lists that is the first
 * thing on the second line.
 *
 * Shown as it stands, untranslated and monospaced, for the same reason the panel shows it that way:
 * it is what the agent was given, and prose made of it would have to guess at a timezone this page
 * was never told. When it next fires in this browser's own clock is on the card it opens, which is
 * where there is room to say it in full rather than to the nearest hour. A one-off has no
 * expression and says so instead, which is the fact that sorts this list into its two kinds.
 */
function meta(task) {
  const row = rowMeta();

  const schedule = document.createElement('span');
  schedule.className = 'min-w-0 flex-1 truncate font-mono tabular-nums';
  schedule.textContent = task.cron || t('task.once');
  row.append(schedule);

  // Before the badge rather than after it, so the badge is the last thing on every row and the
  // counts line up down the right edge — an icon that only some tasks have would otherwise push
  // theirs left and turn a column somebody scans into a ragged one.
  //
  // An icon and no word, because "unattended" is a property most tasks do not have and the ones
  // that do are told apart by its presence — a row of labels reading the same thing is not scanned.
  // The label is still written, for a reader who is not looking at the row.
  if (task.background) row.append(unattended());

  // Only where there is something to count. A task that has never fired and has no ceiling would
  // get a badge reading "0", which is a number where the useful statement is silence.
  const runs = task.maxRuns
    ? `${task.runCount ?? 0}/${task.maxRuns}`
    : (task.runCount ? String(task.runCount) : '');
  if (runs) {
    const badge = document.createElement('span');
    // Outlined rather than filled: the row that is open is already a filled rectangle, and a badge
    // with a background of its own disappears into it on exactly the row being looked at.
    badge.className = 'shrink-0 rounded border border-zinc-300 px-1 font-mono tabular-nums '
      + 'dark:border-edge';
    badge.textContent = runs;
    badge.title = t('task.runs.label');
    row.append(badge);
  }
  return row;
}

function unattended() {
  const mark = document.createElement('span');
  mark.className = 'inline-flex shrink-0 items-center';
  mark.title = t('task.background.yes');

  // Built rather than written as innerHTML, which this page keeps for sanitised markdown and for
  // nothing else — an exception for "our own" markup is how the next one gets made.
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.3');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('class', 'size-3');
  const path = document.createElementNS(SVG, 'path');
  // A crescent: it runs while nobody is here.
  path.setAttribute('d', 'M13.2 9.6A5.2 5.2 0 0 1 6.4 2.8a5.6 5.6 0 1 0 6.8 6.8Z');
  svg.append(path);

  const said = document.createElement('span');
  said.className = 'sr-only';
  said.textContent = t('task.background.label');
  mark.append(svg, said);
  return mark;
}

/**
 * Cancelling one, asked for first.
 *
 * A scheduled task is the one thing on this page that cannot be made again from the page — it comes
 * from asking the agent for it — so cancelling one by catching the wrong row is expensive in a way
 * closing a conversation is not.
 */
export function cancelTask(task, wasOpen) {
  return confirmAction({
    title: t('task.cancel.title'),
    body: t('task.cancel.confirm'),
    action: t('task.cancel.action'),
    run: async () => {
      await api(`/api/tasks/${task.id}`, { method: 'DELETE' });
      // Off the task that no longer exists before the list is fetched again.
      if (wasOpen) go(tasksRoute());
      await loadTasks();
      toast(t('task.cancelled'), 'settled', 3000);
    },
  });
}
