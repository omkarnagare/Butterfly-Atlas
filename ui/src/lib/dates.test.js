import { expect, test } from 'vitest';
import { ago, shortDate } from './dates.js';

test('says how long ago a photo was added, in calendar days', () => {
  const now = new Date(2026, 9, 11, 9, 0);
  expect(ago(new Date(2026, 9, 11, 0, 5).toISOString(), now)).toBe('today');
  expect(ago(new Date(2026, 9, 10, 23, 55).toISOString(), now)).toBe('yesterday');
  expect(ago(new Date(2026, 9, 1, 12, 0).toISOString(), now)).toBe('10 days ago');
  expect(ago(new Date(2026, 9, 12, 8, 0).toISOString(), now)).toBe('today');
  expect(ago('not a date', now)).toBe('');
});

test('names a day briefly, with the year only when it is another year', () => {
  const now = new Date(2026, 9, 12, 9, 0);
  expect(shortDate(new Date(2026, 9, 12, 8, 0).toISOString(), { now, locale: 'en-GB' })).toBe('12 Oct');
  expect(shortDate(new Date(2025, 2, 3, 8, 0).toISOString(), { now, locale: 'en-GB' })).toBe('3 Mar 2025');
  expect(shortDate('not a date', { now })).toBe('');
});
