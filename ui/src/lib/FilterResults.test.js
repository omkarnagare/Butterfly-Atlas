import { fireEvent, render, screen } from '@testing-library/svelte';
import { createRawSnippet } from 'svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import FilterResults from './FilterResults.svelte';
import { filterCount, filterPhotos, filterPlaces, filterSpecies, getTags } from './api.js';
import { EMPTY } from './filter.js';

vi.mock('./api.js', () => ({
  filterCount: vi.fn(), filterSpecies: vi.fn(), filterPhotos: vi.fn(), filterPlaces: vi.fn(), getTags: vi.fn(), search: vi.fn(), searchPlaces: vi.fn(),
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
  displayUrl: (id) => `./api/photos/${id}/display`,
}));

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
