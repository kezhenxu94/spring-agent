// One card per memory, with the index pinned above them.
//
// A memory's front matter says what it is called, what sort of thing it is and, in one line, what
// it claims — which is exactly a card. That is why this is a list of cards rather than the file
// tree the skills tab draws: a skill is a folder whose files mean nothing apart from each other,
// while a memory is one file that stands alone, and a tree of single files is a tree for nothing.
//
// MEMORY.md is drawn above the grid rather than in it, because it is not one of them: it is the
// index the agent reads first, and a row for it sorted among the memories would read as a memory
// called MEMORY.
//
// A card shows what the file claims about itself and nothing more. Where it claims nothing — no
// front matter, or front matter nobody finished — the path is what is left, and the path is the
// identity anyway. A memory the agent wrote badly has to stay visible to whoever would fix it.

import { t } from './i18n.js';
import { fullTime } from './dom.js';
import { menuButton } from './menu.js';

/**
 * Draws the list into `host`.
 *
 * @param memories what the server answered, index first
 * @param open     called with a memory's path when its card is pressed — it navigates
 */
export function renderMemoryList(host, memories, open, actionsFor) {
  host.textContent = '';
  host.removeAttribute('aria-busy');
  memories.forEach((memory) => {
    const item = document.createElement('li');
    item.className = 'skill-card group' + (memory.index ? ' memory-index' : '');

    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'skill-open';

    const name = document.createElement('div');
    name.className = 'skill-name min-w-0 max-w-full truncate';
    // What it calls itself, falling back to where it is. Not both: the name is almost always the
    // path without its extension, and a card saying the same thing twice is a card with less room
    // for the line that actually tells you something.
    name.textContent = memory.name || memory.path;
    row.append(name);

    if (memory.description) {
      const why = document.createElement('p');
      why.className = 'skill-why line-clamp-2 min-w-0';
      why.textContent = memory.description;
      row.append(why);
    }
    row.addEventListener('click', () => open(memory.path));

    const facts = document.createElement('div');
    facts.className = 'skill-facts';
    // The kind of memory it is, where it says. Four words the memory prompt defines — user,
    // feedback, project, reference — so the label is looked up and falls back to the raw word,
    // which is what an agent taught a fifth kind would have written.
    if (memory.type) {
      facts.append(fact(t(`memories.type.${memory.type}`) || memory.type));
    }
    if (memory.index) facts.append(fact(t('memories.index')));
    // Where it is, whenever that is not already the title. A memory in a folder is filed
    // deliberately, and the folder is half of what it means.
    if (memory.name && memory.path !== `${memory.name}.md`) {
      const at = fact(memory.path);
      at.classList.add('memory-path');
      facts.append(at);
    }
    if (memory.updatedAt) {
      const when = fact(t('skills.updated', new Date(memory.updatedAt).toLocaleDateString()));
      when.title = fullTime(memory.updatedAt);
      facts.append(when);
    }

    if (actionsFor) {
      const actions = menuButton(t('memories.actions'), () => actionsFor(memory));
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
