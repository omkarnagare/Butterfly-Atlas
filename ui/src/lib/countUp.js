import { reducedMotion } from './motion.js';

const DURATION = 900;

const target = (value) => (typeof value === 'object' && value !== null ? value.to : value);
const formatOf = (value) => (typeof value === 'object' && value?.format) || String;

/**
 * Svelte action: counts the element's text up to `value` with an ease-out curve. `value` may be
 * `{ from, to }` to start from a number other than 0 (the celebration ticks 11 → 12), and may carry `format`
 * (number → text, e.g. with thousands separators) for every step shown.
 */
export function countUp(node, value) {
  let frame = 0;
  let shown = typeof value === 'object' && value !== null ? value.from ?? 0 : 0;
  let format = formatOf(value);

  function stop() {
    if (frame && typeof cancelAnimationFrame === 'function') cancelAnimationFrame(frame);
    frame = 0;
  }

  function run(to) {
    stop();
    const from = shown;
    if (reducedMotion() || typeof requestAnimationFrame !== 'function' || to === from) {
      shown = to;
      node.textContent = format(to);
      return;
    }
    // The page may have been rendered with the final number: show the start until the first frame.
    node.textContent = format(from);
    const start = performance.now();
    const step = (now) => {
      const k = Math.min(1, (now - start) / DURATION);
      shown = Math.round(from + (to - from) * (1 - (1 - k) ** 3));
      node.textContent = format(shown);
      frame = k < 1 ? requestAnimationFrame(step) : 0;
    };
    frame = requestAnimationFrame(step);
  }

  run(target(value));
  return {
    update(next) {
      format = formatOf(next);
      run(target(next));
    },
    destroy: stop,
  };
}
