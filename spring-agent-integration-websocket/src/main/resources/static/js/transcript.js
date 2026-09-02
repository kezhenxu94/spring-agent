// What a conversation looks like when nothing is running: the turns chat memory kept, and the
// invitation shown where there are none.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { markdown } from './render.js';

export function renderEmptyTranscript() {
  const transcript = $('transcript');
  const empty = document.createElement('div');
  empty.className = 'mx-auto flex h-full max-w-[46rem] flex-col justify-center gap-3 pb-16';
  const heading = document.createElement('p');
  heading.className = 'font-display text-[26px] font-semibold leading-tight tracking-tight';
  heading.textContent = t('empty.title');
  const body = document.createElement('p');
  body.className = 'max-w-[34rem] text-[14px] leading-relaxed text-mist';
  body.textContent = t('empty.body');
  empty.append(heading, body);
  transcript.append(empty);
}

export function appendTurn(role, text) {
  const transcript = $('transcript');
  transcript.querySelector('.empty-state')?.remove();
  const wrapper = document.createElement('div');
  if (role === 'user') {
    wrapper.className = 'mx-auto mt-7 flex max-w-[46rem] justify-end first:mt-0';
    const bubble = document.createElement('div');
    bubble.className = 'max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-zinc-100 '
      + 'px-3.5 py-2.5 text-[14px] leading-relaxed dark:bg-rail';
    bubble.textContent = text ?? '';
    wrapper.append(bubble);
  } else {
    wrapper.className = 'mx-auto mt-4 max-w-[46rem] pl-[3.25rem]';
    const body = document.createElement('div');
    body.className = 'prose max-w-none text-[14.5px] leading-[1.7]';
    body.innerHTML = markdown(text);
    wrapper.append(body);
  }
  transcript.append(wrapper);
  return wrapper;
}
