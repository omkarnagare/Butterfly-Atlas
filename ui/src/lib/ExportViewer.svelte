<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import ZoomPan from './ZoomPan.svelte';
  import { fileLines, viewerProps } from './raw.js';
  import { status as appStatus } from './status.svelte.js';
  import { displayUrl, photoThumb } from './api.js';
  import { growFrom, takeRect } from './flip.js';
  import { isTopModal, modal } from './modal.js';
  import { reducedMotion } from './motion.js';
  import { exportPhotoHref } from './route.js';
  import { labeller } from './tags.js';
  import { forgetViewerOpen } from './viewer.js';
  import { STEP } from './zoom.js';
  import { ABOUT_THIS_PHOTO, CLOSE, NEXT_PHOTO, PREVIOUS_PHOTO, UNKNOWN_PLACE, viewerLabel } from './messages.js';

  /**
   * The photo viewer over the Export screen's Photos view (#/export/photo/<id>?<filter>, contract §4): dark, growing out
   * of the clicked tile, ← → and swipes through the photos loaded so far (the address is replaced, so Back closes it),
   * + − 0 zoom, Escape closes. It only shows: the species (with a link to its page), the tags, the place and the file;
   * changes are made on the species page. Near the end of what is loaded it asks for the next page (`onMore`), once for
   * what is loaded and never while one is `loading` or has failed; a step past the last loaded photo waits for the page
   * and moves on when it comes, and with no more pages it goes round. A failed page (`error`) shows in the viewer with
   * Try again (`onRetry`, the same page again); before the first photo it goes round only when every page is loaded.
   * `total` is the count line's photo count. On closing, focus goes to `returnFocus(id)` for the photo last shown.
   */
  let {
    photos, photoId, query, total = 0, tags = [], hasMore = false, loading = false, error = '', onMore = () => {}, onRetry = () => {}, returnFocus = () => null, onClose,
  } = $props();

  let root = $state(null);
  let closeButton = $state(null);
  let zoompan = $state(null);
  let shownId = untrack(() => photoId);

  let index = $derived(Math.max(0, photos.findIndex((p) => p.id === photoId)));
  let photo = $derived(photos[index]);
  let title = $derived(photo ? photo.species.common || photo.species.name : '');
  let label = $derived(labeller(tags));

  onDestroy(forgetViewerOpen);

  onMount(() => {
    closeButton?.focus();
    const from = takeRect();
    tick().then(() => growFrom(root?.querySelector('.zp-photo'), from));
  });

  let previous = untrack(() => index);
  $effect(() => {
    const now = index;
    shownId = photo?.id ?? shownId;
    const step = now === previous ? 0 : now > previous ? 1 : -1;
    previous = now;
    tick().then(() => {
      const box = root?.querySelector('.zp-photo');
      if (step && !reducedMotion() && typeof box?.animate === 'function') {
        box.animate([{ opacity: 0, translate: `${step * 60}px 0` }, { opacity: 1, translate: '0 0' }], { duration: 320, easing: 'cubic-bezier(.2,.8,.2,1)' });
      }
    });
  });

  // The next page before it is needed: once for each length of what is loaded (a failed page waits for Try again).
  let askedAt = -1;
  $effect(() => {
    if (!hasMore || loading || error || index < photos.length - 5 || askedAt === photos.length) return;
    askedAt = photos.length;
    untrack(() => onMore());
  });

  // A step past the last loaded photo waits for the next page and moves on when it comes.
  let waiting = $state(false);
  $effect(() => {
    if (waiting && index + 1 < photos.length) untrack(() => {
      waiting = false;
      go(1);
    });
  });

  function go(step) {
    const to = index + step;
    waiting = false;
    if (to >= photos.length && hasMore) {
      // Wait for the next page, and ask for it: the page asked for may have been dropped (the photos were loaded again
      // from the start, as many as before). The screen asks for one page at a time, so asking twice does no harm.
      waiting = true;
      if (!loading && !error) onMore();
      return;
    }
    if (to < 0 && hasMore) return; // the last photo is not loaded yet
    if (photos.length < 2) return;
    const next = photos[(to + photos.length) % photos.length];
    window.location.replace(exportPhotoHref(next.id, query));
  }

  // The pressed Try again leaves the viewer: focus goes to Close, never to the page.
  function retry() {
    onRetry();
    closeButton?.focus();
  }

  function onKey(event) {
    if (!isTopModal(root) || event.isComposing || event.metaKey || event.ctrlKey || event.altKey) return;
    const keys = {
      Escape: () => onClose(),
      ArrowRight: () => go(1),
      ArrowLeft: () => go(-1),
      '+': () => zoompan?.zoomBy(STEP),
      '=': () => zoompan?.zoomBy(STEP),
      '-': () => zoompan?.zoomBy(1 / STEP),
      0: () => zoompan?.reset(),
    };
    const run = keys[event.key];
    if (run) {
      event.preventDefault();
      run();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

<div class="ov lb" bind:this={root} role="dialog" aria-modal="true" aria-label={viewerLabel(title)}
     use:modal={{ returnFocus: () => returnFocus(shownId) }}>
  <div class="lb-top">
    <div class="lb-name"><span>{title}</span>{#if photo?.species.common}<i class="bn">{photo.species.name}</i>{/if}</div>
    <span class="c" aria-live="polite">{(index + 1).toLocaleString()} / {Math.max(total, photos.length).toLocaleString()}</span>
    <button class="lb-x" type="button" bind:this={closeButton} aria-label={CLOSE} onclick={onClose}>×</button>
  </div>
  {#if photo}
    <div class="lb-stage">
      {#if photos.length > 1}<button class="lb-nav p" type="button" aria-label={PREVIOUS_PHOTO} onclick={() => go(-1)}>‹</button>{/if}
      {#key photo.id}
        <ZoomPan bind:this={zoompan} src={displayUrl(photo.id)} placeholder={photoThumb(photo)} width={photo.width} height={photo.height}
                 alt="{title}, {photo.fileName}" onSwipe={go} {...viewerProps(photo, appStatus.heic)} />
      {/key}
      {#if photos.length > 1}<button class="lb-nav n" type="button" aria-label={NEXT_PHOTO} onclick={() => go(1)}>›</button>{/if}
      {#if error}
        <div class="lb-more"><p class="problem" role="alert">{error}</p><button class="btn ghost" type="button" onclick={retry}>Try again</button></div>
      {/if}
    </div>
    <aside class="lb-side" aria-label={ABOUT_THIS_PHOTO}>
      <h3>Species</h3>
      <p><a class="lb-link" href="#/species/{encodeURIComponent(photo.species.id)}">Open the species page</a></p>
      <h3>Tags</h3>
      {#if photo.tags.length}
        <div class="chips">{#each photo.tags as key (key)}<span class="chip">{label(key)}</span>{/each}</div>
      {:else}
        <p class="muted">Untagged</p>
      {/if}
      <h3>Place</h3>
      {#if photo.place}<p class="place">{photo.place.path.join(', ')}</p>{:else}<p class="muted">{UNKNOWN_PLACE}</p>{/if}
      <h3>File</h3>
      <p class="fp">{photo.relPath}</p>
      {#each fileLines(photo) as line (line)}<p class="muted">{line}</p>{/each}
      <p class="muted keys">← → photos · + − zoom · 0 fit · Esc close</p>
    </aside>
  {/if}
</div>

<style>
  .lb-link { color: #eaf1ed; font-weight: 600; }
  .lb-more { position: absolute; left: 50%; bottom: 16px; transform: translateX(-50%); display: flex; gap: 12px; align-items: center;
    width: max-content; max-width: calc(100% - 32px); padding: 8px; border-radius: 16px; background: rgba(5,9,8,.86); }
</style>
