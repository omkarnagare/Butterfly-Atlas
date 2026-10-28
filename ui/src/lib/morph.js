import { reducedMotion } from './motion.js';

/*
 * Shared-element transition (docs/PLAN.md §8a): the clicked card's title or cover grows into the
 * next page's header. FLIP with the Web Animations API, which every supported web view has; the
 * View Transitions API is avoided because older WebKit lacks it.
 */
let remembered = null;
let rememberedAt = 0;

/** Call from a card's click handler with the element that should morph. */
export function rememberMorph(element) {
  remembered = element ? element.getBoundingClientRect() : null;
  rememberedAt = performance.now();
}

/** Svelte action for the header of the page being opened. */
export function morphInto(node) {
  const from = performance.now() - rememberedAt <= 1000 ? remembered : null;
  remembered = null;
  if (!from || reducedMotion() || typeof node.animate !== 'function') return;
  const to = node.getBoundingClientRect();
  if (!to.width || !to.height) return;
  const dx = from.left - to.left;
  const dy = from.top - to.top;
  const sx = from.width / to.width;
  const sy = from.height / to.height;
  node.animate(
    [
      { transformOrigin: 'top left', transform: `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})`, opacity: 0.6 },
      { transformOrigin: 'top left', transform: 'none', opacity: 1 },
    ],
    { duration: 420, easing: 'cubic-bezier(.2,.8,.2,1)' },
  );
}
