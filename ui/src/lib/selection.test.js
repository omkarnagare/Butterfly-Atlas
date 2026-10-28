import { expect, test } from 'vitest';
import { moveFrom, orderOf, positions, rangeBetween, pruneTo, select, selectOnly } from './selection.js';

const tiles = (key, ...ids) => ({ kind: 'tiles', key, items: ids.map((id) => ({ id })) });
const rows = [
  { kind: 'section', key: 's1' }, tiles('a', 1, 2, 3), tiles('b', 4),
  { kind: 'burst', key: 'b1' }, tiles('c', 5, 6, 7), { kind: 'section', key: 's2' }, tiles('d', 8, 9),
];
const order = orderOf(rows);

test('the order is the tiles as laid out', () => {
  expect(order).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9]);
  expect(rangeBetween(order, 6, 3)).toEqual([3, 4, 5, 6]);
  expect(rangeBetween(order, 99, 3)).toEqual([3]);
  expect(rangeBetween(order, 3, 99)).toEqual([]);
});

test('a click selects one photo; again on it clears; Ctrl/Cmd adds and removes', () => {
  let state = { selected: new Set(), anchor: null, base: new Set() };
  state = select(state, order, 2);
  expect([...state.selected]).toEqual([2]);
  state = select(state, order, 5, { toggle: true });
  expect([...state.selected]).toEqual([2, 5]);
  state = select(state, order, 2, { toggle: true });
  expect([...state.selected]).toEqual([5]);
  state = select(state, order, 7);
  expect([...state.selected]).toEqual([7]);
  state = select(state, order, 7);
  expect([...state.selected]).toEqual([]);
});

test('Shift adds the range from the anchor, and a second Shift-click replaces that range', () => {
  let state = { selected: new Set(), anchor: null, base: new Set() };
  state = select(state, order, 9);
  state = select(state, order, 2, { toggle: true });
  state = select(state, order, 5, { shift: true });
  expect([...state.selected].sort()).toEqual([2, 3, 4, 5, 9]);
  state = select(state, order, 3, { shift: true });
  expect([...state.selected].sort()).toEqual([2, 3, 9]);
  expect(state.anchor).toBe(2);
});

test('arrows move as the tiles are laid out, Up and Down keeping the column', () => {
  const at = positions(rows);
  expect(at.get(6)).toEqual({ row: 4, col: 1 });
  expect(moveFrom(rows, at, 3, 'ArrowRight')).toBe(4);
  expect(moveFrom(rows, at, 1, 'ArrowLeft')).toBe(1);
  expect(moveFrom(rows, at, 3, 'ArrowDown')).toBe(4);
  expect(moveFrom(rows, at, 4, 'ArrowDown')).toBe(5);
  expect(moveFrom(rows, at, 7, 'ArrowDown')).toBe(9);
  expect(moveFrom(rows, at, 9, 'ArrowDown')).toBe(9);
  expect(moveFrom(rows, at, 6, 'ArrowUp')).toBe(4);
  expect(moveFrom(rows, at, 2, 'ArrowUp')).toBe(2);
  expect(moveFrom(rows, at, 5, 'End')).toBe(9);
  expect(moveFrom(rows, at, 5, 'Home')).toBe(1);
  expect(moveFrom(rows, at, 99, 'ArrowDown')).toBe(1);
  expect(moveFrom([], new Map(), 1, 'ArrowDown')).toBeNull();
});

test('selectOnly ranges over photos that can\'t be selected and leaves them out; pruneTo keeps only the ones given', () => {
  const can = (id) => id !== 3;
  const start = selectOnly({ selected: new Set(), anchor: null, base: new Set() }, [1, 2, 3, 4], 1, {}, can);
  const range = selectOnly(start, [1, 2, 3, 4], 4, { shift: true }, can);
  expect([...range.selected]).toEqual([1, 2, 4]);
  expect([...selectOnly(start, [1, 2, 3, 4], 3, { shift: true }, can).selected]).toEqual([1, 2]);

  expect(pruneTo(range, new Set([1, 2, 4]))).toBe(range);
  expect([...pruneTo(range, new Set([2])).selected]).toEqual([2]);
});
