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

let open = null;

export function closeMenu() {
  if (!open) return;
  open.menu.remove();
  open.trigger.setAttribute('aria-expanded', 'false');
  open = null;
}

/**
 * Opens a menu against `trigger`.
 *
 * Items are `{ label, code, danger, checked, onSelect }`, and anything falsy in the list is dropped
 * so a caller can write a conditional entry inline rather than assembling the array in two steps.
 *
 * `checked` absent and `checked: false` are different things: absent is a command, and false is one
 * of a set of choices that is not the current one. A choice gets the radio role and a tick column,
 * so its rows line up whichever of them is ticked.
 *
 * Two entries are not commands at all: `{ heading }` names the set of choices under it, and
 * `{ separator: true }` divides one set from the next. They exist because a menu holding more than
 * one set of choices — the preferences menu holds two — is otherwise a run of ticked rows with
 * nothing saying which tick answers which question.
 */
export function openMenu(trigger, items) {
  closeMenu();
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
    button.append(document.createTextNode(entry.label));
    // A short constant beside the label — a language tag, so far. It sits at the end of the row so
    // the labels themselves stay in one column whatever length the codes are.
    if (entry.code) {
      const code = document.createElement('span');
      code.className = 'menu-code';
      code.textContent = entry.code;
      button.append(code);
    }
    button.addEventListener('click', () => {
      closeMenu();
      entry.onSelect();
    });
    item.append(button);
    menu.append(item);
  });

  document.body.append(menu);
  place(menu, trigger);
  trigger.setAttribute('aria-expanded', 'true');
  open = { menu, trigger };
  menu.querySelector('button')?.focus();
}

/**
 * Opens a menu against `trigger`, or closes the one already open on it.
 *
 * What a button wants, rather than what openMenu does: pressing a trigger a second time has to put
 * the menu away.
 */
export function toggleMenu(trigger, items) {
  if (open && open.trigger === trigger) closeMenu();
  else openMenu(trigger, typeof items === 'function' ? items() : items);
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

/** Under the trigger, pulled back inside the window wherever that would put it outside. */
function place(menu, trigger) {
  const at = trigger.getBoundingClientRect();
  // Measured where it will not be seen, then moved. A fixed element with no offsets is laid out
  // wherever it happens to fall, which for one appended to the body is the bottom of the page.
  menu.style.visibility = 'hidden';
  menu.style.left = '0';
  menu.style.top = '0';
  const size = menu.getBoundingClientRect();
  const margin = 8;
  const left = Math.min(
    Math.max(margin, at.right - size.width),
    window.innerWidth - size.width - margin,
  );
  const below = at.bottom + 4;
  const top = below + size.height > window.innerHeight - margin
    ? Math.max(margin, at.top - size.height - 4)
    : below;
  menu.style.left = `${left}px`;
  menu.style.top = `${top}px`;
  menu.style.visibility = '';
}

// One set of listeners for every menu there will ever be, registered once. Bound in the capture
// phase so a press anywhere closes this before whatever it landed on acts on it.
document.addEventListener('pointerdown', (event) => {
  // `contains` rather than an identity check on the trigger: a trigger whose label is an icon has
  // the svg as the event's target, so identity said "pressed somewhere else", the menu closed here
  // and the click that followed opened it again — a button that could never be pressed shut.
  if (open && !open.menu.contains(event.target) && !open.trigger.contains(event.target)) closeMenu();
}, true);
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && open) {
    const { trigger } = open;
    closeMenu();
    trigger.focus();
  }
});
// The menu is placed against a rectangle that scrolling moves, so it follows nothing: it goes.
window.addEventListener('resize', closeMenu);
document.addEventListener('scroll', closeMenu, true);
