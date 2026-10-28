import { afterEach, expect, test, vi } from 'vitest';
import { countUp } from './countUp.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

test('shows the number at once when motion is reduced', () => {
  const el = document.createElement('b');
  const action = countUp(el, 42);
  expect(el.textContent).toBe('42');
  action.update(7);
  expect(el.textContent).toBe('7');
});

test('counts up with an ease-out curve otherwise', () => {
  window.matchMedia = () => ({ matches: false });
  const frames = [];
  vi.stubGlobal('requestAnimationFrame', (callback) => frames.push(callback));
  vi.stubGlobal('cancelAnimationFrame', () => {});
  vi.spyOn(performance, 'now').mockReturnValue(0);
  const el = document.createElement('b');

  countUp(el, 100);
  frames.shift()(450);
  expect(el.textContent).toBe('88');
  frames.shift()(900);
  expect(el.textContent).toBe('100');
  expect(frames).toHaveLength(0);
});

test('continuesFromTheShownNumberWhenTheValueChanges', () => {
  window.matchMedia = () => ({ matches: false });
  const frames = [];
  vi.stubGlobal('requestAnimationFrame', (callback) => frames.push(callback));
  vi.stubGlobal('cancelAnimationFrame', () => {});
  const now = vi.spyOn(performance, 'now').mockReturnValue(0);
  const el = document.createElement('b');

  const action = countUp(el, 100);
  frames.shift()(450);
  expect(el.textContent).toBe('88');

  now.mockReturnValue(450);
  action.update(50);
  frames.shift()(450);
  expect(el.textContent).toBe('88');
  frames.shift()(1350);
  expect(el.textContent).toBe('50');
});

test('can start from a given number', () => {
  window.matchMedia = () => ({ matches: false });
  const frames = [];
  vi.stubGlobal('requestAnimationFrame', (callback) => frames.push(callback));
  vi.stubGlobal('cancelAnimationFrame', () => {});
  vi.spyOn(performance, 'now').mockReturnValue(0);
  const el = document.createElement('b');

  countUp(el, { from: 11, to: 12 });
  frames.shift()(100);
  expect(el.textContent).toBe('11');
  frames.shift()(900);
  expect(el.textContent).toBe('12');
});

test('shows each step through `format` when given (thousands separators)', () => {
  window.matchMedia = () => ({ matches: false });
  const frames = [];
  vi.stubGlobal('requestAnimationFrame', (callback) => frames.push(callback));
  vi.stubGlobal('cancelAnimationFrame', () => {});
  vi.spyOn(performance, 'now').mockReturnValue(0);
  const el = document.createElement('b');
  const format = (n) => n.toLocaleString('en-US');

  const action = countUp(el, { to: 12400, format });
  frames.shift()(900);
  expect(el.textContent).toBe('12,400');
  window.matchMedia = () => ({ matches: true });
  action.update({ to: 1139, format });
  expect(el.textContent).toBe('1,139');
});

test('shows the starting number before the first frame, not the final one the page was rendered with', () => {
  window.matchMedia = () => ({ matches: false });
  vi.stubGlobal('requestAnimationFrame', () => 1);
  vi.stubGlobal('cancelAnimationFrame', () => {});
  const el = document.createElement('b');
  el.textContent = '12,400';
  countUp(el, { to: 12400, format: (n) => n.toLocaleString('en-US') });
  expect(el.textContent).toBe('0');
});
