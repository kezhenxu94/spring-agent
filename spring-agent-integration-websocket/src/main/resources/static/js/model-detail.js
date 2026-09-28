// One chat model, opened: the form that registers it. The same reasoning mcp-detail.js gives for
// drawing a server as a form rather than a card with an Edit button — there is nothing to *read*
// about an endpoint, a base URL and a token are not prose, and saving is a real act: it connects.
//
// Drawn as .detail-form, the label-and-value grid the MCP and scheduled-task forms use, so this tab
// reuses the page's vocabulary rather than inventing its own.
//
// The token field is write-only and always required — unlike an MCP server's headers, a chat
// model's token has no "leave it alone" state, because UserModelRegistry.save always reseals
// whatever token it is given. Editing anything about an existing row means retyping the token, the
// same as re-registering one over chat. The headers field keeps the MCP tab's tri-state instead:
// untouched sends nothing (keep what is stored), and a "clear" button sends an empty object.

import { t } from './i18n.js';
import { backButton, detailHead } from './detail.js';
import { busyButton } from './busy.js';
import { attempt } from './toast.js';
import { saveModel } from './model-actions.js';

const EFFORTS = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'not-sent'];

/**
 * Draws the open model into `host`.
 *
 * `model` is null for one being registered from nothing, which is the same form with nothing in it
 * and a name field that is still editable — the name is the identity, so changing it on an existing
 * model would register a second one rather than rename the first.
 */
export function renderModelDetail(host, view) {
  const { model, onBack, onSaved } = view;
  const existing = Boolean(model);
  host.textContent = '';
  host.append(backButton(t('model.back'), onBack));
  host.append(detailHead({
    kind: t('model.kind'),
    pill: existing ? t('model.mine') : t('model.new'),
    name: existing ? model.name : t('model.new.title'),
  }));

  const form = document.createElement('form');
  form.className = 'detail-form';

  const name = field(form, t('model.field.name'), existing ? model.name : '', {
    mono: true,
    readOnly: existing,
    hint: existing ? t('model.field.name.fixed') : t('model.field.name.hint'),
    required: true,
  });
  const provider = field(form, t('model.field.provider'), existing ? model.provider || '' : '', {
    mono: true,
    hint: t('model.field.provider.hint'),
  });
  const baseUrl = field(form, t('model.field.baseurl'), existing ? model.baseUrl || '' : '', {
    mono: true,
    hint: t('model.field.baseurl.hint'),
    type: 'url',
  });
  const modelName = field(form, t('model.field.model'), existing ? model.model || '' : '', {
    mono: true,
    hint: t('model.field.model.hint'),
    required: true,
  });
  const token = field(form, t('model.field.token'), '', {
    mono: true,
    hint: t('model.field.token.hint'),
    required: true,
    type: 'password',
  });
  const effort = effortField(form, existing ? model.reasoningEffort || '' : '');
  const headers = headersField(form, existing ? model.headerNames || [] : []);

  const status = document.createElement('p');
  status.className = 'detail-hint';

  const save = document.createElement('button');
  save.type = 'submit';
  save.className = 'panel-action panel-action-primary';
  save.textContent = existing ? t('model.save') : t('model.register');

  const buttons = document.createElement('div');
  buttons.className = 'detail-field-row';
  buttons.append(save, status);
  form.append(document.createElement('span'), buttons);

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    const wanted = name.value.trim();
    if (!wanted || !modelName.value.trim() || !token.value.trim()) return;
    status.textContent = t('model.connecting');
    const done = busyButton(save, existing ? t('model.save') : t('model.register'));
    attempt(() => saveModel({
      name: wanted,
      provider: provider.value.trim() || null,
      baseUrl: baseUrl.value.trim() || null,
      model: modelName.value.trim(),
      apiToken: token.value.trim(),
      reasoningEffort: effort.value || null,
      headers: headers.value(),
    })
      .then((saved) => {
        status.textContent = '';
        onSaved(saved.model ? saved.model.name : wanted);
      })
      .catch((error) => {
        status.textContent = '';
        throw error;
      })
      .finally(done));
  });

  host.append(form);
}

/**
 * How hard the model should think — a select, since the value has to be one of a fixed vocabulary a
 * gateway understands; typing the wrong word here is a model that fails on every message rather than
 * a validation error.
 */
function effortField(form, current) {
  const select = document.createElement('select');
  select.className = 'field w-full';
  const blank = document.createElement('option');
  blank.value = '';
  blank.textContent = t('model.field.effort.inherit');
  select.append(blank);
  EFFORTS.forEach((value) => {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = value === 'not-sent' ? t('model.field.effort.notsent') : value;
    if (value === current) option.selected = true;
    select.append(option);
  });

  const line = document.createElement('div');
  line.className = 'detail-field-row';
  line.append(select);
  const box = document.createElement('div');
  box.className = 'detail-field';
  box.append(line);
  form.append(label(t('model.field.effort')), box);
  return select;
}

/**
 * Extra HTTP headers, written one `Name: value` per line — the same shape and the same tri-state
 * the MCP tab's authentication field uses: undefined for untouched (keep what is stored), an empty
 * object for cleared, or the parsed lines otherwise.
 */
function headersField(form, headerNames) {
  const input = document.createElement('textarea');
  input.rows = 3;
  input.className = 'field field-area field-mono w-full';
  input.spellcheck = false;
  input.placeholder = 'X-Routing-Key: value';

  const hint = document.createElement('p');
  hint.className = 'detail-hint';
  hint.textContent = headerNames.length
    ? t('model.field.headers.set', headerNames.join(', '))
    : t('model.field.headers.hint');

  let cleared = false;
  const clear = document.createElement('button');
  clear.type = 'button';
  clear.className = 'panel-action';
  clear.textContent = t('model.field.headers.clear');
  clear.hidden = !headerNames.length;
  clear.addEventListener('click', () => {
    cleared = true;
    clear.hidden = true;
    input.value = '';
    hint.textContent = t('model.field.headers.cleared');
  });

  const row = document.createElement('div');
  row.className = 'detail-field-row';
  row.append(input, clear);

  const box = document.createElement('div');
  box.className = 'detail-field';
  box.append(row, hint);
  form.append(label(t('model.field.headers')), box);

  return {
    value() {
      const typed = input.value.trim();
      if (typed) return parseHeaders(typed);
      if (cleared) return {};
      return undefined;
    },
  };
}

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

function field(form, caption, value, { hint, required, readOnly, type } = {}) {
  const input = document.createElement('input');
  input.type = type || 'text';
  input.className = 'field field-mono w-full';
  input.value = value || '';
  if (required) input.required = true;
  if (readOnly) input.readOnly = true;

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

function label(text) {
  const span = document.createElement('span');
  span.className = 'detail-label';
  span.textContent = text;
  return span;
}
