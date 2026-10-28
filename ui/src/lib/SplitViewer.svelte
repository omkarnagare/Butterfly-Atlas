<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import ZoomPan from './ZoomPan.svelte';
  import { fileLines, viewerProps } from './raw.js';
  import { status as appStatus } from './status.svelte.js';
  import { displayUrl, photoThumb } from './api.js';
  import { growFrom, takeRect } from './flip.js';
  import { isTopModal, modal } from './modal.js';
  import { reducedMotion } from './motion.js';
  import { splitPhotoHref } from './route.js';
  import { labeller } from './tags.js';
  import { forgetViewerOpen } from './viewer.js';
  import { STEP } from './zoom.js';
  import { ABOUT_THIS_PHOTO, CLOSE, NEXT_PHOTO, PREVIOUS_PHOTO, UNKNOWN_PLACE, viewerLabel } from './messages.js';

  /**
   * A photo still to sort, large, over the split screen (#/split/<id>/photo/<photoId>, Phase 5b contract §6): dark,
   * growing out of its tile; ← → and swipes through the photos still to sort (the address is replaced, so Back closes
   * it), + − 0 zoom, Escape closes. The side shows its tags, place and file, and the targets (keys 1–9) to sort this
   * photo to (`onSort(photo, target)`, aria-disabled while `busy`); `error` is a refusal. On closing, focus goes to
   * `returnFocus(id)` for the photo last shown.
   */
  let { split, targets, photos, photoId, tags = [], busy = false, error = '', onSort, returnFocus = () => null, onClose } = $props();

  let root = $state(null);
  let closeButton = $state(null);
  let zoompan = $state(null);
  let shownId = untrack(() => photoId);

  let index = $derived(Math.max(0, photos.findIndex((p) => p.id === photoId)));
  let photo = $derived(photos[index]);
  let label = $derived(labeller(tags));
  let title = $derived(split.common || split.name);

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

  function go(step) {
    if (photos.length < 2) return;
    const next = photos[(index + step + photos.length) % photos.length];
    window.location.replace(splitPhotoHref(split.id, next.id));
  }

  function sort(target) {
    if (!busy && photo) onSort(photo, target);
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
    // A held number key sorts once, never photo after photo (arrows may repeat to page through).
    const n = /^[1-9]$/.test(event.key) && !event.repeat ? Number(event.key) : 0;
    if (event.repeat && /^[1-9]$/.test(event.key)) {
      event.preventDefault();
      return;
    }
    const run = keys[event.key] ?? (n && n <= targets.length ? () => sort(targets[n - 1]) : null);
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
    <div class="lb-name">{#if split.common}<span>{split.common}</span><i class="bn">{split.name}</i>{:else}<span class="bn">{split.name}</span>{/if}</div>
    <span class="c">{(index + 1).toLocaleString()} / {photos.length.toLocaleString()}</span>
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
    </div>
    <aside class="lb-side" aria-label={ABOUT_THIS_PHOTO}>
      <h3>Sort this photo to</h3>
      <div class="sv-targets" role="group" aria-label="Sort this photo to">
        {#each targets as target, k (target.id)}
          <button class="btn ghost" type="button" aria-disabled={busy} aria-keyshortcuts={k < 9 ? String(k + 1) : undefined} onclick={() => sort(target)}>
            {#if k < 9}<kbd>{k + 1}</kbd>{/if}<i class="bn">{target.name}</i>{#if target.common}<span>{target.common}</span>{/if}
          </button>
        {/each}
      </div>
      {#if error}<p class="problem" role="alert">{error}</p>{/if}
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
      <p class="muted keys">1–{Math.min(9, targets.length)} sort · ← → photos · + − zoom · Esc close</p>
    </aside>
  {/if}
</div>

<style>
  .sv-targets { display: grid; gap: 6px; }
  .sv-targets .btn { justify-content: flex-start; flex-wrap: wrap; text-align: left; background: rgba(255,255,255,.06); color: #eaf1ed; border-color: rgba(255,255,255,.16); }
  .sv-targets .btn span { font-weight: 400; font-size: 13px; opacity: .8; }
  .sv-targets kbd { background: transparent; color: inherit; border-color: rgba(255,255,255,.3); }
</style>
