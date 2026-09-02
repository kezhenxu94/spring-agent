// A question the agent asked, drawn in the transcript.
//
// Answering it starts a *new* run on the same conversation — the one that asked ended its turn when
// it asked — so this module says so on the bus rather than reaching into the stream itself.

import { t } from './i18n.js';
import { $, scrollToEnd } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { bus, state } from './state.js';

export function removeQuestion() {
  document.querySelectorAll('[data-question]').forEach((node) => node.remove());
}

export function renderQuestion(pending) {
  removeQuestion();
  const form = document.createElement('form');
  form.dataset.question = pending.pendingQuestionId;
  form.className = 'question mx-auto mt-5 max-w-[46rem] space-y-3';

  const head = document.createElement('div');
  head.className = 'flex items-center gap-2';
  const dot = document.createElement('span');
  dot.className = 'size-1.5 rounded-full bg-waiting dot-live';
  const title = document.createElement('span');
  title.className = 'font-mono text-[10px] font-medium uppercase tracking-[0.14em] text-waiting';
  title.textContent = t('question.title');
  head.append(dot, title);
  form.append(head);

  pending.questions.forEach((question) => form.append(block(question)));

  const submit = document.createElement('button');
  submit.type = 'submit';
  submit.className = 'rounded-lg bg-ink px-3.5 py-2 text-[13px] font-medium text-white '
    + 'transition hover:bg-zinc-700 disabled:opacity-40 dark:bg-paper dark:text-ink';
  submit.textContent = t('question.submit');
  form.append(submit);

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    submit.disabled = true;
    const label = submit.textContent;
    submit.textContent = t('question.sending');
    try {
      const answers = pending.questions.map((question) => ({
        index: question.index,
        optionIndexes: [...form.querySelectorAll(`input[name="q${question.index}"]:checked`)]
          .map((input) => Number(input.value)),
        text: (form.querySelector(`input[name="q${question.index}-other"]`) || {}).value || '',
      }));
      const result = await api(`/api/questions/${pending.pendingQuestionId}/answers`, {
        method: 'POST',
        body: JSON.stringify({ answers }),
      });
      form.remove();
      state.runView = null;
      bus.emit('run:attach', { requestId: result.requestId, from: 0 });
    } catch (error) {
      // The reasons this fails — somebody else answered, a later message replaced it, it expired —
      // are all things the person needs to read, so they go in the form as well as in a toast.
      let failure = form.querySelector('.question-failure');
      if (!failure) {
        failure = document.createElement('p');
        failure.className = 'question-failure text-[12.5px] text-alarm';
        form.append(failure);
      }
      failure.textContent = error.message;
      toast(error.message);
      submit.disabled = false;
      submit.textContent = label;
    }
  });

  $('transcript').append(form);
  scrollToEnd(true);
}

function block(question) {
  const block = document.createElement('fieldset');
  block.className = 'space-y-0.5';

  const legend = document.createElement('legend');
  legend.className = 'mb-1.5 text-[14px] font-medium';
  legend.textContent = question.question;
  block.append(legend);

  const type = question.multiSelect ? 'checkbox' : 'radio';
  question.options.forEach((option, index) => {
    const label = document.createElement('label');
    label.className = 'question-option';
    const input = document.createElement('input');
    input.type = type;
    input.name = `q${question.index}`;
    input.value = String(index);
    const text = document.createElement('span');
    const strong = document.createElement('span');
    strong.className = 'text-[13.5px] font-medium';
    strong.textContent = option.label;
    text.append(strong);
    if (option.description) {
      const description = document.createElement('span');
      description.className = 'block text-[11.5px] leading-snug text-mist';
      description.textContent = option.description;
      text.append(description);
    }
    label.append(input, text);
    block.append(label);
  });

  // Free text alongside the options, not instead of them: the tool's contract allows either, and
  // the answer somebody actually wants to give is often none of the four offered.
  const other = document.createElement('input');
  other.type = 'text';
  other.name = `q${question.index}-other`;
  other.placeholder = t('question.other');
  other.className = 'mt-1.5 ml-[1.55rem] w-[calc(100%-1.55rem)] rounded-lg border '
    + 'border-zinc-300 bg-white/70 px-2.5 py-1 text-[12.5px] outline-none '
    + 'focus:border-waiting dark:border-edge dark:bg-panel';
  block.append(other);

  return block;
}
