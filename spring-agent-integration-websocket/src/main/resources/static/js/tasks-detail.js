// One scheduled task, opened: when it next runs, on what schedule, and what it will do when it
// does.
//
// Drawn on the shared record card in detail.js, which is the same card a knowledge-base document
// opens in — the two sections keep the same object model to the reader: something the agent holds
// on to between runs.
//
// The name line is the next occurrence rather than the task's own text, and that is the one place
// this card differs from a document's. A task has no title: its name in the sidebar row and in the
// header is its prompt, and the prompt is already the body below, so a heading repeating it would
// say nothing. When it next comes round is what a person opens a task to find out.
//
// The other thing a document does not offer is editing. A task's text is a prompt somebody wrote,
// and correcting a word in it otherwise means cancelling the task and asking the agent for a new
// one, which loses the conversation its firings write into. When it fires stays the agent's to
// decide — the tools that set a schedule are where the rules about what a schedule may be live.

import { t } from './i18n.js';
import { $, fullTime } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { chatRoute, go } from './route.js';
import { markdown } from './render.js';
import { detailBody, detailFacts, detailHead, openDetail } from './detail.js';

// The cap the server enforces, and the column behind it — see ScheduledTask#taskText.
const MAX = 8192;

/**
 * The edit in progress, if there is one: which task, and what has been typed so far.
 *
 * Held here rather than read off the textarea because this panel is redrawn from the outside — the
 * list is refetched after every cancellation, and a firing changes the run count — and a redraw
 * that dropped the draft would throw away what somebody was in the middle of writing.
 */
let editing = null;

export function renderTaskDetail(task, options) {
  const host = $('task-detail');
  host.replaceChildren();
  host.hidden = !task;
  // The blurb is for somebody who has not chosen anything yet — and it is the only place this
  // section says how a task is made, since it cannot make one.
  openDetail('tasks-panel', 'tasks-intro', Boolean(task));
  if (!task) {
    editing = null;
    return;
  }
  // The task on screen has changed underneath an edit — cancelled from its row, say. The draft
  // belonged to the other one.
  if (editing && editing.id !== task.id) editing = null;

  host.append(detailHead({
    kind: t('task.kind'),
    // Whether it comes back. The one fact that sorts scheduled tasks into kinds, which is what the
    // scope pill is for a document.
    pill: t(task.cron ? 'task.repeats' : 'task.once'),
    pillFilled: Boolean(task.cron),
    name: when(task),
    nameIsTime: Boolean(task.nextFireAt),
    actions: {
      label: t('task.actions'),
      items: () => [
        !editing && { label: t('task.edit'), onSelect: () => startEditing(task, options) },
        task.conversationId && {
          label: t('task.open'),
          onSelect: () => go(chatRoute(task.conversationId)),
        },
        { label: t('task.cancel'), danger: true, onSelect: () => options.cancel() },
      ],
    },
  }));

  host.append(detailFacts([
    // A cron expression is what the agent was given, so it is shown as it stands rather than turned
    // into prose that would have to guess at a timezone this page was never told. The line above
    // has already said what it comes to in this browser's own clock, which is the half of it a
    // person can act on.
    //
    // A one-off has no expression, and its schedule *is* the moment the line above is showing — so
    // there is nothing to add, and a row repeating that moment would be the third time this panel
    // said it. The fallback is for the one case where the line above could not: a task whose next
    // occurrence has not been worked out yet.
    task.cron
      ? [t('task.schedule'), task.cron, true]
      : !task.nextFireAt && task.scheduledAt && [t('task.schedule'), fullTime(task.scheduledAt)],
    task.maxRuns
      ? [t('task.runs.label'), t('task.runs', task.runCount ?? 0, task.maxRuns)]
      : task.runCount && [t('task.runs.label'), String(task.runCount)],
    task.background && [t('task.background.label'), t('task.background.yes')],
  ]));

  host.append(body(task, options));
}

/**
 * The line that names the task: when it next comes round.
 *
 * A task written before the server recorded that — the column arrives null on every existing row,
 * and the sweeper backfills it as it meets it — falls back to saying so rather than to a blank
 * heading, which would read as a task with nothing left to do.
 */
function when(task) {
  return task.nextFireAt ? fullTime(task.nextFireAt) : t('task.next.unknown');
}

/**
 * The prompt itself, read or being rewritten.
 *
 * As markdown when it is read — the same way an answer in the transcript and a stored document are
 * drawn, since a task's text arrives with lists, headings and code in it and read as plain text
 * those are the punctuation instead of the shape. Through the same sanitiser too, and not as a
 * nicety: the text may have been written by the model, so it is exactly as untrusted as anything
 * else it produced.
 *
 * As a monospaced textarea when it is being rewritten, because that is what it is: a prompt, whose
 * blank lines and indentation are what the agent will be handed.
 */
function body(task, options) {
  if (!editing) {
    const what = document.createElement('div');
    what.className = 'detail-text prose max-w-none text-[13.5px] leading-[1.7]';
    what.innerHTML = markdown(task.text);
    return detailBody(t('task.what'), what);
  }

  const field = document.createElement('textarea');
  field.className = 'detail-edit';
  field.value = editing.draft;
  field.setAttribute('aria-label', t('task.what'));

  const buttons = document.createElement('div');
  buttons.className = 'detail-edit-buttons';
  const save = document.createElement('button');
  save.type = 'button';
  save.className = 'panel-action panel-action-primary';
  save.textContent = t('task.edit.save');
  const cancel = document.createElement('button');
  cancel.type = 'button';
  cancel.className = 'panel-action';
  cancel.textContent = t('knowledge.cancel');
  // How much room is left, said only once it is nearly gone. A counter standing there from the
  // first keystroke is a limit presented as a target.
  const count = document.createElement('span');
  count.className = 'detail-edit-count';

  const measure = () => {
    const left = MAX - field.value.trim().length;
    count.textContent = left <= MAX / 10 ? t('task.edit.left', left) : '';
    count.dataset.over = String(left < 0);
    save.disabled = !field.value.trim() || left < 0;
  };

  field.addEventListener('input', () => {
    editing.draft = field.value;
    measure();
  });
  cancel.addEventListener('click', () => {
    editing = null;
    options.redraw();
  });
  save.addEventListener('click', () => store(task, field.value, save, options));

  buttons.append(save, cancel, count);
  measure();
  // After it is in the document; focusing a detached node does nothing.
  queueMicrotask(() => field.focus());
  return detailBody(t('task.what'), field, buttons);
}

function startEditing(task, options) {
  editing = { id: task.id, draft: task.text };
  options.redraw();
}

async function store(task, value, button, options) {
  const text = value.trim();
  if (!text) return;
  button.disabled = true;
  button.textContent = t('task.edit.saving');
  try {
    await api(`/api/tasks/${task.id}`, { method: 'PATCH', body: JSON.stringify({ text }) });
    // Only once the server has it. Clearing the draft first would lose the edit on a failure, which
    // is the one moment somebody most wants it back.
    editing = null;
    await options.saved();
    toast(t('task.edit.saved'), 'settled', 3000);
  } catch (error) {
    button.disabled = false;
    button.textContent = t('task.edit.save');
    if (!error?.handled) toast(error?.message || t('error.generic'));
  }
}
