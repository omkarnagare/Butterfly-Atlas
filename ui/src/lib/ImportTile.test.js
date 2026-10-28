import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import ImportTile from './ImportTile.svelte';
import { resetStatus, status } from './status.svelte.js';

afterEach(() => {
  vi.useRealTimers();
  resetStatus();
});

const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }]]);
const item = (extra = {}) => ({
  id: 812, path: 'Sinhagad/IMG_0412.JPG', folder: 'Sinhagad', name: 'IMG_0412.JPG', size: 1, modifiedAt: '2024-08-12T10:00:00Z',
  capturedAt: null, state: 'read', problem: null, suggestedTaxonId: null, libraryTaxonId: null, copyOf: null, taxonId: null,
  tags: [], skipped: false, outcome: null, imageId: null, error: null, v: 'a1b2', place: null, ...extra,
});
const handlers = () => ({ onClick: vi.fn(), onOpen: vi.fn(), onKey: vi.fn(), onFocus: vi.fn() });

test('a read photo shows its preview and its suggestion, and is a selectable cell', () => {
  const place = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  const { container } = render(ImportTile, { importId: 3, item: item({ suggestedTaxonId: 'A5', libraryTaxonId: 'A5', place }), taxa, selected: true, tabbable: true, ...handlers() });

  const tile = screen.getByRole('gridcell', { name: 'IMG_0412.JPG, Suggested: Plain Tiger, Pune, already in your atlas as Plain Tiger' });
  expect(tile.getAttribute('aria-selected')).toBe('true');
  expect(tile.getAttribute('tabindex')).toBe('0');
  expect(tile.classList.contains('k-suggested')).toBe(true);
  expect(container.querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2');
  expect(container.querySelector('.it-l').textContent).toBe('Suggested: Plain Tiger');
  expect(container.querySelector('.it-b').textContent.trim()).toBe('In library');
  expect(container.querySelector('.it-p').textContent).toBe('Pune');
  expect(container.querySelector('.it-p').getAttribute('aria-hidden')).toBe('true');
});

test('a place that became former: its label is labelled, and the tile says to choose its new place, or file as it is', () => {
  const place = { id: 'P300', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], source: 'user', former: true };
  const { container } = render(ImportTile, { importId: 3, item: item({ taxonId: 'A5', place }), taxa, ...handlers() });
  expect(screen.getByRole('gridcell', { name: 'IMG_0412.JPG, Plain Tiger, Chittoor (former district) — choose its new place, or file as it is' })).toBeTruthy();
  const label = container.querySelector('.it-p');
  expect(label.textContent).toBe('Chittoor (former district)');
  expect(label.getAttribute('title')).toBe('Chittoor (former district) — choose its new place, or file as it is.');
  expect(label.classList.contains('former')).toBe(true);
});

test('a photo without a place shows no place label', () => {
  const { container } = render(ImportTile, { importId: 3, item: item({ taxonId: 'A5' }), taxa, ...handlers() });
  expect(container.querySelector('.it-p')).toBeNull();
  expect(container.querySelector('.it-l').textContent).toBe('Plain Tiger');
});

test('asks for no preview before the photo is read, and shows a problem\'s reason instead of a picture', async () => {
  const { container, rerender } = render(ImportTile, { importId: 3, item: item({ state: 'pending', v: null }), taxa, ...handlers() });
  expect(container.querySelector('img')).toBeNull();
  expect(screen.getByRole('gridcell').classList.contains('waiting')).toBe(true);
  expect(screen.getByRole('gridcell').getAttribute('tabindex')).toBe('-1');

  await rerender({ importId: 3, item: item({ state: 'unsupported', problem: "AVIF photos aren't supported yet.", v: null }), taxa, ...handlers() });
  expect(container.querySelector('img')).toBeNull();
  expect(container.querySelector('.it-why').textContent).toBe("AVIF photos aren't supported yet.");
  expect(screen.getByRole('gridcell').classList.contains('waiting')).toBe(false);

  await rerender({ importId: 3, item: item({ taxonId: 'A5' }), taxa, ...handlers() });
  expect(container.querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2');
  expect(container.querySelector('.it-l').textContent).toBe('Plain Tiger');
});

test('a preview still being made is asked for again, and the tile shimmers until it arrives', async () => {
  vi.useFakeTimers();
  const { container } = render(ImportTile, { importId: 3, item: item(), taxa, ...handlers() });
  const img = container.querySelector('img');
  expect(screen.getByRole('gridcell').classList.contains('waiting')).toBe(true);

  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  await fireEvent.load(img);
  await vi.advanceTimersByTimeAsync(2000);
  expect(img.getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2&r=1');

  Object.defineProperty(img, 'naturalWidth', { value: 480, configurable: true });
  await fireEvent.load(img);
  expect(screen.getByRole('gridcell').classList.contains('waiting')).toBe(false);
});

test('passes clicks, double-clicks, keys and focus to the grid; Space never clicks', async () => {
  const h = handlers();
  render(ImportTile, { importId: 3, item: item(), taxa, ...h });
  const tile = screen.getByRole('gridcell');

  await fireEvent.click(tile, { shiftKey: true });
  expect(h.onClick).toHaveBeenCalledWith(expect.objectContaining({ id: 812 }), expect.objectContaining({ shiftKey: true }));
  await fireEvent.dblClick(tile);
  expect(h.onOpen).toHaveBeenCalledWith(expect.objectContaining({ id: 812 }));
  await fireEvent.keyDown(tile, { key: 'ArrowRight' });
  expect(h.onKey).toHaveBeenCalledWith(expect.objectContaining({ id: 812 }), expect.objectContaining({ key: 'ArrowRight' }));
  await expect(fireEvent.keyUp(tile, { key: ' ' })).resolves.toBe(false);
  tile.focus();
  expect(h.onFocus).toHaveBeenCalled();
});

test('a filed photo is a cell that cannot be selected, and only a read photo asks for a preview', () => {
  const { container, unmount } = render(ImportTile, { importId: 3, item: item({ taxonId: 'A5', outcome: 'filed', error: 'Already in your atlas.' }), taxa, ...handlers() });
  expect(screen.getByRole('gridcell', { name: 'IMG_0412.JPG, Already in your atlas.' }).hasAttribute('aria-selected')).toBe(false);
  expect(container.querySelector('img')).not.toBeNull();
  unmount();

  const missing = render(ImportTile, { importId: 3, item: item({ state: 'missing', taxonId: 'A5', outcome: 'failed', v: 'a1b2' }), taxa, ...handlers() });
  expect(missing.container.querySelector('img')).toBeNull();
});

test('a RAW photo carries a RAW badge, a RAW + JPEG pair one badge for both (Phase 7a)', async () => {
  const raw = { name: 'DSC_1.NEF', fileType: 'raw', rawPreview: { width: 6000, height: 4000, orientation: 1 }, pairId: null };
  const { container, rerender } = render(ImportTile, { importId: 3, item: item(raw), taxa, ...handlers() });
  expect(container.querySelector('.it-b').textContent.trim()).toBe('RAW');

  const pair = item({ fileType: 'jpeg', pairId: 813, pairOf: item({ id: 813, ...raw, pairId: 812 }) });
  await rerender({ importId: 3, item: pair, taxa, ...handlers() });
  expect(container.querySelector('.it-b').textContent.trim()).toBe('RAW + JPEG');
  // the JPEG's preview stands for the pair
  expect(container.querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2');
  expect(screen.getByRole('gridcell').getAttribute('aria-label')).toBe('IMG_0412.JPG, RAW + JPEG');
});

test('a pair filed as JPEG only or RAW only says so in visible words: the half filed, and the half left out (review fix U4)', async () => {
  const raw = { name: 'DSC_1.NEF', fileType: 'raw', rawPreview: { width: 6000, height: 4000, orientation: 1 } };
  const jpegLeft = 'The JPEG of this pair was not filed: you chose RAW only.';
  const rawLeft = 'The RAW of this pair was not filed: you chose JPEG only.';
  const jpegOnly = item({ fileType: 'jpeg', taxonId: 'A5', outcome: 'filed', pairId: 813,
    pairOf: item({ id: 813, ...raw, taxonId: 'A5', outcome: 'pair_left', error: rawLeft, pairId: 812 }) });
  const { container, rerender } = render(ImportTile, { importId: 3, item: jpegOnly, taxa, ...handlers() });
  expect(container.querySelector('.it-b').textContent.trim()).toBe('JPEG filed');
  expect(container.querySelector('.it-lo').textContent).toBe('RAW not filed');
  expect(screen.getByRole('gridcell').getAttribute('aria-label')).toContain(rawLeft);

  const rawOnly = item({ id: 813, ...raw, taxonId: 'A5', outcome: 'filed', pairId: 812,
    pairOf: item({ fileType: 'jpeg', taxonId: 'A5', outcome: 'pair_left', error: jpegLeft, pairId: 813 }) });
  await rerender({ importId: 3, item: rawOnly, taxa, ...handlers() });
  expect(container.querySelector('.it-b').textContent.trim()).toBe('RAW filed');
  expect(container.querySelector('.it-lo').textContent).toBe('JPEG not filed');

  // filed as Both, or not filed yet: one badge for the pair, no left-out line
  const both = item({ fileType: 'jpeg', taxonId: 'A5', outcome: 'filed', pairId: 813, pairOf: item({ id: 813, ...raw, taxonId: 'A5', outcome: 'filed', pairId: 812 }) });
  await rerender({ importId: 3, item: both, taxa, ...handlers() });
  expect(container.querySelector('.it-b').textContent.trim()).toBe('RAW + JPEG');
  expect(container.querySelector('.it-lo')).toBeNull();
});

const heicItem = (extra = {}) => item({ name: 'IMG_0042.HEIC', path: 'Sinhagad/IMG_0042.HEIC', fileType: 'heic', heic: { tenBit: false, auxiliary: false }, ...extra });

test('a HEIC photo carries a HEIC badge; without a decoder it says it can\'t be shown and asks for no preview (Phase 7b)', async () => {
  status.heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  const { container } = render(ImportTile, { importId: 3, item: heicItem({ suggestedTaxonId: 'A5' }), taxa, ...handlers() });
  expect(container.querySelector('.it-b').textContent.trim()).toBe('HEIC');
  expect(container.querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2');

  status.heic = { state: 'missing', decoder: null, system: 'windows', reason: 'absent' };
  await vi.waitFor(() => expect(container.querySelector('img')).toBeNull());
  expect(container.querySelector('.cant').textContent).toBe("Can't show this photo");
  // Still a photo to assign: its suggestion and selection stay.
  expect(container.querySelector('.it-l').textContent).toBe('Suggested: Plain Tiger');
  const tile = screen.getByRole('gridcell');
  expect(tile.getAttribute('aria-label')).toBe("IMG_0042.HEIC, Suggested: Plain Tiger, can't show this photo");
  expect(tile.getAttribute('aria-selected')).toBe('false');
  expect(tile.classList.contains('waiting')).toBe(false);
});

test('a photo whose preview failed says it can\'t be shown, whatever its format (audit 6)', () => {
  const { container } = render(ImportTile, { importId: 3, item: item({ previewFailed: true }), taxa, ...handlers() });
  expect(container.querySelector('img')).toBeNull();
  expect(container.querySelector('.cant').textContent).toBe("Can't show this photo");
});

test('a RAW + HEIC pair shows the RAW\'s preview while the computer can\'t show HEIC photos (audit 15)', async () => {
  const raw = { name: 'IMG_0042.CR3', fileType: 'raw', rawPreview: { width: 6000, height: 4000, orientation: 1 } };
  const pair = heicItem({ pairId: 813, pairOf: item({ id: 813, ...raw, pairId: 812, v: 'r1' }) });
  status.heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  const { container } = render(ImportTile, { importId: 3, item: pair, taxa, ...handlers() });
  expect(container.querySelector('.it-b').textContent.trim()).toBe('RAW + HEIC');
  expect(container.querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2');

  status.heic = { state: 'missing', decoder: null, system: 'linux', reason: 'absent' };
  await vi.waitFor(() => expect(container.querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/813/preview?v=r1'));
  expect(container.querySelector('.cant')).toBeNull();
  expect(container.querySelector('.it-b').textContent.trim()).toBe('RAW + HEIC');
});

test('a HEIC preview answered as pending (a decode that timed out) keeps being asked for, and only previewFailed stops it (review fix U1)', async () => {
  vi.useFakeTimers();
  status.heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  const { container, rerender } = render(ImportTile, { importId: 3, item: heicItem(), taxa, ...handlers() });
  const img = container.querySelector('img');
  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  for (const wait of [2000, 4000, 8000, 16000, 32000]) {
    await fireEvent.load(img);
    await vi.advanceTimersByTimeAsync(wait);
  }
  await fireEvent.load(img);
  expect(img.getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2&r=5');
  // Still pending, still shimmering: asked again every 30 s.
  expect(screen.getByRole('gridcell').classList.contains('waiting')).toBe(true);
  await vi.advanceTimersByTimeAsync(30000);
  expect(img.getAttribute('src')).toBe('./api/imports/3/items/812/preview?v=a1b2&r=6');

  await rerender({ importId: 3, item: heicItem({ previewFailed: true }), taxa, ...handlers() });
  expect(container.querySelector('img')).toBeNull();
  expect(container.querySelector('.cant').textContent).toBe("Can't show this photo");
});
