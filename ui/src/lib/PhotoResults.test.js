import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import PhotoResults from './PhotoResults.svelte';

vi.mock('./api.js', () => ({ photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1` }));

const photo = (id) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_${id}.jpg`, relPath: `images/x/Danaus_chrysippus_${id}.jpg`, tags: [], width: 4000, height: 3000,
  isCover: false, place: null, species: { id: 'A5', name: 'Danaus chrysippus', common: id % 2 ? 'Plain Tiger' : null },
});
const many = (n) => Array.from({ length: n }, (_, i) => photo(i + 1));
const tiles = () => screen.getAllByRole('link');

test('a grid of thumbnails, only the rows near the view in the page; a tile opens the photo over the results', () => {
  render(PhotoResults, { photos: many(1000), query: 'places=P20', onMore: vi.fn() });
  expect(tiles().length).toBeGreaterThan(10);
  expect(tiles().length).toBeLessThan(200);
  const first = tiles()[0];
  expect(first.getAttribute('href')).toBe('#/export/photo/1?places=P20');
  expect(first.getAttribute('aria-label')).toBe('Open Plain Tiger, Danaus_chrysippus_1.jpg');
  expect(tiles()[1].getAttribute('aria-label')).toBe('Open Danaus chrysippus, Danaus_chrysippus_2.jpg');
  expect(first.querySelector('img').getAttribute('src')).toBe('./api/photos/1/thumb?v=1');
  // the species name shows on hover and focus
  expect(first.querySelector('.fcap').textContent).toBe('Plain Tiger');
});

test('asks for the next page when its end comes near, once at a time, and not on the last page', async () => {
  const onMore = vi.fn();
  const { rerender } = render(PhotoResults, { photos: many(30), query: '', hasMore: true, onMore });
  await vi.waitFor(() => expect(onMore).toHaveBeenCalledOnce());
  await rerender({ loading: true });
  expect(screen.getByRole('status').textContent).toBe('Loading more photos…');
  await rerender({ photos: many(60), loading: true });
  expect(onMore).toHaveBeenCalledOnce();
  await rerender({ photos: many(60), loading: false, hasMore: false });
  expect(onMore).toHaveBeenCalledOnce();
});

test('a page that failed shows why, and Try again asks for it again', async () => {
  const onMore = vi.fn();
  render(PhotoResults, { photos: many(3), query: '', hasMore: true, loading: false, error: 'That page is out of date. Reload the results.', onMore });
  expect(screen.getByRole('alert').textContent).toBe('That page is out of date. Reload the results.');
  expect(onMore).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(onMore).toHaveBeenCalledOnce();
});

test('arrow keys move through the grid by tile and by row; Home and End go to the ends', async () => {
  render(PhotoResults, { photos: many(40), query: '', onMore: vi.fn() });
  const first = tiles()[0];
  expect(first.tabIndex).toBe(0);
  first.focus();
  await fireEvent.keyDown(first, { key: 'ArrowRight' });
  expect(document.activeElement.dataset.photoId).toBe('2');
  // 1140 px (no layout in tests) fits 7 tiles a row
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
  expect(document.activeElement.dataset.photoId).toBe('9');
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowLeft' });
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
  expect(document.activeElement.dataset.photoId).toBe('1');
  await fireEvent.keyDown(document.activeElement, { key: 'End' });
  expect(document.activeElement.dataset.photoId).toBe('40');
  await fireEvent.keyDown(document.activeElement, { key: 'Home' });
  expect(document.activeElement.dataset.photoId).toBe('1');
});

test('the photo open in the viewer stays in the page, and is the tile in the tab order', () => {
  render(PhotoResults, { photos: many(1000), query: '', focusId: 990, onMore: vi.fn() });
  const kept = document.querySelector('[data-photo-id="990"]');
  expect(kept).not.toBeNull();
  expect(kept.tabIndex).toBe(0);
});

test('each tile says where it is among the photos (the size unknown while more pages are to come)', async () => {
  const { rerender } = render(PhotoResults, { photos: many(40), query: '', hasMore: true, onMore: vi.fn(), loading: true });
  const items = () => screen.getAllByRole('listitem');
  expect(items()[0].getAttribute('aria-posinset')).toBe('1');
  expect(items()[1].getAttribute('aria-posinset')).toBe('2');
  expect(items()[0].getAttribute('aria-setsize')).toBe('-1');
  await rerender({ hasMore: false, loading: false });
  expect(items()[0].getAttribute('aria-setsize')).toBe('40');
});

test('focus stays on the focused tile when the number of columns changes', async () => {
  const { container } = render(PhotoResults, { photos: many(40), query: '', onMore: vi.fn() });
  const grid = container.querySelector('.fgrid');
  container.querySelector('[data-photo-id="9"]').focus();
  Object.defineProperty(grid, 'clientWidth', { value: 490, configurable: true });
  await fireEvent(window, new Event('resize'));
  await vi.waitFor(() => expect(document.activeElement.dataset.photoId).toBe('9'));
});

test('a tile shimmers until its real thumbnail has arrived (one still being made is asked for again)', async () => {
  const { container } = render(PhotoResults, { photos: many(3), query: '', onMore: vi.fn() });
  const tile = container.querySelector('[data-photo-id="1"]');
  const img = tile.querySelector('img');
  expect(tile.classList.contains('waiting')).toBe(true);
  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  await fireEvent.load(img);
  expect(tile.classList.contains('waiting')).toBe(true);
  Object.defineProperty(img, 'naturalWidth', { value: 480, configurable: true });
  await fireEvent.load(img);
  expect(tile.classList.contains('waiting')).toBe(false);
});

test('RAW photos carry a RAW badge, and a photo whose thumbnail cannot be made says so (Phase 7a)', () => {
  const photos = [{ ...photo(1), fileType: 'raw', rawPreview: { width: 4, height: 3, orientation: 1 } }, { ...photo(2), fileType: 'png', unreadable: true }];
  render(PhotoResults, { photos, query: '', onMore: vi.fn() });
  const [first, second] = tiles();
  expect(first.querySelector('.raw').textContent).toBe('RAW');
  expect(second.querySelector('.raw')).toBeNull();
  expect(second.querySelector('img')).toBeNull();
  expect(second.querySelector('.cant').textContent).toBe("Can't show this photo");
  expect(second.classList.contains('waiting')).toBe(false);
  expect(second.getAttribute('aria-label')).toBe("Open Danaus chrysippus, Danaus_chrysippus_2.jpg, can't show this photo");
});
