<script>
  import { fileLines } from './raw.js';
  import { deletePhotos, movePhotos, placePhotos, setCover, setPhotoTags, unplacePhotos } from './api.js';
  import SpeciesPicker, { addedButFailed } from './SpeciesPicker.svelte';
  import PlacePicker from './PlacePicker.svelte';
  import { showToast } from './toast.svelte.js';
  import { REMOVE, UNKNOWN_PLACE } from './messages.js';

  /**
   * A photo viewer's side panel for editing one photo (moved out of PhotoViewer for Explore's filtered photos,
   * filters-in-Explore design §6): its tags, where it was taken (the place, and whether it came from the photo's GPS
   * or was set by the user) with Change (the place picker) and Remove, its file, and setting it as the cover of its
   * `species`, moving it to another species or deleting it. After a change the viewer reads the photos again
   * (`onChanged`); moving or deleting takes the photo out, and `onLeft()` lets the viewer move on before it does.
   * A refusal shows in the panel's alert.
   */
  let { photo, species, tags = [], onChanged = async () => {}, onLeft } = $props();

  let busy = $state(false);
  let error = $state('');
  let moving = $state(false);
  let placing = $state(false);
  let changeButton = $state(null);
  let title = $derived(species.common || species.name);

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
  const makeCover = () => act(() => setCover(species.id, photo.id), `This photo is now the cover of ${title}.`);
  const automaticCover = () => act(() => setCover(species.id, null), `${title} picks its cover by itself again.`);

  /** Moving or deleting takes the photo out: the viewer moves on (`onLeft`). `failed(message)` words a failure. */
  async function takeOut(change, message, failed = (m) => m) {
    if (busy) return;
    const gone = photo;
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
    await onLeft();
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

{#if moving}
  <SpeciesPicker title="Move this photo to another species" exclude={species.id} onPick={moveTo} onClose={() => (moving = false)} />
{/if}
{#if placing}
  <PlacePicker count={1} onChoose={setPlace} onPlacesChanged={onChanged} returnFocus={() => changeButton} onClose={() => (placing = false)} />
{/if}
