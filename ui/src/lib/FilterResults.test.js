import { fireEvent, render, screen } from '@testing-library/svelte';
import { createRawSnippet } from 'svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import FilterResults from './FilterResults.svelte';
import { deletePhotos, filterCount, filterPhotos, filterPlaces, filterSpecies, getTags, setPhotoTags } from './api.js';
import { showToast } from './toast.svelte.js';
import { EMPTY } from './filter.js';

vi.mock('./api.js', () => ({
  filterCount: vi.fn(), filterSpecies: vi.fn(), filterPhotos: vi.fn(), filterPlaces: vi.fn(), getTags: vi.fn(), search: vi.fn(), searchPlaces: vi.fn(),
  setPhotoTags: vi.fn(), deletePhotos: vi.fn(), movePhotos: vi.fn(), setCover: vi.fn(), placePhotos: vi.fn(), unplacePhotos: vi.fn(),
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
  displayUrl: (id) => `./api/photos/${id}/display`,
}));

vi.mock('./toast.svelte.js', () => ({ showToast: vi.fn() }));

const count = (query) => ({ photos: 2, species: 1, photographedSpecies: 1, bytes: 1000, manualSpecies: false, filter: { ...EMPTY, query } });
const photo = (id) => ({
  id, taxonId: 'A1', fileName: `Danaus_${id}.jpg`, relPath: `images/x/Danaus_${id}.jpg`, tags: [], width: 4, height: 3, isCover: false,
  place: null, species: { id: 'A1', name: 'Danaus species1', common: 'Tiger 1' },
});

beforeEach(() => {
  vi.resetAllMocks();
  getTags.mockResolvedValue([]);
  filterCount.mockImplementation(async (query) => count(query));
  filterSpecies.mockResolvedValue([]);
  filterPlaces.mockResolvedValue([]);
  filterPhotos.mockResolvedValue({ photos: [photo(1), photo(2)], next: null });
  window.location.hash = '#/somewhere';
});

const resultsHref = (query, view) => `#/here?${query}${view !== 'species' ? `&view=${view}` : ''}`;
const photoHref = (id, query) => `#/here/photo/${id}?${query}`;

test('the screen writes the addresses: a view change, the photo tiles, and the actions at the right of the view bar', async () => {
  const actions = createRawSnippet(() => ({ render: () => '<a class="btn" href="#/elsewhere">Export these…</a>' }));
  const { container } = render(FilterResults, { query: 'tags=female', view: 'photos', delay: 0, resultsHref, photoHref, actions });

  expect(container.querySelector('.ex-bar').textContent).toContain('Export these…');
  await vi.waitFor(() => expect(container.querySelectorAll('.fgrid a')).toHaveLength(2));
  expect(container.querySelector('.fgrid a').getAttribute('href')).toBe('#/here/photo/1?tags=female');

  await fireEvent.click(screen.getByRole('button', { name: 'Places' }));
  expect(window.location.hash).toBe('#/here?tags=female&view=places');
});

test('a change of the filter is a new address the screen writes, and the filter is passed back printed', async () => {
  let printed = '';
  const { container } = render(FilterResults, {
    query: 'tags=female', view: 'species', delay: 0, resultsHref, photoHref,
    get printed() { return printed; },
    set printed(value) { printed = value; },
  });
  await vi.waitFor(() => expect(printed).toBe('tags=female'));

  await fireEvent.click(screen.getByRole('button', { name: 'Not yet photographed' }));

  expect(window.location.hash).toBe('#/here?status=not'); // the preset keeps taxa, places, source and file types
  expect(container.querySelector('.ex-filter')).toBeTruthy();
});

const female = { id: 2, key: 'female', label: 'female', source: 'builtin', photoCount: 3 };

function openEditable(photoId = 2) {
  getTags.mockResolvedValue([female]);
  filterPhotos.mockResolvedValue({ photos: [photo(1), { ...photo(2), tags: ['female'] }, photo(3)], next: null });
  window.location.hash = `#/here/photo/${photoId}?tags=female`;
  return render(FilterResults, { query: 'tags=female', view: 'photos', photoId, delay: 0, resultsHref, photoHref, editable: true });
}

test('Explore\'s viewer edits: an edit the photo still matches keeps it, the results and count are read again', async () => {
  setPhotoTags.mockResolvedValue({});
  openEditable();
  const tag = await screen.findByRole('button', { name: 'female' });
  const asked = filterPhotos.mock.calls.length;

  await fireEvent.click(tag);

  await vi.waitFor(() => expect(filterPhotos.mock.calls.length).toBeGreaterThan(asked));
  expect(window.location.hash).toBe('#/here/photo/2?tags=female');
  expect(showToast).not.toHaveBeenCalled();
});

test('an edit that takes the photo out of the filter moves on to the next one, and says so', async () => {
  setPhotoTags.mockResolvedValue({});
  openEditable();
  const tag = await screen.findByRole('button', { name: 'female' });
  filterPhotos.mockResolvedValue({ photos: [photo(1), photo(3)], next: null });

  await fireEvent.click(tag);

  await vi.waitFor(() => expect(window.location.hash).toBe('#/here/photo/3?tags=female'));
  expect(showToast).toHaveBeenCalledWith('This photo no longer matches the filter.');
  expect(screen.getByRole('dialog')).toBeTruthy();
});

test('deleting moves on without the "no longer matches" line: after the last photo, to the one before it', async () => {
  deletePhotos.mockResolvedValue({});
  openEditable(3);
  await screen.findByRole('button', { name: 'Delete' });
  filterPhotos.mockResolvedValue({ photos: [photo(1), { ...photo(2), tags: ['female'] }], next: null });

  await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

  // the photo after the last is the one before it
  await vi.waitFor(() => expect(window.location.hash).toBe('#/here/photo/2?tags=female'));
  expect(showToast).toHaveBeenCalledWith('Photo moved to Recently deleted.');
  expect(showToast).not.toHaveBeenCalledWith('This photo no longer matches the filter.');
});

test('the results viewer is read-only unless the screen makes it editable', async () => {
  filterPhotos.mockResolvedValue({ photos: [photo(1)], next: null });
  window.location.hash = '#/here/photo/1?tags=female';
  render(FilterResults, { query: 'tags=female', view: 'photos', photoId: 1, delay: 0, resultsHref, photoHref });

  await screen.findByRole('dialog');
  expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
});
