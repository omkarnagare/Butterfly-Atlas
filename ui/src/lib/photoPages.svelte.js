import { filterPhotos } from './api.js';
import { loadProblem } from './problems.js';

/**
 * The Export screen's matching photos, in pages of 200 by cursor (Phase 4a contract §3): `load(query, true)` starts
 * again from the first page, `load(query, false)` asks for the next one (never while one is on its way, nor when there
 * are no more). Only the newest start's pages are kept: a page for an older start, or one on its way when the cursor
 * is forgotten (`forget()`, a new filter) or paging stops (`stop()`), is dropped. `keep` leaves the old photos in place
 * until the first page comes (an open viewer is not torn down); `reload(query)` loads the same filter again in place.
 * A failed page sets `error`; `stale` says it was refused as out of date (400 `invalid` for its cursor), so asking for
 * it again would fail again; `reloadFailed` says the failure was a reload's, to be tried again in place.
 */
export class PhotoPages {
  photos = $state([]);
  next = $state(null);
  loaded = $state(false);
  loading = $state(false);
  error = $state('');
  stale = false;
  reloadFailed = false;
  #run = 0;

  async load(query, fromStart, { keep = false } = {}) {
    if (fromStart) {
      this.#run += 1;
      if (!keep) this.photos = [];
      this.next = null;
      this.loaded = false;
      this.loading = false;
      this.error = '';
      this.stale = false;
      this.reloadFailed = false;
    } else if (this.loading || this.next === null) {
      return;
    }
    const run = this.#run;
    this.loading = true;
    this.error = '';
    try {
      const page = await filterPhotos(query, { after: fromStart ? null : this.next, limit: 200 });
      if (run !== this.#run) return;
      this.photos = fromStart ? page.photos : [...this.photos, ...page.photos];
      this.next = page.next;
      this.loaded = true;
      this.reloadFailed = false;
    } catch (e) {
      if (run === this.#run) {
        this.error = loadProblem(e);
        this.stale = !fromStart && e?.code === 'invalid';
      }
    } finally {
      if (run === this.#run) this.loading = false;
    }
  }

  /**
   * The same filter again (the library changed): its pages from the first, until as many photos as are loaded now
   * have come (or there are no more), and only then in place of these, so the grid keeps its height and the page its
   * scroll position. A failure keeps the photos shown, with the reason, and `next` and `stale` as they were (the cursor
   * is still the one before); a later page refused as out of date is marked `stale`. Nothing loaded: the first page.
   * The pages are asked one after another: each needs the cursor the one before answered with.
   */
  async reload(query) {
    const want = this.photos.length;
    const wasStale = this.stale;
    this.#run += 1;
    const run = this.#run;
    this.loading = true;
    this.error = '';
    let photos = [];
    let after = null;
    try {
      do {
        const page = await filterPhotos(query, { after, limit: 200 });
        if (run !== this.#run) return;
        photos.push(...page.photos);
        after = page.next;
      } while (after !== null && photos.length < want);
      this.photos = photos;
      this.next = after;
      this.loaded = true;
      this.stale = false;
      this.reloadFailed = false;
    } catch (e) {
      if (run === this.#run) {
        this.error = loadProblem(e);
        this.stale = wasStale || (after !== null && e?.code === 'invalid');
        this.reloadFailed = true;
      }
    } finally {
      if (run === this.#run) this.loading = false;
    }
  }

  /** The cursor belongs to the old filter: no next page of it may be asked for with a new one (nor may a photo wait on it). */
  forget() {
    this.#run += 1;
    this.next = null;
    this.loaded = false;
    this.loading = false;
  }

  stop() {
    this.#run += 1;
  }
}
