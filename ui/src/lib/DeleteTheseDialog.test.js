import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import DeleteTheseDialog from './DeleteTheseDialog.svelte';
import { deleteMatching } from './api.js';
import { showToast } from './toast.svelte.js';
import { LIBRARY_CHANGED } from './status.svelte.js';

vi.mock('./api.js', () => ({ deleteMatching: vi.fn() }));
vi.mock('./toast.svelte.js', () => ({ showToast: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

const count = { photos: 140, species: 33, photographedSpecies: 31 };

test('says how many photos of how many species go to Recently deleted, for 30 days; Delete sends the filter and the count', async () => {
  deleteMatching.mockResolvedValue(140);
  const onDone = vi.fn();
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  render(DeleteTheseDialog, { query: 'tags=male', count, onDone, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Move 140 photos of 31 species to Recently deleted?' })).toBeTruthy();
  expect(screen.getByText('You can restore them from Recently deleted for 30 days.')).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Cancel' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Delete 140 photos' }));

  expect(deleteMatching).toHaveBeenCalledWith('tags=male', 140);
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
  expect(showToast).toHaveBeenCalledWith('140 photos moved to Recently deleted.',
    { link: { href: '#/deleted', label: 'Recently deleted' } });
  expect(changed).toHaveBeenCalledOnce();
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('one photo of one species is worded so', () => {
  render(DeleteTheseDialog, { query: 'taxa=A5', count: { photos: 1, species: 1, photographedSpecies: 1 }, onDone: vi.fn(), onClose: vi.fn() });
  expect(screen.getByRole('dialog', { name: 'Move 1 photo of 1 species to Recently deleted?' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Delete 1 photo' })).toBeTruthy();
});

test('a refusal (the photos changed meanwhile) is said in the dialog, which stays open', async () => {
  deleteMatching.mockRejectedValue(new Error('The photos matching this filter changed (141 now, not 140), so nothing was deleted. Check the count and try again.'));
  const onDone = vi.fn();
  render(DeleteTheseDialog, { query: 'tags=male', count, onDone, onClose: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'Delete 140 photos' }));
  expect((await screen.findByRole('alert')).textContent).toBe(
    'The photos matching this filter changed (141 now, not 140), so nothing was deleted. Check the count and try again.');
  expect(onDone).not.toHaveBeenCalled();
  expect(screen.getByRole('dialog')).toBeTruthy();
});
