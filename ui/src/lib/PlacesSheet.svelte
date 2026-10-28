<script>
  import { onMount, tick } from 'svelte';
  import { deletePlace, getMyPlaces, renamePlace } from './api.js';
  import Dialog from './Dialog.svelte';
  import { placeWhere } from './places.js';
  import { isTopModal, modal } from './modal.js';
  import { showToast } from './toast.svelte.js';

  /**
   * "Your places" (contract §3, like the Tags sheet): the districts the user added, with where they are and how
   * many photos use them. Rename in place; delete, which first says how many photos will have an unknown place
   * (a place no photo uses goes at once). Places that come with the atlas are not listed: they can't be changed.
   * Focus goes back to `returnFocus()` (the Places button by default) when it closes.
   */
  let { onChanged = () => {}, onClose, returnFocus = () => document.querySelector('[data-places]') } = $props();

  const uid = $props.id();
  let root = $state(null);
  let doneButton = $state(null);
  let places = $state(null);
  let renaming = $state(null);
  let newName = $state('');
  let confirming = $state(null);
  let error = $state('');
  let errorAt = $state('');
  let busy = $state(false);

  const photos = (n) => (n === 1 ? '1 photo' : `${n} photos`);

  onMount(async () => {
    try {
      places = await getMyPlaces();
    } catch (e) {
      error = e.message;
    }
  });

  /** Saves a change, then reads the list again; a refusal is shown at `at` (a row, or the sheet). */
  async function change(work, at = '') {
    if (busy) return false;
    busy = true;
    error = '';
    errorAt = '';
    try {
      await work();
    } catch (e) {
      error = e.message;
      errorAt = at;
      busy = false;
      return false;
    }
    try {
      places = await getMyPlaces();
    } catch {
      // The change went through; the list stays as it was until the next load.
    }
    busy = false;
    onChanged();
    return true;
  }

  async function startRename(place) {
    if (busy) return;
    renaming = place.id;
    newName = place.name;
    error = '';
    errorAt = '';
    await tick();
    const field = document.getElementById(`${uid}-rename`);
    field?.focus();
    field?.select();
  }

  async function stopRename(placeId) {
    renaming = null;
    error = '';
    errorAt = '';
    await tick();
    document.querySelector(`[data-rename="${placeId}"]`)?.focus();
  }

  async function saveRename(place) {
    const name = newName.trim();
    if (!name || name === place.name) {
      stopRename(place.id);
      return;
    }
    if (await change(() => renamePlace(place.id, name), place.id)) stopRename(place.id);
  }

  function renameKey(event, place) {
    if (event.key === 'Escape') {
      event.preventDefault();
      event.stopPropagation();
      stopRename(place.id);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      saveRename(place);
    }
  }

  async function remove(place) {
    let cleared = 0;
    const ok = await change(async () => {
      cleared = await deletePlace(place.id);
    });
    confirming = null;
    await tick();
    doneButton?.focus();
    if (!ok) return;
    const unknown = cleared === 1 ? ' 1 photo now has an unknown place.' : cleared ? ` ${cleared} photos now have an unknown place.` : '';
    showToast(`“${place.name}” deleted.${unknown}`);
  }

  function askDelete(place) {
    if (busy) return;
    if (place.photos) confirming = place;
    else remove(place);
  }

  function onKey(event) {
    if (event.key === 'Escape' && isTopModal(root)) {
      event.preventDefault();
      onClose();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov sheet-bg" bind:this={root} use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="{uid}-title">
    <div class="sh-h">
      <span class="eyebrow">Your atlas</span>
      <h2 id="{uid}-title">Your places</h2>
      <span class="muted">Districts you added. Renaming one changes it on every photo that uses it.</span>
    </div>
    <div class="sh-b">
      {#if error && !errorAt}<p class="problem" role="alert">{error}</p>{/if}
      {#if places === null && !error}
        <p class="muted" role="status">Loading places…</p>
      {:else if places && !places.length}
        <p class="muted">No places of your own yet. Add one with “Add … as a new place” when you choose a place.</p>
      {:else if places}
        <ul class="tag-list">
          {#each places as place (place.id)}
            <li class="tag-row">
              {#if renaming === place.id}
                <input class="field" id="{uid}-rename" bind:value={newName} maxlength="80" aria-label="New name for {place.name}"
                       aria-invalid={error && errorAt === place.id ? 'true' : undefined}
                       aria-describedby={error && errorAt === place.id ? `${uid}-err` : undefined}
                       oninput={() => (error = '')} onkeydown={(e) => renameKey(e, place)} />
                <button class="btn" type="button" aria-disabled={busy} onclick={() => saveRename(place)}>Save</button>
                <button class="btn ghost" type="button" onclick={() => stopRename(place.id)}>Cancel</button>
                {#if error && errorAt === place.id}<p class="problem" id="{uid}-err" role="alert" style="flex-basis: 100%">{error}</p>{/if}
              {:else}
                <span class="tag-name">{place.name} <span class="muted pl-in">{placeWhere(place)}</span></span>
                <span class="tag-n">{place.photos ? photos(place.photos) : 'No photos'}</span>
                <button class="btn ghost" type="button" aria-label="Rename {place.name}" data-rename={place.id} aria-disabled={busy} onclick={() => startRename(place)}>Rename</button>
                <button class="btn ghost" type="button" aria-label="Delete {place.name}" aria-disabled={busy} onclick={() => askDelete(place)}>Delete</button>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}
    </div>
    <div class="sh-f"><button class="btn" type="button" bind:this={doneButton} onclick={onClose}>Done</button></div>
  </div>
  {#if confirming}
    <Dialog title="Delete “{confirming.name}”?" onClose={() => (confirming = null)}>
      <p>{photos(confirming.photos)} will have an unknown place.</p>
      {#snippet actions()}
        <button class="btn ghost" type="button" data-autofocus onclick={() => (confirming = null)}>Cancel</button>
        <button class="btn" type="button" aria-disabled={busy} onclick={() => { if (!busy) remove(confirming); }}>Delete place</button>
      {/snippet}
    </Dialog>
  {/if}
</div>
