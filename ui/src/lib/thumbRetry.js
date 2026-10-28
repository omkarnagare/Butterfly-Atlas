import { THUMBS_AGAIN, status } from './status.svelte.js';

const TRIES = 5;
const FIRST_WAIT = 2000;
// Phase 7b audit 19: after the quick tries, while previews are still being made, every 30 s.
const SLOW_WAIT = 30000;
const RETRY = /&r=(\d+)$/;

/**
 * Svelte action for an <img> showing a thumbnail (GET /api/photos/{id}/thumb): one still being made
 * arrives as a 1×1 placeholder (no-store), so the image asks again after 2, 4, 8, 16 and 32 s and then
 * settles for what it has. Each try adds `&r=<n>` to the page's own address (which keeps its `?v=`), so
 * the browser fetches anew instead of reusing the placeholder. The address the page gives stays in charge:
 * when it changes (another photo in the same <img>) the count starts again and a pending try for the old
 * one is dropped. `onReady` (optional) is called once the real thumbnail, the last try or an error arrives.
 * Phase 7b (audit 19): while the status says previews are still being made (the library's, or an open import's HEIC
 * ones), the tries don't end: the image asks every 30 s and keeps waiting. When previews still being made reach none,
 * or the HEIC decoder's state changes (THUMBS_AGAIN, from lib/status.svelte.js), an image still showing a placeholder
 * asks once more at once. An image marked `data-keep-asking` (an import tile's preview, review fix U1: a placeholder
 * there is always one still pending, e.g. a HEIC decode that timed out and is tried again) asks every 30 s for as long
 * as it is shown.
 */
const stillMaking = () => status.previews.pending > 0 || (status.importPreviews?.making ?? 0) > 0;

export function thumbRetry(img, onReady) {
  let ready = onReady;
  let timer = 0;
  let handled = null; // the address and width whose arrival was already looked at

  const parts = () => {
    const src = img.getAttribute('src') ?? '';
    const match = RETRY.exec(src);
    return match ? { base: src.slice(0, match.index), tries: Number(match[1]) } : { base: src, tries: 0 };
  };

  function arrived() {
    const at = `${img.getAttribute('src')}|${img.naturalWidth}`;
    if (at === handled) return; // a cached image is seen by the early check and again by its load event
    handled = at;
    const { base, tries } = parts();
    if (img.naturalWidth > 1 || (tries >= TRIES && !stillMaking() && !img.hasAttribute('data-keep-asking'))) {
      ready?.();
      return;
    }
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (parts().base === base) img.setAttribute('src', `${base}&r=${tries + 1}`);
    }, tries >= TRIES ? SLOW_WAIT : FIRST_WAIT * 2 ** tries);
  }
  const failed = () => ready?.();
  // A placeholder that has arrived asks once more now.
  const again = () => {
    if (handled === null || img.naturalWidth > 1) return;
    clearTimeout(timer);
    const { base, tries } = parts();
    img.setAttribute('src', `${base}&r=${tries + 1}`);
  };

  img.addEventListener('load', arrived);
  img.addEventListener('error', failed);
  window.addEventListener(THUMBS_AGAIN, again);
  // A thumbnail from the cache can have arrived before the action ran.
  if (img.complete && img.naturalWidth > 0) arrived();

  return {
    update(next) {
      ready = next;
    },
    destroy() {
      clearTimeout(timer);
      img.removeEventListener('load', arrived);
      img.removeEventListener('error', failed);
      window.removeEventListener(THUMBS_AGAIN, again);
    },
  };
}
