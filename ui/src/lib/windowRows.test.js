import { expect, test, vi } from 'vitest';
import { rowRange, trackView } from './windowRows.js';

test('the rows of a fixed height that touch the view, with some either side', () => {
  expect(rowRange(0, 64, 0, 800)).toEqual({ start: 0, end: 0 });
  expect(rowRange(1000, 64, 0, 640, 2)).toEqual({ start: 0, end: 12 });
  expect(rowRange(1000, 64, 6400, 7040, 2)).toEqual({ start: 98, end: 112 });
  expect(rowRange(30, 64, 1000, 1640, 2)).toEqual({ start: 13, end: 28 });
  // the list above the view, or below it
  expect(rowRange(30, 64, 5000, 5640, 2)).toEqual({ start: 27, end: 30 });
  expect(rowRange(30, 64, -2000, -1000, 2)).toEqual({ start: 0, end: 2 });
});

test('follows the window: the view from the element\'s top, on scroll and resize, until stopped', () => {
  const el = document.createElement('div');
  document.body.append(el);
  let top = 300;
  el.getBoundingClientRect = () => ({ top });
  Object.defineProperty(el, 'clientWidth', { value: 900 });
  const onView = vi.fn();
  const stop = trackView(el, onView);
  expect(onView).toHaveBeenLastCalledWith({ from: -300, to: window.innerHeight - 300, width: 900 });

  top = -1000;
  window.dispatchEvent(new Event('scroll'));
  expect(onView).toHaveBeenLastCalledWith({ from: 1000, to: 1000 + window.innerHeight, width: 900 });
  window.dispatchEvent(new Event('resize'));
  expect(onView).toHaveBeenCalledTimes(3);

  stop();
  window.dispatchEvent(new Event('scroll'));
  expect(onView).toHaveBeenCalledTimes(3);
  el.remove();
});

test('measures again, once a frame, when the page resizes (content above moved the list) or the element\'s width changes; not for its height', () => {
  const el = document.createElement('div');
  document.body.append(el);
  let top = 300;
  el.getBoundingClientRect = () => ({ top });
  Object.defineProperty(el, 'clientWidth', { value: 900, configurable: true });
  const observed = [];
  let callback;
  let disconnected = false;
  vi.stubGlobal('ResizeObserver', class {
    constructor(cb) { callback = cb; }
    observe(target) { observed.push(target); }
    disconnect() { disconnected = true; }
  });
  const frames = [];
  vi.stubGlobal('requestAnimationFrame', (fn) => frames.push(fn));
  const cancelled = vi.fn();
  vi.stubGlobal('cancelAnimationFrame', cancelled);
  const onView = vi.fn();
  const stop = trackView(el, onView);
  expect(observed).toEqual([el, document.body]);
  expect(onView).toHaveBeenCalledTimes(1);

  // a banner above the list came: the page grew and the list moved down
  top = 420;
  callback([{ target: document.body, contentRect: { width: 1200, height: 3000 } }]);
  callback([{ target: document.body, contentRect: { width: 1200, height: 3100 } }]);
  expect(frames.length).toBe(1);
  frames.shift()();
  expect(onView).toHaveBeenCalledTimes(2);
  expect(onView).toHaveBeenLastCalledWith({ from: -420, to: window.innerHeight - 420, width: 900 });

  // the list's own height (which it sets) changes nothing; its width (a classic scrollbar came) does
  callback([{ target: el, contentRect: { width: 900, height: 5000 } }]);
  expect(frames.length).toBe(0);
  Object.defineProperty(el, 'clientWidth', { value: 885, configurable: true });
  callback([{ target: el, contentRect: { width: 885, height: 5000 } }]);
  expect(frames.length).toBe(1);
  frames.shift()();
  expect(onView).toHaveBeenLastCalledWith({ from: -420, to: window.innerHeight - 420, width: 885 });

  // a frame still to come when stopped is cancelled
  callback([{ target: document.body, contentRect: { width: 1200, height: 3200 } }]);
  stop();
  expect(disconnected).toBe(true);
  expect(cancelled).toHaveBeenCalled();
  el.remove();
  vi.unstubAllGlobals();
});

test('measure() on the returned stop measures at once (the caller laid out anew)', () => {
  const el = document.createElement('div');
  el.getBoundingClientRect = () => ({ top: 0 });
  const onView = vi.fn();
  const stop = trackView(el, onView);
  stop.measure();
  expect(onView).toHaveBeenCalledTimes(2);
  stop();
});
