import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import ZoomPan from './ZoomPan.svelte';

afterEach(() => vi.useRealTimers());

const props = { src: './api/photos/12/display', placeholder: './api/photos/12/thumb?v=1', width: 4000, height: 3000, alt: 'Plain Tiger' };

test('shows the thumbnail at once and the full-size image once it has loaded', async () => {
  const { container } = render(ZoomPan, props);

  const [thumb, original] = container.querySelectorAll('img');
  expect(thumb.getAttribute('src')).toBe('./api/photos/12/thumb?v=1');
  expect(original.getAttribute('src')).toBe('./api/photos/12/display');
  expect(original.classList.contains('on')).toBe(false);
  await fireEvent.load(original);
  expect(original.classList.contains('on')).toBe(true);
  expect(screen.getByAltText('Plain Tiger')).toBe(original);
});

test('keeps the preview when the full-size image cannot be shown, and double-click zooms in and out', async () => {
  const { container } = render(ZoomPan, props);
  const photo = container.querySelector('.zp-photo');

  vi.useFakeTimers();
  await fireEvent.error(container.querySelector('img.orig'));
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.querySelector('img.orig').getAttribute('src')).toBe('./api/photos/12/display?retry=1');
  await fireEvent.error(container.querySelector('img.orig'));
  await vi.advanceTimersByTimeAsync(4000);
  expect(container.querySelector('img.orig').getAttribute('src')).toBe('./api/photos/12/display?retry=2');
  await fireEvent.error(container.querySelector('img.orig'));
  vi.useRealTimers();
  expect(screen.getByRole('status').textContent).toBe('This photo could not be opened at full size; showing a preview.');
  expect(container.querySelector('img.orig')).toBeNull();

  await fireEvent.dblClick(container.querySelector('.zp'), { clientX: 0, clientY: 0 });
  expect(photo.dataset.scale).toBe('2.5');
  await fireEvent.dblClick(container.querySelector('.zp'), { clientX: 0, clientY: 0 });
  expect(photo.dataset.scale).toBe('1');
});

// A 1000 x 800 stage; the 4000 x 3000 photo then fits at 1000 x 750.
function stageOf(container) {
  const stage = container.querySelector('.zp');
  Object.defineProperty(stage, 'clientWidth', { value: 1000 });
  Object.defineProperty(stage, 'clientHeight', { value: 800 });
  stage.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1000, height: 800 });
  window.dispatchEvent(new Event('resize'));
  return stage;
}

// jsdom has no PointerEvent: a mouse event of the pointer type carries the coordinates.
const pointer = (stage, type, x, y) =>
  fireEvent(stage, Object.assign(new MouseEvent(type, { bubbles: true, clientX: x, clientY: y }), { pointerId: 1 }));

const scaleOf = (container) => Number(container.querySelector('.zp-photo').dataset.scale);
const offsetOf = (container) => container.querySelector('.zp-photo').style.transform;

test('a WebKit trackpad pinch zooms by the change in the gesture scale', async () => {
  const { container } = render(ZoomPan, props);
  const stage = stageOf(container);

  const start = new Event('gesturestart', { cancelable: true });
  stage.dispatchEvent(start);
  expect(start.defaultPrevented).toBe(true);
  for (const scale of [1.5, 2]) {
    const change = Object.assign(new Event('gesturechange', { cancelable: true }), { scale, clientX: 500, clientY: 400 });
    stage.dispatchEvent(change);
  }
  await Promise.resolve();
  expect(scaleOf(container)).toBe(2);
});

test('ctrl+wheel (a Chromium pinch) zooms more strongly than a plain wheel; a plain wheel pans a zoomed photo', async () => {
  const { container } = render(ZoomPan, props);
  const stage = stageOf(container);

  await fireEvent.wheel(stage, { deltaY: -10, clientX: 500, clientY: 400 });
  const plain = scaleOf(container);
  await fireEvent.dblClick(stage, { clientX: 500, clientY: 400 }); // back to fit
  await fireEvent.dblClick(stage, { clientX: 500, clientY: 400 });
  await fireEvent.dblClick(stage, { clientX: 500, clientY: 400 });
  expect(plain).toBeGreaterThan(1);

  await fireEvent.dblClick(stage, { clientX: 500, clientY: 400 });
  await fireEvent.wheel(stage, { deltaY: -10, ctrlKey: true, clientX: 500, clientY: 400 });
  expect(scaleOf(container)).toBeGreaterThan(plain);

  const before = scaleOf(container);
  const at = offsetOf(container);
  await fireEvent.wheel(stage, { deltaY: 40, deltaX: 0, clientX: 500, clientY: 400 });
  expect(scaleOf(container)).toBe(before);
  expect(offsetOf(container)).not.toBe(at); // moved, not zoomed
});

test('a double-click after two quick drags does not zoom', async () => {
  const { container } = render(ZoomPan, props);
  const stage = stageOf(container);

  for (const x of [100, 120]) {
    await pointer(stage, 'pointerdown', x, 100);
    await pointer(stage, 'pointermove', x + 30, 100);
    await pointer(stage, 'pointerup', x + 30, 100);
  }
  await fireEvent.dblClick(stage, { clientX: 150, clientY: 100 });
  expect(scaleOf(container)).toBe(1);

  for (let i = 0; i < 2; i++) {
    await pointer(stage, 'pointerdown', 100, 100);
    await pointer(stage, 'pointerup', 100, 100);
  }
  await fireEvent.dblClick(stage, { clientX: 100, clientY: 100 });
  expect(scaleOf(container)).toBe(2.5);
});

test('a photo of unknown size takes the size of the loaded image', async () => {
  const { container } = render(ZoomPan, { ...props, width: null, height: null });
  const stage = stageOf(container);
  const photo = container.querySelector('.zp-photo');
  await vi.waitFor(() => expect(photo.style.width).toBe('1000px'));
  expect(photo.style.height).toBe('750px'); // the 1600 x 1200 guess

  const original = container.querySelector('img.orig');
  Object.defineProperty(original, 'naturalWidth', { value: 2000 });
  Object.defineProperty(original, 'naturalHeight', { value: 500 });
  await fireEvent.load(original);
  await vi.waitFor(() => expect(photo.style.height).toBe('250px'));
  expect(stage).toBeTruthy();
});

test('a RAW preview stored on its side is turned upright inside the fitted frame (Phase 7a)', async () => {
  // Shown 1080 x 1616 (portrait); the preview's bytes are 1616 x 1080 and turn a quarter clockwise (orientation 6).
  const { container } = render(ZoomPan, { ...props, width: 1080, height: 1616, orientation: 6 });
  stageOf(container);
  await new Promise((r) => setTimeout(r));
  const box = container.querySelector('.zp-photo');
  const original = container.querySelector('img.orig');
  // The frame fits the turned size (800 high on a 1000 x 800 stage); the image inside is laid out unturned and turned.
  expect(box.style.height).toBe('800px');
  expect(original.style.width).toBe(box.style.height);
  expect(original.style.height).toBe(box.style.width);
  expect(original.style.transform).toBe('translate(-50%, -50%) rotate(90deg)');
  // The thumbnail is already upright.
  expect(container.querySelector('img.ph').style.transform).toBe('');
});

test('a mirrored preview is flipped without turning, and orientation 1 is left as it is', async () => {
  const { container, rerender } = render(ZoomPan, { ...props, orientation: 2 });
  expect(container.querySelector('img.orig').style.transform).toBe('scaleX(-1)');
  expect(container.querySelector('img.orig').style.width).toBe('');
  await rerender({ ...props, orientation: 1 });
  expect(container.querySelector('img.orig').style.transform).toBe('');
});

test('a photo the caller knows cannot be shown says the caller\'s note at once, without loading the full size', () => {
  const { container } = render(ZoomPan, { ...props, broken: true, failedNote: 'This RAW photo\'s preview can\'t be read any more. The file may have changed or be damaged.' });
  expect(container.querySelector('img.orig')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe("This RAW photo's preview can't be read any more. The file may have changed or be damaged.");
  expect(container.querySelector('img.ph').getAttribute('src')).toBe('./api/photos/12/thumb?v=1');
});

test('a HEIC display that fails is asked once more for why, and the server\'s words are said (Phase 7b audit 7)', async () => {
  const fetchMock = vi.fn(async () => ({ ok: false, status: 415, json: async () => ({ error: { code: 'unsupported', message: 'This HEIC photo can\'t be shown: the device\'s decoder couldn\'t read it.' } }) }));
  vi.stubGlobal('fetch', fetchMock);
  const { container } = render(ZoomPan, { ...props, explain: true, failedNote: 'Fallback.' });

  await fireEvent.error(container.querySelector('img.orig'));
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe("This HEIC photo can't be shown: the device's decoder couldn't read it."));
  expect(fetchMock).toHaveBeenCalledOnce();
  expect(fetchMock.mock.calls[0][0]).toBe('./api/photos/12/display');
  expect(container.querySelector('img.orig')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  vi.unstubAllGlobals();
});

test('a HEIC display refused as busy says so with Try again, which loads it again', async () => {
  const busy = "Butterfly Atlas is still checking for a HEIC decoder. Try again in a moment.";
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 503, json: async () => ({ error: { code: 'busy', message: busy } }) })));
  const { container } = render(ZoomPan, { ...props, explain: true, failedNote: 'Fallback.' });

  await fireEvent.error(container.querySelector('img.orig'));
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe(busy));
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(container.querySelector('img.orig').getAttribute('src')).toBe('./api/photos/12/display?retry=1');
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  await fireEvent.load(container.querySelector('img.orig'));
  expect(container.querySelector('img.orig').classList.contains('on')).toBe(true);
  vi.unstubAllGlobals();
});

test('a HEIC display whose why can\'t be asked, or comes back unreadable, says Butterfly Atlas isn\'t answering, with Try again (7b review fix U7)', async () => {
  const NOT_ANSWERING = "Butterfly Atlas isn't answering. Try again.";
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
  const { container, unmount } = render(ZoomPan, { ...props, explain: true, failedNote: 'The file may be damaged.' });
  await fireEvent.error(container.querySelector('img.orig'));
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe(NOT_ANSWERING));
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(container.querySelector('img.orig').getAttribute('src')).toBe('./api/photos/12/display?retry=1');
  unmount();

  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, json: async () => { throw new SyntaxError('bad'); } })));
  const again = render(ZoomPan, { ...props, explain: true, failedNote: 'The file may be damaged.' });
  await fireEvent.error(again.container.querySelector('img.orig'));
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe(NOT_ANSWERING));
  expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  vi.unstubAllGlobals();
});

test('zoomed in, the photo is laid out at the zoomed size and only moved, so the browser draws it from the full-size image', async () => {
  const { container } = render(ZoomPan, props);
  const stage = stageOf(container);
  const photo = container.querySelector('.zp-photo');
  await vi.waitFor(() => expect(photo.style.width).toBe('1000px'));
  expect(photo.style.marginLeft).toBe('-500px');
  expect(photo.style.marginTop).toBe('-375px');

  await fireEvent.wheel(stage, { deltaY: -200, ctrlKey: true, clientX: 500, clientY: 400 });
  const scale = scaleOf(container);
  expect(scale).toBeGreaterThan(1);
  expect(parseFloat(photo.style.width)).toBeCloseTo(1000 * scale);
  expect(parseFloat(photo.style.height)).toBeCloseTo(750 * scale);
  expect(parseFloat(photo.style.marginLeft)).toBeCloseTo(-500 * scale);
  expect(photo.style.transform).toMatch(/^translate\(/);
  expect(photo.style.transform).not.toContain('scale(');
});

test('a quarter-turned preview zoomed in is laid out at the zoomed size too', async () => {
  const { container } = render(ZoomPan, { ...props, width: 1080, height: 1616, orientation: 6 });
  const stage = stageOf(container);
  await new Promise((r) => setTimeout(r));
  await fireEvent.dblClick(stage, { clientX: 500, clientY: 400 });

  const box = container.querySelector('.zp-photo');
  const original = container.querySelector('img.orig');
  expect(box.style.height).toBe('2000px');
  expect(original.style.width).toBe(box.style.height);
  expect(original.style.height).toBe(box.style.width);
});
