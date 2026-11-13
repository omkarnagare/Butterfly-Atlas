/**
 * Live answers for a filter that changes as the user works (contract §4, the count line): `ask(query)` waits until
 * changes pause for `delay` ms, then calls `fetcher(query, { signal })`, aborting the request it replaces. Only the
 * answer for the newest filter reaches `onAnswer(answer, query)`; an older one arriving late is ignored, and so is its
 * failure. `onError(error, query)` hears the newest request's failure (not an abort). `stop()` drops anything pending.
 * `ask(query, { now: true })` skips the pause (the filter did not change, only what is shown of it).
 */
export function liveCount(fetcher, { delay = 200, onAnswer, onError = () => {} }) {
  let timer = 0;
  let controller = null;
  let newest = 0;

  function stop() {
    clearTimeout(timer);
    controller?.abort();
    controller = null;
    newest += 1;
  }

  function ask(query, { now = false } = {}) {
    stop();
    const mine = newest;
    timer = setTimeout(async () => {
      controller = new AbortController();
      try {
        const answer = await fetcher(query, { signal: controller.signal });
        if (mine === newest) onAnswer(answer, query);
      } catch (e) {
        if (mine === newest && e?.name !== 'AbortError') onError(e, query);
      }
    }, now ? 0 : delay);
  }

  return { ask, stop };
}
