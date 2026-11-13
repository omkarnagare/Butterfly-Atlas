<script>
  import { deleteMatching } from './api.js';
  import Dialog from './Dialog.svelte';
  import { LIBRARY_CHANGED } from './status.svelte.js';
  import { showToast } from './toast.svelte.js';

  /**
   * Explore's Delete these (2026-11-13 design, addendum §2): "Move 140 photos of 31 species to Recently deleted?", from
   * the filter's `count`. Delete sends the filter (`query`, printed) and the count confirmed; the server deletes only if
   * the matching photos are still that many, else it says so here and nothing is deleted. Done: a toast with a link to
   * Recently deleted, LIBRARY_CHANGED (the open screen reads its data again), then `onDone()`. Cancel is focused first.
   */
  let { query, count, onDone, onClose, returnFocus = undefined } = $props();

  let busy = $state(false);
  let error = $state('');

  const photos = (n) => `${n.toLocaleString()} ${n === 1 ? 'photo' : 'photos'}`;
  let title = $derived(`Move ${photos(count.photos)} of ${(count.photographedSpecies ?? count.species).toLocaleString()} species to Recently deleted?`);

  async function remove() {
    if (busy) return;
    busy = true;
    error = '';
    let deleted;
    try {
      deleted = await deleteMatching(query, count.photos);
    } catch (e) {
      error = e.message;
      busy = false;
      return;
    }
    showToast(`${photos(deleted)} moved to Recently deleted.`, { link: { href: '#/deleted', label: 'Recently deleted' } });
    window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
    busy = false;
    onDone();
  }

  function close() {
    if (!busy) onClose();
  }
</script>

<Dialog {title} onClose={close} {returnFocus}>
  <p>You can restore them from Recently deleted for 30 days.</p>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}
    <button class="btn ghost" type="button" data-autofocus aria-disabled={busy} onclick={close}>Cancel</button>
    <button class="btn" type="button" aria-disabled={busy} onclick={remove}>{busy ? 'Deleting…' : `Delete ${photos(count.photos)}`}</button>
  {/snippet}
</Dialog>
