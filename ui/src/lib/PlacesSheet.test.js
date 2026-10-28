import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import PlacesSheet from './PlacesSheet.svelte';
import { deletePlace, getMyPlaces, renamePlace } from './api.js';
import { toast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ deletePlace: vi.fn(), getMyPlaces: vi.fn(), renamePlace: vi.fn() }));

const tamhini = { id: 'PU3', name: 'Tamhini', path: ['Tamhini', 'Maharashtra', 'India'], level: 'district', mine: true, photos: 4 };
const kew = { id: 'PU4', name: 'Kew', path: ['Kew', 'England', 'United Kingdom'], level: 'district', mine: true, photos: 0 };

beforeEach(() => {
  vi.resetAllMocks();
  getMyPlaces.mockResolvedValue([kew, tamhini]);
});

test('lists your places with where they are and how many photos use them', async () => {
  render(PlacesSheet, { onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Your places' })).toBeTruthy();
  expect(await screen.findByText('Tamhini')).toBeTruthy();
  const rows = screen.getAllByRole('listitem');
  expect(rows.map((row) => row.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Kew England, United Kingdom No photos Rename Delete',
    'Tamhini Maharashtra, India 4 photos Rename Delete',
  ]);
});

test('says when you have no places of your own', async () => {
  getMyPlaces.mockResolvedValue([]);
  render(PlacesSheet, { onClose: vi.fn() });
  expect(await screen.findByText('No places of your own yet. Add one with “Add … as a new place” when you choose a place.')).toBeTruthy();
});

test('renames in place: Enter saves and the list is read again; a refusal stays on the row; Escape leaves it as it was', async () => {
  const onChanged = vi.fn();
  render(PlacesSheet, { onChanged, onClose: vi.fn() });
  await screen.findByText('Tamhini');

  renamePlace.mockRejectedValueOnce(Object.assign(new Error('Kew is already in Maharashtra.'), { code: 'place_exists' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Rename Tamhini' }));
  const field = screen.getByLabelText('New name for Tamhini');
  expect(document.activeElement).toBe(field);
  await fireEvent.input(field, { target: { value: 'Kew' } });
  await fireEvent.keyDown(field, { key: 'Enter' });
  expect((await screen.findByRole('alert')).textContent).toBe('Kew is already in Maharashtra.');
  expect(field.getAttribute('aria-invalid')).toBe('true');

  renamePlace.mockResolvedValueOnce({ ...tamhini, name: 'Tamhini Ghat' });
  getMyPlaces.mockResolvedValue([kew, { ...tamhini, name: 'Tamhini Ghat', path: ['Tamhini Ghat', 'Maharashtra', 'India'] }]);
  await fireEvent.input(field, { target: { value: ' Tamhini Ghat ' } });
  await fireEvent.keyDown(field, { key: 'Enter' });
  expect(renamePlace).toHaveBeenLastCalledWith('PU3', 'Tamhini Ghat');
  expect(await screen.findByText('Tamhini Ghat')).toBeTruthy();
  expect(onChanged).toHaveBeenCalledOnce();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Rename Tamhini Ghat' }));

  await fireEvent.click(screen.getByRole('button', { name: 'Rename Kew' }));
  await fireEvent.keyDown(screen.getByLabelText('New name for Kew'), { key: 'Escape' });
  expect(screen.queryByLabelText('New name for Kew')).toBeNull();
  expect(screen.getByRole('dialog', { name: 'Your places' })).toBeTruthy();
});

test('deleting a place photos use says how many will have an unknown place first; an unused one goes at once', async () => {
  const onChanged = vi.fn();
  deletePlace.mockResolvedValueOnce(4).mockResolvedValueOnce(0);
  render(PlacesSheet, { onChanged, onClose: vi.fn() });
  await screen.findByText('Tamhini');

  await fireEvent.click(screen.getByRole('button', { name: 'Delete Tamhini' }));
  const confirm = screen.getByRole('dialog', { name: 'Delete “Tamhini”?' });
  expect(confirm.textContent).toContain('4 photos will have an unknown place.');
  expect(deletePlace).not.toHaveBeenCalled();
  getMyPlaces.mockResolvedValue([kew]);
  await fireEvent.click(screen.getByRole('button', { name: 'Delete place' }));
  expect(deletePlace).toHaveBeenCalledWith('PU3');
  await vi.waitFor(() => expect(screen.queryByText('Tamhini')).toBeNull());
  expect(toast.message).toBe('“Tamhini” deleted. 4 photos now have an unknown place.');

  getMyPlaces.mockResolvedValue([]);
  await fireEvent.click(screen.getByRole('button', { name: 'Delete Kew' }));
  expect(screen.queryByRole('dialog', { name: 'Delete “Kew”?' })).toBeNull();
  expect(deletePlace).toHaveBeenLastCalledWith('PU4');
  await vi.waitFor(() => expect(toast.message).toBe('“Kew” deleted.'));
  expect(onChanged).toHaveBeenCalledTimes(2);
});

test('one photo reads as one photo', async () => {
  getMyPlaces.mockResolvedValue([{ ...tamhini, photos: 1 }]);
  render(PlacesSheet, { onClose: vi.fn() });
  await fireEvent.click(await screen.findByRole('button', { name: 'Delete Tamhini' }));
  expect(screen.getByRole('dialog', { name: 'Delete “Tamhini”?' }).textContent).toContain('1 photo will have an unknown place.');
});

test('Escape and Done close it', async () => {
  const onClose = vi.fn();
  render(PlacesSheet, { onClose });
  await screen.findByText('Tamhini');
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  expect(onClose).toHaveBeenCalledTimes(2);
});

test('Rename waits while a change is being saved', async () => {
  let answer;
  deletePlace.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
  render(PlacesSheet, { onClose: vi.fn() });
  await screen.findByText('Tamhini');

  await fireEvent.click(screen.getByRole('button', { name: 'Delete Kew' }));
  const rename = screen.getByRole('button', { name: 'Rename Tamhini' });
  expect(rename.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(rename);
  expect(screen.queryByLabelText('New name for Tamhini')).toBeNull();

  answer(0);
  await vi.waitFor(() => expect(rename.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(rename);
  expect(screen.getByLabelText('New name for Tamhini')).toBeTruthy();
});
