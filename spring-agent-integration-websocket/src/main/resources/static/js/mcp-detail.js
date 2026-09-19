// One MCP server, opened: the form that registers it, and the list of who it has been shared with.
//
// The form is the record rather than a card with an Edit button on it, unlike the other two tabs.
// That is because there is nothing to *read* about an MCP server: a URL and a set of header names
// is not prose, and a card showing them with a pencil in the corner would be a card whose only
// purpose was to be edited. So the fields are always fields, and the button says what pressing it
// will do — which for this endpoint is a real thing, because saving connects.
//
// It is drawn as .detail-form, the label-and-value grid a scheduled task is edited in, and not as a
// column of captioned full-width boxes. Two reasons, and the second is the real one: the labels in
// their own column mean one box per field instead of a caption, a box and a hint stacked; and this
// card already divides its parts with a single top hairline — .detail-facts above does, and so does
// the share list below — so a form that framed every field would be the only thing on the page
// drawing a border for its own sake.
//
// Saving is slow on purpose. The server validates, checks the prefix against everything this person
// can reach, and then dials the endpoint and asks what it offers, all before storing anything. The
// waiting state is therefore not decoration: it is most of a second to several, and the answer is
// the list of tools — the only evidence anybody gets that the token they pasted is the right one.
//
// The authentication field is write-only and says so. The server hands back header *names* and
// never their values, so an untouched form must send no headers at all rather than send back the
// names with empty values, which would clear the credential on the first save of an unrelated edit.

import { t } from './i18n.js';
import { svgIcon } from './dom.js';
import { backButton, detailHead } from './detail.js';
import { busyButton, spinner } from './busy.js';
import { attempt } from './toast.js';
import { EVERYONE, saveServer, shareServer, unshareServer } from './mcp-actions.js';

const TRASH = 'M2.9 4.4h10.2M6.2 4.4V3.1h3.6v1.3M4.2 4.4l.6 8.2h6.4l.6-8.2M6.6 6.8v3.6M9.4 6.8v3.6';

/**
 * Draws the open server into `host`.
 *
 * `server` is null for one being registered from nothing, which is the same form with nothing in
 * it and a name field that is still editable — the name is the identity, so changing it on an
 * existing server would register a second one rather than rename the first.
 */
export function renderServerDetail(host, view) {
  const { server, onBack, refresh, onSaved } = view;
  const existing = Boolean(server);
  host.textContent = '';
  host.append(backButton(t('mcp.back'), onBack));
  host.append(detailHead({
    kind: t('mcp.kind'),
    pill: existing ? t('mcp.mine') : t('mcp.new'),
    name: existing ? server.name : t('mcp.new.title'),
  }));

  const form = document.createElement('form');
  form.className = 'detail-form';

  const name = field(form, t('mcp.field.name'), existing ? server.name : '', {
    // An identifier the agent refers to it by, not a sentence.
    mono: true,
    // The name addresses the row, so an existing server's is fixed here. Renaming one means
    // registering the new name and removing the old, which is two deliberate acts rather than one
    // that silently leaves a stale row behind.
    readOnly: existing,
    hint: existing ? t('mcp.field.name.fixed') : t('mcp.field.name.hint'),
    required: true,
  });
  const url = field(form, t('mcp.field.url'), existing ? server.url || '' : '', {
    mono: true,
    hint: t('mcp.field.url.hint'),
    required: true,
    type: 'url',
  });
  const auth = authField(form, existing ? server.headerNames || [] : []);
  const prefix = field(form, t('mcp.field.prefix'), prefixValue(server), {
    mono: true,
    hint: t('mcp.field.prefix.hint'),
  });
  // A note is prose and runs to more than a line — the same textarea the knowledge base's
  // "write a note" opens, because it is the same act.
  const description = field(
    form, t('mcp.field.description'), (existing && server.description) || '', { rows: 4 },
  );

  const tools = document.createElement('p');
  tools.className = 'detail-hint';

  const save = document.createElement('button');
  save.type = 'submit';
  save.className = 'panel-action panel-action-primary';
  save.textContent = existing ? t('mcp.save') : t('mcp.register');

  const buttons = document.createElement('div');
  buttons.className = 'detail-field-row';
  buttons.append(save, tools);
  // The grid is two columns, so the buttons take the value column and leave the label one empty —
  // which puts them under the fields rather than under their captions.
  form.append(document.createElement('span'), buttons);

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const wanted = name.value.trim();
    if (!wanted || !url.value.trim()) return;
    tools.textContent = t('mcp.connecting');
    const done = busyButton(save, existing ? t('mcp.save') : t('mcp.register'));
    attempt(() => saveServer({
      name: wanted,
      url: url.value.trim(),
      headers: auth.value(),
      toolPrefix: prefix.value.trim(),
      description: description.value.trim(),
    })
      .then((saved) => {
        tools.textContent = '';
        onSaved(saved.server ? saved.server.name : wanted);
      })
      .catch((error) => {
        // Cleared rather than left saying "connecting…", which after a failure reads as a request
        // still in flight. The toast carries what went wrong; attempt() puts it there.
        tools.textContent = '';
        throw error;
      })
      .finally(done));
  });

  host.append(form);
  if (existing) host.append(sharing(server, refresh));
}

/**
 * Who the server has been shared with, and the two ways to change that.
 *
 * Only for a server that exists: sharing is a change to a stored row, and offering it beside a
 * form that has not been submitted would be offering to share something that is not there.
 */
function sharing(server, refresh) {
  const box = document.createElement('section');
  box.className = 'detail-text-box';

  const label = document.createElement('p');
  label.className = 'detail-label';
  label.textContent = t('mcp.sharing');
  const hint = document.createElement('p');
  hint.className = 'detail-hint';
  hint.textContent = t('mcp.sharing.hint');
  box.append(label, hint);

  const targets = server.sharedWith || [];
  if (!targets.length) {
    const none = document.createElement('p');
    none.className = 'detail-hint';
    none.textContent = t('mcp.sharing.none');
    box.append(none);
  } else {
    const list = document.createElement('ul');
    list.className = 'mcp-shares';
    targets.forEach((target) => {
      const row = document.createElement('li');
      const who = document.createElement('span');
      who.className = target === EVERYONE ? 'mcp-share-everyone' : 'detail-fact-mono';
      who.textContent = target === EVERYONE ? t('mcp.share.everyone') : target;
      const revoke = document.createElement('button');
      revoke.type = 'button';
      revoke.className = 'tool-button tool-button-sm';
      revoke.title = t('mcp.unshare');
      revoke.setAttribute('aria-label', t('mcp.unshare'));
      revoke.append(svgIcon(TRASH));
      revoke.addEventListener('click', () => {
        const done = busyButton(revoke);
        attempt(() => unshareServer(server.name, target).then(refresh).finally(done));
      });
      row.append(who, revoke);
      list.append(row);
    });
    box.append(list);
  }

  const form = document.createElement('form');
  form.className = 'flex flex-wrap items-center gap-2';
  const target = document.createElement('input');
  target.type = 'text';
  target.className = 'field field-mono flex-1 min-w-[12rem]';
  target.placeholder = t('mcp.share.placeholder');
  const add = document.createElement('button');
  add.type = 'submit';
  add.className = 'panel-action';
  add.textContent = t('mcp.share');
  // Everyone is its own button rather than a value somebody types, because `*` is a sentinel and
  // nothing about the box says so — and because it is the one share that earns a confirmation.
  const all = document.createElement('button');
  all.type = 'button';
  all.className = 'panel-action';
  all.textContent = t('mcp.share.everyone.do');
  all.addEventListener('click', () => {
    const done = busyButton(all, t('mcp.share.everyone.do'));
    attempt(() => shareServer(server.name, EVERYONE).then(refresh).finally(done));
  });
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const who = target.value.trim();
    if (!who) return;
    const done = busyButton(add, t('mcp.share'));
    attempt(() => shareServer(server.name, who)
      .then(() => { target.value = ''; return refresh(); })
      .finally(done));
  });
  form.append(target, add, all);
  box.append(form);
  return box;
}

/**
 * The authentication field, which is write-only and one line.
 *
 * One line because one header is what a server takes — a bearer token, or an API key — and a box two
 * rows tall standing among single-line fields claims otherwise. A server wanting two headers is
 * still reachable, through the `AddMcpServer` tool, which takes a map; that trade is worth making
 * for the field somebody meets every time they add a server.
 *
 * What is stored is reported as header *names* — that the connection is authenticated and which
 * header carries it — and never as values. So the box starts empty with those names beside it, and
 * `value()` answers three different things: undefined for a form nobody touched, which the server
 * reads as "keep what is stored"; an empty object for one somebody cleared; and the parsed header
 * otherwise. Sending back the names with blank values would clear the credential every time an
 * unrelated field was edited, and the failure would arrive in a later conversation as a 401.
 */
function authField(form, headerNames) {
  const input = document.createElement('input');
  input.type = 'text';
  // Mono: a header is `Name: value`, which is a thing somebody pastes and compares, not prose.
  input.className = 'field field-mono w-full';
  input.spellcheck = false;
  input.placeholder = 'Authorization: Bearer …';

  const hint = document.createElement('p');
  hint.className = 'detail-hint';
  hint.textContent = headerNames.length
    ? t('mcp.field.auth.set', headerNames.join(', '))
    : t('mcp.field.auth.hint');

  let cleared = false;
  const clear = document.createElement('button');
  clear.type = 'button';
  clear.className = 'panel-action';
  clear.textContent = t('mcp.field.auth.clear');
  clear.hidden = !headerNames.length;
  clear.addEventListener('click', () => {
    cleared = true;
    clear.hidden = true;
    hint.textContent = t('mcp.field.auth.cleared');
  });

  const row = document.createElement('div');
  row.className = 'detail-field-row';
  row.append(input, clear);

  const box = document.createElement('div');
  box.className = 'detail-field';
  box.append(row, hint);
  form.append(label(t('mcp.field.auth')), box);

  return {
    value() {
      const typed = input.value.trim();
      if (typed) return parseHeaders(typed);
      if (cleared) return {};
      // Untouched. Undefined and not {}, so that JSON.stringify leaves the key out altogether and
      // the server keeps what it has — see the note above.
      return undefined;
    },
  };
}

/**
 * `Name: value`, which is how anybody who has seen an HTTP header would write one.
 *
 * Still splits on newlines, although the field is one line: the same parse answers a pasted pair,
 * and a split that cannot fire costs nothing next to a second spelling to keep in step.
 */
function parseHeaders(text) {
  const headers = {};
  text.split('\n').forEach((line) => {
    const at = line.indexOf(':');
    if (at <= 0) return;
    const name = line.slice(0, at).trim();
    const value = line.slice(at + 1).trim();
    if (name && value) headers[name] = value;
  });
  return headers;
}

/**
 * What goes in the prefix box for an existing server.
 *
 * Blank where nobody chose one, rather than the hash the server reports. The hash is what the tools
 * are called and is worth showing on the row; putting it in an editable field would turn it into a
 * choice the moment somebody pressed save on an unrelated edit, and a chosen hash is a prefix that
 * can no longer follow the name it was derived from.
 */
function prefixValue(server) {
  return server && server.toolPrefixChosen ? server.toolPrefix : '';
}

function field(form, caption, value, { hint, required, readOnly, type, mono, rows } = {}) {
  // `rows` makes it a textarea, styled exactly as the knowledge base's note body is: .field for
  // the frame, .field-area for the multi-line behaviour. One field vocabulary, so a note is the
  // same control wherever somebody writes one.
  const input = document.createElement(rows ? 'textarea' : 'input');
  if (rows) {
    input.rows = rows;
    input.className = `field field-area w-full${mono ? ' field-mono' : ''}`;
  } else {
    input.type = type || 'text';
    input.className = `field w-full${mono ? ' field-mono' : ''}`;
  }
  input.value = value || '';
  if (required) input.required = true;
  if (readOnly) input.readOnly = true;

  // The input goes in a .detail-field-row and not straight into the column, which is not cosmetic:
  // a field given a flex basis in a column takes that basis as a *height*, so it renders as tall as
  // it should be wide. A row makes it the width it was meant to be. Same reason tasks-detail.js
  // wraps its fields, and the reason the authentication field below wraps its own.
  const line = document.createElement('div');
  line.className = 'detail-field-row';
  line.append(input);

  const box = document.createElement('div');
  box.className = 'detail-field';
  box.append(line);
  if (hint) {
    const why = document.createElement('p');
    why.className = 'detail-hint';
    why.textContent = hint;
    box.append(why);
  }
  form.append(label(caption), box);
  return input;
}

/** The left column of the grid: what this row of the form is. */
function label(text) {
  const span = document.createElement('span');
  span.className = 'detail-label';
  span.textContent = text;
  return span;
}

/**
 * A server named by the route whose row has not arrived yet.
 *
 * Reached by a pasted link or a reload with a server open, where the list is still in flight. The
 * card is drawn as its own outline rather than left empty, for the reason the skills tab gives: a
 * page that looks like it is still working when nothing is, is the one thing worse than an error.
 */
export function renderServerPending(host, { name, onBack }) {
  host.textContent = '';
  host.append(backButton(t('mcp.back'), onBack));
  host.append(detailHead({ kind: t('mcp.kind'), pill: t('mcp.mine'), name }));

  const waiting = document.createElement('p');
  waiting.className = 'file-loading';
  waiting.append(spinner(), document.createTextNode(t('mcp.loading')));
  host.append(waiting);
}
