/** True when the OS asks for less motion (docs/PLAN.md §8a). Every script-driven animation checks it. */
export function reducedMotion() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches === true;
}

/** Longest stagger step, so long grids do not take seconds to appear. */
export const STAGGER_MAX = 12;
export const stagger = (i) => Math.min(i, STAGGER_MAX);
