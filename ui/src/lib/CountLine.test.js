import { render, screen } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import CountLine from './CountLine.svelte';
import { EMPTY } from './filter.js';

const count = (over, filter = {}) => ({
  photos: 312, species: 48, photographedSpecies: 48, bytes: 4509715660, manualSpecies: false, ...over, filter: { ...EMPTY, query: '', ...filter },
});
const maharashtra = { id: 'P20', name: 'Maharashtra', path: ['Maharashtra', 'India'], level: 'state', mine: false };
const goa = { id: 'P21', name: 'Goa', path: ['Goa', 'India'], level: 'state', mine: false };

test('says what the filter matches, with the places under it, and tells screen readers the whole line once', () => {
  const { container } = render(CountLine, { count: count({}, { places: [maharashtra, goa] }) });
  expect(container.querySelector('.cl-n').textContent).toBe('312 photos of 48 species, 4.2 GB');
  expect(container.querySelector('.cl-n').getAttribute('aria-hidden')).toBe('true');
  expect(container.querySelector('.cl-where').textContent).toBe('in Maharashtra and Goa');
  expect(screen.getByRole('status').textContent).toBe('312 photos of 48 species, 4.2 GB, in Maharashtra and Goa');
});

test('numbers have thousands separators; no places line without places', () => {
  const { container } = render(CountLine, { count: count({ photos: 12400, species: 1139, photographedSpecies: 600, bytes: 0 }) });
  expect(container.querySelector('.cl-n').textContent).toBe('12,400 photos of 600 species, 0 B · 1,139 species in the list');
  expect(container.querySelector('.cl-where')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe('12,400 photos of 600 species, 0 B · 1,139 species in the list');
});

test('a new answer replaces the line; nothing matching says so', async () => {
  const { container, rerender } = render(CountLine, { count: count({}) });
  await rerender({ count: count({ species: 212, photographedSpecies: 0, photos: 0, bytes: 0 }, { status: 'not', places: [{ id: 'unknown' }] }) });
  expect(container.querySelector('.cl-n').textContent).toBe('212 species not yet photographed');
  expect(container.querySelector('.cl-where').textContent).toBe('with an unknown place');
  await rerender({ count: count({ species: 0, photographedSpecies: 0, photos: 0, bytes: 0 }) });
  expect(container.querySelector('.cl-n').textContent).toBe('Nothing matches these filters.');
});

test('before the first answer it is counting; a failure shows the message; a pending answer dims the line', async () => {
  const { container, rerender } = render(CountLine, { count: null });
  expect(screen.getByRole('status').textContent).toBe('Counting…');
  await rerender({ count: null, error: 'Butterfly Atlas could not reach its service.' });
  expect(screen.getByRole('alert').textContent).toBe('Butterfly Atlas could not reach its service.');
  await rerender({ count: count({}), error: '', pending: true });
  expect(container.querySelector('.cl').classList.contains('pending')).toBe(true);
  expect(container.querySelector('.cl').getAttribute('aria-busy')).toBe('true');
});

test('a failed recount shows its message over the previous numbers and keeps them dimmed until a new answer', async () => {
  const shown = count({});
  const { container, rerender } = render(CountLine, { count: shown });
  await rerender({ count: shown, error: 'Butterfly Atlas could not reach its service.' });
  expect(screen.getByRole('alert').textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(container.querySelector('.cl').classList.contains('pending')).toBe(true);
  await rerender({ count: count({ photos: 3 }), error: '' });
  expect(screen.queryByRole('alert')).toBeNull();
  expect(container.querySelector('.cl').classList.contains('pending')).toBe(false);
});

test('one status lives through the first answer, so the first count is announced', async () => {
  const { container, rerender } = render(CountLine, { count: null });
  const status = screen.getByRole('status');
  expect(status.textContent).toBe('Counting…');
  expect(container.querySelector('.cl-n').getAttribute('aria-hidden')).toBe('true');
  await rerender({ count: count({}) });
  expect(screen.getByRole('status')).toBe(status);
  expect(status.textContent).toBe('312 photos of 48 species, 4.2 GB');
});
