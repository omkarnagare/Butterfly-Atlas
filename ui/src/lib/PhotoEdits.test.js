import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import PhotoEdits from './PhotoEdits.svelte';
import { deletePhotos, setCover, setPhotoTags } from './api.js';

vi.mock('./api.js', () => ({
  deletePhotos: vi.fn(), movePhotos: vi.fn(), setCover: vi.fn(), setPhotoTags: vi.fn(), placePhotos: vi.fn(), unplacePhotos: vi.fn(),
  search: vi.fn(), getRecentPlaces: vi.fn(), searchPlaces: vi.fn(), addPlace: vi.fn(), getMyPlaces: vi.fn(), renamePlace: vi.fn(), deletePlace: vi.fn(),
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), getChildren: vi.fn(), renameCustomSpecies: vi.fn(),
}));

beforeEach(() => vi.resetAllMocks());

const species = { id: 'A9', name: 'Danaus genutia', common: null };
const photo = { id: 7, taxonId: 'A9', fileName: 'x.jpg', relPath: 'images/x.jpg', tags: ['male'], place: null, isCover: false };
const tags = [{ key: 'male', label: 'male' }, { key: 'female', label: 'female' }];

test('edits act on the photo and its own species, then the viewer reads the photos again', async () => {
  setPhotoTags.mockResolvedValue({});
  setCover.mockResolvedValue({});
  const onChanged = vi.fn(async () => {});
  render(PhotoEdits, { photo, species, tags, onChanged, onLeft: vi.fn() });

  await fireEvent.click(screen.getByRole('button', { name: 'female' }));
  expect(setPhotoTags).toHaveBeenCalledWith(7, ['male', 'female']);
  await vi.waitFor(() => expect(onChanged).toHaveBeenCalledOnce());

  await fireEvent.click(screen.getByRole('button', { name: 'Set as cover' }));
  expect(setCover).toHaveBeenCalledWith('A9', 7);
});

test('deleting takes the photo out: the viewer is told to move on; a refusal shows in the panel instead', async () => {
  deletePhotos.mockResolvedValueOnce({}).mockRejectedValueOnce(new Error('A backup of everything is running.'));
  const onLeft = vi.fn(async () => {});
  render(PhotoEdits, { photo, species, tags, onLeft });

  await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
  await vi.waitFor(() => expect(onLeft).toHaveBeenCalledOnce());

  await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
  expect((await screen.findByRole('alert')).textContent).toBe('A backup of everything is running.');
  expect(onLeft).toHaveBeenCalledOnce();
});
