import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { sharpen, resetSharpen } from './sharpen.js';

// A tile of `w` x `h` page pixels holding a thumbnail of `nw` x `nh`, on a screen of `density`.
function tile({ w = 300, h = 300, nw = 480, nh = 320, density = 2 } = {}) {
  Object.defineProperty(window, 'devicePixelRatio', { value: density, configurable: true });
  const img = document.createElement('img');
  img.setAttribute('src', './api/photos/7/thumb?v=k');
  img.getBoundingClientRect = () => ({ width: w, height: h, left: 0, top: 0, right: w, bottom: h });
  Object.defineProperty(img, 'naturalWidth', { value: nw, configurable: true });
  Object.defineProperty(img, 'naturalHeight', { value: nh, configurable: true });
  Object.defineProperty(img, 'complete', { value: true, configurable: true });
  document.body.append(img);
  return img;
}
const answer = (status, pending = false) => ({
  status, ok: status === 200, headers: { get: (name) => (pending && name === 'X-Atlas-Pending' ? '1' : null) },
  blob: async () => new Blob(['jpeg'], { type: 'image/jpeg' }),
});
let urls = 0;
const photo = { id: 7, v: 'k' };

beforeEach(() => {
  vi.useFakeTimers();
  resetSharpen();
  global.fetch = vi.fn();
  urls = 0;
  URL.createObjectURL = vi.fn(() => `blob:sharp-${++urls}`);
  URL.revokeObjectURL = vi.fn();
});
afterEach(() => {
  vi.useRealTimers();
  document.body.innerHTML = '';
  Object.defineProperty(window, 'devicePixelRatio', { value: 1, configurable: true });
});

test('a tile that needs more pixels than its thumbnail has asks for the sharp copy and shows it once it is there', async () => {
  fetch.mockResolvedValue(answer(200));
  const img = tile();
  sharpen(img, photo);
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).toHaveBeenCalledWith('./api/photos/7/sharp?v=k');
  // the copy fetched is the copy shown: read once, no second download, no connection left holding an unread answer
  expect(img.getAttribute('srcset')).toBe('blob:sharp-1 2x');
  // the thumbnail stays the image's own address
  expect(img.getAttribute('src')).toBe('./api/photos/7/thumb?v=k');
});

test('a tile its thumbnail already covers asks for nothing (a 1× screen, or a small tile)', async () => {
  sharpen(tile({ density: 1 }), photo);
  sharpen(tile({ w: 150, h: 150 }), photo);
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).not.toHaveBeenCalled();
});

test('a photo no bigger than its thumbnail asks for nothing', async () => {
  sharpen(tile({ nw: 400, nh: 300 }), photo);
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).not.toHaveBeenCalled();
});

test('a copy still being made is asked for again, with longer and longer waits; one that cannot be made is left alone', async () => {
  fetch.mockResolvedValueOnce(answer(204, true)).mockResolvedValueOnce(answer(204, true)).mockResolvedValueOnce(answer(200));
  const img = tile();
  sharpen(img, photo);
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(2000);
  expect(fetch).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(3999);
  expect(fetch).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(1);
  expect(fetch).toHaveBeenCalledTimes(3);
  expect(img.getAttribute('srcset')).toBe('blob:sharp-1 2x');

  fetch.mockReset();
  fetch.mockResolvedValue(answer(204));
  const other = tile();
  sharpen(other, { id: 8, v: 'z' });
  await vi.advanceTimersByTimeAsync(60000);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(other.hasAttribute('srcset')).toBe(false);
});

test('a copy still pending after the quick tries (thumbnails being made, an import) is asked for every 30 s while shown', async () => {
  fetch.mockResolvedValue(answer(204, true));
  const img = tile();
  sharpen(img, photo);
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(2000 + 4000 + 8000 + 16000 + 32000);
  expect(fetch).toHaveBeenCalledTimes(6);
  await vi.advanceTimersByTimeAsync(30000);
  expect(fetch).toHaveBeenCalledTimes(7);
  fetch.mockResolvedValue(answer(200));
  await vi.advanceTimersByTimeAsync(30000);
  expect(img.getAttribute('srcset')).toBe('blob:sharp-1 2x');
  await vi.advanceTimersByTimeAsync(90000);
  expect(fetch).toHaveBeenCalledTimes(8);
});

test('at most two copies are asked for at once, so thumbnails and the page are never kept waiting', async () => {
  const waits = [];
  fetch.mockImplementation(() => new Promise((resolve) => waits.push(resolve)));
  for (let i = 0; i < 5; i++) sharpen(tile(), { id: i, v: 'k' });
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).toHaveBeenCalledTimes(2);
  waits[0](answer(200));
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).toHaveBeenCalledTimes(3);
});

test('another photo in the same image drops the old copy and starts again; a destroyed image asks no more', async () => {
  fetch.mockResolvedValue(answer(200));
  const img = tile();
  const action = sharpen(img, photo);
  await vi.advanceTimersByTimeAsync(0);
  action.update({ id: 9, v: 'q' });
  expect(img.hasAttribute('srcset')).toBe(false);
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:sharp-1');
  await vi.advanceTimersByTimeAsync(0);
  expect(img.getAttribute('srcset')).toBe('blob:sharp-2 2x');
  action.destroy();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:sharp-2');

  fetch.mockReset();
  fetch.mockResolvedValue(answer(204, true));
  const gone = tile();
  const done = sharpen(gone, { id: 10, v: 'k' });
  await vi.advanceTimersByTimeAsync(0);
  done.destroy();
  await vi.advanceTimersByTimeAsync(60000);
  expect(fetch).toHaveBeenCalledTimes(1);
});

test('a thumbnail still a placeholder waits for the real one before deciding', async () => {
  fetch.mockResolvedValue(answer(200));
  const img = tile({ nw: 1, nh: 1 });
  sharpen(img, photo);
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).not.toHaveBeenCalled();
  Object.defineProperty(img, 'naturalWidth', { value: 480, configurable: true });
  Object.defineProperty(img, 'naturalHeight', { value: 320, configurable: true });
  img.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(0);
  expect(fetch).toHaveBeenCalledTimes(1);
});
