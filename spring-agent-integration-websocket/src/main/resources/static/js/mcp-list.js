// The MCP servers this person can reach, in three groups that are three different things.
//
// Yours are rows you can change. Shared with you are rows somebody else owns — the name, who from,
// and nothing about the connection, because a share grants the use of a server's tools and never
// its configuration. Configured here are this deployment's own, available to everyone and owned by
// nobody, so they carry no controls at all.
//
// Three labelled groups rather than one list with a column saying which: the difference is not an
// attribute of a row, it is what the row is, and a reader scanning for "can I change this" should
// not have to read a column to find out.
//
// A row says the name, the prefix its tools actually wear, and where it points. The prefix is on
// the row rather than behind the server, because it is the only thing about an MCP server a person
// ever sees anywhere else — it is what sits in front of every tool name in a transcript, and when
// nobody chose one it is a hash that nothing else in the product would ever explain.

import { t } from './i18n.js';
import { menuButton } from './menu.js';
import { EVERYONE } from './mcp-actions.js';

/**
 * Draws one group into `host`.
 *
 * @param servers   the rows, already in the server's order
 * @param open      called with a server's name when a row is pressed, or null where the group is
 *                  not something to open — a shared or configured server has nothing to show
 * @param actionsFor the ⋯ menu's items, or null for a group that offers none
 */
export function renderServerList(host, servers, open, actionsFor) {
  host.textContent = '';
  host.removeAttribute('aria-busy');
  servers.forEach((server) => {
    const item = document.createElement('li');
    item.className = 'skill-card group' + (server.enabled === false ? ' mcp-muted' : '');

    // A row that opens is a button; one that does not is a plain container. Not a disabled button:
    // a disabled control says "not now", and a shared server is not something that becomes
    // openable later — there is simply nothing of it to show.
    const row = document.createElement(open ? 'button' : 'div');
    if (open) {
      row.type = 'button';
      row.addEventListener('click', () => open(server.name));
    }
    row.className = 'skill-open';

    const name = document.createElement('div');
    name.className = 'skill-name min-w-0 max-w-full truncate';
    name.textContent = server.name;
    row.append(name);

    if (server.description || server.url) {
      const why = document.createElement('p');
      why.className = 'skill-why line-clamp-2 min-w-0';
      why.textContent = server.description || server.url;
      row.append(why);
    }

    const facts = document.createElement('div');
    facts.className = 'skill-facts';
    if (server.toolPrefix) {
      const prefix = fact(t('mcp.prefix.is', server.toolPrefix));
      prefix.classList.add('memory-path');
      // A hash is valid and unreadable, and the only place anybody meets it is in front of a tool
      // name they did not recognise. Saying so here is the one chance to explain it.
      if (server.toolPrefixChosen === false) prefix.title = t('mcp.prefix.derived');
      facts.append(prefix);
    }
    if (server.owner) facts.append(fact(t('mcp.by', server.owner)));
    if (server.enabled === false) facts.append(fact(t('mcp.off')));
    if (server.headerNames && server.headerNames.length) {
      // That it is authenticated, never with what. The values never leave the server.
      facts.append(fact(t('mcp.authenticated', server.headerNames.join(', '))));
    }
    if (server.sharedWith && server.sharedWith.length) {
      facts.append(fact(server.sharedWith.includes(EVERYONE)
        ? t('mcp.shared.everyone')
        : t('mcp.shared.count', server.sharedWith.length)));
    }

    if (actionsFor) {
      const actions = menuButton(t('mcp.actions'), () => actionsFor(server));
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
