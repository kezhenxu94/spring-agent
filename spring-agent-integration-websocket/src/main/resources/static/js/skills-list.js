// One row per skill, in the page rather than in the rail.
//
// A row says three things and stops: what the skill is called, what it says it does, and how much
// of it there is. Everything else a person might want — which files, what they contain — is what
// opening it is for, and putting a preview of it in the row would make a list of five skills as
// long as the page.
//
// The one thing here that is not description is the shadowed mark. See below: it is the only fact
// on this page that nothing else in the product tells anybody.

import { t } from './i18n.js';
import { fullTime } from './dom.js';

/**
 * Draws the list into `host`.
 *
 * @param skills  what the server answered, already in the store's own order
 * @param open    called with a skill's name when its row is pressed — it navigates
 */
export function renderSkillList(host, skills, open) {
  host.textContent = '';
  skills.forEach((skill) => {
    const item = document.createElement('li');
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'skill-card';

    const name = document.createElement('div');
    name.className = 'skill-name min-w-0 max-w-full truncate';
    name.textContent = skill.name;
    row.append(name);

    if (skill.description) {
      const why = document.createElement('p');
      why.className = 'skill-why line-clamp-2 min-w-0';
      why.textContent = skill.description;
      row.append(why);
    }

    const facts = document.createElement('div');
    facts.className = 'skill-facts';
    facts.append(fact(t(skill.fileCount === 1 ? 'skills.file.one' : 'skills.files',
      skill.fileCount)));
    if (skill.updatedAt) {
      const when = fact(t('skills.updated', new Date(skill.updatedAt).toLocaleDateString()));
      when.title = fullTime(skill.updatedAt);
      facts.append(when);
    }
    // A skill whose SKILL.md names nothing is skipped by the runtime with a warning in a log, so
    // this row is the only place a person can find out why their skill never fires.
    if (!skill.declaredName) facts.append(mark(t('skills.unnamed'), t('skills.unnamed.why')));
    // And a company skill whose name the reader also has privately: the agent loads the nearer of
    // the two, so this one is installed and inert. Said in words rather than in a colour — colour
    // on this page means a run — and shown rather than hidden, because somebody looking for what
    // their team shared has to be able to find it and see why it is not the one being used.
    if (skill.shadowed) mark(t('skills.shadowed'), t('skills.shadowed.why'), facts);

    row.append(facts);
    row.addEventListener('click', () => open(skill.name));
    item.append(row);
    host.append(item);
  });
}

function fact(text) {
  const span = document.createElement('span');
  span.className = 'skill-fact';
  span.textContent = text;
  return span;
}

function mark(text, why, host) {
  const span = document.createElement('span');
  span.className = 'skill-shadowed';
  span.textContent = text;
  span.title = why;
  if (host) host.append(span);
  return span;
}
