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

// And the one on a title, which is a name rather than a sentence — see ScheduledTaskEdit#MAX_TITLE.
const MAX_NAME = 120;

// How much of a prompt stands in for a task that has no title. Shorter than a title may be, because
// this is the middle of a sentence somebody wrote for the model rather than a name for the job.
const NAME = 80;

/**
 * The edit in progress, if there is one: which task, and every field as it now stands in the form.
 *
 * Held here rather than read off the inputs because this panel is redrawn from the outside — the
 * list is refetched after every cancellation, and a firing changes the run count — and a redraw
 * that dropped the draft would throw away what somebody was in the middle of writing. It is also
 * what the form is drawn from, so choosing "once" over "repeats" can swap one field for another
 * without the rest of the draft going anywhere.
 */
let editing = null;

/**
 * Re-asks whether the draft is complete enough to save, set by the body when it draws the button
 * that answer belongs to.
 *
 * The two halves of the form are drawn by different functions — the fields above, the prompt and
 * its buttons below — and the fields can invalidate the draft as surely as the prompt can: a cron
 * expression cleared to nothing is not a schedule. Rather than have the fields reach for a button
 * that may not exist yet, the button leaves this behind for them; it is a no-op the rest of the
 * time, which is what a field changing outside an edit should do.
 */
let revalidate = () => {};

/**
 * A task the sidebar asked to be opened *for editing*, waiting for the panel that will draw it.
 *
 * The row's menu cannot start an edit itself: the panel owns the draft, and the task the row names
 * may not be the one on screen yet. So the row leaves the id here and navigates, and the draw that
 * navigation causes picks it up — which makes one path of it whether the task was already open or
 * is being opened by the same press.
 */
let opening = null;

/** Says the next draw of `taskId` should open its form rather than the spec sheet. */
export function editTask(taskId) {
  opening = taskId;
}

export function renderTaskDetail(task, options) {
  const host = $('task-detail');
  host.replaceChildren();
  host.hidden = !task;
  // The blurb is for somebody who has not chosen anything yet — and it is the only place this
  // section says how a task is made, since it cannot make one.
  openDetail('tasks-panel', 'tasks-intro', Boolean(task));
  // Every redraw throws away the elements the last one left behind, so the hook they held has to go
  // with them, before anything decides whether there is still an edit to draw.
  revalidate = () => {};
  if (!task) {
    editing = null;
    return;
  }
  // The task on screen has changed underneath an edit — cancelled from its row, say. The draft
  // belonged to the other one.
  if (editing && editing.id !== task.id) editing = null;
  // Read once, whichever task this draw is of: an intent left behind for a task that has since been
  // cancelled has nothing to open and must not wait around for the next one.
  const wanted = opening;
  opening = null;
  // Never over an edit already in progress on this task — pressing Edit on the row of the task you
  // are editing is a press that should change nothing, not one that discards the draft.
  if (wanted === task.id && !editing) draft(task);

  host.append(detailHead({
    kind: t('task.kind'),
    // Whether it comes back. The one fact that sorts scheduled tasks into kinds, which is what the
    // scope pill is for a document.
    pill: t(task.cron ? 'task.repeats' : 'task.once'),
    pillFilled: Boolean(task.cron),
    name: taskName(task),
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

  host.append(editing ? form(options) : detailFacts([
    [t('task.next'), when(task)],
    // A cron expression is what the agent was given, so it is shown as it stands rather than turned
    // into prose that would have to guess at a timezone this page was never told. The row above has
    // already said what it comes to in this browser's own clock, which is the half of it a person
    // can act on.
    //
    // A one-off has no expression, and its schedule *is* the moment that row is showing — so there
    // is nothing to add, and a row repeating it would say the same thing twice. The fallback is for
    // the one case where that row could not: a task whose next occurrence is not worked out yet.
    task.cron
      ? [t('task.schedule'), task.cron, true]
      : !task.nextFireAt && task.scheduledAt && [t('task.schedule'), fullTime(task.scheduledAt)],
    task.expiresAt && [t('task.edit.expires'), fullTime(task.expiresAt)],
    task.maxRuns
      ? [t('task.runs.label'), t('task.runs', task.runCount ?? 0, task.maxRuns)]
      : task.runCount && [t('task.runs.label'), String(task.runCount)],
    task.background && [t('task.background.label'), t('task.background.yes')],
  ]));

  host.append(body(task, options));
}

/**
 * When it next comes round.
 *
 * A task written before the server recorded that — the column arrives null on every existing row,
 * and the sweeper backfills it as it meets it — says so rather than leaving the row blank, which
 * would read as a task with nothing left to do.
 */
function when(task) {
  return task.nextFireAt ? fullTime(task.nextFireAt) : t('task.next.unknown');
}

/**
 * What to call a task: the name somebody gave it.
 *
 * A task written before titles existed has none — the column arrives null on every existing row —
 * and its prompt stands in, shortened, because a prompt runs to paragraphs and every place this is
 * used has one line. Exported because the sidebar row and the panel heading have to agree; a list
 * and the thing it opens calling the same task two different names is worse than either name.
 */
export function taskName(task) {
  if (task.title) return task.title;
  const text = (task.text || '').trim().replace(/\s+/g, ' ');
  return text.length <= NAME ? text : `${text.slice(0, NAME)}…`;
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
  field.value = editing.text;
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
    save.disabled = left < 0 || !complete();
  };
  revalidate = measure;

  field.addEventListener('input', () => {
    editing.text = field.value;
    measure();
  });
  cancel.addEventListener('click', () => {
    editing = null;
    options.redraw();
  });
  save.addEventListener('click', () => store(task, save, options));

  buttons.append(save, cancel, count);
  measure();
  // After it is in the document; focusing a detached node does nothing.
  queueMicrotask(() => field.focus());
  return detailBody(t('task.what'), field, buttons);
}

/**
 * The spec sheet, editable: the same grid, the same labels, the values as fields.
 *
 * Every field writes straight into the draft and nothing else, so nothing here has to know what
 * saving means. The two that change which other fields make sense — repeats-or-once, and whether
 * there is an expiry at all — are the only ones that ask for a redraw or reach across to another
 * input, and each says why below.
 */
function form(options) {
  const host = document.createElement('div');
  host.className = 'detail-form';

  // First, because it is what the card is titled with and what every list shows: somebody who opens
  // this to fix the name should not have to read past the schedule to find it.
  const named = input('text', editing.title, (value) => { editing.title = value; });
  named.maxLength = MAX_NAME;
  host.append(label(t('task.title')), field(row(named)));

  // Repeats or once. A radio pair rather than the pill the card shows when it is read: this is the
  // choice the rest of the schedule hangs off, and a task carries one schedule — picking the other
  // kind replaces it rather than adding to it, exactly as the server's edit does.
  host.append(label(t('task.edit.kind')), kindField(options));

  if (editing.repeats) {
    const cron = input('text', editing.cron, (value) => { editing.cron = value; });
    cron.placeholder = '0 0 9 * * MON';
    cron.spellcheck = false;
    host.append(label(t('task.schedule')), field(row(cron), hint(t('task.edit.cron.hint'))));
  } else {
    const at = input('datetime-local', editing.scheduledAt, (value) => {
      editing.scheduledAt = value;
    });
    host.append(label(t('task.edit.when')), field(row(at)));
  }

  // The expiry and the checkbox that says there is none, on one line. The field is disabled rather
  // than taken away, so what the expiry was is still readable while it is switched off and comes
  // back if the checkbox does.
  const until = input('datetime-local', editing.expiresAt, (value) => {
    editing.expiresAt = value;
  });
  until.disabled = !editing.expires;
  // Disabled in place rather than redrawn, so the field somebody is working in keeps the focus
  // they put there.
  const never = check(t('task.edit.expires.never'), !editing.expires, (on) => {
    editing.expires = !on;
    until.disabled = on;
  });
  host.append(label(t('task.edit.expires')), field(row(until, never)));

  // Empty means uncounted, which the hint says because an empty number field otherwise reads as a
  // value somebody forgot to fill in.
  const runs = input('number', editing.maxRuns, (value) => { editing.maxRuns = value; });
  runs.min = '1';
  runs.classList.add('detail-input-narrow');
  // Labelled for what it is — the ceiling — and never as "runs", which on the card beside it is the
  // count of firings that have already happened. That is a record of what the task did and is not
  // editable here or anywhere else.
  host.append(label(t('task.edit.maxruns')), field(row(runs), hint(t('task.edit.runs.hint'))));

  host.append(
    label(t('task.background.label')),
    field(row(check(t('task.background.yes'), editing.background, (on) => {
      editing.background = on;
    }))));

  return host;
}

function kindField(options) {
  const group = row();
  group.setAttribute('role', 'radiogroup');
  [['task.repeats', true], ['task.once', false]].forEach(([key, repeats]) => {
    const pick = check(t(key), editing.repeats === repeats, () => {
      editing.repeats = repeats;
      options.redraw();
    }, 'radio');
    group.append(pick);
  });
  return field(group);
}

function label(text) {
  const said = document.createElement('span');
  said.className = 'detail-label';
  said.textContent = text;
  return said;
}

function field(...content) {
  const box = document.createElement('div');
  box.className = 'detail-field';
  box.append(...content);
  return box;
}

function row(...content) {
  const line = document.createElement('div');
  line.className = 'detail-field-row';
  line.append(...content);
  return line;
}

function hint(text) {
  const said = document.createElement('p');
  said.className = 'detail-hint';
  said.textContent = text;
  return said;
}

function input(type, value, onInput) {
  const box = document.createElement('input');
  box.type = type;
  box.className = 'detail-input';
  box.value = value ?? '';
  box.addEventListener('input', () => {
    onInput(box.value);
    revalidate();
  });
  return box;
}

/**
 * A checkbox or a radio and the words beside it, in one label so the words are part of the target.
 * `onChange` is told whether it is now on, which for a radio is only ever true.
 */
function check(text, on, onChange, type = 'checkbox') {
  const said = document.createElement('label');
  said.className = 'detail-check';
  const box = document.createElement('input');
  box.type = type;
  box.checked = on;
  if (type === 'radio') box.name = 'task-edit-kind';
  box.addEventListener('change', () => {
    onChange(box.checked);
    revalidate();
  });
  said.append(box, document.createTextNode(text));
  return said;
}

/** Whether the draft says enough to be a task at all. What it says is the server's to judge. */
function complete() {
  if (!editing.title.trim() || !editing.text.trim()) return false;
  if (editing.repeats ? !editing.cron.trim() : !editing.scheduledAt) return false;
  return !(editing.expires && !editing.expiresAt);
}

function startEditing(task, options) {
  draft(task);
  options.redraw();
}

/** The draft a form is drawn from, taken from the task as it stands. */
function draft(task) {
  editing = {
    id: task.id,
    // The name it will keep if nothing is typed, which for a task written before titles existed is
    // the stand-in every list has been showing — so saving an unrelated change makes the name it
    // already appeared to have its own rather than replacing it with a blank.
    title: taskName(task),
    text: task.text,
    repeats: Boolean(task.cron),
    cron: task.cron || '',
    // A recurring task has no time of its own to show, so the field starts at the occurrence it was
    // heading for. Somebody switching a task to "once" means "just this once, then", and the moment
    // it was next due is the nearest thing to that.
    scheduledAt: localInput(task.scheduledAt || task.nextFireAt),
    expires: Boolean(task.expiresAt),
    expiresAt: localInput(task.expiresAt),
    background: Boolean(task.background),
    maxRuns: task.maxRuns ? String(task.maxRuns) : '',
  };
}

/**
 * A stored moment in the form a `datetime-local` input wants: the browser's own clock, to the
 * minute, and no zone.
 *
 * Built by hand rather than with `toISOString`, which is UTC — filling the field with that would
 * show somebody in Shanghai a nine o'clock task as one o'clock and then save whatever they left
 * there.
 */
function localInput(value) {
  if (!value) return '';
  const when = new Date(value);
  if (Number.isNaN(when.getTime())) return '';
  const pad = (number) => String(number).padStart(2, '0');
  return `${when.getFullYear()}-${pad(when.getMonth() + 1)}-${pad(when.getDate())}`
    + `T${pad(when.getHours())}:${pad(when.getMinutes())}`;
}

/**
 * The other direction: what the form holds, as the absolute instant the server stores.
 *
 * `new Date` reads a `datetime-local` value as local time, which is what it means, so the offset
 * this browser is in is applied for us. Null where the field is empty or unreadable, which the
 * caller treats as nothing to send.
 */
function instant(value) {
  if (!value) return null;
  const when = new Date(value);
  return Number.isNaN(when.getTime()) ? null : when.toISOString();
}

/**
 * What the draft changed, and nothing else.
 *
 * Only the keys somebody actually moved: the PATCH leaves a part of the task alone where its key is
 * absent, and sending the whole definition every time would make a corrected typo a rewrite of the
 * row the sweeper is also writing. The two fields where "gone" is itself a value are sent as an
 * explicit null, which the endpoint tells apart from an absent key.
 */
function changes(task) {
  const body = {};
  const title = editing.title.trim();
  if (title !== task.title) body.title = title;
  const text = editing.text.trim();
  if (text !== task.text) body.text = text;

  if (editing.repeats) {
    const cron = editing.cron.trim();
    if (cron !== task.cron) body.cron = cron;
  } else {
    const at = instant(editing.scheduledAt);
    // Sent whenever the task was recurring, even for an unchanged moment: the change being asked
    // for is that it stops repeating, and the server reads a scheduledAt as exactly that.
    if (at && (task.cron || at !== task.scheduledAt)) body.scheduledAt = at;
  }

  if (!editing.expires) {
    if (task.expiresAt) body.expiresAt = null;
  } else {
    const until = instant(editing.expiresAt);
    if (until && until !== task.expiresAt) body.expiresAt = until;
  }

  if (editing.background !== Boolean(task.background)) body.background = editing.background;

  const runs = editing.maxRuns.trim() ? Number(editing.maxRuns) : null;
  if (runs !== (task.maxRuns ?? null)) body.maxRuns = runs;

  return body;
}

async function store(task, button, options) {
  const body = changes(task);
  // Nothing moved. Said rather than sent, because the server would refuse an edit naming nothing
  // and a person who pressed Save on an unchanged form has not made a mistake.
  if (!Object.keys(body).length) {
    editing = null;
    options.redraw();
    toast(t('task.edit.nothing'), 'settled', 2500);
    return;
  }
  button.disabled = true;
  button.textContent = t('task.edit.saving');
  try {
    await api(`/api/tasks/${task.id}`, { method: 'PATCH', body: JSON.stringify(body) });
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
