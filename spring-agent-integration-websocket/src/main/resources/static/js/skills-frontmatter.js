// SKILL.md as it is read: without the two front-matter lines the panel has already said.
//
// A skill's name and description are the head of this panel — the title, and the sentence under
// it. Left in the rendered file as well they are the first thing under that head, so every skill
// opens on a repeat of itself and the instructions, the only part anybody opens SKILL.md to read,
// start below them.
//
// Reading only. Pressing Edit gives the file as it is stored, front matter and all: what is saved
// is whatever is in the box, and a box that showed less than the file would drop the rest on the
// first save — leaving a skill with no name, which is the state the panel warns about elsewhere
// (see `skills.unnamed.why`). It is also where somebody goes to fix exactly these two lines.
//
// One rule about what is taken out is worth knowing: only a key whose whole value is on its own
// line. A value written as a block (`description: >`) or continued by indentation carries on into
// the lines below, and taking the first of those away would leave the rest as stray text.
// Everything else in the front matter stays — it is the skill's own configuration, this panel says
// nothing about it, and hiding it would be hiding the file.

/** The one file this applies to. Every other file in a skill is shown as it is stored. */
export const SKILL_DOC = 'SKILL.md';

const HIDDEN = ['name', 'description'];

/** Where the front matter is, or null. `end` is the index of the closing fence. */
function frontMatter(lines) {
  if (!lines.length || lines[0].trim() !== '---') return null;
  for (let i = 1; i < lines.length; i += 1) {
    if (lines[i].trim() === '---') return { end: i };
  }
  return null; // an opening fence with no closing one is not front matter, it is the file's text
}

/** The key a top-level `key: ...` line declares, or ''. Nothing indented is top level. */
function keyOf(line) {
  const match = /^([A-Za-z0-9_-]+)\s*:(.*)$/.exec(line);
  return match ? match[1] : '';
}

/** Which lines of the front matter the head above the file has already said. */
function hidden(lines, block) {
  const out = new Set();
  for (let i = 1; i < block.end; i += 1) {
    if (!HIDDEN.includes(keyOf(lines[i]))) continue;
    const value = lines[i].slice(lines[i].indexOf(':') + 1).trim();
    const continued = i + 1 < block.end && /^[ \t]/.test(lines[i + 1]);
    if (value && !continued) out.add(i);
  }
  return out;
}

/** `text` without those lines — and without the fence, where they were all of it. */
export function withoutDeclared(text) {
  const lines = (text || '').split('\n');
  const block = frontMatter(lines);
  if (!block) return text || '';

  const taken = hidden(lines, block);
  const kept = lines.slice(1, block.end).filter((line, i) => !taken.has(i + 1));
  const body = lines.slice(block.end + 1);

  // An empty fence is a box with nothing in it. The blank lines that separated it from the text
  // go with it, or the file opens on whitespace.
  if (!kept.some((line) => line.trim())) {
    while (body.length && !body[0].trim()) body.shift();
    return body.join('\n');
  }
  return ['---', ...kept, '---', ...body].join('\n');
}
