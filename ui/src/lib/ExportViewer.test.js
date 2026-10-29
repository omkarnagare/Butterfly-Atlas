import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import ExportViewer from './ExportViewer.svelte';

vi.mock('./api.js', () => ({
  displayUrl: (id) => `./api/photos/${id}/display`,
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
}));

const photo = (id, over = {}) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.jpg`, relPath: `images/x/Danaus_chrysippus_0${id}.jpg`, tags: [], width: 4000, height: 3000,
  isCover: false, place: { id: 'P414', path: ['Pune', 'Maharashtra', 'India'], ids: ['P414', 'P20', 'P1'], source: 'gps' },
  species: { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger' }, ...over,
});
const photos = [photo(3, { tags: ['larva'] }), photo(2, { place: null }), photo(1, { taxonId: 'A9', species: { id: 'A9', name: 'Danaus genutia', common: null } })];
const tags = [{ key: 'larva', label: 'larva' }];

beforeEach(() => {
  window.location.hash = '#/export/photo/3?places=P20';
});

function open(over = {}) {
  const onClose = vi.fn();
  const onMore = vi.fn();
  const view = render(ExportViewer, { photos, photoId: 3, query: 'places=P20', total: 312, tags, onClose, onMore, ...over });
  return { ...view, onClose, onMore };
}

test('shows the photo, its species (linked), where it is among the results, its tags, place and file', () => {
  const { container } = open();
  expect(screen.getByRole('dialog', { name: 'Photo viewer: Plain Tiger' })).toBeTruthy();
  expect(container.querySelector('.c').textContent).toBe('1 / 312');
  expect(container.querySelector('img.orig').getAttribute('src')).toBe('./api/photos/3/display');
  expect(screen.getByRole('link', { name: 'Open the species page' }).getAttribute('href')).toBe('#/species/A5');
  expect(screen.getByText('larva')).toBeTruthy();
  expect(screen.getByText('Pune, Maharashtra, India')).toBeTruthy();
  expect(screen.getByText('images/x/Danaus_chrysippus_03.jpg')).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
});

test('arrow keys move through the loaded photos without filling the history; Escape closes', async () => {
  const { onClose, rerender, container } = open();
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/export/photo/2?places=P20');
  await rerender({ photoId: 2 });
  expect(screen.getByText('Unknown place')).toBeTruthy();
  expect(screen.getByText('Untagged')).toBeTruthy();
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  await rerender({ photoId: 1 });
  expect(screen.getByRole('dialog', { name: 'Photo viewer: Danaus genutia' })).toBeTruthy();
  expect(container.querySelector('.c').textContent).toBe('3 / 312');
  await fireEvent.keyDown(window, { key: 'ArrowLeft' });
  expect(window.location.hash).toBe('#/export/photo/2?places=P20');
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();
});

test('near the end of the loaded photos it asks for the next page; past the last loaded one it waits for it', async () => {
  window.location.hash = '#/export/photo/1?places=P20';
  const { onMore } = open({ hasMore: true, photoId: 1 });
  expect(onMore).toHaveBeenCalled();
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/export/photo/1?places=P20');
});

test('without more pages the last photo goes round to the first', async () => {
  open({ photoId: 1, total: 3 });
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/export/photo/3?places=P20');
});

test('asks for the next page once for what is loaded, never while one is loading or has failed', async () => {
  window.location.hash = '#/export/photo/1?places=P20';
  const { onMore, rerender } = open({ hasMore: true, photoId: 1 });
  expect(onMore).toHaveBeenCalledTimes(1);
  await rerender({ loading: true });
  await rerender({ loading: false, error: 'Butterfly Atlas could not reach its service.' });
  await rerender({ error: '' });
  expect(onMore).toHaveBeenCalledTimes(1);
  await rerender({ photos: [...photos, photo(4)] });
  expect(onMore).toHaveBeenCalledTimes(2);
});

test('a failed page shows its message in the viewer; Try again asks again, and a step past the end waits for the page', async () => {
  window.location.hash = '#/export/photo/1?places=P20';
  const onRetry = vi.fn();
  const { rerender } = open({ hasMore: true, photoId: 1, error: 'Butterfly Atlas could not reach its service.', onRetry });
  const dialog = screen.getByRole('dialog');
  expect(dialog.querySelector('[role="alert"]').textContent).toBe('Butterfly Atlas could not reach its service.');
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/export/photo/1?places=P20');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(onRetry).toHaveBeenCalledOnce();
  await rerender({ error: '', loading: true });
  expect(dialog.querySelector('[role="alert"]')).toBeNull();
  await rerender({ loading: false, photos: [...photos, photo(4)], hasMore: false });
  expect(window.location.hash).toBe('#/export/photo/4?places=P20');
});

test('the first photo does not go back round to the last loaded one while more pages are to come', async () => {
  open({ hasMore: true });
  await fireEvent.keyDown(window, { key: 'ArrowLeft' });
  expect(window.location.hash).toBe('#/export/photo/3?places=P20');
});

test('on closing, focus goes to the place the screen names for the photo shown', async () => {
  const tile = document.createElement('a');
  tile.href = '#';
  document.body.append(tile);
  const returnFocus = vi.fn(() => tile);
  const { unmount } = open({ returnFocus });
  unmount();
  expect(returnFocus).toHaveBeenCalledWith(3);
  await vi.waitFor(() => expect(document.activeElement).toBe(tile));
  tile.remove();
});

test('a step past the end asks for the next page itself (after a restart the loaded photos can be as many as before)', async () => {
  window.location.hash = '#/export/photo/1?places=P20';
  const { onMore, rerender } = open({ hasMore: true, photoId: 1 });
  expect(onMore).toHaveBeenCalledTimes(1);
  // the photos were loaded again from the start: as many as before, more to come
  await rerender({ photos: [...photos] });
  expect(onMore).toHaveBeenCalledTimes(1);
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(onMore).toHaveBeenCalledTimes(2);
});

test('after Try again focus goes to Close, not to the page', async () => {
  window.location.hash = '#/export/photo/1?places=P20';
  const onRetry = vi.fn();
  const { rerender } = open({ hasMore: true, photoId: 1, error: 'Butterfly Atlas could not reach its service.', onRetry });
  const retry = screen.getByRole('button', { name: 'Try again' });
  retry.focus();
  await fireEvent.click(retry);
  await rerender({ error: '', loading: true });
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
});

test('a RAW photo is turned upright and says what it shows; a broken one says why at once (Phase 7a)', async () => {
  const raw = [photo(3, { fileType: 'raw', width: 1616, height: 1080, rawPreview: { width: 1616, height: 1080, orientation: 3 } }), photo(2, { fileType: 'raw', rawPreview: null, unreadable: true })];
  const { container } = open({ photos: raw });
  expect(container.querySelector('img.orig').style.transform).toBe('rotate(180deg)');
  expect(screen.getByText('RAW preview, 1616 × 1080')).toBeTruthy();
  const second = open({ photos: raw, photoId: 2 });
  expect(second.container.querySelector('img.orig')).toBeNull();
  expect(second.container.querySelector('.zp-note').textContent).toBe("This RAW photo's preview can't be read any more. The file may have changed or be damaged. Add it again from your camera's card, or save it as JPEG from your RAW software and add that.");
});

test('a JPEG that can\'t be shown says why and what to do at once (review fix U2)', () => {
  const { container } = open({ photos: [photo(2, { fileType: 'jpeg', rawPreview: null, unreadable: true })], photoId: 2 });
  expect(container.querySelector('img.orig')).toBeNull();
  expect(container.querySelector('.zp-note').textContent).toMatch(/^This photo can't be shown: its file may be damaged/);
});

test('the title always links to the photo\'s species, replacing the viewer\'s address', async () => {
  open({ photoId: 1 });

  const link = screen.getByRole('link', { name: 'Danaus genutia' });
  expect(link.getAttribute('href')).toBe('#/species/A9');
  expect(link.getAttribute('title')).toBe('Open Danaus genutia');
  await fireEvent.click(link);

  expect(window.location.hash).toBe('#/species/A9');
});
