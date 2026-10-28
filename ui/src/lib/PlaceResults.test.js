import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import PlaceResults from './PlaceResults.svelte';

const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false, species: 31, photos: 140 };
const india = { id: 'P1', name: 'India', path: ['India'], level: 'country', mine: false, species: 1, photos: 1 };
const unknown = { id: 'unknown', species: 3, photos: 7 };

test('one row per place with its species and photo counts; the unknown row last', () => {
  render(PlaceResults, { rows: [pune, india, unknown], onChoose: vi.fn() });
  // role="list" kept explicitly: WebKit drops the list role of a list without bullets
  expect(screen.getByRole('list', { name: 'Places' }).getAttribute('role')).toBe('list');
  expect(screen.getAllByRole('button').map((b) => b.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Pune · Maharashtra, India — 31 species · 140 photos', 'India — 1 species · 1 photo', 'Unknown place — 3 species · 7 photos',
  ]);
});

test('choosing a row narrows the filter to that place alone', async () => {
  const onChoose = vi.fn();
  render(PlaceResults, { rows: [pune, unknown], onChoose });
  await fireEvent.click(screen.getByRole('button', { name: /^Pune/ }));
  expect(onChoose).toHaveBeenCalledWith(pune);
  await fireEvent.click(screen.getByRole('button', { name: /^Unknown place/ }));
  expect(onChoose).toHaveBeenLastCalledWith(unknown);
});

test('arrow keys, Home and End move between rows', async () => {
  render(PlaceResults, { rows: [pune, india, unknown], onChoose: vi.fn() });
  const buttons = screen.getAllByRole('button');
  buttons[0].focus();
  await fireEvent.keyDown(buttons[0], { key: 'ArrowDown' });
  expect(document.activeElement).toBe(buttons[1]);
  await fireEvent.keyDown(buttons[1], { key: 'End' });
  expect(document.activeElement).toBe(buttons[2]);
  await fireEvent.keyDown(buttons[2], { key: 'ArrowDown' });
  expect(document.activeElement).toBe(buttons[2]);
  await fireEvent.keyDown(buttons[2], { key: 'Home' });
  expect(document.activeElement).toBe(buttons[0]);
});

test('no rows: says why there is nothing to show', () => {
  render(PlaceResults, { rows: [], onChoose: vi.fn() });
  expect(screen.getByText('No matching photos, so no places to show.')).toBeTruthy();
});
