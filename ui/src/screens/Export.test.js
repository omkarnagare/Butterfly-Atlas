import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Export from './Export.svelte';
import {
  cancelExport, checkExport, deleteExport, filterCount, filterPhotos, filterPlaces, filterSpecies, getExports, getFsRoots, getStatus, getTags, listFolder,
  resumeExport, searchPlaces, startExport,
} from '../lib/api.js';
import { EMPTY } from '../lib/filter.js';
import { asked } from '../lib/liveCount.js';
import { EXPORT_CHANGED, exportChanged, exportGone, resetStatus, status } from '../lib/status.svelte.js';
import { toast } from '../lib/toast.svelte.js';

vi.mock('../lib/api.js', () => ({
  filterCount: vi.fn(), filterSpecies: vi.fn(), filterPhotos: vi.fn(), filterPlaces: vi.fn(), getTags: vi.fn(), search: vi.fn(), searchPlaces: vi.fn(),
  getExports: vi.fn(), getExport: vi.fn(), checkExport: vi.fn(), startExport: vi.fn(), pauseExport: vi.fn(), resumeExport: vi.fn(),
  cancelExport: vi.fn(), deleteExport: vi.fn(), clearExports: vi.fn(), getStatus: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn(),
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
  displayUrl: (id) => `./api/photos/${id}/display`,
}));

// The real live answers, with every ask recorded (`asked`: [fetcher, query]) so a test can see a view asked for twice.
vi.mock('../lib/liveCount.js', async (importOriginal) => {
  const actual = await importOriginal();
  const asked = [];
  const liveCount = (fetcher, options) => {
    const live = actual.liveCount(fetcher, options);
    return { ...live, ask: (query) => { asked.push([fetcher, query]); live.ask(query); } };
  };
  return { asked, liveCount };
});

const maharashtra = { id: 'P20', name: 'Maharashtra', path: ['Maharashtra', 'India'], level: 'state', mine: false };
const count = (query, over = {}, filter = {}) => ({
  photos: 312, species: 48, photographedSpecies: 48, bytes: 4509715660, manualSpecies: false, ...over, filter: { ...EMPTY, query, ...filter },
});
const row = (n, over = {}) => ({
  id: `A${n}`, name: `Danaus species${n}`, common: `Tiger ${n}`, family: 'Nymphalidae', subfamily: null, tribe: null, genus: 'Danaus',
  photos: n, missing: [], source: 'official', active: true, ...over,
});
const photo = (id) => ({
  id, taxonId: 'A1', fileName: `Danaus_${id}.jpg`, relPath: `images/x/Danaus_${id}.jpg`, tags: [], width: 4, height: 3, isCover: false,
  place: null, species: { id: 'A1', name: 'Danaus species1', common: 'Tiger 1' },
});

const exportRun = (over = {}) => ({
  id: 4, state: 'running', what: 'photos', layout: 'species', stripLocation: true, placeDepth: 'none', filter: '',
  path: '/Volumes/Backup/Butterfly_Atlas_export_2026-10-13', csv: null, species: 48, total: 312, done: 0, copied: 0, skipped: 0, failed: 0,
  renamed: 0, bytesTotal: 4509715660, bytesDone: 0, pausedBy: null, notice: null, cancel: null, notRemoved: 0,
  startedAt: new Date(2026, 9, 13, 21, 40).toISOString(), finishedAt: null, ...over,
});

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  asked.length = 0;
  getExports.mockResolvedValue({ current: null, past: [] });
  getStatus.mockImplementation(() => new Promise(() => {}));
  getTags.mockResolvedValue([{ id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 1 }]);
  filterCount.mockImplementation(async (query) => count(query));
  filterSpecies.mockResolvedValue([row(1), row(2)]);
  filterPlaces.mockResolvedValue([]);
  filterPhotos.mockResolvedValue({ photos: [], next: null });
});

const show = (props) => render(Export, { delay: 0, query: '', view: 'species', ...props });

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

test('the filter row, presets, the live count line, the views and the Export… button', async () => {
  filterCount.mockResolvedValue(count('places=P20&status=photographed', {}, { places: [maharashtra], status: 'photographed' }));
  const { container } = show({ query: 'places=P20&status=photographed' });

  expect(screen.getByRole('heading', { level: 1, name: 'Export' })).toBeTruthy();
  await vi.waitFor(() => expect(container.querySelector('.cl-n').textContent).toBe('312 photos of 48 species, 4.2 GB'));
  expect(container.querySelector('.cl-where').textContent).toBe('in Maharashtra');
  expect(filterCount).toHaveBeenCalledWith('places=P20&status=photographed', expect.objectContaining({ signal: expect.any(AbortSignal) }));
  // the chips are named from the count answer
  expect(screen.getByRole('button', { name: 'Place: Maharashtra' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Photographed' })).toBeTruthy();
  expect(screen.getByRole('group', { name: 'Presets' })).toBeTruthy();

  const views = screen.getByRole('group', { name: 'View' });
  expect([...views.querySelectorAll('button')].map((b) => [b.textContent, b.getAttribute('aria-pressed')])).toEqual([
    ['Species', 'true'], ['Photos', 'false'], ['Places', 'false'],
  ]);
  const exportButton = screen.getByRole('button', { name: 'Export…' });
  expect(exportButton.getAttribute('aria-disabled')).toBe('false');
  expect(exportButton.hasAttribute('title')).toBe(false);

  expect(await screen.findByRole('link', { name: /Danaus species2/ })).toBeTruthy();
  expect(filterSpecies).toHaveBeenCalledWith('places=P20&status=photographed', expect.anything());
  expect(screen.getAllByRole('link')[0].getAttribute('href')).toBe('#/species/A1?place=P20');
});

test('the address is rewritten to the filter the server used (an unknown place dropped), without a new history entry', async () => {
  window.location.hash = '#/export?places=P20,P404&view=places';
  const entries = window.history.length;
  filterCount.mockResolvedValue(count('places=P20', {}, { places: [maharashtra] }));
  show({ query: 'places=P20,P404', view: 'places' });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export?places=P20&view=places'));
  expect(window.history.length).toBe(entries);
});

test('the address the count rewrote to the filter the server used, coming back, loads nothing again (the answers are its own)', async () => {
  window.location.hash = '#/export?places=P20,P404&view=places';
  filterCount.mockResolvedValue(count('places=P20', {}, { places: [maharashtra] }));
  const { rerender } = show({ query: 'places=P20,P404', view: 'places' });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export?places=P20&view=places'));
  await vi.waitFor(() => expect(filterPlaces).toHaveBeenCalledTimes(1));
  await rerender({ query: 'places=P20', view: 'places' });
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(filterCount).toHaveBeenCalledTimes(1);
  expect(filterPlaces).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('button', { name: 'Place: Maharashtra' })).toBeTruthy();
});

test('a merged place in the address: the survivor is used, and a line says so above the results until the filter changes', async () => {
  window.location.hash = '#/export?places=P4245,P404';
  const brezice = { id: 'P4187', name: 'Brežice', path: ['Brežice', 'Slovenia'], level: 'district', mine: false, former: false };
  const notes = [
    { id: 'P4245', kind: 'merged', name: 'Brežice (Pečice)', into: brezice },
    { id: 'P404', kind: 'gone', name: null, into: null },
  ];
  filterCount.mockImplementation(async (query) => (query === 'places=P4245,P404'
    ? count('places=P4187', {}, { places: [brezice], placeNotes: notes })
    : count(query, {}, { places: query === 'places=P4187' ? [brezice] : [], placeNotes: [] })));
  const { container, rerender } = show({ query: 'places=P4245,P404' });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export?places=P4187'));
  const lines = () => [...container.querySelectorAll('.ex-note')].map((p) => p.textContent);
  expect(lines()).toEqual(['Brežice (Pečice) is now part of Brežice.', '1 place in this filter no longer exists and was left out.']);
  // the lines are above the results, in the filter's box, and are not actions
  expect(container.querySelector('.ex-filter').contains(container.querySelector('.ex-note'))).toBe(true);
  expect(container.querySelector('.ex-note').querySelector('a, button')).toBeNull();

  // the rewritten address coming back keeps them
  await rerender({ query: 'places=P4187' });
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(lines()).toHaveLength(2);

  // a filter the user changes is counted again; its answer has no notes, so they go
  await rerender({ query: '' });
  await vi.waitFor(() => expect(lines()).toEqual([]));
});

test('the place lines go when the count of another filter fails, and stay when the same filter\'s count fails', async () => {
  const brezice = { id: 'P4187', name: 'Brežice', path: ['Brežice', 'Slovenia'], level: 'district', mine: false, former: false };
  const notes = [{ id: 'P4245', kind: 'merged', name: 'Brežice (Pečice)', into: brezice }];
  filterCount.mockResolvedValueOnce(count('places=P4187', {}, { places: [brezice], placeNotes: notes }))
    .mockRejectedValueOnce(new Error('Butterfly Atlas answered with status 500.'))
    .mockRejectedValueOnce(new Error('Butterfly Atlas answered with status 500.'));
  const { container, rerender } = show({ query: 'places=P4187' });
  const lines = () => [...container.querySelectorAll('.ex-note')].map((p) => p.textContent);
  await vi.waitFor(() => expect(lines()).toEqual(['Brežice (Pečice) is now part of Brežice.']));
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  await vi.waitFor(() => expect(filterCount).toHaveBeenCalledTimes(2));
  await vi.waitFor(() => expect(container.querySelector('.cl .problem')).toBeTruthy());
  expect(lines()).toHaveLength(1);
  await rerender({ query: 'status=photographed' });
  await vi.waitFor(() => expect(filterCount).toHaveBeenCalledTimes(3));
  await vi.waitFor(() => expect(lines()).toEqual([]));
});

test('several places gone from a filter are counted in one line', async () => {
  const gone = (id) => ({ id, kind: 'gone', name: null, into: null });
  filterCount.mockResolvedValue(count('', {}, { placeNotes: [gone('P404'), gone('P405'), gone('P406')] }));
  const { container } = show({ query: 'places=P404,P405,P406' });
  await vi.waitFor(() => expect([...container.querySelectorAll('.ex-note')].map((p) => p.textContent))
    .toEqual(['3 places in this filter no longer exist and were left out.']));
});

test('photos loaded again while the viewer is open (the library changed) keep the old ones until the first page comes', async () => {
  window.location.hash = '#/export/photo/2';
  let answer;
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: null })
    .mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  show({ view: 'photos', photoId: 2 });
  const dialog = await screen.findByRole('dialog', { name: 'Photo viewer: Tiger 1' });
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  await vi.waitFor(() => expect(filterPhotos).toHaveBeenCalledTimes(2));
  expect(dialog.isConnected).toBe(true);
  answer({ photos: [photo(1), photo(2), photo(3)], next: null });
  await vi.waitFor(() => expect(document.querySelector('.lb .c').textContent).toBe('2 / 312'));
  expect(dialog.isConnected).toBe(true);
});

test('a library change in the Photos view loads the photos again in place: the grid keeps its tiles (and its height) until as many have come', async () => {
  window.location.hash = '#/export?view=photos';
  let answer;
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' }).mockResolvedValueOnce({ photos: [photo(3)], next: null })
    .mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  const { container } = show({ view: 'photos' });
  await vi.waitFor(() => expect(container.querySelectorAll('.ftile')).toHaveLength(3));
  const height = container.querySelector('.fgrid').style.height;

  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  await vi.waitFor(() => expect(filterPhotos).toHaveBeenCalledTimes(3));
  expect(filterPhotos.mock.calls[2]).toEqual(['', { after: null, limit: 200 }]);
  expect(container.querySelectorAll('.ftile')).toHaveLength(3);
  expect(container.querySelector('.fgrid').style.height).toBe(height);
  answer({ photos: [photo(1), photo(2), photo(4)], next: null });
  await vi.waitFor(() => expect([...container.querySelectorAll('.ftile')].map((a) => a.dataset.photoId)).toEqual(['1', '2', '4']));
});

test('a failed reload in the Photos view: Try again loads them again in place, so the grid keeps its tiles', async () => {
  window.location.hash = '#/export?view=photos';
  let answer;
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: null })
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  const { container } = show({ view: 'photos' });
  await vi.waitFor(() => expect(container.querySelectorAll('.ftile')).toHaveLength(2));
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe('Butterfly Atlas could not reach its service.');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(filterPhotos).toHaveBeenCalledTimes(3);
  expect(filterPhotos.mock.calls[2]).toEqual(['', { after: null, limit: 200 }]);
  expect(container.querySelectorAll('.ftile')).toHaveLength(2);
  answer({ photos: [photo(1), photo(3)], next: null });
  await vi.waitFor(() => expect([...container.querySelectorAll('.ftile')].map((a) => a.dataset.photoId)).toEqual(['1', '3']));
});

test('a failed reload under the viewer: its Try again loads the photos again in place, and the viewer stays', async () => {
  window.location.hash = '#/export/photo/2';
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: null })
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockResolvedValueOnce({ photos: [photo(1), photo(2), photo(3)], next: null });
  show({ view: 'photos', photoId: 2 });
  const dialog = await screen.findByRole('dialog', { name: 'Photo viewer: Tiger 1' });
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  await vi.waitFor(() => expect(dialog.querySelector('[role="alert"]')?.textContent).toBe('Butterfly Atlas could not reach its service.'));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(document.querySelector('.lb .c').textContent).toBe('2 / 312'));
  expect(filterPhotos.mock.calls.map(([, options]) => options.after)).toEqual([null, null, null]);
  expect(dialog.isConnected).toBe(true);
  expect(dialog.querySelector('[role="alert"]')).toBeNull();
});

test('a library change before the Photos view\'s first page has come starts the photos again', async () => {
  window.location.hash = '#/export?view=photos';
  const answers = [];
  filterPhotos.mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  const { container } = show({ view: 'photos' });
  await vi.waitFor(() => expect(answers).toHaveLength(1));
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  await vi.waitFor(() => expect(answers).toHaveLength(2));
  answers[0]({ photos: [photo(1)], next: null });
  answers[1]({ photos: [photo(2)], next: null });
  await vi.waitFor(() => expect([...container.querySelectorAll('.ftile')].map((a) => a.dataset.photoId)).toEqual(['2']));
  expect(filterPhotos.mock.calls.map(([, options]) => options.after)).toEqual([null, null]);
});

test('× on a chip and a preset change the filter in the address (Back returns to the last one)', async () => {
  window.location.hash = '#/export?tags=larva&status=not';
  show({ query: 'tags=larva&status=not' });
  await fireEvent.click(await screen.findByRole('button', { name: 'Remove Not photographed' }));
  expect(window.location.hash).toBe('#/export?tags=larva');

  await fireEvent.click(screen.getByRole('button', { name: 'Missing life stages' }));
  expect(window.location.hash).toBe('#/export?stages=missing');
});

test('+ Add filter → Place: choosing Unknown place filters by it; Escape closes the popover and focus goes back to + Add filter', async () => {
  window.location.hash = '#/export';
  searchPlaces.mockResolvedValue([]);
  const { rerender } = show();
  const add = screen.getByRole('button', { name: '+ Add filter' });
  await fireEvent.click(add);
  await fireEvent.click(screen.getByRole('menuitem', { name: 'Place' }));
  const dialog = await screen.findByRole('dialog', { name: 'Place' });
  await fireEvent.click(dialog.querySelector('[role="option"]'));
  expect(window.location.hash).toBe('#/export?places=unknown');

  await rerender({ query: 'places=unknown' });
  expect(screen.getByRole('list', { name: 'Chosen places' })).toBeTruthy();
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: 'Place' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: '+ Add filter' })));
});

test('a chip opens its popover and focus returns to the chip; Source is offered only while there are manual species', async () => {
  filterCount.mockImplementation(async (query) => count(query, { manualSpecies: true }));
  show({ query: 'status=photographed' });
  const chip = await screen.findByRole('button', { name: 'Photographed' });
  await fireEvent.click(chip);
  expect(screen.getByRole('radiogroup', { name: 'Photo status' })).toBeTruthy();
  await fireEvent.keyDown(window, { key: 'Escape' });
  await vi.waitFor(() => expect(document.activeElement).toBe(chip));

  await fireEvent.click(screen.getByRole('button', { name: '+ Add filter' }));
  await vi.waitFor(() => expect(screen.getByRole('menuitem', { name: 'Source' })).toBeTruthy());
  await fireEvent.click(screen.getByRole('menuitem', { name: 'Source' }));
  const source = await screen.findByRole('radiogroup', { name: 'Source' });
  expect(within(source).getAllByRole('radio').map((radio) => radio.textContent)).toEqual(['All', 'Official', 'Custom']);
});

test('+ Add filter → Life stages: Missing or Complete, and its chip switches between them', async () => {
  window.location.hash = '#/export';
  const { rerender } = show();
  await fireEvent.click(screen.getByRole('button', { name: '+ Add filter' }));
  await fireEvent.click(screen.getByRole('menuitem', { name: 'Life stages' }));
  const group = await screen.findByRole('radiogroup', { name: 'Life stages' });
  expect(within(group).getAllByRole('radio').map((radio) => radio.textContent)).toEqual(['Missing', 'Complete']);
  await fireEvent.click(within(group).getByRole('radio', { name: 'Complete' }));
  expect(window.location.hash).toBe('#/export?stages=complete');

  await rerender({ query: 'stages=complete' });
  await fireEvent.click(await screen.findByRole('button', { name: 'Life stages: complete' }));
  await fireEvent.click(within(screen.getByRole('radiogroup', { name: 'Life stages' })).getByRole('radio', { name: 'Missing' }));
  expect(window.location.hash).toBe('#/export?stages=missing');
});

test('the Tags popover uses the tags the screen has read, without asking again', async () => {
  window.location.hash = '#/export?tags=larva';
  show({ query: 'tags=larva' });
  await vi.waitFor(() => expect(getTags).toHaveBeenCalledTimes(1));
  await fireEvent.click(await screen.findByRole('button', { name: 'Tags: larva' }));
  const dialog = await screen.findByRole('dialog', { name: 'Tags' });
  await vi.waitFor(() => expect(within(dialog).getByRole('button', { name: 'larva' }).getAttribute('aria-pressed')).toBe('true'));
  expect(getTags).toHaveBeenCalledTimes(1);
});

test('the view switch keeps the filter; Photos loads pages of 200 by cursor as it scrolls', async () => {
  window.location.hash = '#/export?places=P20';
  const { rerender } = show({ query: 'places=P20' });
  await fireEvent.click(screen.getByRole('button', { name: 'Photos' }));
  expect(window.location.hash).toBe('#/export?places=P20&view=photos');

  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' }).mockResolvedValueOnce({ photos: [photo(3)], next: null });
  await rerender({ query: 'places=P20', view: 'photos' });
  await vi.waitFor(() => expect(screen.getAllByRole('link', { name: /^Open Tiger 1/ })).toHaveLength(3));
  expect(filterPhotos.mock.calls.map(([query, options]) => [query, options.after ?? null])).toEqual([['places=P20', null], ['places=P20', 'c1']]);
  expect(screen.getAllByRole('link')[0].getAttribute('href')).toBe('#/export/photo/1?places=P20');
});

test('the Places view: a row narrows the place filter to it, in the same view', async () => {
  window.location.hash = '#/export?view=places';
  filterPlaces.mockResolvedValue([{ id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false, species: 31, photos: 140 }]);
  show({ view: 'places' });
  await fireEvent.click(await screen.findByRole('button', { name: /^Pune · Maharashtra, India/ }));
  expect(window.location.hash).toBe('#/export?places=P414&view=places');
});

test('after choosing a Places row its chip is named at once, and focus goes to the first new row (else the view switch)', async () => {
  window.location.hash = '#/export?view=places';
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false, species: 31, photos: 140 };
  const haveli = { id: 'P5000', name: 'Haveli', path: ['Haveli', 'Pune', 'Maharashtra', 'India'], level: 'taluk', mine: false, species: 12, photos: 40 };
  filterPlaces.mockResolvedValueOnce([pune]).mockResolvedValueOnce([haveli]).mockResolvedValueOnce([]);
  show({ view: 'places' });
  await fireEvent.click(await screen.findByRole('button', { name: /^Pune · Maharashtra, India/ }));
  expect(screen.getByRole('button', { name: 'Place: Pune' })).toBeTruthy();
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: /^Haveli/ })));

  await fireEvent.click(screen.getByRole('button', { name: /^Haveli/ }));
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Places' })));
});

test('a photo address opens the viewer over the loaded photos, loading pages until it is there; Escape closes to the Photos view', async () => {
  window.location.hash = '#/export/photo/3?places=P20';
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' }).mockResolvedValueOnce({ photos: [photo(3)], next: null });
  show({ query: 'places=P20', view: 'photos', photoId: 3 });
  expect(await screen.findByRole('dialog', { name: 'Photo viewer: Tiger 1' })).toBeTruthy();
  expect(document.querySelector('.lb img.orig').getAttribute('src')).toBe('./api/photos/3/display');
  const entries = window.history.length;
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(window.location.hash).toBe('#/export?places=P20&view=photos');
  expect(window.history.length).toBe(entries);
});

test('a photo the results do not have shows the Photos view alone', async () => {
  window.location.hash = '#/export/photo/99';
  filterPhotos.mockResolvedValue({ photos: [photo(1)], next: null });
  show({ view: 'photos', photoId: 99 });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export?view=photos'));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('a count that fails shows its message', async () => {
  filterCount.mockRejectedValue(new Error('Choose at most 200 taxa.'));
  show();
  expect((await screen.findByRole('alert')).textContent).toBe('Choose at most 200 taxa.');
});

test('a place added and another removed at once keeps the added one; the chip names it at once, before the count answers', async () => {
  window.location.hash = '#/export?places=P5528';
  const mahrah = { id: 'P5528', name: 'Al Mahrah', path: ['Al Mahrah', 'Yemen'], level: 'state', mine: false };
  const maharashtra388 = { ...maharashtra, id: 'P388' };
  filterCount.mockImplementation((query) => (query === 'places=P5528'
    ? Promise.resolve(count(query, {}, { places: [mahrah] }))
    : new Promise(() => {})));
  searchPlaces.mockResolvedValue([maharashtra388]);
  show({ query: 'places=P5528' });

  await fireEvent.click(await screen.findByRole('button', { name: 'Place: Al Mahrah' }));
  await fireEvent.input(screen.getByRole('combobox', { name: 'Place' }), { target: { value: 'mah' } });
  await fireEvent.click(await screen.findByRole('option', { name: /^Maharashtra/ }));
  expect(window.location.hash).toBe('#/export?places=P5528,P388');
  // the address has not come back to the screen yet (no hashchange reaches it in this test)
  const chosen = screen.getByRole('list', { name: 'Chosen places' });
  expect([...chosen.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['Al Mahrah ×', 'Maharashtra ×']);
  expect(screen.getByRole('button', { name: 'Place: Al Mahrah, Maharashtra' })).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'Remove Al Mahrah' }));
  expect(window.location.hash).toBe('#/export?places=P388');
  expect(screen.getByRole('button', { name: 'Place: Maharashtra' })).toBeTruthy();
});

test('Back or Forward to another filter shows it', async () => {
  const { rerender } = show({ query: 'status=not' });
  expect(screen.getByRole('button', { name: 'Not photographed' })).toBeTruthy();
  await rerender({ query: 'status=photographed' });
  expect(screen.getByRole('button', { name: 'Photographed' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Not photographed' })).toBeNull();
});

test('Back or Forward to another filter in another view loads that view once', async () => {
  const { rerender } = show({ query: 'status=not' });
  expect(await screen.findByRole('link', { name: /Danaus species2/ })).toBeTruthy();
  await rerender({ query: 'status=photographed', view: 'places' });
  await vi.waitFor(() => expect(filterPlaces).toHaveBeenCalledTimes(1));
  expect(asked.filter(([fetcher]) => fetcher === filterPlaces).map(([, query]) => query)).toEqual(['status=photographed']);
  expect(asked.filter(([fetcher]) => fetcher === filterSpecies)).toHaveLength(1);
});

test('a page that fails in the viewer says so there; Try again asks for the same page, and the step waiting for it moves on', async () => {
  window.location.hash = '#/export/photo/2?places=P20';
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' })
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockResolvedValueOnce({ photos: [photo(3)], next: null });
  const { rerender } = show({ query: 'places=P20', view: 'photos', photoId: 2 });
  const dialog = await screen.findByRole('dialog', { name: 'Photo viewer: Tiger 1' });
  await vi.waitFor(() => expect(dialog.querySelector('[role="alert"]').textContent).toBe('Butterfly Atlas could not reach its service.'));
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/export/photo/2?places=P20');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export/photo/3?places=P20'));
  expect(filterPhotos.mock.calls.map(([, options]) => options.after ?? null)).toEqual([null, 'c1', 'c1']);
  await rerender({ query: 'places=P20', view: 'photos', photoId: 3 });
  expect(document.querySelector('.lb img.orig').getAttribute('src')).toBe('./api/photos/3/display');
});

test('Try again in the viewer after an out-of-date page starts the photos again, and the step waiting for it moves on', async () => {
  window.location.hash = '#/export/photo/2?places=P20';
  const outOfDate = Object.assign(new Error('That page is out of date. Reload the results.'), { code: 'invalid', status: 400 });
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' })
    .mockRejectedValueOnce(outOfDate)
    .mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c2' })
    .mockResolvedValueOnce({ photos: [photo(3)], next: null });
  show({ query: 'places=P20', view: 'photos', photoId: 2 });
  const dialog = await screen.findByRole('dialog', { name: 'Photo viewer: Tiger 1' });
  await vi.waitFor(() => expect(dialog.querySelector('[role="alert"]').textContent).toBe('That page is out of date. Reload the results.'));
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export/photo/3?places=P20'));
  expect(filterPhotos.mock.calls.map(([, options]) => options.after ?? null)).toEqual([null, 'c1', null, 'c2']);
  expect(dialog.isConnected).toBe(true);
});

test('a new filter in the Photos view never sends the old filter\'s cursor, even when scrolled to the end before it loads', async () => {
  window.location.hash = '#/export?view=photos&status=photographed';
  const many = Array.from({ length: 100 }, (_, k) => photo(k + 1));
  filterPhotos.mockResolvedValueOnce({ photos: many, next: 'c1' }).mockResolvedValue({ photos: [photo(500)], next: null });
  const { rerender, container } = show({ query: 'status=photographed', view: 'photos', delay: 50 });
  await vi.waitFor(() => expect(container.querySelectorAll('.ftile').length).toBeGreaterThan(0));
  expect(filterPhotos).toHaveBeenCalledTimes(1);

  await rerender({ query: 'status=photographed&source=official', view: 'photos' });
  // scrolled to the end of the old photos before the new filter's first page is asked for
  container.querySelector('.fgrid').getBoundingClientRect = () => ({ top: -100000 });
  window.dispatchEvent(new Event('scroll'));
  await vi.waitFor(() => expect(filterPhotos).toHaveBeenCalledWith('status=photographed&source=official', expect.objectContaining({ after: null })));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(filterPhotos.mock.calls.filter(([, options]) => options.after === 'c1')).toEqual([]);
});

test('the Species and Places views show "Opening…" until the answer for the new filter comes, not the old rows with new links', async () => {
  window.location.hash = '#/export';
  const { rerender } = show();
  expect(await screen.findByRole('link', { name: /Danaus species2/ })).toBeTruthy();
  filterSpecies.mockImplementation(() => new Promise(() => {}));
  await rerender({ query: 'status=not' });
  expect(screen.queryByRole('link', { name: /Danaus species2/ })).toBeNull();
  expect(screen.getByText('Opening…')).toBeTruthy();

  filterPlaces.mockResolvedValueOnce([{ id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false, species: 31, photos: 140 }])
    .mockImplementation(() => new Promise(() => {}));
  await rerender({ query: 'status=not', view: 'places' });
  expect(await screen.findByRole('button', { name: /^Pune/ })).toBeTruthy();
  await rerender({ query: 'status=photographed', view: 'places' });
  expect(screen.queryByRole('button', { name: /^Pune/ })).toBeNull();
  expect(screen.getByText('Opening…')).toBeTruthy();
});

test('a Species or Places view that fails says so, with Try again', async () => {
  filterSpecies.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  show();
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByRole('link', { name: /Danaus species2/ })).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
});

test('one history entry for each opening of a popover, however many changes are made in it', async () => {
  window.location.hash = '#/export';
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false };
  searchPlaces.mockResolvedValue([pune, maharashtra]);
  show();
  const entries = window.history.length;
  await fireEvent.click(screen.getByRole('button', { name: '+ Add filter' }));
  await fireEvent.click(screen.getByRole('menuitem', { name: 'Place' }));
  await fireEvent.input(await screen.findByRole('combobox', { name: 'Place' }), { target: { value: 'pu' } });
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));
  await fireEvent.click(screen.getByRole('option', { name: /^Maharashtra/ }));
  await fireEvent.click(screen.getByRole('option', { name: /^Unknown place/ }));
  expect(window.location.hash).toBe('#/export?places=P414,P20,unknown');
  expect(window.history.length).toBe(entries + 1);

  // a second opening is a second entry
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(screen.getByRole('button', { name: 'Place: Pune, Maharashtra, Unknown place' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Unknown place' }));
  expect(window.history.length).toBe(entries + 2);
});

test('a page of photos for an older filter arriving late is dropped', async () => {
  window.location.hash = '#/export?view=photos';
  let late;
  filterPhotos.mockImplementationOnce(() => new Promise((resolve) => { late = resolve; }))
    .mockResolvedValueOnce({ photos: [photo(7)], next: null });
  const { rerender, container } = show({ view: 'photos' });
  await vi.waitFor(() => expect(filterPhotos).toHaveBeenCalledTimes(1));
  await rerender({ query: 'status=photographed', view: 'photos' });
  await vi.waitFor(() => expect(container.querySelectorAll('.ftile')).toHaveLength(1));
  late({ photos: [photo(1), photo(2)], next: 'c1' });
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect([...container.querySelectorAll('.ftile')].map((a) => a.dataset.photoId)).toEqual(['7']);
  expect(filterPhotos).toHaveBeenCalledTimes(2);
});

test('the same cursor is never asked for twice while its page is on its way, from the grid or the viewer', async () => {
  window.location.hash = '#/export/photo/2';
  let page2;
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' })
    .mockImplementationOnce(() => new Promise((resolve) => { page2 = resolve; }));
  const { container } = show({ view: 'photos', photoId: 2 });
  await screen.findByRole('dialog', { name: 'Photo viewer: Tiger 1' });
  await vi.waitFor(() => expect(filterPhotos).toHaveBeenCalledTimes(2));
  window.dispatchEvent(new Event('scroll'));
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(filterPhotos.mock.calls.map(([, options]) => options.after ?? null)).toEqual([null, 'c1']);
  page2({ photos: [photo(3)], next: null });
  await vi.waitFor(() => expect(container.querySelectorAll('.ftile')).toHaveLength(3));
  expect(filterPhotos).toHaveBeenCalledTimes(2);
});

test('the address coming back with the screen\'s own filter changes nothing: chips and chosen list stay', async () => {
  window.location.hash = '#/export';
  searchPlaces.mockResolvedValue([maharashtra]);
  const { rerender } = show();
  await fireEvent.click(screen.getByRole('button', { name: '+ Add filter' }));
  await fireEvent.click(screen.getByRole('menuitem', { name: 'Place' }));
  await fireEvent.click((await screen.findAllByRole('option'))[0]);
  await fireEvent.input(screen.getByRole('combobox', { name: 'Place' }), { target: { value: 'ma' } });
  await fireEvent.click(await screen.findByRole('option', { name: /^Maharashtra/ }));
  expect(window.location.hash).toBe('#/export?places=unknown,P20');
  const chosen = () => [...screen.getByRole('list', { name: 'Chosen places' }).querySelectorAll('li')].map((li) => li.textContent);
  expect(chosen()).toEqual(['Unknown place ×', 'Maharashtra ×']);
  await vi.waitFor(() => expect(filterCount).toHaveBeenCalledWith('places=unknown,P20', expect.anything()));
  const calls = filterCount.mock.calls.length;

  await rerender({ query: 'places=unknown,P20' });
  expect(chosen()).toEqual(['Unknown place ×', 'Maharashtra ×']);
  expect(screen.getByRole('button', { name: 'Place: Unknown place, Maharashtra' })).toBeTruthy();
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(filterCount.mock.calls.length).toBe(calls);
});

test('a view answered before the count rewrites the address shows its rows (not "Opening…" for ever), without asking again', async () => {
  window.location.hash = '#/export?places=P20,P404';
  let countAnswer;
  filterCount.mockImplementationOnce(() => new Promise((resolve) => { countAnswer = resolve; }));
  show({ query: 'places=P20,P404' });
  expect(await screen.findByRole('link', { name: /Danaus species2/ })).toBeTruthy();
  countAnswer(count('places=P20', {}, { places: [maharashtra] }));
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export?places=P20'));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(screen.getByRole('link', { name: /Danaus species2/ })).toBeTruthy();
  expect(screen.queryByText('Opening…')).toBeNull();
  expect(filterSpecies).toHaveBeenCalledTimes(1);
  expect(filterCount).toHaveBeenCalledTimes(1);
});

test('a view answered after the count rewrote the address shows its rows, without asking again', async () => {
  window.location.hash = '#/export?places=P20,P404&view=places';
  let placesAnswer;
  filterPlaces.mockImplementationOnce(() => new Promise((resolve) => { placesAnswer = resolve; }));
  filterCount.mockResolvedValue(count('places=P20', {}, { places: [maharashtra] }));
  show({ query: 'places=P20,P404', view: 'places' });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/export?places=P20&view=places'));
  placesAnswer([{ id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false, species: 31, photos: 140 }]);
  expect(await screen.findByRole('button', { name: /^Pune/ })).toBeTruthy();
  expect(filterPlaces).toHaveBeenCalledTimes(1);
});

test('a Places row whose rows fail puts focus on Try again; one whose rows come after a view switch does not take focus', async () => {
  window.location.hash = '#/export?view=places';
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false, species: 31, photos: 140 };
  const goa = { id: 'P21', name: 'Goa', path: ['Goa', 'India'], level: 'state', mine: false, species: 3, photos: 4 };
  filterPlaces.mockResolvedValueOnce([pune, goa]).mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  const { rerender } = show({ view: 'places' });
  await fireEvent.click(await screen.findByRole('button', { name: /^Pune/ }));
  await rerender({ query: 'places=P414', view: 'places' });
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Try again' })));

  filterPlaces.mockResolvedValueOnce([pune, goa]);
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await fireEvent.click(await screen.findByRole('button', { name: /^Goa/ }));
  // the Species view before the Places rows come; then back to Places
  await rerender({ query: 'places=P21', view: 'species' });
  const outside = screen.getByRole('button', { name: 'Export…' });
  outside.focus();
  filterPlaces.mockResolvedValueOnce([goa]);
  await rerender({ query: 'places=P21', view: 'places' });
  await screen.findByRole('button', { name: /^Goa/ });
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(document.activeElement).toBe(outside);
});

test('after Try again focus goes to the view switch (the photos start again), never to the page', async () => {
  filterSpecies.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  const { rerender, container } = show();
  await fireEvent.click(await screen.findByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Species' })));

  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' })
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockResolvedValueOnce({ photos: [photo(1), photo(2), photo(3)], next: null });
  await rerender({ query: '', view: 'photos' });
  const retry = await screen.findByRole('button', { name: 'Try again' });
  retry.focus();
  await fireEvent.click(retry);
  await vi.waitFor(() => expect(container.querySelectorAll('.ftile')).toHaveLength(3));
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Photos' }));
});

/* ---------- exports (Phase 4b) ---------- */

const pinNumbers = () => vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
const backup = {
  path: '/Volumes/Backup', name: 'Backup', parent: '/Volumes', crumbs: [{ name: 'Backup', path: '/Volumes/Backup' }], folders: [], photos: 0,
  isLibrary: false, openImport: null,
};

async function startFrom(what) {
  getFsRoots.mockResolvedValue([{ name: 'Backup', path: '/Volumes/Backup', kind: 'drive' }]);
  listFolder.mockResolvedValue(backup);
  checkExport.mockResolvedValue({ photos: 312, species: 48, bytes: 4509715660, neededBytes: what === 'csv' ? 0 : 4719430860, freeBytes: 332859965440, name: 'x', problem: null });
  const button = screen.getByRole('button', { name: 'Export…' });
  await vi.waitFor(() => expect(button.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(button);
  const sheet = screen.getByRole('dialog', { name: 'Export' });
  if (what === 'csv') await fireEvent.click(within(sheet).getByRole('radio', { name: 'Species list (CSV)' }));
  await fireEvent.click(within(sheet).getByRole('button', { name: 'Choose where to export…' }));
  await fireEvent.click(await screen.findByRole('button', { name: 'Export here' }));
  const go = within(sheet).getByRole('button', { name: 'Export' });
  await vi.waitFor(() => expect(go.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(go);
}

test('Export… opens the sheet with the count line of the filter; Escape closes it and focus goes back to Export…', async () => {
  show({ query: 'status=photographed' });
  const button = screen.getByRole('button', { name: 'Export…' });
  await vi.waitFor(() => expect(button.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(button);
  const sheet = screen.getByRole('dialog', { name: 'Export' });
  expect(sheet.querySelector('.cl-n').textContent).toBe('312 photos of 48 species, 4.2 GB');
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: 'Export' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(button));
});

test('Export… is inactive, with its reason, while an export is running or paused and when nothing matches', async () => {
  getExports.mockResolvedValue({ current: exportRun({ state: 'paused', pausedBy: 'you' }), past: [] });
  const { unmount } = show();
  const button = screen.getByRole('button', { name: 'Export…' });
  await vi.waitFor(() => expect(button.getAttribute('title')).toBe('Finish or cancel the export in progress first.'));
  expect(button.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(button);
  expect(screen.queryByRole('dialog', { name: 'Export' })).toBeNull();
  unmount();

  getExports.mockResolvedValue({ current: null, past: [] });
  filterCount.mockImplementation(async (query) => count(query, { photos: 0, species: 0, photographedSpecies: 0, bytes: 0 }));
  show();
  const nothing = screen.getByRole('button', { name: 'Export…' });
  await vi.waitFor(() => expect(nothing.getAttribute('title')).toBe('Nothing matches these filters.'));
  expect(nothing.getAttribute('aria-disabled')).toBe('true');
});

test('before the exports have been read, a current export in the status keeps Export… inactive', async () => {
  getExports.mockImplementation(() => new Promise(() => {}));
  status.export = { id: 4, state: 'running', done: 1, total: 312, pausedBy: null, notice: null, path: '/x' };
  show();
  const button = screen.getByRole('button', { name: 'Export…' });
  await vi.waitFor(() => expect(filterCount).toHaveBeenCalled());
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(button.getAttribute('title')).toBe('Finish or cancel the export in progress first.');
});

test('the progress card sits at the top, then Past exports, then the filter; the filter keeps working below them', async () => {
  pinNumbers();
  getExports.mockResolvedValue({ current: exportRun({ done: 120 }), past: [exportRun({ id: 3, state: 'done', done: 312, finishedAt: 'x' })] });
  const { container } = show();
  const card = await vi.waitFor(() => {
    const found = container.querySelector('.xp');
    if (!found) throw new Error('no card yet');
    return found;
  });
  expect(card.textContent).toContain('Exporting photos… 120 of 312');
  const past = screen.getByRole('heading', { name: 'Past exports' }).closest('section');
  const filter = screen.getByRole('region', { name: 'Filter' });
  expect(card.compareDocumentPosition(past) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(past.compareDocumentPosition(filter) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Missing life stages' }));
  expect(window.location.hash).toBe('#/export?stages=missing');
});

test('starting a photo export closes the sheet, shows the card and moves focus to it', async () => {
  pinNumbers();
  startExport.mockResolvedValue(exportRun());
  const { container } = show({ query: 'status=photographed' });
  await startFrom('photos');
  expect(startExport).toHaveBeenCalledWith(expect.objectContaining({ filter: 'status=photographed', what: 'photos', path: '/Volumes/Backup' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Export' })).toBeNull());
  const card = container.querySelector('.xp');
  expect(card.textContent).toContain('Exporting photos… 0 of 312');
  await vi.waitFor(() => expect(document.activeElement).toBe(card));
  expect(screen.getByRole('button', { name: 'Export…' }).getAttribute('aria-disabled')).toBe('true');
  expect(getStatus).toHaveBeenCalled();
});

test('a photo export that is already over when the status first answers still says "Export finished.", once', async () => {
  pinNumbers();
  startExport.mockResolvedValue(exportRun());
  getStatus.mockResolvedValue({ rescanning: false, export: null });
  show({ query: 'status=photographed' });
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Export…' }).getAttribute('aria-disabled')).toBe('false'));
  const before = toast.seq;
  await startFrom('photos');
  await vi.waitFor(() => expect(toast.message).toBe('Export finished.'));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(toast.seq).toBe(before + 1);
});

test('a photo export already over when the status first answers says its HEIC photos too (7b review fix U9)', async () => {
  pinNumbers();
  startExport.mockResolvedValue(exportRun({ rawCount: 2, heicCount: 30 }));
  getStatus.mockResolvedValue({ rescanning: false, export: null });
  show({ query: 'status=photographed' });
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Export…' }).getAttribute('aria-disabled')).toBe('false'));
  await startFrom('photos');
  await vi.waitFor(() => expect(toast.message).toBe('Export finished. 2 RAW and 30 HEIC photos exported with their location.'));
});

test('a species list saved at once gets its own toast only, not "Export finished." as well', async () => {
  pinNumbers();
  startExport.mockResolvedValue(exportRun({ id: 6, state: 'done', what: 'csv', total: 0, done: 0, path: '/x.csv', finishedAt: 'x' }));
  getStatus.mockResolvedValue({ rescanning: false, export: null });
  show();
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Export…' }).getAttribute('aria-disabled')).toBe('false'));
  const before = toast.seq;
  await startFrom('csv');
  await vi.waitFor(() => expect(toast.message).toBe('Species list saved.'));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(toast.seq).toBe(before + 1);
});

test('a species list alone is saved at once: its Past exports entry takes focus, with a toast', async () => {
  pinNumbers();
  const saved = exportRun({ id: 6, state: 'done', what: 'csv', total: 0, done: 0, path: '/Volumes/Backup/Butterfly_Atlas_species_list_2026-10-13.csv', finishedAt: 'x' });
  startExport.mockResolvedValue(saved);
  const { container } = show();
  await startFrom('csv');
  await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Export' })).toBeNull());
  const row = container.querySelector('[data-export-id="6"]');
  expect(row.textContent).toContain('Species list (CSV)');
  await vi.waitFor(() => expect(document.activeElement).toBe(row));
  expect(toast.message).toBe('Species list saved.');
  expect(container.querySelector('.xp')).toBeNull();
});

test('Cancel… on the card asks first; stopped, the card goes and its Past exports entry takes focus', async () => {
  pinNumbers();
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 120 }), past: [] });
  const cancelled = exportRun({ done: 120, state: 'cancelled', cancel: 'keep', finishedAt: 'x' });
  cancelExport.mockResolvedValue(cancelled);
  getExports.mockResolvedValue({ current: null, past: [cancelled] });
  const { container } = show();
  await fireEvent.click(await screen.findByRole('button', { name: 'Cancel…' }));
  const dialog = screen.getByRole('dialog', { name: 'Stop this export?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Stop export' }));
  expect(cancelExport).toHaveBeenCalledWith(4, false);
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(container.querySelector('.xp')).toBeNull();
  const row = container.querySelector('[data-export-id="4"]');
  expect(row.textContent).toContain('Cancelled at 120 of 312 (files kept)');
  await vi.waitFor(() => expect(document.activeElement).toBe(row));
});

test('Go back in the stop dialog returns focus to Cancel…', async () => {
  getExports.mockResolvedValue({ current: exportRun(), past: [] });
  show();
  const cancel = await screen.findByRole('button', { name: 'Cancel…' });
  await fireEvent.click(cancel);
  await fireEvent.click(screen.getByRole('button', { name: 'Go back' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(cancel));
});

test('an export that finishes while its card has focus: the card goes and focus moves to its Past exports entry', async () => {
  pinNumbers();
  const done = exportRun({ state: 'done', done: 312, finishedAt: 'x' });
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 311 }), past: [] }).mockResolvedValue({ current: null, past: [done] });
  // the card's poll is set up with fake timers, so the test moves it on instead of waiting on the real second
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const { container } = show();
  const card = await vi.waitFor(() => {
    const found = container.querySelector('.xp');
    if (!found) throw new Error('no card yet');
    return found;
  });
  card.focus();
  await vi.advanceTimersByTimeAsync(1000);
  await vi.waitFor(() => expect(container.querySelector('.xp')).toBeNull());
  const row = container.querySelector('[data-export-id="4"]');
  await vi.waitFor(() => expect(document.activeElement).toBe(row));
  // the footer and Home are told
  expect(getStatus).toHaveBeenCalled();
});

test('Resume on the card refused because the export has already finished: the exports are read again and the card goes, without an error', async () => {
  pinNumbers();
  const done = exportRun({ state: 'done', done: 312, finishedAt: 'x' });
  getExports.mockResolvedValueOnce({ current: exportRun({ state: 'paused', pausedBy: 'you', done: 300 }), past: [] }).mockResolvedValue({ current: null, past: [done] });
  resumeExport.mockRejectedValue(Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 }));
  const { container } = show();
  const resume = await screen.findByRole('button', { name: 'Resume' });
  resume.focus();
  await fireEvent.click(resume);
  await vi.waitFor(() => expect(container.querySelector('.xp')).toBeNull());
  expect(screen.queryByText('This export has already finished.')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(container.querySelector('[data-export-id="4"]')));
});

test('an export changed elsewhere (the footer, Home) reloads the exports', async () => {
  show();
  await vi.waitFor(() => expect(getExports).toHaveBeenCalledTimes(1));
  getExports.mockResolvedValue({ current: exportRun({ state: 'paused', pausedBy: 'you', done: 7 }), past: [] });
  window.dispatchEvent(new CustomEvent(EXPORT_CHANGED, { detail: {} }));
  await vi.waitFor(() => expect(getExports).toHaveBeenCalledTimes(2));
  expect(await screen.findByRole('button', { name: 'Resume' })).toBeTruthy();
});

test('an export that finishes while its stop dialog is open closes the dialog, and focus goes to its Past exports entry', async () => {
  pinNumbers();
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 300 }), past: [] });
  const { container } = show();
  await fireEvent.click(await screen.findByRole('button', { name: 'Cancel…' }));
  expect(screen.getByRole('dialog', { name: 'Stop this export?' })).toBeTruthy();
  getExports.mockResolvedValue({ current: null, past: [exportRun({ state: 'done', done: 312, finishedAt: 'x' })] });
  window.dispatchEvent(new CustomEvent(EXPORT_CHANGED, { detail: null }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(container.querySelector('[data-export-id="4"]')));
});

test('a stop refused because the export has already finished reads the exports again and closes the dialog, without an error', async () => {
  pinNumbers();
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 311 }), past: [] })
    .mockResolvedValue({ current: null, past: [exportRun({ state: 'done', done: 312, finishedAt: 'x' })] });
  cancelExport.mockRejectedValue(Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 }));
  const { container } = show();
  await fireEvent.click(await screen.findByRole('button', { name: 'Cancel…' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop export' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.queryByText('This export has already finished.')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(container.querySelector('[data-export-id="4"]')));
  // read once for the refusal (EXPORT_CHANGED), after the screen's own first read
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(getExports).toHaveBeenCalledTimes(2);
});

test('an inactive Export… is described by its reason (for screen readers too): counting, an export in progress, nothing to export', async () => {
  let answer;
  filterCount.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  getExports.mockResolvedValueOnce({ current: null, past: [] });
  const { unmount } = show();
  const button = screen.getByRole('button', { name: 'Export…' });
  const reason = () => document.getElementById(button.getAttribute('aria-describedby'))?.textContent;
  expect(button.getAttribute('aria-disabled')).toBe('true');
  expect([button.getAttribute('title'), reason()]).toEqual(['Counting…', 'Counting…']);
  await vi.waitFor(() => expect(answer).toBeTypeOf('function'));
  answer(count(''));
  await vi.waitFor(() => expect(button.getAttribute('aria-disabled')).toBe('false'));
  expect(button.hasAttribute('aria-describedby')).toBe(false);
  unmount();

  getExports.mockResolvedValue({ current: exportRun(), past: [] });
  const second = show();
  const busy = screen.getByRole('button', { name: 'Export…' });
  await vi.waitFor(() => expect(document.getElementById(busy.getAttribute('aria-describedby'))?.textContent)
    .toBe('Finish or cancel the export in progress first.'));
  second.unmount();

  getExports.mockResolvedValue({ current: null, past: [] });
  filterCount.mockImplementation(async (query) => count(query, { photos: 0, species: 0, photographedSpecies: 0, bytes: 0 }));
  show();
  const nothing = screen.getByRole('button', { name: 'Export…' });
  await vi.waitFor(() => expect(document.getElementById(nothing.getAttribute('aria-describedby'))?.textContent).toBe('Nothing matches these filters.'));
  expect(nothing.getAttribute('aria-disabled')).toBe('true');
});

test('an export the status shows that the screen does not (started or paused elsewhere) makes it read the exports again, once', async () => {
  pinNumbers();
  const { container } = show();
  await vi.waitFor(() => expect(getExports).toHaveBeenCalledTimes(1));
  getExports.mockResolvedValue({ current: exportRun({ done: 5 }), past: [] });
  status.export = { id: 4, state: 'running', done: 5, total: 312, pausedBy: null, notice: null, path: '/x' };
  await vi.waitFor(() => expect(container.querySelector('.xp')?.textContent).toContain('Exporting photos… 5 of 312'));
  expect(getExports).toHaveBeenCalledTimes(2);
  // the same export moving on is no news
  status.export = { ...status.export, done: 9 };
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(getExports).toHaveBeenCalledTimes(2);

  // paused by itself (the drive is full), seen by the footer's poll
  getExports.mockResolvedValue({ current: exportRun({ state: 'paused', pausedBy: 'drive_full', notice: 'The drive is full: about 2.1 GB more is needed.' }), past: [] });
  status.export = { ...status.export, state: 'paused', pausedBy: 'drive_full' };
  expect(await screen.findByRole('button', { name: 'Resume' })).toBeTruthy();
  expect(getExports).toHaveBeenCalledTimes(3);
});

test('an export that finishes after focus fell from its card to the page moves focus to its entry; focus elsewhere is left alone', async () => {
  pinNumbers();
  const done = exportRun({ state: 'done', done: 312, finishedAt: 'x' });
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 311 }), past: [] }).mockResolvedValue({ current: null, past: [done] });
  // the card's poll is set up with fake timers, so the test moves it on instead of waiting on the real second
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const { container, unmount } = show();
  const card = await vi.waitFor(() => {
    const found = container.querySelector('.xp');
    if (!found) throw new Error('no card yet');
    return found;
  });
  card.querySelector('button').focus();
  // the focused button went away (Resume turned to Pause in an older build): focus is on the page
  document.activeElement.blur();
  expect(document.activeElement).toBe(document.body);
  await vi.advanceTimersByTimeAsync(1000);
  await vi.waitFor(() => expect(container.querySelector('.xp')).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(container.querySelector('[data-export-id="4"]')));
  unmount();

  getExports.mockResolvedValueOnce({ current: exportRun({ done: 311 }), past: [] }).mockResolvedValue({ current: null, past: [done] });
  const second = show();
  await vi.waitFor(() => expect(second.container.querySelector('.xp')).toBeTruthy());
  second.container.querySelector('.xp').focus();
  const add = screen.getByRole('button', { name: '+ Add filter' });
  add.focus();
  await vi.advanceTimersByTimeAsync(1000);
  await vi.waitFor(() => expect(second.container.querySelector('.xp')).toBeNull());
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(document.activeElement).toBe(add);
});

test('a click on the page after using the card: an export that finishes then leaves focus where it is', async () => {
  pinNumbers();
  const done = exportRun({ state: 'done', done: 312, finishedAt: 'x' });
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 311 }), past: [] }).mockResolvedValue({ current: null, past: [done] });
  // the card's poll is set up with fake timers, so the test moves it on instead of waiting on the real second
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const { container } = show();
  await vi.waitFor(() => expect(container.querySelector('.xp')).toBeTruthy());
  container.querySelector('.xp button').focus();
  // a mouse click on the page's background: focus goes to the page, and no focusin says so
  await fireEvent.pointerDown(document.body);
  document.activeElement.blur();
  await vi.advanceTimersByTimeAsync(1000);
  await vi.waitFor(() => expect(container.querySelector('.xp')).toBeNull());
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(document.activeElement).toBe(document.body);
});

test('an older read of the exports answering after the screen\'s own start does not undo it: the card stays, with focus', async () => {
  pinNumbers();
  let first;
  getExports.mockImplementationOnce(() => new Promise((resolve) => { first = resolve; })).mockResolvedValue({ current: exportRun(), past: [] });
  startExport.mockResolvedValue(exportRun());
  const { container } = show();
  await startFrom('photos');
  await vi.waitFor(() => expect(document.activeElement).toBe(container.querySelector('.xp')));
  first({ current: null, past: [] });
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(container.querySelector('.xp')).toBeTruthy();
  expect(document.activeElement).toBe(container.querySelector('.xp'));
});

test('an older read of the exports answering after a removal from the list does not bring the entry back', async () => {
  const old = exportRun({ id: 3, state: 'done', done: 312, finishedAt: 'x' });
  getExports.mockResolvedValueOnce({ current: null, past: [old] });
  deleteExport.mockResolvedValue(null);
  const { container } = show();
  await vi.waitFor(() => expect(container.querySelector('[data-export-id="3"]')).toBeTruthy());
  let late;
  getExports.mockImplementationOnce(() => new Promise((resolve) => { late = resolve; })).mockResolvedValue({ current: null, past: [] });
  window.dispatchEvent(new CustomEvent(EXPORT_CHANGED, { detail: null }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(container.querySelector('[data-export-id="3"]')).toBeNull());
  late({ current: null, past: [old] });
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(container.querySelector('[data-export-id="3"]')).toBeNull();
});

test('the stop dialog\'s count follows the export while it is open', async () => {
  pinNumbers();
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 120 }), past: [] });
  show();
  await fireEvent.click(await screen.findByRole('button', { name: 'Cancel…' }));
  const dialog = screen.getByRole('dialog', { name: 'Stop this export?' });
  expect(dialog.textContent).toContain('120 of 312 photos were copied');
  getExports.mockResolvedValue({ current: exportRun({ done: 150 }), past: [] });
  window.dispatchEvent(new CustomEvent(EXPORT_CHANGED, { detail: null }));
  await vi.waitFor(() => expect(dialog.textContent).toContain('150 of 312 photos were copied'));
});

test('a stop dialog that is stopping stays open when a read sees its export gone first; it closes once the stop answers, or fails', async () => {
  pinNumbers();
  const cancelled = exportRun({ done: 120, state: 'cancelled', cancel: 'keep', finishedAt: 'x' });
  for (const answers of ['resolve', 'reject']) {
    resetStatus();
    getExports.mockReset();
    getExports.mockResolvedValueOnce({ current: exportRun({ done: 120 }), past: [] }).mockResolvedValue({ current: null, past: [cancelled] });
    let stop;
    cancelExport.mockImplementationOnce(() => new Promise((resolve, reject) => { stop = { resolve, reject }; }));
    const { container, unmount } = show();
    await fireEvent.click(await screen.findByRole('button', { name: 'Cancel…' }));
    await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop export' }));
    window.dispatchEvent(new CustomEvent(EXPORT_CHANGED, { detail: null }));
    await vi.waitFor(() => expect(container.querySelector('.xp')).toBeNull());
    expect(screen.getByRole('dialog', { name: 'Stop this export?' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Stopping…' })).toBeTruthy();
    if (answers === 'resolve') stop.resolve(cancelled);
    else stop.reject(Object.assign(new Error('Butterfly Atlas could not reach its service.'), { code: 'offline' }));
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await vi.waitFor(() => expect(document.activeElement).toBe(container.querySelector('[data-export-id="4"]')));
    unmount();
  }
});

test('a change this page made to the export (the footer\'s Continue export) reads the exports once', async () => {
  pinNumbers();
  const paused = exportRun({ state: 'paused', pausedBy: 'you', done: 7 });
  getExports.mockResolvedValueOnce({ current: paused, past: [] });
  status.export = { id: 4, state: 'paused', done: 7, total: 312, pausedBy: 'you', notice: null, path: '/x' };
  show();
  expect(await screen.findByRole('button', { name: 'Resume' })).toBeTruthy();
  expect(getExports).toHaveBeenCalledTimes(1);
  getExports.mockResolvedValue({ current: exportRun({ done: 7 }), past: [] });
  exportChanged(exportRun({ done: 7 }));
  expect(await screen.findByRole('button', { name: 'Pause' })).toBeTruthy();
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(getExports).toHaveBeenCalledTimes(2);
});

test('the card\'s poll asked before a removal from the list, answering after it, does not bring the entry back', async () => {
  pinNumbers();
  const old = exportRun({ id: 3, state: 'done', done: 312, finishedAt: 'x' });
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 100 }), past: [old] });
  deleteExport.mockResolvedValue(null);
  vi.useFakeTimers({ shouldAdvanceTime: true });
  const { container } = show();
  await vi.waitFor(() => expect(container.querySelector('[data-export-id="3"]')).toBeTruthy());
  let poll;
  getExports.mockImplementationOnce(() => new Promise((resolve) => { poll = resolve; })).mockResolvedValue({ current: exportRun({ done: 120 }), past: [] });
  await vi.advanceTimersByTimeAsync(1000);
  expect(poll).toBeTypeOf('function');
  await fireEvent.click(screen.getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(container.querySelector('[data-export-id="3"]')).toBeNull());
  poll({ current: exportRun({ done: 110 }), past: [old] });
  await vi.advanceTimersByTimeAsync(0);
  expect(container.querySelector('[data-export-id="3"]')).toBeNull();
  // the next poll, asked after the removal, is shown
  await vi.advanceTimersByTimeAsync(1000);
  await vi.waitFor(() => expect(container.querySelector('.xp').textContent).toContain('Exporting photos… 120 of 312'));
  expect(container.querySelector('[data-export-id="3"]')).toBeNull();
});

test('an export gone by a refusal is read once, though the status says so before the exports answer', async () => {
  pinNumbers();
  status.export = { id: 4, state: 'running', done: 120, total: 312, pausedBy: null, notice: null, path: '/x' };
  getExports.mockResolvedValueOnce({ current: exportRun({ done: 120 }), past: [] });
  const { container } = show();
  await vi.waitFor(() => expect(container.querySelector('.xp')).toBeTruthy());
  let list;
  getExports.mockImplementation(() => new Promise((resolve) => { list = resolve; }));
  getStatus.mockResolvedValue({ rescanning: false, export: null });
  exportGone();
  await vi.waitFor(() => expect(status.export).toBeNull());
  await new Promise((resolve) => setTimeout(resolve, 20));
  list({ current: null, past: [exportRun({ state: 'done', done: 312, finishedAt: 'x' })] });
  await vi.waitFor(() => expect(container.querySelector('.xp')).toBeNull());
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(getExports).toHaveBeenCalledTimes(2);
});

test('File type is offered in + Add filter and sets the filter in the address (Phase 7a)', async () => {
  show({ query: '' });
  await fireEvent.click(await screen.findByRole('button', { name: '+ Add filter' }));
  await fireEvent.click(await screen.findByRole('menuitem', { name: 'File type' }));
  const dialog = await screen.findByRole('dialog', { name: 'File type' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'RAW' }));
  expect(window.location.hash).toBe('#/export?types=raw');
});
