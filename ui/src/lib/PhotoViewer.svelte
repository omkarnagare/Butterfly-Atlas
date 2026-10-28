<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import ZoomPan from './ZoomPan.svelte';
  import { fileLines, viewerProps } from './raw.js';
  import { status as appStatus } from './status.svelte.js';
  import { fileTypeLabel } from './filter.js';
  import { deletePhotos, movePhotos, displayUrl, photoThumb, placePhotos, setCover, setPhotoTags, unplacePhotos } from './api.js';
  import SpeciesPicker, { addedButFailed } from './SpeciesPicker.svelte';
  import PlacePicker from './PlacePicker.svelte';
  import { growFrom, takeRect } from './flip.js';
  import { isTopModal, modal } from './modal.js';
  import { reducedMotion } from './motion.js';
  import { forgetViewerOpen, showPhoto } from './viewer.js';
  import { labeller } from './tags.js';
  import { atPlace, nameAt } from './places.js';
  import { showToast } from './toast.svelte.js';
  import { STEP } from './zoom.js';
  import { thumbRetry } from './thumbRetry.js';
  import { ABOUT_THIS_PHOTO, CLOSE, NEXT_PHOTO, PREVIOUS_PHOTO, REMOVE, UNKNOWN_PLACE, viewerLabel } from './messages.js';

  /**
   * Full-screen photo viewer over the species page (prototype openLB), always dark. It opens by growing
   * out of the clicked thumbnail; ← → keys, swipes and the strip move between photos; + − 0 zoom. The
   * side panel edits the photo's tags, shows where it was taken (the place, and whether it came from the photo's
   * GPS or was set by the user) with Change (the place picker) and Remove, sets it as the species cover, moves it to
   * another species or deletes it. After a change the page reads the photos again (`onChanged`).
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
  let busy = $state(false);
  let error = $state('');
  let moving = $state(false);
  let placing = $state(false);
  let changeButton = $state(null);
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

  async function act(change, message) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      await change();
      await onChanged();
      if (message) showToast(message);
    } catch (e) {
      error = e.message;
    } finally {
      busy = false;
    }
  }

  const toggleTag = (key) => act(() => setPhotoTags(photo.id, photo.tags.includes(key) ? photo.tags.filter((k) => k !== key) : [...photo.tags, key]));
  const makeCover = () => act(() => setCover(taxon.id, photo.id), `This photo is now the cover of ${title}.`);
  const automaticCover = () => act(() => setCover(taxon.id, null), `${title} picks its cover by itself again.`);

  /** Moving or deleting takes the photo out of this species: show the next one (or close), then reload. */
  /** `failed(message)` words a failure (by default the message as it is). */
  async function takeOut(change, message, failed = (m) => m) {
    if (busy) return;
    const gone = photo;
    const next = list.length > 1 ? list[(index + 1) % list.length] : null;
    busy = true;
    error = '';
    try {
      await change(gone.id);
    } catch (e) {
      error = failed(e.message);
      busy = false;
      moving = false; // so the alert below is live, not behind the picker
      return;
    }
    busy = false;
    moving = false;
    showToast(message);
    // The Delete button's page is going away: keep keyboard focus inside the viewer.
    closeButton?.focus();
    if (next) showAt(next);
    else onClose();
    await onChanged();
  }

  /** The picker closes either way: a refusal shows in the panel, as for Move. */
  async function setPlace(place) {
    await act(() => placePhotos([photo.id], place.id));
    placing = false;
  }

  async function removePlace() {
    await act(() => unplacePhotos([photo.id]));
    // The Remove button is gone once the photo has no place: keep keyboard focus beside it.
    if (!photo?.place) changeButton?.focus();
  }

  const remove = () => takeOut((photoId) => deletePhotos([photoId]), 'Photo moved to Recently deleted.');
  // A species just added from the picker (`how.added`) exists even if the move fails: the message says so.
  const moveTo = (hit, how) => takeOut((photoId) => movePhotos([photoId], hit.id), `Photo moved to ${hit.common || hit.name}.`,
    (m) => (how?.added ? addedButFailed(hit, m) : m));
</script>

<svelte:window onkeydown={onKey} />

<div class="ov lb" bind:this={root} role="dialog" aria-modal="true" aria-label={viewerLabel(title)}
     use:modal={{ returnFocus: () => document.querySelector(`.gal [data-photo-id="${shownId}"]`) }}>
  <div class="lb-top">
    <div class="lb-name"><span>{title}</span>{#if taxon.common}<i class="bn">{taxon.name}</i>{/if}</div>
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
      <h3>Tags</h3>
      <div class="chips" role="group" aria-label="Tags on this photo">
        {#each tags as tag (tag.key)}
          <button class="chip" type="button" class:on={photo.tags.includes(tag.key)} aria-pressed={photo.tags.includes(tag.key)}
                  aria-disabled={busy} onclick={() => toggleTag(tag.key)}>{tag.label}</button>
        {/each}
      </div>
      {#if !photo.tags.length}<p class="muted">Untagged</p>{/if}
      <h3>Place</h3>
      {#if photo.place}
        <p class="place">{photo.place.path.join(', ')} <span class="muted">{photo.place.source === 'user' ? 'set by you' : 'from GPS'}</span></p>
      {:else}
        <p class="muted">{UNKNOWN_PLACE}</p>
      {/if}
      <div class="lb-place">
        <button class="btn ghost" type="button" bind:this={changeButton} aria-disabled={busy} onclick={() => { if (!busy) placing = true; }}>Change</button>
        {#if photo.place}<button class="btn ghost" type="button" aria-disabled={busy} onclick={removePlace}>{REMOVE}</button>{/if}
      </div>
      <h3>File</h3>
      <p class="fp">{photo.relPath}</p>
      {#each fileLines(photo) as line (line)}<p class="muted">{line}</p>{/each}
      <div class="lb-acts">
        {#if photo.isCover}<span class="tg">Species cover</span>{/if}
        <button class="btn ghost" type="button" aria-disabled={busy} onclick={photo.isCover ? automaticCover : makeCover}>
          {photo.isCover ? 'Choose the cover automatically' : 'Set as cover'}
        </button>
        <button class="btn ghost" type="button" aria-disabled={busy} onclick={() => { if (!busy) moving = true; }}>Move…</button>
        <button class="btn ghost" type="button" aria-disabled={busy} onclick={remove}>Delete</button>
      </div>
      {#if error}<p class="problem" role="alert">{error}</p>{/if}
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
  {#if moving}
    <SpeciesPicker title="Move this photo to another species" exclude={taxon.id} onPick={moveTo} onClose={() => (moving = false)} />
  {/if}
  {#if placing}
    <PlacePicker count={1} onChoose={setPlace} onPlacesChanged={onChanged} returnFocus={() => changeButton} onClose={() => (placing = false)} />
  {/if}
</div>
