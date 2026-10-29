<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import ZoomPan from './ZoomPan.svelte';
  import { viewerProps } from './raw.js';
  import { status as appStatus } from './status.svelte.js';
  import { fileTypeLabel } from './filter.js';
  import { displayUrl, photoThumb } from './api.js';
  import PhotoEdits from './PhotoEdits.svelte';
  import { growFrom, takeRect } from './flip.js';
  import { isTopModal, modal } from './modal.js';
  import { reducedMotion } from './motion.js';
  import { forgetViewerOpen, showPhoto, viewerOpenedFrom } from './viewer.js';
  import ViewerTitle from './ViewerTitle.svelte';
  import { parseRoute } from './route.js';
  import { labeller } from './tags.js';
  import { atPlace, nameAt } from './places.js';
  import { STEP } from './zoom.js';
  import { thumbRetry } from './thumbRetry.js';
  import { ABOUT_THIS_PHOTO, CLOSE, NEXT_PHOTO, PREVIOUS_PHOTO, UNKNOWN_PLACE, viewerLabel } from './messages.js';

  /**
   * Full-screen photo viewer over the species page (prototype openLB), always dark. It opens by growing
   * out of the clicked thumbnail; ← → keys, swipes and the strip move between photos; + − 0 zoom. The
   * side panel (lib/PhotoEdits) edits the photo's tags and place, sets it as the species cover, moves it to another
   * species or deletes it. After a change the page reads the photos again (`onChanged`).
   *
   * `tag` and `place` are the gallery filters from the address (?tag=, ?place=<id|unknown>): paging stays within the
   * photos matching both, and `type` (?type=, a file type, Phase 7a) to photos of that type. A filter the photo no
   * longer matches (a stale link, a tag or place just changed) is dropped.
   */
  let { taxon, photos, photoId, tag = null, place = null, type = null, tags = [], onChanged = async () => {}, onClose } = $props();

  let root = $state(null);
  let closeButton = $state(null);
  let strip = $state(null);
  let zoompan = $state(null);
  let shownId = untrack(() => photoId);

  let current = $derived(photos.find((p) => p.id === photoId));
  let filtering = $derived(tag !== null && (current?.tags.includes(tag) ?? false));
  let placed = $derived(place !== null && current !== undefined && atPlace(current, place));
  let typed = $derived(type !== null && current?.fileType === type);
  let list = $derived(photos.filter((p) => (!filtering || p.tags.includes(tag)) && (!placed || atPlace(p, place)) && (!typed || p.fileType === type)));
  let filters = $derived([
    filtering ? label(tag) : null, placed ? (place === 'unknown' ? UNKNOWN_PLACE : nameAt(current.place, place)) : null, typed ? fileTypeLabel(type) : null,
  ].filter(Boolean).map((name) => ` · ${name}`).join(''));
  const showAt = (next) => showPhoto(taxon.id, next.id, filtering ? tag : null, placed ? place : null, typed ? type : null);
  let index = $derived(Math.max(0, list.findIndex((p) => p.id === photoId)));
  let photo = $derived(list[index]);
  let label = $derived(labeller(tags));
  let title = $derived(taxon.common || taxon.name);
  // Opened from another page in the app, the title leads to the species page (from the species' own page, or from the
  // viewer's address, closing already does).
  const from = viewerOpenedFrom();
  let linked = $derived.by(() => {
    if (from === null) return false;
    const route = parseRoute(from);
    return !(route.screen === 'species' && route.id === taxon.id);
  });

  // Left by Back rather than closed: the next one opened from its address closes the same way.
  onDestroy(forgetViewerOpen);

  onMount(() => {
    closeButton?.focus();
    const from = takeRect();
    // The photo gets its fitted size once the stage has been measured, one update later.
    tick().then(() => growFrom(root?.querySelector('.zp-photo'), from));
  });

  // Next/previous slides in from the side it came from; the strip keeps the current photo in view.
  let previous = untrack(() => index);
  $effect(() => {
    const now = index;
    if (photo) shownId = photo.id;
    const step = now === previous ? 0 : now === (previous + 1) % list.length ? 1 : -1;
    previous = now;
    tick().then(() => {
      strip?.querySelector('[aria-current="true"]')?.scrollIntoView?.({ inline: 'center', block: 'nearest', behavior: reducedMotion() ? 'auto' : 'smooth' });
      const box = root?.querySelector('.zp-photo');
      if (step && !reducedMotion() && typeof box?.animate === 'function') {
        box.animate([{ opacity: 0, translate: `${step * 60}px 0` }, { opacity: 1, translate: '0 0' }], { duration: 320, easing: 'cubic-bezier(.2,.8,.2,1)' });
      }
    });
  });

  function go(step) {
    if (list.length < 2) return;
    showAt(list[(index + step + list.length) % list.length]);
  }

  function onKey(event) {
    if (!isTopModal(root) || event.isComposing || event.metaKey || event.ctrlKey || event.altKey) return;
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(event.target?.tagName ?? '')) return;
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

  /** Moved or deleted (lib/PhotoEdits): show the next photo (or close), then reload. */
  async function leave() {
    const next = list.length > 1 ? list[(index + 1) % list.length] : null;
    // The pressed button's page is going away: keep keyboard focus inside the viewer.
    closeButton?.focus();
    if (next) showAt(next);
    else onClose();
    await onChanged();
  }

</script>

<svelte:window onkeydown={onKey} />

<div class="ov lb" bind:this={root} role="dialog" aria-modal="true" aria-label={viewerLabel(title)}
     use:modal={{ returnFocus: () => document.querySelector(`.gal [data-photo-id="${shownId}"]`) }}>
  <div class="lb-top">
    <ViewerTitle taxonId={taxon.id} name={taxon.name} common={taxon.common} {linked} />
    <span class="c" aria-live="polite">{index + 1} / {list.length}{filters}</span>
    <button class="lb-x" type="button" bind:this={closeButton} aria-label={CLOSE} onclick={onClose}>×</button>
  </div>
  {#if photo}
    <div class="lb-stage">
      {#if list.length > 1}<button class="lb-nav p" type="button" aria-label={PREVIOUS_PHOTO} onclick={() => go(-1)}>‹</button>{/if}
      {#key photo.id}
        <ZoomPan bind:this={zoompan} src={displayUrl(photo.id)} placeholder={photoThumb(photo)} width={photo.width} height={photo.height}
                 alt="{title}, {photo.fileName}" onSwipe={go} {...viewerProps(photo, appStatus.heic)} />
      {/key}
      {#if list.length > 1}<button class="lb-nav n" type="button" aria-label={NEXT_PHOTO} onclick={() => go(1)}>›</button>{/if}
    </div>
    <aside class="lb-side" aria-label={ABOUT_THIS_PHOTO}>
      <PhotoEdits {photo} species={taxon} {tags} {onChanged} onLeft={leave} />
      <p class="muted keys">← → photos · + − zoom · 0 fit · Esc close</p>
    </aside>
    <div class="lb-strip" bind:this={strip}>
      {#each list as p, k (p.id)}
        <button type="button" class:on={k === index} aria-current={k === index ? 'true' : undefined}
                aria-label="Photo {k + 1} of {list.length}" onclick={() => showAt(p)}>
          <img src={photoThumb(p)} alt="" loading="lazy" use:thumbRetry />
        </button>
      {/each}
    </div>
  {/if}
</div>
