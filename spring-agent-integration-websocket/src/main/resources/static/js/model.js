// Chat models: the fourth of Customize's tabs, and the endpoints a person has brought of their own
// to answer their conversations instead of the application's own model.
//
// One store, unlike the tab beside it — a chat model is never shared and never configured by the
// deployment for everyone, so there is no pill switch here, only a list and the form that adds to
// it. Everything that decides what is on screen is in the address bar: the model being read, and
// `new` for the empty form — the same rule every other tab in this section keeps.

import { t } from './i18n.js';
import { $ } from './dom.js';
import { bus, state } from './state.js';
import { attempt } from './toast.js';
import { customizeRoute, go } from './route.js';
import { openCustomizeDetail } from './customize-chrome.js';
import { renderSkillSkeleton } from './skills-list.js';
import { renderModelList } from './model-list.js';
import { renderModelDetail } from './model-detail.js';
import { DEFAULT, activateModel, fetchModels, removeModel } from './model-actions.js';

/**
 * The route that means "the empty form" — see mcp.js's `NEW` for why this is reserved rather than
 * escaped: a model is named by whoever registers it, and the one name they cannot use is worth less
 * than a second spelling of every route here.
 */
const NEW = 'new';

/** Which answer is still wanted — see the same counter in the other tabs. */
let wanted = 0;

export function initModel() {
  state.model = { name: null, models: [], loading: false, loaded: false };

  const addButton = $('model-new');
  if (addButton) addButton.addEventListener('click', () => go(customizeRoute('model', null, NEW)));

  bus.on('model:changed', () => {
    const model = state.model;
    if (!model || !model.loaded) return;
    attempt(() => load(true));
  });
}

/** What the route means here: which model is open. */
export function showModel(route) {
  const model = state.model;
  const name = route.id || null;
  const moved = model.name !== name;
  model.name = name;

  if (moved || !model.loaded) {
    attempt(() => load(false));
    return;
  }
  draw();
}

async function load(again) {
  const model = state.model;
  const mine = (wanted += 1);
  model.loading = true;
  if (!again) draw();

  const body = await fetchModels();
  if (mine !== wanted) return;
  model.models = body.models || [];
  model.loading = false;
  model.loaded = true;
  draw();
}

// ─────────────────────────────────────── drawing ───────────────────────────────────────

function draw() {
  const model = state.model;
  const open = Boolean(model.name);

  openCustomizeDetail(open, open && {
    title: model.name === NEW ? t('model.new.title') : model.name,
    label: t('model.back'),
    onBack: back,
  });
  const browse = $('model-browse');
  const detail = $('model-detail');
  if (browse) browse.hidden = open;
  if (detail) detail.hidden = !open;

  if (open) {
    drawDetail();
    return;
  }
  drawList();
}

function drawList() {
  const model = state.model;
  const host = $('model-list');
  if (!host) return;
  if (model.loading && !model.loaded) {
    renderSkillSkeleton(host);
    const note = $('model-note');
    if (note) note.textContent = '';
    return;
  }

  renderModelList(
    host,
    model.models,
    (name) => go(customizeRoute('model', null, name)),
    (row) => [
      row.activated
        ? null
        : { label: t('model.activate'), onSelect: () => attempt(() => activateModel(row.name)) },
      { label: t('model.delete'), danger: true, onSelect: () => removeModel(row.name) },
    ].filter(Boolean),
  );

  const note = $('model-note');
  if (note) note.textContent = model.models.length || model.loading ? '' : t('model.none');
}

function drawDetail() {
  const model = state.model;
  const row = model.name === NEW ? null : model.models.find((each) => each.name === model.name);

  if (!row && model.name !== NEW) {
    if (!model.loaded) return;
    back();
    return;
  }

  renderModelDetail($('model-detail'), {
    model: row,
    onBack: back,
    onSaved: (name) => go(customizeRoute('model', null, name)),
  });
}

function back() {
  go(customizeRoute('model', null, null));
}

/** For the sidebar or a "use the built-in model" control, when this tab wants one. */
export { DEFAULT };
