/*
 * Virtualised lists that scroll with the page (docs/PLAN.md §8a "Performance at scale"), as the import grid does:
 * the list is as tall as all its rows, and only the rows near the window's view are in the page.
 */

/**
 * Of `count` rows `height` px tall, the ones that touch [from, to) (pixels from the list's top), plus `overscan` either
 * side: `{ start, end }`, end exclusive. A list wholly above or below the view keeps a few rows at that end.
 */
export function rowRange(count, height, from, to, overscan = 4) {
  if (!count) return { start: 0, end: 0 };
  const start = Math.max(0, Math.floor(from / height) - overscan);
  const end = Math.min(count, Math.ceil(to / height) + overscan);
  if (start >= count) return { start: Math.max(0, count - 1 - overscan), end: count };
  if (end <= 0) return { start: 0, end: Math.min(count, overscan) };
  return { start, end };
}

/**
 * Calls `onView({ from, to, width })` now and whenever it may have changed: the window's view in pixels from `el`'s top,
 * and `el`'s width. On every scroll and resize of the window at once; and, at most once a frame, when `el`'s width
 * changes (a classic scrollbar came or went) or the page's size does (content above the list came, went or wrapped,
 * moving it). `el`'s own height, which the list sets itself, is not followed. jsdom has no ResizeObserver: there only
 * the window is followed. Returns a function that stops; its `measure()` measures at once (after the caller laid out
 * anew). `onView` may be called with an unchanged view: callers keep their own when nothing changed.
 */
export function trackView(el, onView) {
  const measure = () => {
    const top = el.getBoundingClientRect().top;
    onView({ from: -top, to: window.innerHeight - top, width: el.clientWidth });
  };
  measure();
  window.addEventListener('scroll', measure, { passive: true });
  window.addEventListener('resize', measure);

  let frame = 0;
  let lastWidth = el.clientWidth;
  const soon = () => {
    if (frame) return;
    const raf = globalThis.requestAnimationFrame ?? ((fn) => setTimeout(fn, 16));
    frame = raf(() => {
      frame = 0;
      measure();
    });
  };
  const observer = typeof ResizeObserver === 'function'
    ? new ResizeObserver((entries) => {
      let due = false;
      for (const entry of entries) {
        if (entry.target !== el) due = true;
        else if (entry.contentRect.width !== lastWidth) {
          lastWidth = entry.contentRect.width;
          due = true;
        }
      }
      if (due) soon();
    })
    : null;
  observer?.observe(el);
  observer?.observe(document.body);

  const stop = () => {
    window.removeEventListener('scroll', measure);
    window.removeEventListener('resize', measure);
    observer?.disconnect();
    if (frame) (globalThis.cancelAnimationFrame ?? clearTimeout)(frame);
    frame = 0;
  };
  stop.measure = measure;
  return stop;
}
