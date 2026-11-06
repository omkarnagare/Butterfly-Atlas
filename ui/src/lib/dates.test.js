import { expect, test } from 'vitest';
import { ago, clockOf, dayAt, dayClock, longDate, minutesSince, shortDate } from './dates.js';

test('names a day in full, with the year only when it is another year', () => {
  const now = new Date(2026, 9, 30, 9, 0);
  expect(longDate(new Date(2026, 9, 27, 8, 0).toISOString(), { now, locale: 'en-GB' })).toBe('27 October');
  expect(longDate(new Date(2025, 2, 3, 8, 0).toISOString(), { now, locale: 'en-GB' })).toBe('3 March 2025');
  expect(longDate('not a date', { now })).toBe('');
});

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

// One device at a time (contract preamble): a server instant in this device's local time, and how long ago it was.
test('names an instant by day and clock, in local time', () => {
  const at = new Date(2026, 10, 4, 11, 5).toISOString();
  expect(dayAt(at)).toBe('4 Nov at 11:05');
  expect(dayClock(at)).toBe('4 Nov, 11:05');
  expect(clockOf(at)).toBe('11:05');
  expect(dayAt(new Date(2026, 8, 30, 9, 0).toISOString())).toBe('30 Sep at 09:00');
  expect(dayAt('not a date')).toBe('');
  expect(clockOf(null)).toBe('');
});

test('counts whole minutes since an instant by this device\'s clock; one in the future is 0', () => {
  const now = new Date(2026, 10, 4, 11, 7, 30);
  expect(minutesSince(new Date(2026, 10, 4, 11, 5).toISOString(), now)).toBe(2);
  expect(minutesSince(new Date(2026, 10, 4, 10, 25).toISOString(), now)).toBe(42);
  expect(minutesSince(new Date(2026, 10, 4, 11, 30).toISOString(), now)).toBe(0);
  expect(minutesSince('not a date', now)).toBe(0);
});
