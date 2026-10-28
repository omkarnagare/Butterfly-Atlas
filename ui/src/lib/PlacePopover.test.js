import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import PlacePopover from './PlacePopover.svelte';
import { searchPlaces } from './api.js';
import { EMPTY, mergeNames } from './filter.js';

vi.mock('./api.js', () => ({ searchPlaces: vi.fn() }));

const place = (id, path, level, mine = false) => ({ id, name: path[0], path, level, mine });
const pune = place('P414', ['Pune', 'Maharashtra', 'India'], 'district');
const maharashtra = place('P20', ['Maharashtra', 'India'], 'state');

beforeEach(() => vi.resetAllMocks());

const field = () => screen.getByRole('combobox', { name: 'Place' });
const rows = () => screen.getAllByRole('option').map((o) => o.textContent.replace(/\s+/g, ' ').trim());

test('opens on "Unknown place"; typing searches places after a pause (from 2 letters); Enter and a click add', async () => {
  searchPlaces.mockResolvedValue([pune, maharashtra]);
  const onChange = vi.fn();
  const { rerender } = render(PlacePopover, { filter: EMPTY, names: mergeNames(undefined, {}), onChange, onClose: vi.fn() });
  expect(screen.getByRole('dialog', { name: 'Place' })).toBeTruthy();
  expect(document.activeElement).toBe(field());
  expect(field().getAttribute('placeholder')).toBe('Type a district, state or country');
  expect(rows()).toEqual(['Unknown place']);

  await fireEvent.input(field(), { target: { value: 'p' } });
  await new Promise((resolve) => setTimeout(resolve, 200));
  expect(searchPlaces).not.toHaveBeenCalled();
  await fireEvent.input(field(), { target: { value: 'pu' } });
  await vi.waitFor(() => expect(rows()).toEqual(['Unknown place', 'Pune · Maharashtra, India district', 'Maharashtra · India state']));
  expect(searchPlaces).toHaveBeenCalledWith('pu', expect.objectContaining({ signal: expect.any(AbortSignal) }));
  expect(field().getAttribute('aria-activedescendant')).toBe(screen.getAllByRole('option')[1].id);

  await fireEvent.keyDown(field(), { key: 'Enter' });
  expect(onChange).toHaveBeenCalledWith({ ...EMPTY, places: ['P414'] }, { places: [pune] });
  await rerender({ filter: { ...EMPTY, places: ['P414'] }, names: mergeNames(undefined, { places: [pune] }) });
  await fireEvent.click(screen.getAllByRole('option')[0]);
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, places: ['P414', 'unknown'] }, {});
});

test('chosen places are listed with remove buttons; a chosen row cannot be added again', async () => {
  const onChange = vi.fn();
  render(PlacePopover, { filter: { ...EMPTY, places: ['P20', 'unknown'] }, names: mergeNames(undefined, { places: [maharashtra] }), onChange, onClose: vi.fn() });
  const chosen = screen.getByRole('list', { name: 'Chosen places' });
  expect([...chosen.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['Maharashtra ×', 'Unknown place ×']);
  expect(screen.getAllByRole('option')[0].getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(screen.getAllByRole('option')[0]);
  expect(onChange).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Unknown place' }));
  expect(onChange).toHaveBeenCalledWith({ ...EMPTY, places: ['P20'] }, {});
});

test('no match says so', async () => {
  searchPlaces.mockResolvedValue([]);
  render(PlacePopover, { filter: EMPTY, names: mergeNames(undefined, {}), onChange: vi.fn(), onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'zzz' } });
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe('No place matches “zzz”.'));
  expect(rows()).toEqual(['Unknown place']);
});

test('removing a chosen place moves focus to the next Remove button, else the previous, else the search field', async () => {
  const names = mergeNames(undefined, { places: [maharashtra, pune] });
  const { rerender } = render(PlacePopover, { filter: { ...EMPTY, places: ['P20', 'P414', 'unknown'] }, names, onChange: vi.fn(), onClose: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Pune' }));
  await rerender({ filter: { ...EMPTY, places: ['P20', 'unknown'] } });
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Remove Unknown place' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Unknown place' }));
  await rerender({ filter: { ...EMPTY, places: ['P20'] } });
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Remove Maharashtra' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Maharashtra' }));
  await rerender({ filter: EMPTY });
  expect(document.activeElement).toBe(field());
});

test('a chosen place whose name is not known yet reads "…", and its Remove button is named by the id', () => {
  render(PlacePopover, { filter: { ...EMPTY, places: ['P99'] }, names: mergeNames(undefined, {}), onChange: vi.fn(), onClose: vi.fn() });
  const chosen = screen.getByRole('list', { name: 'Chosen places' });
  expect([...chosen.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['… ×']);
  expect(screen.getByRole('button', { name: 'Remove P99' })).toBeTruthy();
});
