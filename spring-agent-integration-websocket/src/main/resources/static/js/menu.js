// A menu of what can be done to the thing you pressed it on.
//
// Rows used to carry their one action as a × that appeared on hover, which works for exactly one
// action and for exactly one input device: on a touchscreen there is no hover, and a second action
// has nowhere to go. A menu holds however many there are, names each of them in words, and is
// reachable by tapping.
//
// The menu is put in the body and positioned against the trigger's rectangle rather than nested
// inside the row. A row in the sidebar sits inside a scrolling column, and a menu positioned within
// it is clipped by that column's overflow — the item nearest the bottom of the list would open a
// menu with its own last entries cut off. Fixed placement is also why this closes on scroll: the
// rectangle it was measured against has moved.
//
// A menu can open another one from one of its rows, so what is open is a *stack* rather than a
// single menu: the account menu at the foot of the sidebar holds the theme and the language as two
// sets of choices, and a flat menu listing both is a run of ticked rows with nothing saying which
// tick answers which question. Headings say it for two short sets; a submenu says it for a set
// worth its own panel, and keeps the parent one line per decision.

const SVG = 'http://www.w3.org/2000/svg';

let layers = [];

/** Closes every open menu, from the outermost down. */
export function closeMenu() {
  closeFrom(0);
}

/** Closes the menu at `depth` and everything it opened, deepest first. */
function closeFrom(depth) {
  while (layers.length > depth) {
    const layer = layers.pop();
    layer.menu.remove();
    layer.trigger.setAttribute('aria-expanded', 'false');
  }
}

/**
 * Opens a menu against `trigger`.
 *
 * Items are `{ label, icon, code, value, danger, checked, onSelect }`, and anything falsy in the list is
 * dropped so a caller can write a conditional entry inline rather than assembling the array in two
 * steps.
 *
 * `checked` absent and `checked: false` are different things: absent is a command, and false is one
 * of a set of choices that is not the current one. A choice gets the radio role and a tick column,
 * so its rows line up whichever of them is ticked.
 *
 * Three entries are not commands at all: `{ heading }` names the set of choices under it,
 * `{ separator: true }` divides one set from the next, and `{ node }` puts an element of the
 * caller's own in a row — something to read, and whatever controls belong to it, rather than
 * something the menu will act on and close.
 *
 * `{ submenu }` is a row that opens a menu of its own instead of doing anything: an array, or a
 * function returning one, so that its ticks are built from the state at the moment it is opened.
 * `value` beside such a label is what the set under it currently says, so the answer is readable
 * without opening it.
 */
export function openMenu(trigger, items) {
  closeMenu();
  openLayer(trigger, items, 0);
}

function openLayer(trigger, items, depth, focusFirst = true) {
  closeFrom(depth);
  const entries = items.filter(Boolean);
  if (!entries.length) return;

  const menu = document.createElement('ul');
  menu.className = 'menu menu-floating';
  menu.setAttribute('role', 'menu');

  entries.forEach((entry) => {
    if (entry.separator) {
      const rule = document.createElement('li');
      rule.className = 'menu-separator';
      rule.setAttribute('role', 'separator');
      menu.append(rule);
      return;
    }
    if (entry.heading) {
      const head = document.createElement('li');
      head.setAttribute('role', 'presentation');
      head.className = 'menu-heading';
      head.textContent = entry.heading;
      menu.append(head);
      return;
    }
    if (entry.node) {
      const row = document.createElement('li');
      row.setAttribute('role', 'presentation');
      row.className = 'menu-node';
      row.append(entry.node);
      menu.append(row);
      return;
    }
    const item = document.createElement('li');
    item.setAttribute('role', 'none');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = entry.danger ? 'menu-item menu-item-danger' : 'menu-item';
    const choice = typeof entry.checked === 'boolean';
    button.setAttribute('role', choice ? 'menuitemradio' : 'menuitem');
    if (choice) {
      button.setAttribute('aria-checked', String(entry.checked));
      const tick = document.createElement('span');
      tick.className = 'menu-tick';
      tick.textContent = entry.checked ? '✓' : '';
      button.append(tick);
    }
    // A glyph before the label, for a menu whose entries are actions rather than a set of choices
    // — it is what lets two of them be told apart at a glance rather than read. Given as a
    // function so the node is built per press, like everything else in here.
    if (entry.icon) {
      const mark = document.createElement('span');
      mark.className = 'menu-icon';
      mark.append(entry.icon());
      button.append(mark);
    }
    button.append(document.createTextNode(entry.label));
    // A short constant beside the label — a language tag, so far. It sits at the end of the row so
    // the labels themselves stay in one column whatever length the codes are.
    if (entry.code) {
      const code = document.createElement('span');
      code.className = 'menu-code';
      code.textContent = entry.code;
      button.append(code);
    }
    if (entry.value) {
      const value = document.createElement('span');
      value.className = 'menu-value';
      value.textContent = entry.value;
      button.append(value);
    }
    if (entry.submenu) {
      button.setAttribute('aria-haspopup', 'menu');
      button.setAttribute('aria-expanded', 'false');
      const arrow = document.createElement('span');
      // .row-icon, which is also what the copy button on the id row above wears: the two are the
      // only things at this menu's right edge and are read as one column, so they are one size.
      arrow.className = 'menu-arrow row-icon';
      arrow.append(chevron());
      button.append(arrow);
      button.addEventListener('click', () => toggleLayer(button, entry.submenu, depth + 1));
    } else {
      button.addEventListener('click', () => {
        closeMenu();
        entry.onSelect();
      });
    }
    // A submenu opens on the pointer arriving, which is what a menu that holds one is expected to
    // do — reading a row and then having to press it is a step nobody takes on purpose. Still
    // clickable, because that is the whole of the interaction where there is no pointer at all, and
    // the focus stays where it was on a hover: taking it would move the keyboard somewhere the
    // reader did not ask to go.
    //
    // Moving onto any other row closes the submenu again, which would otherwise stand over the rows
    // beside it long after the pointer left the one that opened it.
    button.addEventListener('mouseenter', () => {
      if (layers[depth + 1] && layers[depth + 1].trigger === button) return;
      closeFrom(depth + 1);
      if (entry.submenu) {
        openLayer(button, resolve(entry.submenu), depth + 1, false);
      }
    });
    item.append(button);
    menu.append(item);
  });

  document.body.append(menu);
  place(menu, trigger, depth === 0 ? 'below' : 'beside');
  trigger.setAttribute('aria-expanded', 'true');
  layers[depth] = { menu, trigger };
  if (focusFirst) menu.querySelector('button')?.focus();
}

/**
 * The mark on a row that opens a menu of its own.
 *
 * Drawn rather than a `›`, because a character is sized by the font's metrics and an icon by the
 * box it sits in: no font-size makes a text glyph agree with the svg on the row above it, and this
 * menu's right edge is a column of two things that have to look like one kind of thing.
 */
function chevron() {
  const svg = document.createElementNS(SVG, 'svg');
  svg.setAttribute('viewBox', '0 0 16 16');
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '1.5');
  svg.setAttribute('stroke-linecap', 'round');
  svg.setAttribute('stroke-linejoin', 'round');
  svg.setAttribute('aria-hidden', 'true');
  const path = document.createElementNS(SVG, 'path');
  path.setAttribute('d', 'M6.4 3.8 10.6 8l-4.2 4.2');
  svg.append(path);
  return svg;
}

/** Items given as a function are built at the moment of opening, so their ticks are current. */
const resolve = (items) => (typeof items === 'function' ? items() : items);

function toggleLayer(trigger, items, depth) {
  if (layers[depth] && layers[depth].trigger === trigger) closeFrom(depth);
  else openLayer(trigger, resolve(items), depth);
}

/**
 * Opens a menu against `trigger`, or closes the one already open on it.
 *
 * What a button wants, rather than what openMenu does: pressing a trigger a second time has to put
 * the menu away.
 */
export function toggleMenu(trigger, items) {
  toggleLayer(trigger, items, 0);
}

/** A ⋯ button that opens one. The items are built on each press, so they follow the current state. */
export function menuButton(label, items, className = 'row-menu') {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = className;
  button.setAttribute('aria-haspopup', 'menu');
  button.setAttribute('aria-expanded', 'false');
  button.setAttribute('aria-label', label);
  button.title = label;
  button.textContent = '⋯';
  button.addEventListener('click', (event) => {
    // The row underneath opens a conversation or a document; pressing its menu is not that.
    event.preventDefault();
    event.stopPropagation();
    toggleMenu(button, items);
  });
  return button;
}

/**
 * Under the trigger, or — for a submenu — to the side of the row that opened it, pulled back inside
 * the window wherever that would put it outside.
 */
function place(menu, trigger, how) {
  const at = trigger.getBoundingClientRect();
  // Measured where it will not be seen, then moved. A fixed element with no offsets is laid out
  // wherever it happens to fall, which for one appended to the body is the bottom of the page.
  menu.style.visibility = 'hidden';
  menu.style.left = '0';
  menu.style.top = '0';
  const size = menu.getBoundingClientRect();
  const margin = 8;
  let left;
  let top;
  if (how === 'beside') {
    // Out to the right of the parent row, and back to its left where the window ends first — which
    // on a narrow screen it does, the sidebar being a drawer nearly as wide as the viewport.
    const right = at.right + 4;
    left = right + size.width > window.innerWidth - margin
      ? Math.max(margin, at.left - size.width - 4)
      : right;
    top = Math.min(
      Math.max(margin, at.top - 4),
      window.innerHeight - size.height - margin,
    );
  } else {
    left = Math.min(
      Math.max(margin, at.right - size.width),
      window.innerWidth - size.width - margin,
    );
    const below = at.bottom + 4;
    top = below + size.height > window.innerHeight - margin
      ? Math.max(margin, at.top - size.height - 4)
      : below;
  }
  menu.style.left = `${left}px`;
  menu.style.top = `${Math.max(margin, top)}px`;
  menu.style.visibility = '';
}

// One set of listeners for every menu there will ever be, registered once. Bound in the capture
// phase so a press anywhere closes this before whatever it landed on acts on it.
document.addEventListener('pointerdown', (event) => {
  if (!layers.length) return;
  // `contains` rather than an identity check on the trigger: a trigger whose label is an icon has
  // the svg as the event's target, so identity said "pressed somewhere else", the menu closed here
  // and the click that followed opened it again — a button that could never be pressed shut.
  if (layers.some((layer) => layer.menu.contains(event.target))) return;
  if (layers[0].trigger.contains(event.target)) return;
  closeMenu();
}, true);
document.addEventListener('keydown', (event) => {
  // The deepest menu only: Escape in a submenu goes back to the menu that opened it, which is
  // where the key left off, rather than shutting the lot.
  if (event.key === 'Escape' && layers.length) {
    const { trigger } = layers[layers.length - 1];
    closeFrom(layers.length - 1);
    trigger.focus();
  }
});
// The menu is placed against a rectangle that scrolling moves, so it follows nothing: it goes.
window.addEventListener('resize', closeMenu);
document.addEventListener('scroll', closeMenu, true);
