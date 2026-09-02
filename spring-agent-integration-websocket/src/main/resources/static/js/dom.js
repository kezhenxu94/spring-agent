// The small things every part of the page uses to draw itself.

/** By id, because that is how this page addresses everything it did not just create. */
export const $ = (id) => document.getElementById(id);

// Icons, at 16 and stroked in currentColor so they take the button's own state.
const ICONS = {
  auto: '<rect x="2.4" y="2.8" width="11.2" height="8.2" rx="1.5"/><path d="M6 13.6h4"/>',
  light: '<circle cx="8" cy="8" r="2.9"/><path d="M8 1.6v1.4M8 13v1.4M3.5 3.5l1 1M11.5 11.5l1 1'
    + 'M1.6 8H3M13 8h1.4M3.5 12.5l1-1M11.5 4.5l1-1"/>',
  dark: '<path d="M13.4 9.7A5.8 5.8 0 0 1 6.3 2.6a5.8 5.8 0 1 0 7.1 7.1Z"/>',
};

export function icon(name, size = 15) {
  return `<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3"
    stroke-linecap="round" stroke-linejoin="round" class="size-[${size}px]"
    aria-hidden="true">${ICONS[name]}</svg>`;
}

export function humanSize(bytes) {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)}KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)}MB`;
}

export function scrollToEnd(force) {
  const transcript = $('transcript');
  // Only when the reader is already at the bottom, so scrolling up to read something earlier is not
  // undone by the next delta.
  const atBottom = transcript.scrollHeight - transcript.scrollTop - transcript.clientHeight < 140;
  if (force || atBottom) transcript.scrollTop = transcript.scrollHeight;
}
