// One document, opened: what it is, where it came from, who else can read it, and what it holds.
//
// The text is the point of this panel. A listing can say a document exists and a search can say it
// matched, but neither answers the question somebody checking their knowledge base actually has —
// what does the agent think I told it? So the panel fetches the stored text, which is the same text
// retrieval hands the model.
//
// Drawn on the shared record card in detail.js, which is the same card a scheduled task opens in.
// With nothing selected this draws nothing and puts the two ways of adding a document back on
// screen, which is what the section is for when it is empty.

import { t } from './i18n.js';
import { $, fullTime } from './dom.js';
import { api } from './api.js';
import { toast } from './toast.js';
import { markdown } from './render.js';
import { loading } from './busy.js';
import { detailBody, detailFacts, detailHead, openDetail } from './detail.js';
import { documentActions } from './knowledge-actions.js';

// The document whose text is on screen, and a counter that says which fetch is the current one.
// Without the counter, opening a large document and then a small one races: the large one answers
// second and overwrites the small one's text under the small one's title.
// Keyed by the knowledge base as well as by the id, because an id is unique inside one base and
// not across them: the same file filed privately and company-wide is two documents wearing one id,
// and keying on the id alone would show one of them the other's text.
let shown = { docId: null, scope: null, text: null };
let fetching = 0;

/**
 * Forgets the text held for the document on screen.
 *
 * Called by whatever has just written to the knowledge base: the id is the same, so nothing else
 * would tell this panel that what it is showing is no longer what is stored.
 */
export function forgetDocumentText() {
  shown = { docId: null, scope: null, text: null };
}

export function renderKnowledgeDetail(entry, options) {
  const host = $('knowledge-detail');
  host.replaceChildren();
  host.hidden = !entry;
  // The blurb and the add box are for somebody who has not chosen anything yet; a document on
  // screen is what they came for, and putting a form under it would bury it.
  openDetail('knowledge-panel', 'knowledge-intro', Boolean(entry));
  $('knowledge-add').hidden = Boolean(entry) || Boolean(options.readOnly);
  if (!entry) return;

  const items = () => documentActions(entry, { ...options, selected: true });
  host.append(detailHead({
    kind: t('knowledge.kind'),
    // Who else can read it. Filled only for the company base, which is the one worth picking out at
    // a glance: what is in it is readable by more people than the reader.
    pill: t(`knowledge.scope.${entry.scope}`),
    pillFilled: entry.scope === 'tenant',
    // A document's name is its title, and where it has none the id is what names it.
    name: entry.title || entry.docId,
    actions: { label: t('knowledge.actions'), items },
  }));

  host.append(detailFacts([
    entry.source && [t('knowledge.source'), entry.source, true],
    entry.chunkCount && [t('knowledge.chunks.label'), String(entry.chunkCount)],
    entry.createdAt && [t('knowledge.added'), fullTime(entry.createdAt)],
    typeof entry.score === 'number' && [t('knowledge.score'), entry.score.toFixed(3)],
  ]));

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
  if (shown.docId === entry.docId && shown.scope === entry.scope) {
    return detailBody(t('knowledge.content'), text(shown.text));
  }

  const waiting = loading();
  const box = detailBody(t('knowledge.content'), waiting);

  const mine = fetching + 1;
  fetching = mine;
  // The knowledge base goes with the id, and is not optional: it is the other half of what names
  // the document — see KnowledgeBase#delete in core.
  const params = new URLSearchParams({ docId: entry.docId, scope: entry.scope });
  // The one place a request from this page names somebody: an admin reading another person's, which
  // the server checks again and allows only for a read.
  if (options.owner) params.set('owner', options.owner);
  api(`/api/knowledge/document?${params}`)
    .then((stored) => {
      // A later document was opened while this was in flight; its own fetch owns the panel now.
      if (mine !== fetching) return;
      shown = { docId: stored.docId, scope: stored.scope, text: stored.text };
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
    body.className = 'detail-text text-mist';
    body.textContent = t('knowledge.content.empty');
    return body;
  }
  body.className = 'detail-text prose max-w-none text-[13.5px] leading-[1.7]';
  body.innerHTML = markdown(value);
  return body;
}
