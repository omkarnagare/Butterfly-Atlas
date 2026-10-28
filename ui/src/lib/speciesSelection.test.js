import { afterEach, expect, test, vi } from 'vitest';
import { photoCount, speciesSelection } from './speciesSelection.svelte.js';
import { deletePhotos, editTags, movePhotos, placePhotos, unplacePhotos } from './api.js';
import { anyModalOpen } from './modal.js';
import { showToast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ deletePhotos: vi.fn(), editTags: vi.fn(), movePhotos: vi.fn(), placePhotos: vi.fn(), unplacePhotos: vi.fn() }));
vi.mock('./modal.js', () => ({ anyModalOpen: vi.fn(() => false) }));
// showProblem says a refusal through showToast (its taxonomy link is checked in Toast.test.js)
vi.mock('./toast.svelte.js', () => {
  const showToast = vi.fn();
  return { showToast, showProblem: (e) => showToast(e.message) };
});
afterEach(() => {
  vi.resetAllMocks();
  document.body.innerHTML = '';
});

/** The species page's state as speciesSelection sees it: four photos, the gallery showing the first three. */
function fakePage(extra = {}) {
  const page = {
    photos: [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }], selecting: true, selected: new Set([2, 3]), choosing: null, busy: false,
    get shown() { return page.photos.slice(0, 3); },
    get chosen() { return page.shown.filter((p) => page.selected.has(p.id)); },
    refresh: vi.fn(async () => {}), ...extra,
  };
  Object.assign(page, speciesSelection(page));
  return page;
}

test('a photo is toggled in and out; Select all takes what the gallery shows; gone photos drop out', () => {
  expect([photoCount(1), photoCount(1200)]).toEqual(['1 photo', '1200 photos']);
  const page = fakePage();
  page.toggle({ id: 2 });
  page.toggle({ id: 1 });
  expect([...page.selected]).toEqual([3, 1]);
  page.selectAll();
  expect([...page.selected]).toEqual([1, 2, 3]);
  const kept = page.selected;
  page.prune();
  expect(page.selected).toBe(kept); // nothing gone: the same Set
  page.photos = [{ id: 1 }, { id: 4 }];
  page.prune();
  expect([...page.selected]).toEqual([1]);
});

test('a bulk action saves for the chosen photos, says so, stops selecting with focus on Select, then reloads', async () => {
  document.body.innerHTML = '<button data-select>Select</button>';
  const page = fakePage({ choosing: 'species' });
  movePhotos.mockResolvedValue([]);
  await page.moveSelected({ id: 'A6', name: 'Danaus genutia', common: 'Common Tiger' });
  expect(movePhotos).toHaveBeenCalledWith([2, 3], 'A6');
  expect(showToast).toHaveBeenCalledWith('2 photos moved to Common Tiger.');
  expect(page.selecting).toBe(false);
  expect(page.selected.size).toBe(0);
  expect(page.choosing).toBe(null);
  expect(page.busy).toBe(false);
  expect(page.refresh).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(document.activeElement).toBe(document.querySelector('[data-select]')));

  const tagging = fakePage({ selected: new Set([1]) });
  editTags.mockResolvedValue([]);
  await tagging.tagSelected([6], []);
  expect(editTags).toHaveBeenCalledWith([1], [6], []);
  expect(showToast).toHaveBeenCalledWith('Tags updated on 1 photo.');

  const placing = fakePage();
  placePhotos.mockResolvedValue([]);
  await placing.placeSelected({ id: 'P414', name: 'Pune' });
  expect(placePhotos).toHaveBeenCalledWith([2, 3], 'P414');
  expect(showToast).toHaveBeenCalledWith('Pune set on 2 photos.');
});

test('a refusal from a dialog is thrown to it and the selection stays; Remove place and Delete show theirs as a toast', async () => {
  const page = fakePage({ choosing: 'tags' });
  editTags.mockRejectedValue(new Error('No such tag.'));
  await expect(page.tagSelected([9], [])).rejects.toThrow('No such tag.');
  expect(page.selecting).toBe(true);
  expect(page.choosing).toBe('tags');
  expect(page.busy).toBe(false);
  expect(page.refresh).not.toHaveBeenCalled();

  unplacePhotos.mockRejectedValue(new Error('The app could not be reached.'));
  await page.unplaceSelected();
  expect(unplacePhotos).toHaveBeenCalledWith([2, 3]);
  expect(showToast).toHaveBeenCalledWith('The app could not be reached.');

  deletePhotos.mockResolvedValue(2);
  await page.deleteSelected();
  expect(deletePhotos).toHaveBeenCalledWith([2, 3]);
  expect(showToast).toHaveBeenCalledWith('2 photos moved to Recently deleted.');
  expect(page.selecting).toBe(false);

  const busy = fakePage({ busy: true });
  await busy.deleteSelected();
  expect(deletePhotos).toHaveBeenCalledTimes(1);
});

test('Escape stops selecting unless an overlay is open', () => {
  const page = fakePage();
  const escape = { key: 'Escape', preventDefault: vi.fn() };
  anyModalOpen.mockReturnValue(true);
  page.onPageKey(escape);
  expect(page.selecting).toBe(true);
  anyModalOpen.mockReturnValue(false);
  page.onPageKey({ key: 'a', preventDefault: vi.fn() });
  expect(page.selecting).toBe(true);
  page.onPageKey(escape);
  expect(escape.preventDefault).toHaveBeenCalled();
  expect(page.selecting).toBe(false);
  expect(page.selected.size).toBe(0);
});
