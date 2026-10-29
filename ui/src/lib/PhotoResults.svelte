<script>
  import { tick, untrack } from 'svelte';
  import { SvelteSet } from 'svelte/reactivity';
  import { photoThumb } from './api.js';
  import { rememberRect } from './flip.js';
  import { exportPhotoHref } from './route.js';
  import { thumbRetry } from './thumbRetry.js';
  import { CANT_SHOW, typeBadge } from './raw.js';
  import HeicBar from './HeicBar.svelte';
  import { isHeic } from './heic.js';
  import { heicBarOn } from './status.svelte.js';
  import { noteViewerOpen } from './viewer.js';
  import { rowRange, trackView } from './windowRows.js';

  /**
   * The Export screen's Photos view (contract §4): the matching photos loaded so far as a virtualised grid of square
   * thumbnails, with the species name on hover and focus; a tile opens the viewer over the results
   * (`photoHref`, by default #/export/photo/<id>?<filter>). When the end of what is loaded comes near and there are more pages (`hasMore`),
   * `onMore()` asks for the next one (never while one is `loading`; a failed page shows `error` and Try again, which
   * calls `onRetry`, by default `onMore`). One
   * tile is in the tab order; arrow keys move by tile and by row, Home and End to the ends. `focusId` (the photo open
   * in the viewer) is kept in the page so focus can return to it, and focus stays on the focused tile when the number
   * of columns changes. Each tile says where it is among the photos (the size unknown, -1, while more pages are to
   * come), and shimmers until its real thumbnail arrives (thumbRetry asks again for one still being made). The first
   * rows rise in when a new result arrives. A RAW photo carries a "RAW" badge; one whose thumbnail can't be made
   * (`unreadable`) says "Can't show this photo" (Phase 7a). With HEIC photos loaded while the computer has no HEIC
   * decoder, a bar says so above the grid, with How and Check again (Phase 7b audit D1).
   */
  let { photos, query, photoHref = exportPhotoHref, hasMore = false, loading = false, error = '', focusId = null, onMore, onRetry = onMore } = $props();

  const GAP = 10;
  const MIN_TILE = 150;
  const FALLBACK_WIDTH = 1140; // jsdom and the first paint have no layout
  let grid = $state(null);
  let view = $state({ from: 0, to: 800, width: 0 });
  let focusAt = $state(0);
  let entering = $state(true);
  // Photos whose real thumbnail has arrived (or the last try, or an error): their tiles stop shimmering.
  const ready = new SvelteSet();

  let usable = $derived(view.width || FALLBACK_WIDTH);
  let cols = $derived(Math.max(2, Math.floor((usable + GAP) / (MIN_TILE + GAP))));
  let tile = $derived(Math.floor((usable - GAP * (cols - 1)) / cols));
  let rowCount = $derived(Math.ceil(photos.length / cols));
  let range = $derived(rowRange(rowCount, tile + GAP, view.from, view.to, 2));
  let rows = $derived.by(() => {
    const indexes = [];
    for (let r = range.start; r < range.end; r++) indexes.push(r);
    const focused = Math.floor(focusAt / cols);
    if (focused < rowCount && focused < range.start) indexes.unshift(focused);
    else if (focused < rowCount && focused >= range.end) indexes.push(focused);
    return indexes;
  });

  $effect(() => {
    if (!grid) return undefined;
    // untrack: the first measure runs inside this effect, which must not come to depend on the view it sets.
    return trackView(grid, (next) => untrack(() => {
      if (next.from !== view.from || next.to !== view.to || next.width !== view.width) view = next;
    }));
  });

  // A new result starts at the first tile, and rises in.
  $effect(() => {
    query;
    untrack(() => {
      focusAt = 0;
      entering = true;
    });
    const timer = setTimeout(() => (entering = false), 900);
    return () => clearTimeout(timer);
  });

  // When the columns change, the tiles move to other rows and are made anew: note whether focus was in the grid
  // before the page changes, and if it fell to the page, give it back to the focused tile.
  let hadFocus = false;
  $effect.pre(() => {
    cols;
    hadFocus = Boolean(grid?.contains(document.activeElement));
  });
  $effect(() => {
    cols;
    untrack(() => {
      if (!hadFocus || !grid || grid.contains(document.activeElement)) return;
      if (document.activeElement && document.activeElement !== document.body) return;
      hadFocus = false;
      grid.querySelector(`[data-index="${focusAt}"]`)?.focus({ preventScroll: true });
    });
  });

  $effect(() => {
    const at = focusId == null ? -1 : photos.findIndex((photo) => photo.id === focusId);
    if (at >= 0) untrack(() => (focusAt = at));
  });

  // The next page, when the end of the loaded rows is near.
  $effect(() => {
    if (hasMore && !loading && !error && range.end >= rowCount - 2) untrack(() => onMore());
  });

  const title = (photo) => photo.species.common || photo.species.name;

  function open(event) {
    rememberRect(event.currentTarget.querySelector('img'));
    noteViewerOpen();
  }

  async function onKey(event, at) {
    const to = {
      ArrowRight: at + 1, ArrowLeft: at - 1, ArrowDown: at + cols, ArrowUp: at - cols, Home: 0, End: photos.length - 1,
    }[event.key];
    if (to === undefined || to < 0 || to >= photos.length) {
      if (to !== undefined) event.preventDefault();
      return;
    }
    event.preventDefault();
    focusAt = to;
    await tick();
    const link = grid?.querySelector(`[data-index="${to}"]`);
    link?.focus();
    link?.scrollIntoView?.({ block: 'nearest' });
  }
</script>

{#if heicBarOn() && photos.some(isHeic)}<HeicBar />{/if}
<div class="fgrid" role="list" aria-label="Photos" bind:this={grid} style="height: {rowCount * (tile + GAP)}px">
  {#each rows as r (r)}
    <div class="frow-t" class:in={entering} style="top: {r * (tile + GAP)}px; height: {tile}px; --i: {Math.min(Math.max(0, r - range.start), 12)}">
      {#each photos.slice(r * cols, r * cols + cols) as photo, c (photo.id)}
        {@const i = r * cols + c}
        <div role="listitem" aria-setsize={hasMore ? -1 : photos.length} aria-posinset={i + 1} style="width: {tile}px">
          <a class="ftile" class:waiting={!ready.has(photo.id) && !photo.unreadable} href={photoHref(photo.id, query)} data-photo-id={photo.id} data-index={i} tabindex={i === focusAt ? 0 : -1}
             aria-label="Open {title(photo)}, {photo.fileName}{photo.unreadable ? `, ${CANT_SHOW.toLowerCase()}` : ''}" onclick={open} onkeydown={(e) => onKey(e, i)} onfocus={() => (focusAt = i)}>
            {#if photo.unreadable}
              <span class="cant" aria-hidden="true">{CANT_SHOW}</span>
            {:else}
              <img src={photoThumb(photo)} alt="" loading="lazy" decoding="async" use:thumbRetry={() => ready.add(photo.id)} />
            {/if}
            {#if typeBadge(photo)}<span class="tb"><span class={typeBadge(photo).toLowerCase()}>{typeBadge(photo)}</span></span>{/if}
            <span class="fcap" aria-hidden="true">{title(photo)}</span>
          </a>
        </div>
      {/each}
    </div>
  {/each}
</div>
{#if error}
  <div class="fmore"><p class="problem" role="alert">{error}</p><button class="btn ghost" type="button" onclick={() => onRetry()}>Try again</button></div>
{:else if loading}
  <p class="fmore muted" role="status">Loading more photos…</p>
{/if}

<style>
  .fgrid { position: relative; }
  .frow-t { position: absolute; left: 0; right: 0; display: flex; gap: 10px; }
  .frow-t.in { animation: rise .55s cubic-bezier(.2,.75,.2,1) backwards; animation-delay: calc(var(--i, 0) * 45ms); }
  .ftile { position: relative; display: block; width: 100%; height: 100%; border-radius: 14px; overflow: hidden; background: var(--surface-2); }
  .ftile img { width: 100%; height: 100%; object-fit: cover; display: block; transition: transform .4s cubic-bezier(.2,.8,.2,1); }
  .ftile:hover img, .ftile:focus-visible img { transform: scale(1.04); }
  /* .ftile.waiting shimmers as the gallery's tiles do (atlas.css) */
  .fcap { position: absolute; left: 0; right: 0; bottom: 0; padding: 18px 10px 8px; font-size: 12px; font-weight: 600; color: #fff;
    background: linear-gradient(transparent, rgba(0,0,0,.6)); opacity: 0; transition: opacity .2s; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .ftile:hover .fcap, .ftile:focus-visible .fcap { opacity: 1; }
  .ftile:focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }
  .fmore { display: flex; gap: 12px; align-items: center; justify-content: center; margin: 18px 0 0; }
</style>
