// What the agent has been asked to do later.
//
// A list in the sidebar read the way the conversations are, and one task opened in the main column.
// Nothing here creates one: a scheduled task comes from asking the agent for it in a conversation,
// which is why this section offers no "new" button and says so instead.

import { t } from './i18n.js';
import { $, glyph } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { skeletonList } from './busy.js';
import { confirmAction } from './confirm.js';
import { menuButton } from './menu.js';
import { chatRoute, tasksRoute, go } from './route.js';
import { headline } from './panels.js';
import { editTask, renderTaskDetail, taskName } from './tasks-detail.js';
import { state } from './state.js';

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

  // A count and a name, on one line, as the conversations are and for the same reason: this list is
  // read by scanning, and the more of it fits on screen the sooner a task is found. Everything else
  // a task is — its expression, whether it runs unattended — is on the card it opens, which is
  // where there is room to say it in words. The expression especially: it cannot be read at a
  // glance and cannot be read at all without a timezone this page was never told, where the card
  // says when it next fires in this browser's own clock.
  const open = document.createElement('button');
  open.type = 'button';
  open.className = 'row-open flex w-full items-center gap-[0.4rem] rounded-md py-1 pl-[0.5rem] pr-7 text-left '
    + 'text-[13px] transition '
    + (current
      ? 'row-on font-medium'
      : 'text-zinc-600 group-hover:bg-zinc-100 dark:text-mist dark:group-hover:bg-rail/60');

  const text = document.createElement('span');
  text.className = 'min-w-0 flex-1 truncate';
  text.textContent = taskName(task);
  open.append(glyph(runs(task)), text);

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
 * How many times it has gone, where the other lists keep their dot.
 *
 * A conversation's dot says whether it is live; a task has no such state to report — it is waiting,
 * always, which is a dot saying the same thing on every row. The count is what tells two tasks
 * apart at a glance instead, with a ceiling where the task has one.
 *
 * Nought where nothing has run yet rather than nothing at all: this is what every name on the list
 * is indented past, and a row without it would hang its name where no other row's is.
 *
 * Outlined rather than filled: the row that is open is already a filled rectangle, and a badge with
 * a background of its own disappears into it on exactly the row being looked at.
 */
function runs(task) {
  const badge = document.createElement('span');
  badge.className = 'side-count rounded border border-zinc-300 px-1 text-[10px] leading-[1.1] '
    + 'text-mist font-mono tabular-nums dark:border-edge';
  badge.textContent = task.maxRuns
    ? `${task.runCount ?? 0}/${task.maxRuns}`
    : String(task.runCount ?? 0);
  badge.title = t('task.runs.label');
  return badge;
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
