// What the agent has been asked to do later.
//
// A list in the sidebar read the way the conversations are, and one task opened in the main column.
// Nothing here creates one: a scheduled task comes from asking the agent for it in a conversation,
// which is why this section offers no "new" button and says so instead.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { skeletonList } from './busy.js';
import { confirmAction } from './confirm.js';
import { menuButton } from './menu.js';
import { chatRoute, tasksRoute, go } from './route.js';
import { headline } from './panels.js';
import { renderTaskDetail } from './tasks-detail.js';
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
  // The panel is handed what cancelling means rather than importing it: this module already owns
  // the dialog and the reload, and a detail that imported them back would close the loop.
  renderTaskDetail(task, { cancel: () => cancelTask(task, true) });
  headline(task ? task.text : t('tasks.title'));
}

function row(task) {
  const current = task.id === state.taskId;
  const item = document.createElement('li');
  item.className = 'group relative';

  const open = document.createElement('button');
  open.type = 'button';
  open.className = 'flex w-full items-center gap-2 rounded-md py-1.5 pl-2 pr-7 text-left '
    + 'text-[13px] transition '
    + (current
      ? 'bg-zinc-200/70 font-medium dark:bg-rail'
      : 'text-zinc-600 hover:bg-zinc-100 dark:text-mist dark:hover:bg-rail/60');

  // Where a conversation's dot says it is live, a task's says it is waiting — violet, the colour
  // this page uses for the agent waiting on something rather than working.
  const dot = document.createElement('span');
  dot.className = 'size-1.5 shrink-0 rounded-full bg-waiting';
  const text = document.createElement('span');
  text.className = 'min-w-0 flex-1 truncate';
  text.textContent = task.text;
  open.append(dot, text);
  open.addEventListener('click', () => go(tasksRoute(task.id)));
  item.append(open);

  const actions = menuButton(t('task.actions'), [
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
