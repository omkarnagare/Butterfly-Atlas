import { afterEach, expect, test, vi } from 'vitest';
import { forgetPassedOn, passOn, takeOn } from './newWindow.js';

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

test('the address passed on is taken by a new window that starts with none of its own, once', () => {
  expect(passOn('#/explore/A1?tags=female', 1000)).toBe(true);

  expect(takeOn('#/export', 2000)).toBeNull(); // a window with an address of its own keeps it, and leaves it for another
  expect(takeOn('', 2000)).toBe('#/explore/A1?tags=female');
  expect(takeOn('', 2000)).toBeNull();
});

test('an address passed on more than a minute ago is not taken, and is removed', () => {
  passOn('#/explore?tags=female', 1000);

  expect(takeOn('#/', 61_001)).toBeNull();
  expect(localStorage.getItem('atlas.newWindowAt')).toBeNull();
});

test('a window that could not be opened forgets the address it passed on', () => {
  passOn('#/explore?tags=female', 1000);
  forgetPassedOn();
  expect(takeOn('', 2000)).toBeNull();
});

test('storage the browser refuses: nothing is passed on, and that is said by the answer', () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked'); });
  expect(passOn('#/explore?tags=female', 1000)).toBe(false);
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });
  expect(takeOn('', 2000)).toBeNull();
});
