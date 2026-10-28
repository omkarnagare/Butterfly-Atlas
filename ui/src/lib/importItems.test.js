import { expect, test } from 'vitest';
import { emptyItems, mergePage } from './importItems.js';

test('a full answer fills the items and taxa; a later one replaces changed items and moves the version on', () => {
  let state = mergePage(emptyItems(), { version: 5, items: [{ id: 1, taxonId: null }, { id: 2, taxonId: null }], taxa: {} });
  expect([...state.items.keys()]).toEqual([1, 2]);
  expect(state.version).toBe(5);

  const before = state.items;
  state = mergePage(state, { version: 7, items: [{ id: 2, taxonId: 'A5' }], taxa: { A5: { name: 'Danaus chrysippus', common: 'Plain Tiger' } } });
  expect(state.items.get(2).taxonId).toBe('A5');
  expect(state.items.get(1)).toBe(before.get(1));
  expect(state.items).not.toBe(before);
  expect(state.taxa.get('A5').common).toBe('Plain Tiger');
  expect(state.version).toBe(7);
});

test('an answer to a change merges its items but keeps the polled version', () => {
  const state = mergePage(mergePage(emptyItems(), { version: 5, items: [{ id: 1, skipped: false }] }), { version: 9, items: [{ id: 1, skipped: true }] }, { advance: false });
  expect(state.items.get(1).skipped).toBe(true);
  expect(state.version).toBe(5);
});

test('an answer with nothing new keeps the same items and taxa, so nothing is laid out again', () => {
  const state = mergePage(emptyItems(), { version: 5, items: [{ id: 1 }], taxa: { A5: { name: 'x' } } });
  const next = mergePage(state, { version: 6, items: [], taxa: {} });
  expect(next.items).toBe(state.items);
  expect(next.taxa).toBe(state.taxa);
  expect(next.version).toBe(6);
  expect(mergePage(next, { version: 6, items: [], taxa: {} })).toBe(next);
});

test('an item\'s place is kept as it came, whether it has one or not', () => {
  const pune = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  let state = mergePage(emptyItems(), { version: 5, items: [{ id: 1, place: null }, { id: 2, place: pune }] });
  state = mergePage(state, { version: 6, items: [{ id: 1, place: pune }] });
  expect(state.items.get(1).place).toEqual(pune);
  expect(state.items.get(2).place).toBe(pune);
});
