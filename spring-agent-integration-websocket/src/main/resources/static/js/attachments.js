// A file goes to the caller's artifacts directory — the same place a file sent to the bot on Feishu
// lands — and the message that follows names it, so the run knows there is something to look at.
// The agent reads it with the file and shell tools it already has; nothing is parsed here.

import { t } from './i18n.js';
import { $, humanSize } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { busyButton } from './busy.js';
import { loadConversations } from './conversations.js';
import { bus, state } from './state.js';

export function renderAttachments() {
  const list = $('attachments');
  list.replaceChildren();
  list.classList.toggle('hidden', !state.attachments.length);
  list.classList.toggle('flex', state.attachments.length > 0);
  state.attachments.forEach((file, index) => {
    const chip = document.createElement('li');
    chip.className = 'flex items-center gap-1.5 rounded-md border border-zinc-200 bg-zinc-50 '
      + 'py-1 pl-2 pr-1 font-mono text-[11px] dark:border-rail dark:bg-panel';
    const name = document.createElement('span');
    name.className = 'max-w-[16rem] truncate';
    name.textContent = file.name;
    const size = document.createElement('span');
    size.className = 'text-mist';
    size.textContent = humanSize(file.size);
    const drop = document.createElement('button');
    drop.type = 'button';
    drop.className = 'px-1 text-mist transition hover:text-alarm';
    drop.textContent = '×';
    drop.setAttribute('aria-label', t('composer.attach.remove'));
    drop.addEventListener('click', () => {
      // Only from the next message. The file itself stays in their artifacts — it is theirs now,
      // and silently deleting somebody's file because they closed a chip would be a surprise.
      state.attachments.splice(index, 1);
      renderAttachments();
    });
    chip.append(name, size, drop);
    list.append(chip);
  });
  bus.emit('composer:refresh');
}

export async function uploadFiles(fileList) {
  const chosen = [...fileList];
  if (!chosen.length) return;
  // The paperclip is the only thing on screen that knows an upload is happening — the chips it
  // produces do not exist yet — so it is what spins. A file dropped on the page or pasted into the
  // composer goes through here too, and lands on the same button.
  const done = busyButton($('attach'));
  await attempt(async () => {
    if (!state.conversationId) {
      const created = await api('/api/conversations', { method: 'POST' });
      state.conversationId = created.id;
      await loadConversations();
    }
    const form = new FormData();
    chosen.forEach((file) => form.append('files', file));
    const result = await api(`/api/conversations/${state.conversationId}/files`, {
      method: 'POST',
      body: form,
    });
    // The server's names, not the browser's: a collision is resolved on the way in, so the name the
    // message quotes has to be the one actually on disk or the agent looks for a file that is not
    // there.
    state.attachments.push(...(result.files || []));
    renderAttachments();
    toast(t('composer.attach.done', result.files.length), 'settled', 3000);
  });
  done();
}

export function initAttachments() {
  const input = $('file-input');
  $('attach').addEventListener('click', () => input.click());
  input.addEventListener('change', () => {
    uploadFiles(input.files);
    // Reset, so choosing the same file twice in a row still fires a change event.
    input.value = '';
  });

  // Drag onto the page, which is what anybody tries first.
  const dropped = (event) => [...(event.dataTransfer?.files || [])];
  document.addEventListener('dragover', (event) => {
    if (event.dataTransfer?.types?.includes('Files')) {
      event.preventDefault();
      document.body.classList.add('dropping');
    }
  });
  document.addEventListener('dragleave', (event) => {
    if (!event.relatedTarget) document.body.classList.remove('dropping');
  });
  document.addEventListener('drop', (event) => {
    const files = dropped(event);
    if (!files.length) return;
    event.preventDefault();
    document.body.classList.remove('dropping');
    uploadFiles(files);
  });

  // Paste a screenshot straight in.
  $('composer').addEventListener('paste', (event) => {
    const files = [...(event.clipboardData?.files || [])];
    if (files.length) {
      event.preventDefault();
      uploadFiles(files);
    }
  });
}
