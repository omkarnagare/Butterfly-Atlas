/**
 * A Svelte action for a bar fixed at the bottom of the window (the selection bars): while it is in the page its height
 * goes to `--selbar-h` on the document, so the footer can leave room under it (atlas.css) and its lines (a paused
 * update's notice) stay readable. Removed with the bar.
 */
export function reserveBelow(node) {
  const root = document.documentElement;
  const note = () => root.style.setProperty('--selbar-h', `${node.offsetHeight}px`);
  note();
  const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(note) : null;
  observer?.observe(node);
  return {
    destroy() {
      observer?.disconnect();
      root.style.removeProperty('--selbar-h');
    },
  };
}
