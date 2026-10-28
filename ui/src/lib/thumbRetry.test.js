import { afterEach, expect, test, vi } from 'vitest';
import { thumbRetry } from './thumbRetry.js';
import { THUMBS_AGAIN, resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({ getStatus: vi.fn(), checkHeic: vi.fn(), getBackup: vi.fn() }));

afterEach(() => {
  vi.useRealTimers();
  resetStatus();
});

const URL = './api/photos/12/thumb?v=1791700000000';

function image(src = URL) {
  const img = document.createElement('img');
  img.setAttribute('src', src);
  return img;
}

/** The image "arrives" `width` pixels wide. */
function arrive(img, width) {
  Object.defineProperty(img, 'naturalWidth', { value: width, configurable: true });
  img.dispatchEvent(new Event('load'));
}

test('asks again after 2, 4, 8, 16 and 32 seconds while the placeholder comes back, then settles', async () => {
  vi.useFakeTimers();
  const img = image();
  const onReady = vi.fn();
  thumbRetry(img, onReady);

  for (const [r, wait] of [[1, 2000], [2, 4000], [3, 8000], [4, 16000], [5, 32000]]) {
    arrive(img, 1);
    await vi.advanceTimersByTimeAsync(wait - 1);
    expect(img.getAttribute('src')).not.toContain(`&r=${r}`);
    await vi.advanceTimersByTimeAsync(1);
    expect(img.getAttribute('src')).toBe(`${URL}&r=${r}`);
    expect(onReady).not.toHaveBeenCalled();
  }

  arrive(img, 1);
  expect(onReady).toHaveBeenCalledOnce();
  await vi.advanceTimersByTimeAsync(120000);
  expect(img.getAttribute('src')).toBe(`${URL}&r=5`);
});

test('a real thumbnail, or one that fails, is ready at once and asks nothing more', async () => {
  vi.useFakeTimers();
  const img = image();
  const onReady = vi.fn();
  thumbRetry(img, onReady);
  arrive(img, 480);
  expect(onReady).toHaveBeenCalledOnce();

  const broken = image();
  const onBroken = vi.fn();
  thumbRetry(broken, onBroken);
  broken.dispatchEvent(new Event('error'));
  expect(onBroken).toHaveBeenCalledOnce();

  await vi.advanceTimersByTimeAsync(120000);
  expect(img.getAttribute('src')).toBe(URL);
  expect(broken.getAttribute('src')).toBe(URL);
});

test('works without a callback, and stops asking once the image is gone', async () => {
  vi.useFakeTimers();
  const img = image();
  const action = thumbRetry(img);
  arrive(img, 1);
  action.destroy();
  await vi.advanceTimersByTimeAsync(120000);
  expect(img.getAttribute('src')).toBe(URL);
});

test('a new address starts afresh, and a retry for the old one is dropped', async () => {
  vi.useFakeTimers();
  const img = image();
  thumbRetry(img);
  arrive(img, 1);
  arrive(img, 1); // a second load of the same placeholder does not add a second timer

  const next = './api/photos/13/thumb?v=5';
  img.setAttribute('src', next); // the page shows another photo in the same <img>
  await vi.advanceTimersByTimeAsync(2000);
  expect(img.getAttribute('src')).toBe(next);

  arrive(img, 1);
  await vi.advanceTimersByTimeAsync(2000);
  expect(img.getAttribute('src')).toBe(`${next}&r=1`);
});

test('an image that had already arrived before the action ran is looked at straight away', async () => {
  vi.useFakeTimers();
  const img = image();
  Object.defineProperty(img, 'complete', { value: true, configurable: true });
  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  thumbRetry(img);
  await vi.advanceTimersByTimeAsync(2000);
  expect(img.getAttribute('src')).toBe(`${URL}&r=1`);

  const ready = image();
  Object.defineProperty(ready, 'complete', { value: true, configurable: true });
  Object.defineProperty(ready, 'naturalWidth', { value: 480, configurable: true });
  const onReady = vi.fn();
  thumbRetry(ready, onReady);
  expect(onReady).toHaveBeenCalledOnce();
});

test('an image that had already arrived is handled once, not again by its late load event', async () => {
  vi.useFakeTimers();
  const img = image();
  Object.defineProperty(img, 'complete', { value: true, configurable: true });
  Object.defineProperty(img, 'naturalWidth', { value: 480, configurable: true });
  const onReady = vi.fn();
  thumbRetry(img, onReady);
  img.dispatchEvent(new Event('load'));
  expect(onReady).toHaveBeenCalledOnce();

  const placeholder = image();
  Object.defineProperty(placeholder, 'complete', { value: true, configurable: true });
  Object.defineProperty(placeholder, 'naturalWidth', { value: 1, configurable: true });
  thumbRetry(placeholder);
  placeholder.dispatchEvent(new Event('load'));
  await vi.advanceTimersByTimeAsync(2000);
  expect(placeholder.getAttribute('src')).toBe(`${URL}&r=1`);
  await vi.advanceTimersByTimeAsync(3999);
  expect(placeholder.getAttribute('src')).toBe(`${URL}&r=1`);
});

/** Runs the five quick tries with the placeholder coming back each time. */
async function exhaust(img) {
  for (const wait of [2000, 4000, 8000, 16000, 32000]) {
    arrive(img, 1);
    await vi.advanceTimersByTimeAsync(wait);
  }
  arrive(img, 1);
}

test('while previews are still being made, a placeholder keeps asking every 30 s (Phase 7b audit 19)', async () => {
  vi.useFakeTimers();
  status.previews = { pending: 40, done: 10 };
  const img = image();
  const onReady = vi.fn();
  thumbRetry(img, onReady);
  await exhaust(img);
  expect(img.getAttribute('src')).toBe(`${URL}&r=5`);
  // Not left grey: it is still waiting, so the tile keeps its shimmer.
  expect(onReady).not.toHaveBeenCalled();

  await vi.advanceTimersByTimeAsync(30000);
  expect(img.getAttribute('src')).toBe(`${URL}&r=6`);
  arrive(img, 1);
  await vi.advanceTimersByTimeAsync(30000);
  expect(img.getAttribute('src')).toBe(`${URL}&r=7`);

  // Done being made: the next placeholder is the last word.
  status.previews = { pending: 0, done: 50 };
  arrive(img, 1);
  expect(onReady).toHaveBeenCalledOnce();
  await vi.advanceTimersByTimeAsync(120000);
  expect(img.getAttribute('src')).toBe(`${URL}&r=7`);
});

test('a placeholder that settled asks once more when the previews are done or the decoder changes (audit 19)', async () => {
  vi.useFakeTimers();
  const img = image();
  const real = image('./api/photos/13/thumb?v=1');
  thumbRetry(img);
  thumbRetry(real);
  await exhaust(img);
  arrive(real, 480);

  window.dispatchEvent(new CustomEvent(THUMBS_AGAIN));
  expect(img.getAttribute('src')).toBe(`${URL}&r=6`);
  // A real thumbnail is left as it is.
  expect(real.getAttribute('src')).toBe('./api/photos/13/thumb?v=1');
  arrive(img, 480);
  window.dispatchEvent(new CustomEvent(THUMBS_AGAIN));
  expect(img.getAttribute('src')).toBe(`${URL}&r=6`);
});
