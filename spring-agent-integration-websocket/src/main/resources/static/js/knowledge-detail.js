// One document, opened: what it is, where it came from, who else can read it, and what it holds.
//
// The text is the point of this panel. A listing can say a document exists and a search can say it
// matched, but neither answers the question somebody checking their knowledge base actually has —
// what does the agent think I told it? So the panel fetches the stored text, which is the same text
// retrieval hands the model.
//
// With nothing selected this draws nothing and puts the two ways of adding a document back on
// screen, which is what the section is for when it is empty.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { markdown } from './render.js';
import { loading } from './busy.js';
import { menuButton } from './menu.js';
import { documentActions } from './knowledge-actions.js';

// The document whose text is on screen, and a counter that says which fetch is the current one.
// Without the counter, opening a large document and then a small one races: the large one answers
// second and overwrites the small one's text under the small one's title.
let shown = { docId: null, text: null };
let fetching = 0;

/**
 * Forgets the text held for the document on screen.
 *
 * Called by whatever has just written to the knowledge base: the id is the same, so nothing else
 * would tell this panel that what it is showing is no longer what is stored.
 */
export function forgetDocumentText() {
  shown = { docId: null, text: null };
}

export function renderKnowledgeDetail(entry, options) {
  const host = $('knowledge-detail');
  host.replaceChildren();
  host.hidden = !entry;
  // The blurb and the add box are for somebody who has not chosen anything yet; a document on
  // screen is what they came for, and putting a form under it would bury it.
  $('knowledge-intro').hidden = Boolean(entry);
  $('knowledge-add').hidden = Boolean(entry) || Boolean(options.readOnly);
  // With one open, the panel becomes that document rather than a card floating in it — see
  // knowledge.css for what the class turns on and why every step of it needs a minimum of zero.
  $('knowledge-panel').classList.toggle('knowledge-open', Boolean(entry));
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

  // Everything this panel can do, in the heading rather than as a row of buttons under the text:
  // the actions belong to the document named beside them, and a document long enough to scroll
  // would otherwise put them somewhere nobody scrolls to.
  const items = () => documentActions(entry, { ...options, selected: true });
  if (items().length) {
    const actions = menuButton(t('knowledge.actions'), items);
    actions.classList.add('mt-0.5', 'shrink-0');
    head.append(actions);
  }
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

  host.append(content(entry, options));
}

/**
 * What is stored, fetched the first time the document is opened and kept until another one is.
 *
 * Only this block waits: the title, the origin and the scope are already known from the listing and
 * are drawn straight away, so what the reader sees is a document with its text arriving rather than
 * a panel that is not there yet.
 */
function content(entry, options) {
  const box = document.createElement('section');
  box.className = 'knowledge-text-box';

  const label = document.createElement('p');
  label.className = 'font-mono text-[10px] uppercase tracking-wider text-mist';
  label.textContent = t('knowledge.content');
  box.append(label);

  if (shown.docId === entry.docId) {
    box.append(text(shown.text));
    return box;
  }

  const waiting = loading();
  box.append(waiting);

  const mine = fetching + 1;
  fetching = mine;
  const params = new URLSearchParams({ docId: entry.docId });
  // The one place a request from this page names somebody: an admin reading another person's, which
  // the server checks again and allows only for a read.
  if (options.owner) params.set('owner', options.owner);
  api(`/api/knowledge/document?${params}`)
    .then((stored) => {
      // A later document was opened while this was in flight; its own fetch owns the panel now.
      if (mine !== fetching) return;
      shown = { docId: stored.docId, text: stored.text };
      waiting.replaceWith(text(stored.text));
    })
    .catch((error) => {
      if (mine !== fetching) return;
      waiting.remove();
      if (!error?.handled) toast(error?.message || t('error.generic'));
    });
  return box;
}

/**
 * The stored text, as markdown — the same way an answer in the transcript is drawn.
 *
 * Through the same sanitiser too, and not as a nicety: a document in here is whatever somebody
 * uploaded or a run stored, so it is exactly as untrusted as model output. What is rendered is
 * what the model is handed on retrieval, so reading it here reads it as the agent has it.
 */
function text(value) {
  const body = document.createElement('div');
  if (!value || !value.trim()) {
    body.className = 'knowledge-text text-mist';
    body.textContent = t('knowledge.content.empty');
    return body;
  }
  body.className = 'knowledge-text prose max-w-none text-[13.5px] leading-[1.7]';
  body.innerHTML = markdown(value);
  return body;
}

function fact(host, label, value, mono) {
  const term = document.createElement('dt');
  term.className = 'text-mist';
  term.textContent = label;
  const said = document.createElement('dd');
  // A path or a URL is long and is read left to right; wrapping it beats truncating something
  // whose whole purpose is to be gone and looked at.
  said.className = mono ? 'break-all font-mono text-[11px]' : '';
  said.textContent = value;
  host.append(term, said);
}
