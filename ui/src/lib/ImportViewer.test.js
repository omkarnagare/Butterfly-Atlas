import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';

afterEach(() => {
  vi.restoreAllMocks();
  resetStatus();
});
import ImportViewer from './ImportViewer.svelte';
import { resetStatus, status } from './status.svelte.js';

const taxa = new Map([['A5', { name: 'Danaus chrysippus', common: 'Plain Tiger' }]]);
const item = (id, extra = {}) => ({
  id, path: `Sinhagad/IMG_${id}.JPG`, folder: 'Sinhagad', name: `IMG_${id}.JPG`, size: 1, modifiedAt: '2024-08-12T10:00:00Z',
  capturedAt: '2024-08-12T10:22:00Z', state: 'read', problem: null, suggestedTaxonId: null, libraryTaxonId: null, copyOf: null,
  taxonId: null, tags: [], skipped: false, outcome: null, imageId: null, error: null, v: 'v1', place: null, ...extra,
});
const pune = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
const items = new Map([[1, item(1, { taxonId: 'A5', place: pune })], [2, item(2)], [3, item(3, { state: 'unsupported', problem: "AVIF photos aren't supported yet.", v: null })]]);

test('shows the source photo over its preview, with what is known about it', () => {
  const { container } = render(ImportViewer, { importId: 3, items, taxa, order: [1, 2, 3], itemId: 1, onShow: vi.fn(), onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Photo: IMG_1.JPG' })).toBeTruthy();
  expect(container.querySelector('.orig').getAttribute('src')).toBe('./api/imports/3/items/1/display?v=v1');
  expect(container.querySelector('.ph').getAttribute('src')).toBe('./api/imports/3/items/1/preview?v=v1');
  expect(screen.getByRole('complementary', { name: 'About this photo' }).textContent).toContain('Plain Tiger');
  expect(screen.getByText('Sinhagad/IMG_1.JPG')).toBeTruthy();
  expect(screen.getByText('1 / 3')).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
  expect(screen.getByRole('complementary', { name: 'About this photo' }).textContent).toContain('PlacePune, Maharashtra, India');
});

test('a place that became former: "Chittoor (former district) — choose its new place, or file as it is."', () => {
  const chittoor = { id: 'P300', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], source: 'user', former: true };
  render(ImportViewer, { importId: 3, items: new Map([[1, item(1, { place: chittoor })]]), taxa, order: [1], itemId: 1, onShow: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('complementary', { name: 'About this photo' }).textContent)
    .toContain('PlaceChittoor (former district) — choose its new place, or file as it is.');
});

test('a photo without a place says so', () => {
  render(ImportViewer, { importId: 3, items, taxa, order: [1, 2, 3], itemId: 2, onShow: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('complementary', { name: 'About this photo' }).textContent).toContain('PlaceUnknown place');
  // as the photo viewers say it
  expect(screen.getByText('Unknown place').classList.contains('muted')).toBe(true);
});

test('arrows page through the photos the grid shows, round the ends; Escape closes', async () => {
  const onShow = vi.fn();
  const onClose = vi.fn();
  render(ImportViewer, { importId: 3, items, taxa, order: [1, 2, 3], itemId: 1, onShow, onClose });

  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(onShow).toHaveBeenLastCalledWith(2);
  await fireEvent.keyDown(window, { key: 'ArrowLeft' });
  expect(onShow).toHaveBeenLastCalledWith(3);
  await fireEvent.click(screen.getByRole('button', { name: 'Next photo' }));
  expect(onShow).toHaveBeenLastCalledWith(2);
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();
});

test('a photo that can\'t be read says why instead of showing a picture', () => {
  const { container } = render(ImportViewer, { importId: 3, items, taxa, order: [3], itemId: 3, onShow: vi.fn(), onClose: vi.fn() });

  expect(container.querySelector('.zp')).toBeNull();
  expect(container.querySelector('.lb-why').textContent).toBe("AVIF photos aren't supported yet.");
  expect(screen.queryByRole('button', { name: 'Next photo' })).toBeNull();
});

test('sizes the photo from the item before the photo has loaded', () => {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockImplementation(function () { return this.classList.contains('zp') ? 800 : 0; });
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockImplementation(function () { return this.classList.contains('zp') ? 600 : 0; });
  const sized = new Map([[1, item(1, { width: 4000, height: 2000 })]]);
  const { container, unmount } = render(ImportViewer, { importId: 3, items: sized, taxa, order: [1], itemId: 1, onShow: vi.fn(), onClose: vi.fn() });
  expect(container.querySelector('.zp-photo').getAttribute('style')).toContain('width: 800px; height: 400px');
  unmount();

  // without a size (an older answer) it fits 4:3 until the photo says otherwise
  const unsized = new Map([[1, item(1, { width: null, height: null })]]);
  const again = render(ImportViewer, { importId: 3, items: unsized, taxa, order: [1], itemId: 1, onShow: vi.fn(), onClose: vi.fn() });
  expect(again.container.querySelector('.zp-photo').getAttribute('style')).toContain('width: 800px; height: 600px');
});

test('a photo not read yet says so, whatever species it has; one gone from the folder says that', () => {
  const waiting = new Map([[1, item(1, { state: 'pending', v: null, taxonId: 'A5' })], [2, item(2, { state: 'missing', taxonId: 'A5' })]]);
  const { container, rerender } = render(ImportViewer, { importId: 3, items: waiting, taxa, order: [1, 2], itemId: 1, onShow: vi.fn(), onClose: vi.fn() });
  expect(container.querySelector('.lb-why').textContent).toBe("This photo hasn't been read yet.");
  rerender({ itemId: 2 });
  return Promise.resolve().then(() => {
    expect(container.querySelector('.lb-why').textContent).toBe("This photo isn't in the folder any more.");
  });
});

test('says it is loading the photo until it arrives, and explains a photo that can no longer be opened', async () => {
  vi.useFakeTimers();
  const { container } = render(ImportViewer, { importId: 3, items, taxa, order: [1], itemId: 1, onShow: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('status').textContent).toBe('Loading the photo…');
  const orig = container.querySelector('.orig');
  for (let k = 0; k < 3; k++) {
    await fireEvent.error(container.querySelector('.orig'));
    await vi.advanceTimersByTimeAsync(6000);
  }
  expect(container.querySelector('.orig')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe("This photo can't be opened. It may have been moved or deleted from the folder since it was read; showing its preview.");
  expect(orig).toBeTruthy();
  vi.useRealTimers();
});

test('a RAW item is turned upright; one that fails to open keeps the import\'s own note (Phase 7a, review fix U5)', async () => {
  const raw = item(4, { name: 'DSC_4.NEF', path: 'Sinhagad/DSC_4.NEF', fileType: 'raw', width: 4000, height: 6000, rawPreview: { width: 4000, height: 6000, orientation: 6 }, pairId: null });
  const { container } = render(ImportViewer, { importId: 3, items: new Map([[4, raw]]), taxa, order: [4], itemId: 4, onShow: vi.fn(), onClose: vi.fn() });
  const original = container.querySelector('img.orig');
  expect(original.style.transform).toContain('rotate(90deg)');
  expect(screen.getByText('RAW preview, 4000 × 6000')).toBeTruthy();

  vi.useFakeTimers();
  for (let k = 0; k < 3; k += 1) {
    await fireEvent.error(container.querySelector('img.orig'));
    await vi.advanceTimersByTimeAsync(2000 * (k + 1));
  }
  vi.useRealTimers();
  expect(container.querySelector('.zp-note').textContent).toBe("This photo can't be opened. It may have been moved or deleted from the folder since it was read; showing its preview.");
});

test('a RAW item whose preview can\'t be read says so at once (review fix U5)', () => {
  const raw = item(4, { name: 'DSC_4.NEF', path: 'Sinhagad/DSC_4.NEF', fileType: 'raw', rawPreview: null, pairId: null });
  const { container } = render(ImportViewer, { importId: 3, items: new Map([[4, raw]]), taxa, order: [4], itemId: 4, onShow: vi.fn(), onClose: vi.fn() });
  expect(container.querySelector('img.orig')).toBeNull();
  expect(container.querySelector('.zp-note').textContent).toBe("This RAW photo's preview can't be read any more. The file may have changed or be damaged. Add it again from your camera's card, or save it as JPEG from your RAW software and add that.");
});

test('a pair\'s half left out by the pair choice is named under the status (Phase 7a)', () => {
  const left = 'The RAW of this pair was not filed: you chose JPEG only.';
  const both = new Map([
    [5, item(5, { taxonId: 'A5', outcome: 'filed', fileType: 'jpeg', pairId: 6 })],
    [6, item(6, { name: 'IMG_6.NEF', taxonId: 'A5', outcome: 'pair_left', error: left, fileType: 'raw', pairId: 5, rawPreview: { width: 6000, height: 4000, orientation: 1 } })],
  ]);
  render(ImportViewer, { importId: 3, items: both, taxa, order: [5], itemId: 5, onShow: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('complementary', { name: 'About this photo' }).textContent).toContain(left);
});

const heicItem = (id, extra = {}) => item(id, { name: `IMG_${id}.HEIC`, path: `Sinhagad/IMG_${id}.HEIC`, fileType: 'heic', width: 4032, height: 3024,
  heic: { tenBit: false, auxiliary: true }, ...extra });

test('a HEIC item says the install note at once without a decoder, and its lines (Phase 7b)', () => {
  status.heic = { state: 'missing', decoder: null, system: 'mac', reason: 'failed' };
  const { container } = render(ImportViewer, { importId: 3, items: new Map([[7, heicItem(7)]]), taxa, order: [7], itemId: 7, onShow: vi.fn(), onClose: vi.fn() });
  expect(container.querySelector('.orig')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe(
    "This Mac couldn't show HEIC photos: its built-in sips tool didn't work. Choose Check again in Settings → About; if it keeps failing, save the photo as JPEG and add that.");
  expect(screen.getByText('HEIC, 4032 × 3024')).toBeTruthy();
  expect(screen.getByText("Shows the main picture. The depth map and other extra images stay in the file but aren't shown.")).toBeTruthy();
});

test('a HEIC item whose preview failed says the decode note; one that can be shown loads (audit 6, 7)', async () => {
  status.heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  const list = new Map([[7, heicItem(7, { previewFailed: true })], [8, heicItem(8)]]);
  const { container } = render(ImportViewer, { importId: 3, items: list, taxa, order: [7, 8], itemId: 7, onShow: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('status').textContent).toBe(
    "This HEIC photo can't be shown: the device's decoder couldn't read it. The file may be damaged, or use a HEIC feature the decoder doesn't support. Save it as JPEG on the phone or camera it came from and add that.");
  const again = render(ImportViewer, { importId: 3, items: list, taxa, order: [7, 8], itemId: 8, onShow: vi.fn(), onClose: vi.fn() });
  expect(again.container.querySelector('.orig').getAttribute('src')).toBe('./api/imports/3/items/8/display?v=v1');
  expect(container).toBeTruthy();
});

test('a RAW + HEIC pair\'s large view shows the RAW\'s preview while the computer can\'t show HEIC photos, and says so (7b review fix U10)', () => {
  status.heic = { state: 'missing', decoder: null, system: 'windows', reason: 'absent' };
  const raw = item(8, { name: 'IMG_7.CR3', path: 'Sinhagad/IMG_7.CR3', fileType: 'raw', pairId: 7, v: 'r1', width: 6000, height: 4000,
    rawPreview: { width: 6000, height: 4000, orientation: 1 } });
  const list = new Map([[7, heicItem(7, { pairId: 8 })], [8, raw]]);
  const { container } = render(ImportViewer, { importId: 3, items: list, taxa, order: [7], itemId: 7, onShow: vi.fn(), onClose: vi.fn() });
  expect(container.querySelector('.orig').getAttribute('src')).toBe('./api/imports/3/items/8/display?v=r1');
  expect(container.querySelector('.ph').getAttribute('src')).toBe('./api/imports/3/items/8/preview?v=r1');
  expect(screen.getByText("HEIC half: this device can't show it yet.")).toBeTruthy();
  expect(screen.getByText('Sinhagad/IMG_7.HEIC')).toBeTruthy();
});
