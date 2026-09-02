// One scheduled task, opened: what it will do, when, how often, and where its answers go.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { chatRoute, tasksRoute, go } from './route.js';

export function renderTaskDetail(task, options) {
  const host = $('task-detail');
  host.replaceChildren();
  host.hidden = !task;
  // The blurb is for somebody who has not chosen anything yet — and it is the only place this
  // section says how a task is made, since it cannot make one.
  $('tasks-intro').hidden = Boolean(task);
  if (!task) return;

  const what = document.createElement('p');
  what.className = 'whitespace-pre-wrap text-[14px] leading-relaxed';
  what.textContent = task.text;
  host.append(what);

  const facts = document.createElement('dl');
  facts.className = 'grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[11.5px]';
  // A cron expression is what the agent was given, so it is shown as it stands rather than turned
  // into prose that would have to guess at a timezone this page was never told.
  fact(facts, t('task.schedule'), task.cron || task.scheduledAt, true);
  if (task.maxRuns) fact(facts, t('task.runs.label'), t('task.runs', task.runCount ?? 0, task.maxRuns));
  else if (task.runCount) fact(facts, t('task.runs.label'), String(task.runCount));
  if (task.background) fact(facts, t('task.background.label'), t('task.background.yes'));
  host.append(facts);

  host.append(actions(task, options));
}

function fact(host, label, value, mono) {
  const key = document.createElement('dt');
  key.className = 'text-mist';
  key.textContent = label;
  const text = document.createElement('dd');
  text.className = mono ? 'font-mono text-[11px]' : '';
  text.textContent = value;
  host.append(key, text);
}

function actions(task, options) {
  const host = document.createElement('div');
  host.className = 'flex flex-wrap gap-2 pt-1';

  if (task.conversationId) {
    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'panel-action';
    open.textContent = t('task.open');
    // Where its runs are written, which is the whole reason a task remembers a conversation.
    open.addEventListener('click', () => go(chatRoute(task.conversationId)));
    host.append(open);
  }

  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'panel-action';
  cancel.textContent = t('task.cancel');
  cancel.addEventListener('click', () => attempt(async () => {
    await api(`/api/tasks/${task.id}`, { method: 'DELETE' });
    go(tasksRoute());
    await options.refresh();
    toast(t('task.cancelled'), 'settled', 3000);
  }));
  host.append(cancel);
  return host;
}
