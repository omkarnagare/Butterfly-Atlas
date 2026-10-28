import { getImport, getImportItems } from './api.js';

/**
 * Asks about an import while it is busy (contract §3, Updates): its summary, then the items changed since the
 * last answer (`since()`), about once a second. Requests never overlap: the next is asked for only after the
 * last has answered. `answer(summary, page, token)` applies an answer and says whether to go on (an error it throws
 * is logged, not counted as a miss); `token` is what
 * `begin()` returned before the requests went out (the page compares it to see whether the user changed
 * something meanwhile). When the app can't be reached it waits longer each time (1 s doubling, at most 15 s)
 * and calls `onReach(false)` after three misses in a row, `onReach(true)` once it answers again; an import that
 * is gone (404) calls `onGone(error)` and stops. Returns a function that stops it.
 */
export function pollImport(importId, { since, begin = () => null, answer, onReach = () => {}, onGone = () => {} }, { every = 1000, most = 15000 } = {}) {
  let stopped = false;
  let timer;
  let misses = 0;
  const again = async () => {
    let go = true;
    const token = begin();
    try {
      const summary = await getImport(importId);
      const page = await getImportItems(importId, since());
      if (stopped) return;
      if (misses >= 3) onReach(true);
      misses = 0;
      try {
        go = answer(summary, page, token);
      } catch (e) {
        // A mistake in applying an answer is not the app being out of reach: say so and go on asking.
        console.error(e);
      }
    } catch (e) {
      if (stopped) return;
      if (e?.status === 404) {
        onGone(e);
        return;
      }
      misses += 1;
      if (misses === 3) onReach(false);
    }
    if (!stopped && go) timer = setTimeout(again, Math.min(most, every * 2 ** misses));
  };
  timer = setTimeout(again, every);
  return () => {
    stopped = true;
    clearTimeout(timer);
  };
}
