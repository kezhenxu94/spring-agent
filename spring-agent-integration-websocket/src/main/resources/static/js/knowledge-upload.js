// Putting something into the knowledge base: a file, or something typed.
//
// Both write through the endpoints the chat tools write through, and both are indexed by the time
// the request answers — which is the point of doing it here rather than asking the model to. A
// large file takes as long as it takes; the button says so while it waits.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { bus } from './state.js';

export function initKnowledgeAdd(scopes) {
  const target = $('knowledge-target');
  const input = $('knowledge-file-input');
  const upload = $('knowledge-upload');
  const form = $('knowledge-note-form');

  const drawTarget = () => {
    const chosen = target.value;
    target.replaceChildren();
    scopes.forEach((scope) => {
      const option = document.createElement('option');
      option.value = scope;
      option.textContent = t(`knowledge.scope.${scope}`);
      target.append(option);
    });
    if (chosen) target.value = chosen;
  };
  drawTarget();
  bus.on('language:changed', drawTarget);

  upload.addEventListener('click', () => input.click());
  input.addEventListener('change', () => {
    const files = [...input.files];
    // Reset first, so choosing the same file again still fires a change event.
    input.value = '';
    if (files.length) uploadFiles(files, target.value, upload);
  });

  $('knowledge-note-open').addEventListener('click', () => {
    form.hidden = false;
    $('knowledge-note-title').focus();
  });
  $('knowledge-note-cancel').addEventListener('click', () => {
    form.hidden = true;
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    saveNote(target.value, form);
  });
}

function uploadFiles(files, scope, button) {
  const label = button.textContent;
  button.disabled = true;
  button.textContent = t('knowledge.uploading', files.length);
  attempt(async () => {
    const body = new FormData();
    files.forEach((file) => body.append('files', file));
    body.append('scope', scope);
    const result = await api('/api/knowledge/files', { method: 'POST', body });
    toast(t('knowledge.uploaded', result.documents.length), 'settled', 3000);
    bus.emit('knowledge:changed');
  }).finally(() => {
    button.disabled = false;
    button.textContent = label;
  });
}

function saveNote(scope, form) {
  const title = $('knowledge-note-title');
  const text = $('knowledge-note-text');
  const submit = form.querySelector('button[type="submit"]');
  const label = submit.textContent;
  submit.disabled = true;
  submit.textContent = t('knowledge.uploading', 1);
  attempt(async () => {
    await api('/api/knowledge/notes', {
      method: 'POST',
      body: JSON.stringify({ title: title.value, text: text.value, scope }),
    });
    // Cleared only once it is stored, so a refusal does not also lose what they wrote.
    title.value = '';
    text.value = '';
    form.hidden = true;
    toast(t('knowledge.note.saved'), 'settled', 3000);
    bus.emit('knowledge:changed');
  }).finally(() => {
    submit.disabled = false;
    submit.textContent = label;
  });
}
