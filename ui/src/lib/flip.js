import { reducedMotion } from './motion.js';

/*
 * FLIP with the Web Animations API (docs/PLAN.md §8a): record where things are, change the page,
 * then play each element from its old place to its new one. Nothing moves under reduced motion or
 * where element.animate is missing.
 */
const EASE = 'cubic-bezier(.2,.8,.2,1)';
const canAnimate = (el) => !reducedMotion() && typeof el?.animate === 'function';

/**
 * Where each keyed child of `container` is now. Children carry their key in data-key. `items` are the elements to
 * follow when they are not the container's own children (a grid's tiles inside its rows).
 */
export function measure(container, items = container?.children ?? []) {
  const rects = new Map();
  for (const child of items) {
    if (child.dataset.key) rects.set(child.dataset.key, child.getBoundingClientRect());
  }
  return rects;
}

/** True when the rectangle is within 200 px of the window; photos far off-screen just land. */
function nearViewport(rect) {
  const margin = 200;
  return rect.top + rect.height + margin >= 0 && rect.top - margin <= window.innerHeight
    && rect.left + rect.width + margin >= 0 && rect.left - margin <= window.innerWidth;
}

/** After the DOM has changed: children (or `items`) that moved glide from their old place, new ones fade in. */
export function play(container, before, items = container?.children ?? []) {
  if (!container) return;
  let fresh = 0;
  for (const child of items) {
    if (!child.dataset.key || !canAnimate(child)) continue;
    const old = before.get(child.dataset.key);
    // A fast second click: stop what is still running so the new place is measured as it really is.
    child.getAnimations?.().forEach((a) => a.cancel());
    const now = child.getBoundingClientRect();
    if (old) {
      if (!nearViewport(old) && !nearViewport(now)) continue;
      const dx = old.left - now.left;
      const dy = old.top - now.top;
      if (dx || dy) {
        child.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'none' }], { duration: 480, easing: EASE });
      }
    } else {
      child.animate([{ opacity: 0, transform: 'scale(.9)' }, { opacity: 1, transform: 'none' }],
        { duration: 380, delay: Math.min(fresh++, 12) * 30, easing: 'ease-out', fill: 'backwards' });
    }
  }
}

/** Elements leaving the page shrink and fade out first; resolves when they have (at once under reduced motion). */
export function fadeOut(elements) {
  const runs = [...elements].filter(canAnimate).map((el) => {
    const run = el.animate([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.85)' }], { duration: 260, easing: 'ease-in', fill: 'forwards' });
    return run?.finished?.catch(() => {}) ?? Promise.resolve();
  });
  return Promise.all(runs).then(() => {});
}

let remembered = null;
let rememberedAt = 0;

/** Call when a thumbnail is clicked, so the viewer can grow the photo out of it. */
export function rememberRect(element) {
  remembered = element ? element.getBoundingClientRect() : null;
  rememberedAt = performance.now();
}

/** The remembered thumbnail (once, and only for a second). */
export function takeRect() {
  const rect = performance.now() - rememberedAt <= 1000 ? remembered : null;
  remembered = null;
  return rect;
}

/** Plays `node` from the rectangle `from` (a thumbnail) to where it is laid out now. */
export function growFrom(node, from) {
  if (!from || !canAnimate(node)) return;
  const to = node.getBoundingClientRect();
  if (!to.width || !to.height) return;
  const dx = from.left + from.width / 2 - (to.left + to.width / 2);
  const dy = from.top + from.height / 2 - (to.top + to.height / 2);
  const scale = Math.max(from.width / to.width, from.height / to.height);
  node.animate(
    [{ transform: `translate(${dx}px, ${dy}px) scale(${scale})`, borderRadius: '16px' }, { transform: 'none' }],
    { duration: 420, easing: EASE },
  );
}
