// One scheduled task, opened: what it will do, when, how often, and where its answers go.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { chatRoute, go } from './route.js';
import { markdown } from './render.js';

export function renderTaskDetail(task, options) {
  const host = $('task-detail');
  host.replaceChildren();
  host.hidden = !task;
  // The blurb is for somebody who has not chosen anything yet — and it is the only place this
  // section says how a task is made, since it cannot make one.
  $('tasks-intro').hidden = Boolean(task);
  if (!task) return;

  // What the task will do, as markdown — the same way an answer in the transcript and a stored
  // document are drawn. A task's text is a prompt somebody wrote, so it arrives with lists,
  // headings and code in it, and read as plain text those are the punctuation instead of the
  // shape. Through the same sanitiser too, and not as a nicety: the text may have been written by
  // the model, so it is exactly as untrusted as anything else it produced.
  const what = document.createElement('div');
  what.className = 'prose max-w-none text-[14px] leading-[1.7]';
  what.innerHTML = markdown(task.text);
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
  cancel.className = 'panel-action panel-action-danger';
  cancel.textContent = t('task.cancel');
  // Asked for, and carried out, in the dialog: the button stays as it is and the confirmation is
  // what spins, so there is one place on this page where an irreversible thing is in progress.
  cancel.addEventListener('click', () => options.cancel());
  host.append(cancel);
  return host;
}
