import { sharpUrl } from './api.js';

/**
 * Svelte action for an <img> showing a 480 px thumbnail (2026-11-14 design §6): when the image is drawn larger than its
 * thumbnail's pixels on this screen (its size in page pixels × the screen's density, against the thumbnail's own size,
 * as a cropped tile covers it), it asks for the photo's sharp copy (GET /api/photos/{id}/sharp, made and kept on this
 * device) and, once it is there, adds it as the image's `srcset` for this density (as an object URL of the answer
 * read: one download, and no connection left holding an unread answer; revoked when the image changes or goes), so the
 * browser swaps it in and the thumbnail stays the image's `src`. Only an image on screen asks (IntersectionObserver, where
 * there is one), and only once its real thumbnail has arrived (a placeholder is 1×1). A copy still being made (204 with
 * X-Atlas-Pending) is asked for again after 2, 4, 8, 16 and 32 s, then every 30 s while the image is shown (thumbnails
 * go first, so during an import it can wait a while); one that can't be made (204 without it), or any
 * failure, leaves the thumbnail as it is. At most two copies are asked for at once across the page, so the browser's
 * few connections stay free for thumbnails and the page's own requests. `photo` is `{ id, v }`; another photo starts
 * again.
 */
const AT_ONCE = 2;
const NEEDS = 1.15; // drawn more than 15% larger than the thumbnail's pixels
const THUMB = 480;
const TRIES = 5;
const FIRST_WAIT = 2000;
const SLOW_WAIT = 30000;
const PENDING = 'X-Atlas-Pending';

let running = 0;
const waiting = [];

/** For tests: forget the queue. */
export function resetSharpen() {
  running = 0;
  waiting.length = 0;
}

function next() {
  while (running < AT_ONCE && waiting.length) {
    const job = waiting.shift();
    if (job.cancelled) continue;
    running += 1;
    job.run().finally(() => {
      running -= 1;
      next();
    });
  }
}

function enqueue(run) {
  const job = { run, cancelled: false };
  waiting.push(job);
  next();
  return job;
}

export function sharpen(img, photo) {
  let current = photo;
  let generation = 0;
  let job = null;
  let timer = 0;
  let visible = typeof IntersectionObserver !== 'function';
  let asked = false;
  let objectUrl = null;

  function forgetCopy() {
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = null;
    img.removeAttribute('srcset');
  }

  const density = () => (window.devicePixelRatio > 0 ? window.devicePixelRatio : 1);

  function needed() {
    const w = img.naturalWidth;
    const h = img.naturalHeight;
    if (w <= 1 || h <= 1 || Math.max(w, h) < THUMB) return false;
    const box = img.getBoundingClientRect();
    const d = density();
    return Math.max((box.width * d) / w, (box.height * d) / h) > NEEDS;
  }

  function consider() {
    if (asked || !visible || !current || !needed()) return;
    asked = true;
    ask(0);
  }

  function ask(tries) {
    const mine = generation;
    const url = sharpUrl(current.id, current.v);
    const d = density();
    job = enqueue(async () => {
      if (mine !== generation) return;
      let response;
      let blob = null;
      try {
        response = await fetch(url);
        if (response.status === 200) blob = await response.blob();
      } catch {
        return;
      }
      if (mine !== generation) return;
      if (blob) {
        forgetCopy();
        objectUrl = URL.createObjectURL(blob);
        img.setAttribute('srcset', `${objectUrl} ${d}x`);
      } else if (response.status === 204 && response.headers.get(PENDING)) {
        timer = setTimeout(() => ask(tries + 1), tries < TRIES ? FIRST_WAIT * 2 ** tries : SLOW_WAIT);
      }
    });
  }

  function stop() {
    generation += 1;
    if (job) job.cancelled = true;
    job = null;
    clearTimeout(timer);
  }

  const onLoad = () => consider();
  img.addEventListener('load', onLoad);
  const intersection = typeof IntersectionObserver === 'function'
    ? new IntersectionObserver((entries) => {
      visible = entries.some((entry) => entry.isIntersecting);
      consider();
    })
    : null;
  intersection?.observe(img);
  const resize = typeof ResizeObserver === 'function' ? new ResizeObserver(() => consider()) : null;
  resize?.observe(img);
  if (img.complete) consider();

  return {
    update(nextPhoto) {
      if (nextPhoto?.id === current?.id && nextPhoto?.v === current?.v) return;
      stop();
      current = nextPhoto;
      asked = false;
      forgetCopy();
      if (img.complete) consider();
    },
    destroy() {
      stop();
      forgetCopy();
      img.removeEventListener('load', onLoad);
      intersection?.disconnect();
      resize?.disconnect();
    },
  };
}
