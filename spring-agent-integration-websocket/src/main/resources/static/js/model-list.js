// The chat models a person has brought of their own. One list, unlike the MCP tab's three groups:
// a chat model is never shared and never configured by the deployment for everyone, so there is
// only ever one store to read from.
//
// A row says the name, what it asks for (provider and model), where it points, and whether it is
// the one this person's conversations currently go through.

import { t } from './i18n.js';
import { menuButton } from './menu.js';

/**
 * Draws the list into `host`.
 *
 * @param models     the rows, already in the server's order
 * @param open       called with a model's name when a row is pressed
 * @param actionsFor the ⋯ menu's items for a row
 */
export function renderModelList(host, models, open, actionsFor) {
  host.textContent = '';
  host.removeAttribute('aria-busy');
  models.forEach((model) => {
    const item = document.createElement('li');
    item.className = 'skill-card group';

    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'skill-open';
    row.addEventListener('click', () => open(model.name));

    const name = document.createElement('div');
    name.className = 'skill-name min-w-0 max-w-full truncate';
    name.textContent = model.name;
    row.append(name);

    const why = document.createElement('p');
    why.className = 'skill-why line-clamp-2 min-w-0';
    why.textContent = model.baseUrl
      ? t('model.summary.endpoint', model.model, model.baseUrl)
      : t('model.summary.builtin', model.model);
    row.append(why);

    const facts = document.createElement('div');
    facts.className = 'skill-facts';
    if (model.activated) facts.append(fact(t('model.active')));
    if (model.provider) facts.append(fact(model.provider));
    if (model.reasoningEffort) facts.append(fact(t('model.thinking', model.reasoningEffort)));
    if (model.headerNames && model.headerNames.length) {
      // That it sends extra headers, never their values.
      facts.append(fact(t('model.headers.set', model.headerNames.join(', '))));
    }

    if (actionsFor) {
      const actions = menuButton(t('model.actions'), () => actionsFor(model));
      actions.classList.add('skill-card-menu');
      facts.append(actions);
    }

    item.append(row, facts);
    host.append(item);
  });
}

function fact(text) {
  const span = document.createElement('span');
  span.className = 'skill-fact';
  span.textContent = text;
  return span;
}
