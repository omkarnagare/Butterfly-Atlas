import { expect, test, vi } from 'vitest';
import { BURST_GAP_MS, burstTime, bursts, itemTime } from './bursts.js';

const at = (id, time, extra = {}) => ({ id, name: `IMG_${id}.JPG`, capturedAt: time, modifiedAt: '2024-01-01T00:00:00Z', ...extra });

test('uses the capture time, else the modified time', () => {
  expect(itemTime(at(1, '2024-08-12T10:22:00Z'))).toBe(Date.parse('2024-08-12T10:22:00Z'));
  expect(itemTime(at(2, null, { modifiedAt: '2024-08-12T10:23:00Z' }))).toBe(Date.parse('2024-08-12T10:23:00Z'));
  expect(itemTime({ id: 3, capturedAt: null, modifiedAt: null })).toBe(0);
});

test('shots within two minutes of the one before make a burst, however long the chain', () => {
  const groups = bursts([
    at(3, '2024-08-12T10:25:30Z'), at(1, '2024-08-12T10:22:00Z'), at(2, '2024-08-12T10:24:00Z'),
    at(4, '2024-08-12T11:00:00Z'), at(5, '2024-08-12T11:02:00Z'),
  ]);
  expect(groups.map((g) => [g.burst, g.items.map((i) => i.id)])).toEqual([[true, [1, 2, 3]], [true, [4, 5]]]);
  expect(groups[0].key).toBe('b1');
  expect(groups[0].start).toBe(Date.parse('2024-08-12T10:22:00Z'));
  expect(groups[0].end).toBe(Date.parse('2024-08-12T10:25:30Z'));
  expect(BURST_GAP_MS).toBe(120000);
});

test('single shots between bursts share one loose group', () => {
  const groups = bursts([
    at(1, '2024-08-12T08:00:00Z'), at(2, '2024-08-12T09:00:00Z'),
    at(3, '2024-08-12T10:00:00Z'), at(4, '2024-08-12T10:00:30Z'),
    at(5, '2024-08-12T12:00:00Z'),
  ]);
  expect(groups.map((g) => [g.key, g.burst, g.items.map((i) => i.id)])).toEqual([
    ['l1', false, [1, 2]], ['b3', true, [3, 4]], ['l5', false, [5]],
  ]);
});

test('without a capture time the modified time decides, and equal times go by name in number order', () => {
  const groups = bursts([
    at(10, null, { name: 'IMG_10.JPG', modifiedAt: '2024-08-12T10:00:00Z' }),
    at(9, null, { name: 'IMG_9.JPG', modifiedAt: '2024-08-12T10:00:00Z' }),
    at(11, null, { name: 'IMG_11.JPG', modifiedAt: '2024-08-12T10:05:00Z' }),
  ]);
  expect(groups.map((g) => g.items.map((i) => i.id))).toEqual([[9, 10], [11]]);
});

test('a burst header names its time', () => {
  expect(burstTime(Date.parse('2024-08-12T10:22:00Z'), { locale: 'en-GB', timeZone: 'UTC' })).toBe('12 Aug 2024, 10:22');
});

test('reads each photo\'s time once, however many comparisons the sort makes', () => {
  const items = Array.from({ length: 500 }, (_, k) => at(k + 1, new Date(Date.UTC(2024, 0, 1) + ((k * 7919) % 500) * 60000).toISOString()));
  const parse = vi.spyOn(Date, 'parse');
  bursts(items);
  expect(parse.mock.calls.length).toBeLessThanOrEqual(items.length);
  parse.mockRestore();
});
