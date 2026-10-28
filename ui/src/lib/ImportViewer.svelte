<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import ZoomPan from './ZoomPan.svelte';
  import { fileLines, viewerProps } from './raw.js';
  import { status as appStatus } from './status.svelte.js';
  import { heicMissing, isHeic } from './heic.js';
  import { importDisplayUrl, importPreviewUrl } from './api.js';
  import { placeLine, tileStatus } from './importView.js';
  import { burstTime, itemTime } from './bursts.js';
  import { isTopModal, modal } from './modal.js';
  import { reducedMotion } from './motion.js';
  import { STEP } from './zoom.js';
  import { forgetViewerOpen } from './viewer.js';
  import { ITEM_READ } from './states.js';
  import { ABOUT_THIS_PHOTO, CLOSE, NEXT_PHOTO, PREVIOUS_PHOTO, UNKNOWN_PLACE } from './messages.js';

  /**
   * The import's large view of one photo (#/import/<id>/photo/<itemId>, design §4.3): the source photo,
   * reusing the viewer's ZoomPan (wheel, pinch, double-click, + − 0), over the preview while it loads, sized
   * from the item's width and height before the photo arrives. ← → and
   * swipes move through the photos the grid shows (`order`); Escape or × closes it. Always dark, like the
   * photo viewer. A photo not read yet, gone from the folder, or one that can't be read, shows why instead
   * (worded from its state); the full-size photo says it is loading, and one that can no longer be opened
   * (moved or deleted since it was read) says so over its preview.
   * Its place (Phase 5c): the path, "Unknown place" for a read photo without one, and for a place that became former
   * "Chittoor (former district) — choose its new place, or file as it is.".
   * `onShow(itemId)` follows paging, so the grid keeps that tile and focus returns to it on close.
   */
  let { importId, items, taxa, order, itemId, onShow, onClose } = $props();

  let root = $state(null);
  let closeButton = $state(null);
  let zoompan = $state(null);
  let shownId = untrack(() => itemId);

  let index = $derived(Math.max(0, order.indexOf(itemId)));
  let item = $derived(items.get(itemId));
  let status = $derived(item ? tileStatus(item, taxa) : null);
  // The other half of a RAW + JPEG pair, when the pair choice left it out: its reason shows under the status (Phase 7a).
  let partnerLeft = $derived.by(() => {
    const other = item?.pairId == null ? null : items.get(item.pairId);
    return other?.outcome === 'pair_left' ? other.error : '';
  });
  let viewable = $derived(item?.state === ITEM_READ);
  // A RAW item's turn; the RAW note only for a preview that can't be read, else the import's own note (review fix U5).
  // A HEIC item's note is chosen from the decoder's state (Phase 7b audit 7).
  // Review fix U10: the HEIC half of a RAW + HEIC pair, while this computer can't show HEIC photos, shows its RAW's preview.
  let standIn = $derived.by(() => {
    const other = item?.pairId == null ? null : items.get(item.pairId);
    return isHeic(item) && heicMissing(appStatus.heic) && other?.fileType === 'raw' && other.state === ITEM_READ ? other : null;
  });
  let viewed = $derived(standIn ?? item);
  let shown = $derived(viewerProps(viewed, appStatus.heic));
  const MOVED_NOTE = "This photo can't be opened. It may have been moved or deleted from the folder since it was read; showing its preview.";
  let preview = $derived(viewed ? importPreviewUrl(importId, viewed) : null);

  onMount(() => closeButton?.focus());
  // Left by Back or a replaced address rather than closed: the next one opened from its address closes the same way.
  onDestroy(forgetViewerOpen);

  const WHY = {
    pending: "This photo hasn't been read yet.",
    missing: "This photo isn't in the folder any more.",
  };
  let why = $derived(item ? WHY[item.state] ?? (item.problem || "Butterfly Atlas couldn't read this photo.") : '');

  let previous = untrack(() => index);
  $effect(() => {
    const now = index;
    shownId = itemId;
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
    if (order.length < 2) return;
    onShow(order[(index + step + order.length) % order.length]);
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

<div class="ov lb" bind:this={root} role="dialog" aria-modal="true" aria-label="Photo: {item?.name ?? ''}"
     use:modal={{ returnFocus: () => document.querySelector(`.ig [data-item-id="${shownId}"]`) }}>
  <div class="lb-top">
    <div class="lb-name"><span>{item?.name}</span>{#if item?.folder}<span class="lb-folder">{item.folder}</span>{/if}</div>
    <span class="c" aria-live="polite">{index + 1} / {order.length}</span>
    <button class="lb-x" type="button" bind:this={closeButton} aria-label={CLOSE} onclick={onClose}>×</button>
  </div>
  {#if item}
    <div class="lb-stage">
      {#if order.length > 1}<button class="lb-nav p" type="button" aria-label={PREVIOUS_PHOTO} onclick={() => go(-1)}>‹</button>{/if}
      {#if viewable}
        {#key viewed.id}
          <ZoomPan bind:this={zoompan} src={importDisplayUrl(importId, viewed)} placeholder={preview} width={viewed.width} height={viewed.height} alt={viewed.name} onSwipe={go}
                   loadingNote="Loading the photo…"
                   {...shown} failedNote={shown.broken ? shown.failedNote : MOVED_NOTE} />
        {/key}
      {:else}
        <p class="lb-why">{why}</p>
      {/if}
      {#if order.length > 1}<button class="lb-nav n" type="button" aria-label={NEXT_PHOTO} onclick={() => go(1)}>›</button>{/if}
    </div>
    <aside class="lb-side" aria-label={ABOUT_THIS_PHOTO}>
      <h3>Status</h3>
      <p>{status.label || 'No species yet'}</p>
      {#if partnerLeft}<p class="muted">{partnerLeft}</p>{/if}
      {#if item.place}<h3>Place</h3><p>{placeLine(item)}</p>{:else if item.state === ITEM_READ}<h3>Place</h3><p class="muted">{UNKNOWN_PLACE}</p>{/if}
      {#if itemTime(item)}<h3>{item.capturedAt ? 'Taken' : 'Changed'}</h3><p>{burstTime(itemTime(item))}</p>{/if}
      <h3>File</h3>
      <p class="fp">{item.path}</p>
      {#each fileLines(item) as line (line)}<p class="muted">{line}</p>{/each}
      {#if standIn}<p class="muted">HEIC half: this computer can't show it yet.</p>{/if}
      <p class="muted keys">← → photos · + − zoom · 0 fit · Esc close</p>
    </aside>
  {/if}
</div>
