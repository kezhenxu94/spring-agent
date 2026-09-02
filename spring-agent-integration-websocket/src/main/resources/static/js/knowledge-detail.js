// One document, opened: what it is, where it came from, and who else can read it.
//
// With nothing selected this draws nothing and puts the two ways of adding a document back on
// screen, which is what the section is for when it is empty.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { api } from './api.js';
import { attempt, toast } from './toast.js';
import { knowledgeRoute, go } from './route.js';

export function renderKnowledgeDetail(entry, options) {
  const host = $('knowledge-detail');
  host.replaceChildren();
  host.hidden = !entry;
  // The blurb and the add box are for somebody who has not chosen anything yet; a document on
  // screen is what they came for, and putting a form under it would bury it.
  $('knowledge-intro').hidden = Boolean(entry);
  $('knowledge-add').hidden = Boolean(entry) || Boolean(options.readOnly);
  if (!entry) return;

  const head = document.createElement('div');
  head.className = 'flex items-start gap-2';
  const title = document.createElement('h2');
  title.className = 'min-w-0 flex-1 font-display text-[17px] font-semibold leading-snug '
    + 'tracking-tight';
  title.textContent = entry.title || entry.docId;
  const scope = document.createElement('span');
  scope.className = `knowledge-scope knowledge-scope-${entry.scope} mt-1 shrink-0`;
  scope.textContent = t(`knowledge.scope.${entry.scope}`);
  head.append(title, scope);
  host.append(head);

  const facts = document.createElement('dl');
  facts.className = 'grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-[11.5px]';
  if (entry.source) fact(facts, t('knowledge.source'), entry.source, true);
  if (entry.chunkCount) fact(facts, t('knowledge.chunks.label'), String(entry.chunkCount));
  if (entry.createdAt) {
    fact(facts, t('knowledge.added'), new Date(entry.createdAt).toLocaleString());
  }
  if (typeof entry.score === 'number') fact(facts, t('knowledge.score'), entry.score.toFixed(3));
  host.append(facts);

  if (!options.readOnly) host.append(actions(entry, options));
}

function fact(host, label, value, mono) {
  const key = document.createElement('dt');
  key.className = 'text-mist';
  key.textContent = label;
  const text = document.createElement('dd');
  // A path or a URL is long and is read left to right; wrapping it beats truncating something
  // whose whole purpose is to be gone and looked at.
  text.className = mono ? 'break-all font-mono text-[11px]' : '';
  text.textContent = value;
  host.append(key, text);
}

function actions(entry, options) {
  const host = document.createElement('div');
  host.className = 'flex flex-wrap gap-2 pt-1';

  if (options.tenant) {
    const target = entry.scope === 'tenant' ? 'own' : 'tenant';
    const move = document.createElement('button');
    move.type = 'button';
    move.className = 'panel-action';
    move.textContent = t(entry.scope === 'tenant' ? 'knowledge.unshare' : 'knowledge.share');
    move.addEventListener('click', () => attempt(async () => {
      // Re-embeds every chunk on the way, so it is not instant on a large document.
      move.disabled = true;
      // The id travels in the body, never in the path: a document indexed from a file is
      // identified by its absolute path, and a path inside a path is not a route.
      await api('/api/knowledge', {
        method: 'PATCH',
        body: JSON.stringify({ docId: entry.docId, scope: target }),
      });
      await options.refresh();
      toast(t('knowledge.moved'), 'settled', 2500);
    }));
    host.append(move);
  }

  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'panel-action';
  remove.textContent = t('knowledge.delete');
  remove.addEventListener('click', () => {
    if (!window.confirm(t('knowledge.delete.confirm'))) return;
    attempt(async () => {
      await api(`/api/knowledge?docId=${encodeURIComponent(entry.docId)}`, { method: 'DELETE' });
      go(knowledgeRoute());
      await options.refresh();
      toast(t('knowledge.deleted'), 'settled', 2500);
    });
  });
  host.append(remove);
  return host;
}
