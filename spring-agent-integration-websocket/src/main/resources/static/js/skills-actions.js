// Everything on the skills page that changes something, in one place.
//
// Each of these ends by announcing `skills:changed` rather than redrawing anything itself. That is
// the layering rule at the top of state.js doing its job: an action sits earlier than the section
// that owns the list, so it cannot call back into it, and it should not want to — what a delete
// leaves behind depends on where the reader is, which is the section's business and not the
// delete's.
//
// Everything destructive goes through confirmAction, which does the work while its own dialog is
// still up. A confirmation that closes first is where a double delete comes from.

import { t } from './i18n.js';
import { api } from './api.js';
import { bus } from './state.js';
import { toast } from './toast.js';
import { confirmAction } from './confirm.js';

/** The query string every one of these endpoints is addressed by. */
function where(scope, skill, path) {
  const params = new URLSearchParams({ scope, skill });
  if (path) params.set('path', path);
  return params.toString();
}

/** Reads one skill's tree. */
export function fetchSkills(scope) {
  return api(`/api/skills?scope=${encodeURIComponent(scope)}`);
}

export function fetchTree(scope, skill) {
  return api(`/api/skills/tree?${where(scope, skill)}`);
}

export function fetchFile(scope, skill, path) {
  return api(`/api/skills/file?${where(scope, skill, path)}`);
}

export async function saveFile(scope, skill, path, text) {
  const saved = await api('/api/skills/file', {
    method: 'PUT',
    body: JSON.stringify({ scope, skill, path, text }),
  });
  toast(t('skills.saved', path), 'settled');
  bus.emit('skills:changed');
  return saved;
}

export async function createSkill(scope, name, description) {
  const made = await api('/api/skills', {
    method: 'POST',
    body: JSON.stringify({ scope, name, description }),
  });
  toast(t('skills.created', name), 'settled');
  bus.emit('skills:changed');
  return made;
}

/**
 * A whole skill out of a zip.
 *
 * The name is sent beside the archive rather than taken from it: an archive names its own folder,
 * and letting it choose where it lands would mean uploading a file decides what it overwrites.
 */
export async function importSkill(scope, name, file) {
  const form = new FormData();
  form.append('file', file);
  form.append('scope', scope);
  form.append('name', name);
  const made = await api('/api/skills/import', { method: 'POST', body: form });
  toast(t('skills.imported', name, made.fileCount), 'settled');
  bus.emit('skills:changed');
  return made;
}

export async function uploadInto(scope, skill, dir, files) {
  const form = new FormData();
  Array.from(files).forEach((file) => form.append('files', file));
  form.append('scope', scope);
  form.append('skill', skill);
  if (dir) form.append('dir', dir);
  const done = await api('/api/skills/files', { method: 'POST', body: form });
  toast(t('skills.uploaded', (done.files || []).length), 'settled');
  bus.emit('skills:changed');
  return done;
}

/**
 * The whole skill as a zip.
 *
 * A link the page clicks for itself, rather than a fetch: the response is a download and the
 * browser already knows how to take one. Reading it into a blob to hand back to the same browser
 * would buffer the whole skill in the tab for nothing, and throw away the filename the server put
 * in `Content-Disposition`.
 *
 * An anchor rather than `location.assign` for one reason that matters: assigning the location
 * commits the top-level document to the response, so anything other than a download — a session
 * that expired into a redirect, an error body — replaces the page the person was working in.
 * A click on a link that turns out not to be a download leaves them where they were.
 */
export function downloadSkill(scope, skill) {
  const link = document.createElement('a');
  link.href = `/api/skills/export?${where(scope, skill)}`;
  // Only a hint; the server's Content-Disposition is what actually names the file, and it spells
  // a non-ASCII name in the two forms a header can carry.
  link.download = `${skill}.zip`;
  link.rel = 'noopener';
  link.hidden = true;
  document.body.append(link);
  link.click();
  link.remove();
}

/**
 * Deletes one file, having asked.
 *
 * Answers whether it went ahead, because the caller has to know whether the file it was showing is
 * still there — the pane it is in cannot go on displaying a file that has gone.
 */
export function deleteFile(scope, skill, path) {
  return confirmAction({
    title: t('skills.file.delete.confirm', path),
    body: t('skills.file.delete.body'),
    action: t('skills.file.delete'),
    run: async () => {
      await api(`/api/skills/file?${where(scope, skill, path)}`, { method: 'DELETE' });
      toast(t('skills.file.deleted', path), 'settled');
      bus.emit('skills:changed');
    },
  });
}

export function deleteSkill(scope, skill) {
  return confirmAction({
    title: t('skills.delete.confirm', skill),
    body: t('skills.delete.body'),
    action: t('skills.delete'),
    run: async () => {
      await api(`/api/skills?${where(scope, skill)}`, { method: 'DELETE' });
      toast(t('skills.deleted', skill), 'settled');
      bus.emit('skills:changed');
    },
  });
}
