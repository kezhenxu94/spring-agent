// The left-hand pane: a skill's files, as a tree.
//
// The server answers with a flat list of skill-relative paths, already in the order a tree is read
// down — folders before files at each level, then by name. So there is no tree to build here in
// the data sense: the order is the structure, and a row's depth is the number of slashes in its
// own path. That is deliberate on both sides. A nested JSON tree would be a second shape to keep
// sorted, and the one thing a tree pane must never do is disagree with itself about where a file
// is.
//
// Which folders are shut is the one piece of state on this page that is *not* in the address bar.
// Everything else here — the store, the search, the skill, the file — is, because each of those
// makes the page show something different and is therefore worth linking to. A folded folder shows
// nothing different; it just puts fewer rows between you and the row you are going to press. It is
// the same kind of setting the sidebar's own fold is, and it is kept the same way: in memory, per
// reader, forgotten on reload.

import { t } from './i18n.js';

/** A chevron, pointed down; the row turns it when it is shut. See .tree-mark in customize.css. */
function chevron() {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  path.setAttribute('d', 'M3.5 5.5 8 10l4.5-4.5');
  path.setAttribute('stroke', 'currentColor');
  path.setAttribute('stroke-width', '1.6');
  path.setAttribute('stroke-linecap', 'round');
  path.setAttribute('stroke-linejoin', 'round');
  svg.append(path);
  return svg;
}

/** Whether `path` lies inside a folder that is shut. */
function hidden(path, collapsed) {
  const parts = path.split('/');
  // Every ancestor, not only the immediate parent: shutting a folder has to take its whole
  // subtree with it, and a row two levels down has a parent that is still nominally open.
  for (let i = 1; i < parts.length; i += 1) {
    if (collapsed.has(parts.slice(0, i).join('/'))) return true;
  }
  return false;
}

/**
 * Draws the tree into `host`.
 *
 * @param entries  the flat `[{ path, dir, size }]` the server answered with, in its own order
 * @param selected the path of the file being read, or ''
 * @param collapsed a Set of folder paths that are shut, mutated by pressing one
 * @param onFile   called with a path when a file is chosen — it navigates; see route.js
 * @param onFold   called when a folder is opened or shut, so the caller can redraw
 */
export function renderTree(host, entries, selected, collapsed, onFile, onFold) {
  host.textContent = '';
  host.setAttribute('role', 'tree');
  host.setAttribute('aria-label', t('skills.tree'));

  entries.forEach((entry) => {
    if (hidden(entry.path, collapsed)) return;

    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'tree-row';
    row.setAttribute('role', 'treeitem');
    // Depth from the path itself. A tree drawn from a list has exactly one place its indentation
    // can come from, and reading it off the data is what keeps the two from drifting.
    row.style.setProperty('--depth', String(entry.path.split('/').length - 1));

    if (entry.dir) {
      const shut = collapsed.has(entry.path);
      row.setAttribute('aria-expanded', String(!shut));
      const mark = document.createElement('span');
      mark.className = 'tree-mark';
      mark.append(chevron());
      row.append(mark);
    } else {
      row.setAttribute('aria-selected', String(entry.path === selected));
      // An empty box the width of the chevron, so a file's name starts where a folder's does. A
      // tree whose two kinds of row are half a character apart reads as two lists.
      const pad = document.createElement('span');
      pad.className = 'tree-mark';
      row.append(pad);
    }

    const label = document.createElement('span');
    label.className = 'tree-label';
    label.textContent = entry.path.split('/').pop();
    label.title = entry.path;
    row.append(label);

    row.addEventListener('click', () => {
      if (!entry.dir) {
        onFile(entry.path);
        return;
      }
      if (collapsed.has(entry.path)) collapsed.delete(entry.path);
      else collapsed.add(entry.path);
      onFold();
    });

    host.append(row);
  });
}
