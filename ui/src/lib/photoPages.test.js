import { beforeEach, expect, test, vi } from 'vitest';
import { PhotoPages } from './photoPages.svelte.js';
import { filterPhotos } from './api.js';

vi.mock('./api.js', () => ({ filterPhotos: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

const photo = (id) => ({ id });
const ids = (pages) => pages.photos.map((p) => p.id);

test('the first page, then the next by its cursor, 200 at a time, until there are no more', async () => {
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' }).mockResolvedValueOnce({ photos: [photo(3)], next: null });
  const pages = new PhotoPages();
  expect([pages.photos, pages.next, pages.loaded, pages.loading, pages.error]).toEqual([[], null, false, false, '']);

  const first = pages.load('places=P20', true);
  expect(pages.loading).toBe(true);
  await first;
  expect([ids(pages), pages.next, pages.loaded, pages.loading]).toEqual([[1, 2], 'c1', true, false]);

  await pages.load('places=P20', false);
  expect([ids(pages), pages.next]).toEqual([[1, 2, 3], null]);
  // no more pages: nothing is asked
  await pages.load('places=P20', false);
  expect(filterPhotos.mock.calls).toEqual([['places=P20', { after: null, limit: 200 }], ['places=P20', { after: 'c1', limit: 200 }]]);
});

test('the same cursor is never asked for twice while its page is on its way', async () => {
  let answer;
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: 'c1' }).mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  const pages = new PhotoPages();
  await pages.load('', true);
  const second = pages.load('', false);
  await pages.load('', false);
  expect(filterPhotos).toHaveBeenCalledTimes(2);
  answer({ photos: [photo(2)], next: null });
  await second;
  expect(ids(pages)).toEqual([1, 2]);
});

test('starting again empties the photos, unless they are kept (an open viewer) until the first page comes', async () => {
  let answer;
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: null })
    .mockResolvedValueOnce({ photos: [photo(7)], next: null })
    .mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  const pages = new PhotoPages();
  await pages.load('', true);
  const again = pages.load('status=not', true);
  expect(ids(pages)).toEqual([]);
  await again;
  expect(ids(pages)).toEqual([7]);

  const kept = pages.load('', true, { keep: true });
  expect([ids(pages), pages.loaded]).toEqual([[7], false]);
  answer({ photos: [photo(1)], next: null });
  await kept;
  expect(ids(pages)).toEqual([1]);
});

test('a page for an older start arriving late is dropped; so is one on its way when the cursor is forgotten or paging stops', async () => {
  let late;
  filterPhotos.mockImplementationOnce(() => new Promise((resolve) => { late = resolve; }))
    .mockResolvedValueOnce({ photos: [photo(7)], next: 'c7' });
  const pages = new PhotoPages();
  const old = pages.load('', true);
  await pages.load('status=photographed', true);
  late({ photos: [photo(1)], next: 'c1' });
  await old;
  expect([ids(pages), pages.next, pages.loading]).toEqual([[7], 'c7', false]);

  let onItsWay;
  filterPhotos.mockImplementationOnce(() => new Promise((resolve) => { onItsWay = resolve; }));
  const more = pages.load('status=photographed', false);
  pages.forget();
  expect([pages.next, pages.loaded, pages.loading]).toEqual([null, false, false]);
  onItsWay({ photos: [photo(8)], next: null });
  await more;
  expect(ids(pages)).toEqual([7]);
  // the old cursor is gone: no next page of the old filter is asked for
  await pages.load('status=photographed&source=official', false);
  expect(filterPhotos).toHaveBeenCalledTimes(3);

  let stopped;
  filterPhotos.mockImplementationOnce(() => new Promise((resolve) => { stopped = resolve; }));
  const last = pages.load('', true);
  pages.stop();
  stopped({ photos: [photo(9)], next: null });
  await last;
  expect(ids(pages)).toEqual([]);
});

test('a failed page says why; one refused as out of date is marked stale, and starting again clears both', async () => {
  const outOfDate = Object.assign(new Error('That page is out of date. Reload the results.'), { code: 'invalid' });
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: 'c1' })
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockRejectedValueOnce(outOfDate)
    .mockResolvedValueOnce({ photos: [photo(1)], next: null });
  const pages = new PhotoPages();
  await pages.load('', true);
  await pages.load('', false);
  expect([pages.error, pages.stale, pages.loading, ids(pages)]).toEqual(['Butterfly Atlas could not reach its service.', false, false, [1]]);
  await pages.load('', false);
  expect([pages.error, pages.stale]).toEqual(['That page is out of date. Reload the results.', true]);
  await pages.load('', true);
  expect([pages.error, pages.stale, ids(pages)]).toEqual(['', false, [1]]);
});

test('a first page that fails is not marked stale', async () => {
  filterPhotos.mockRejectedValueOnce(Object.assign(new Error('Unknown status “x”.'), { code: 'invalid' }));
  const pages = new PhotoPages();
  await pages.load('', true);
  expect([pages.error, pages.stale, pages.loaded]).toEqual(['Unknown status “x”.', false, false]);
});

test('loading again in place asks for as many photos as are loaded, keeping them on screen until all have come', async () => {
  const answers = [];
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' })
    .mockResolvedValueOnce({ photos: [photo(3)], next: 'c2' })
    .mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  const pages = new PhotoPages();
  await pages.load('', true);
  await pages.load('', false);

  const again = pages.reload('');
  expect([ids(pages), pages.loading]).toEqual([[1, 2, 3], true]);
  answers[0]({ photos: [photo(1), photo(4)], next: 'd1' });
  await vi.waitFor(() => expect(answers).toHaveLength(2));
  expect(ids(pages)).toEqual([1, 2, 3]);
  answers[1]({ photos: [photo(2), photo(3)], next: 'd2' });
  await again;
  expect([ids(pages), pages.next, pages.loaded, pages.loading]).toEqual([[1, 4, 2, 3], 'd2', true, false]);
  expect(filterPhotos.mock.calls.map(([, options]) => options.after)).toEqual([null, 'c1', null, 'd1']);
});

test('loading again in place stops at the last page, is dropped for a newer start, and a failure keeps the photos with the reason', async () => {
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1), photo(2)], next: 'c1' })
    .mockResolvedValueOnce({ photos: [photo(1)], next: null });
  const pages = new PhotoPages();
  await pages.load('', true);
  await pages.reload('');
  expect([ids(pages), pages.next]).toEqual([[1], null]);

  let late;
  filterPhotos.mockImplementationOnce(() => new Promise((resolve) => { late = resolve; }))
    .mockResolvedValueOnce({ photos: [photo(9)], next: null });
  const old = pages.reload('');
  await pages.load('status=not', true);
  late({ photos: [photo(5)], next: null });
  await old;
  expect(ids(pages)).toEqual([9]);

  filterPhotos.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  await pages.reload('status=not');
  expect([ids(pages), pages.error, pages.loading]).toEqual([[9], 'Butterfly Atlas could not reach its service.', false]);
});

test('loading again in place with nothing loaded yet starts from the first page', async () => {
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: null });
  const pages = new PhotoPages();
  await pages.reload('');
  expect([ids(pages), pages.loaded]).toEqual([[1], true]);
  expect(filterPhotos).toHaveBeenCalledTimes(1);
});

test('a later page refused as out of date while loading again in place is marked stale', async () => {
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: 'c1' }).mockResolvedValueOnce({ photos: [photo(2)], next: null })
    .mockResolvedValueOnce({ photos: [photo(1)], next: 'd1' })
    .mockRejectedValueOnce(Object.assign(new Error('That page is out of date. Reload the results.'), { code: 'invalid' }));
  const pages = new PhotoPages();
  await pages.load('', true);
  await pages.load('', false);
  await pages.reload('');
  expect([pages.error, pages.stale, ids(pages)]).toEqual(['That page is out of date. Reload the results.', true, [1, 2]]);
});

test('a failed reload keeps the out-of-date mark of the page before it, and says it failed until a load or reload succeeds', async () => {
  const outOfDate = Object.assign(new Error('That page is out of date. Reload the results.'), { code: 'invalid' });
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: 'c1' }).mockRejectedValueOnce(outOfDate)
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockResolvedValueOnce({ photos: [photo(1)], next: null });
  const pages = new PhotoPages();
  await pages.load('', true);
  await pages.load('', false);
  expect([pages.stale, pages.reloadFailed]).toEqual([true, false]);
  await pages.reload('');
  // the cursor is still the old one: asking for it again would be refused again
  expect([pages.error, pages.stale, pages.reloadFailed, pages.next, ids(pages)]).toEqual(['Butterfly Atlas could not reach its service.', true, true, 'c1', [1]]);
  await pages.reload('');
  expect([pages.error, pages.stale, pages.reloadFailed, pages.next]).toEqual(['', false, false, null]);
});

test('a failed reload is no longer one once the photos start again', async () => {
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: null }).mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockResolvedValueOnce({ photos: [photo(2)], next: null });
  const pages = new PhotoPages();
  await pages.load('', true);
  await pages.reload('');
  expect(pages.reloadFailed).toBe(true);
  await pages.load('status=not', true);
  expect([pages.reloadFailed, pages.error, ids(pages)]).toEqual([false, '', [2]]);
});

test('a reload on its way is dropped when the cursor is forgotten or paging stops', async () => {
  const answers = [];
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: null }).mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  const pages = new PhotoPages();
  await pages.load('', true);

  const forgotten = pages.reload('');
  pages.forget();
  answers[0]({ photos: [photo(5)], next: null });
  await forgotten;
  expect([ids(pages), pages.loaded, pages.loading]).toEqual([[1], false, false]);

  const stopped = pages.reload('');
  pages.stop();
  answers[1]({ photos: [photo(6)], next: null });
  await stopped;
  expect(ids(pages)).toEqual([1]);
});

test('no next page is asked for while a reload is on its way', async () => {
  let answer;
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: 'c1' }).mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  const pages = new PhotoPages();
  await pages.load('', true);
  const again = pages.reload('');
  await pages.load('', false);
  expect(filterPhotos).toHaveBeenCalledTimes(2);
  answer({ photos: [photo(1)], next: 'd1' });
  await again;
  expect(pages.next).toBe('d1');
});

test('reloading a filter with no photos keeps "no photos" shown (loaded) until the answer comes', async () => {
  let answer;
  filterPhotos.mockResolvedValueOnce({ photos: [], next: null }).mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  const pages = new PhotoPages();
  await pages.load('', true);
  const again = pages.reload('');
  expect([pages.loaded, pages.loading]).toEqual([true, true]);
  answer({ photos: [photo(3)], next: null });
  await again;
  expect([ids(pages), pages.loaded]).toEqual([[3], true]);
});

test('a next page that comes after a failed reload clears the failure', async () => {
  filterPhotos.mockResolvedValueOnce({ photos: [photo(1)], next: 'c1' }).mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockResolvedValueOnce({ photos: [photo(2)], next: null });
  const pages = new PhotoPages();
  await pages.load('', true);
  await pages.reload('');
  expect(pages.reloadFailed).toBe(true);
  await pages.load('', false);
  expect([pages.reloadFailed, pages.error, ids(pages)]).toEqual([false, '', [1, 2]]);
});
