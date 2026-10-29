import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import PhotoTile from './PhotoTile.svelte';

const photo = {
  id: 12, taxonId: 'A5', fileName: 'Danaus_chrysippus_01.jpg', relPath: 'images/x/Danaus_chrysippus_01.jpg',
  tags: ['larva', 'upper_wing'], addedAt: '2026-10-11T09:00:00Z', v: 1791700000000, width: 4000, height: 3000, isCover: false,
  place: { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' },
};
afterEach(() => vi.useRealTimers());

const label = (key) => ({ larva: 'larva', upper_wing: 'upper_wing' })[key];

test('links to the viewer with a lazy thumbnail, its tags and its proportions', () => {
  const { container } = render(PhotoTile, { photo, label, fresh: true });

  const link = screen.getByRole('link', { name: 'Open Danaus_chrysippus_01.jpg, larva, upper_wing' });
  expect(link.getAttribute('href')).toBe('#/species/A5/photo/12');
  expect(link.getAttribute('style')).toContain('aspect-ratio: 4000 / 3000');
  const img = container.querySelector('img');
  expect(img.getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700000000');
  expect(img.getAttribute('loading')).toBe('lazy');
  expect([...container.querySelectorAll('.tt span')].map((s) => s.textContent)).toEqual(['larva', 'upper_wing']);
  expect(screen.getByText('New')).toBeTruthy();
  expect(container.textContent).not.toContain('Pune'); // the place is shown in the viewer, not on the tile
});

test('while selecting it is a toggle', async () => {
  const onToggle = vi.fn();
  render(PhotoTile, { photo: { ...photo, tags: [] }, label, selecting: true, selected: true, onToggle });

  const toggle = screen.getByRole('button', { name: 'Select Danaus_chrysippus_01.jpg' });
  expect(toggle.getAttribute('aria-pressed')).toBe('true');
  await fireEvent.click(toggle);
  expect(onToggle).toHaveBeenCalledWith(expect.objectContaining({ id: 12 }));
  expect(screen.queryByRole('link')).toBeNull();
});

test('asks again for a thumbnail that was still being made, and copes without a size', async () => {
  vi.useFakeTimers();
  const { container } = render(PhotoTile, { photo: { ...photo, width: null, height: null }, label });
  const img = container.querySelector('img');
  expect(container.querySelector('a').getAttribute('style')).toContain('aspect-ratio: 4 / 3');

  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  await fireEvent.load(img);
  await vi.advanceTimersByTimeAsync(2000);

  expect(img.getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700000000&r=1');
});

test('shimmers until its real thumbnail has arrived, and passes the gallery filter on to the viewer', async () => {
  const { container } = render(PhotoTile, { photo, label, tag: 'larva' });
  const tile = container.querySelector('a');
  const img = container.querySelector('img');
  expect(tile.getAttribute('href')).toBe('#/species/A5/photo/12?tag=larva');
  expect(tile.classList.contains('waiting')).toBe(true);

  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  await fireEvent.load(img);
  expect(tile.classList.contains('waiting')).toBe(true);

  Object.defineProperty(img, 'naturalWidth', { value: 480, configurable: true });
  await fireEvent.load(img);
  expect(tile.classList.contains('waiting')).toBe(false);
});

test('a rerender with other tags keeps the retry address; a new version starts afresh', async () => {
  vi.useFakeTimers();
  const { container, rerender } = render(PhotoTile, { photo, label });
  const img = container.querySelector('img');
  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  await fireEvent.load(img);
  await vi.advanceTimersByTimeAsync(2000);
  expect(img.getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700000000&r=1');

  await rerender({ photo: { ...photo, tags: ['larva'] }, label });
  expect(container.querySelector('img').getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700000000&r=1');

  await rerender({ photo: { ...photo, v: 1791700009999 }, label });
  expect(container.querySelector('img').getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700009999');
});

test('passes the place filter on to the viewer with the tag', () => {
  const { container } = render(PhotoTile, { photo, label, tag: 'larva', place: 'unknown' });
  expect(container.querySelector('a').getAttribute('href')).toBe('#/species/A5/photo/12?tag=larva&place=unknown');
});

test('a RAW photo carries a RAW badge; other formats none (Phase 7a)', () => {
  const { container, rerender } = render(PhotoTile, { photo: { ...photo, fileName: 'Danaus_chrysippus_01.nef', fileType: 'raw', rawPreview: { width: 4000, height: 3000, orientation: 1 } }, label });
  expect(container.querySelector('.raw').textContent).toBe('RAW');
  rerender({ photo: { ...photo, fileType: 'jpeg', rawPreview: null }, label });
  expect(container.querySelector('.raw')).toBeNull();
});

test('a HEIC photo carries a HEIC badge (Phase 7b)', () => {
  const { container } = render(PhotoTile, { photo: { ...photo, fileName: 'Danaus_chrysippus_01.heic', fileType: 'heic', heic: { tenBit: false, auxiliary: false } }, label });
  expect(container.querySelector('.tb').textContent).toBe('HEIC');
});

test('a photo whose thumbnail cannot be made says so instead of a grey tile, whatever its format', () => {
  const { container } = render(PhotoTile, { photo: { ...photo, fileType: 'tiff', unreadable: true }, label });
  const link = screen.getByRole('link', { name: "Open Danaus_chrysippus_01.jpg, larva, upper_wing, can't show this photo" });
  expect(link.querySelector('img')).toBeNull();
  expect(link.classList.contains('waiting')).toBe(false);
  expect(container.querySelector('.cant').textContent).toBe("Can't show this photo");
});
