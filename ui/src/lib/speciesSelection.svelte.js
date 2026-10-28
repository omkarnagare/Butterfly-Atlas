import { tick } from 'svelte';
import { deletePhotos, editTags, movePhotos, placePhotos, unplacePhotos } from './api.js';
import { anyModalOpen } from './modal.js';
import { showProblem, showToast } from './toast.svelte.js';

/**
 * Selecting a species page's photos and the bulk actions on them, for screens/Species.svelte: Select, a photo at a
 * time or all shown, Escape to stop; Tag, Move, Place…, Remove place and Delete. `page` is the page's state, read
 * and written through accessors; `page.refresh` reloads its data after a change.
 */
export const photoCount = (n) => (n === 1 ? '1 photo' : `${n} photos`);

export function speciesSelection(page) {
  // Selected photos that are gone (moved, deleted, purged) drop out of the selection.
  function prune() {
    if (!page.selected.size) return;
    const live = new Set(page.photos.map((p) => p.id));
    if ([...page.selected].some((photoId) => !live.has(photoId))) page.selected = new Set([...page.selected].filter((photoId) => live.has(photoId)));
  }

  function toggle(photo) {
    const next = new Set(page.selected);
    if (next.has(photo.id)) next.delete(photo.id);
    else next.add(photo.id);
    page.selected = next;
  }

  /** Select all: what the gallery shows under its tag and place filters. */
  function selectAll() {
    page.selected = new Set(page.shown.map((p) => p.id));
  }

  function stopSelecting() {
    page.selecting = false;
    page.selected = new Set();
    page.choosing = null;
    tick().then(() => document.querySelector('[data-select]')?.focus());
  }

  /**
   * Runs a bulk action on the chosen photos. A failure is thrown to the caller (the open dialog shows it
   * and stays usable); busy is always reset.
   */
  async function onSelection(change, message) {
    if (page.busy) return;
    page.busy = true;
    try {
      await change(page.chosen.map((p) => p.id));
      showToast(message);
      stopSelecting();
    } finally {
      page.busy = false;
    }
    await page.refresh();
  }

  const tagSelected = (add, remove) =>
    onSelection((ids) => editTags(ids, add, remove), `Tags updated on ${photoCount(page.chosen.length)}.`);
  const moveSelected = (hit) =>
    onSelection((ids) => movePhotos(ids, hit.id), `${photoCount(page.chosen.length)} moved to ${hit.common || hit.name}.`);
  const placeSelected = (place) =>
    onSelection((ids) => placePhotos(ids, place.id), `${place.name} set on ${photoCount(page.chosen.length)}.`);
  const unplaceSelected = () =>
    onSelection((ids) => unplacePhotos(ids), `Place removed from ${photoCount(page.chosen.length)}.`).catch((e) => showProblem(e));
  const deleteSelected = () =>
    onSelection((ids) => deletePhotos(ids), `${photoCount(page.chosen.length)} moved to Recently deleted.`).catch((e) => showProblem(e));

  function onPageKey(event) {
    if (event.key === 'Escape' && page.selecting && !anyModalOpen()) {
      event.preventDefault();
      stopSelecting();
    }
  }

  return { prune, toggle, selectAll, stopSelecting, tagSelected, moveSelected, placeSelected, unplaceSelected, deleteSelected, onPageKey };
}
