import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import PhotoEdits from './PhotoEdits.svelte';
import { createTag, deletePhotos, setCover, setPhotoTags } from './api.js';

vi.mock('./api.js', () => ({
  createTag: vi.fn(), deletePhotos: vi.fn(), movePhotos: vi.fn(), setCover: vi.fn(), setPhotoTags: vi.fn(), placePhotos: vi.fn(), unplacePhotos: vi.fn(),
  search: vi.fn(), getRecentPlaces: vi.fn(), searchPlaces: vi.fn(), addPlace: vi.fn(), getMyPlaces: vi.fn(), renamePlace: vi.fn(), deletePlace: vi.fn(),
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), getChildren: vi.fn(), renameCustomSpecies: vi.fn(),
}));

beforeEach(() => vi.resetAllMocks());

const species = { id: 'A9', name: 'Danaus genutia', common: null };
const photo = { id: 7, taxonId: 'A9', fileName: 'x.jpg', relPath: 'images/x.jpg', tags: ['male'], place: null, isCover: false };
const tags = [
  { id: 6, key: 'male', label: 'male', source: 'builtin' }, { id: 7, key: 'female', label: 'female', source: 'builtin' },
  { id: 20, key: 'basking', label: 'basking', source: 'user' },
];

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

test('the photo\'s tags are a picker: built-in tags, the box, and a user tag found by typing goes on at once', async () => {
  setPhotoTags.mockResolvedValue({});
  render(PhotoEdits, { photo, species, tags, onLeft: vi.fn() });

  const group = screen.getByRole('group', { name: 'Tags on this photo' });
  expect([...group.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['male', 'female']);
  await fireEvent.input(screen.getByRole('searchbox', { name: 'Find or add a tag' }), { target: { value: 'bask' } });
  await fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' });
  expect(setPhotoTags).toHaveBeenCalledWith(7, ['male', 'basking']);
});

test('a tag created in the viewer: the screen reads its tags again (onTagsChanged), then it goes on the photo', async () => {
  const order = [];
  createTag.mockResolvedValue({ id: 21, key: 'roosting', label: 'roosting', source: 'user', photoCount: 0 });
  setPhotoTags.mockImplementation(async (id, keys) => order.push(`set ${keys.join(',')}`));
  const onTagsChanged = vi.fn(async () => order.push('re-read'));
  render(PhotoEdits, { photo, species, tags, onTagsChanged, onLeft: vi.fn() });

  await fireEvent.input(screen.getByRole('searchbox'), { target: { value: 'Roosting' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Create "roosting"' }));

  await vi.waitFor(() => expect(order).toEqual(['re-read', 'set male,roosting']));
  expect(createTag).toHaveBeenCalledWith('roosting');
  // the screen is given the new tag, so it can keep it even if reading its tags again fails
  expect(onTagsChanged).toHaveBeenCalledWith(expect.objectContaining({ key: 'roosting' }));
});

test('while a change is being saved the picker does nothing', async () => {
  let finish;
  setPhotoTags.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  render(PhotoEdits, { photo, species, tags, onLeft: vi.fn() });

  await fireEvent.click(screen.getByRole('button', { name: 'female' }));
  await fireEvent.input(screen.getByRole('searchbox'), { target: { value: 'roosting' } });
  const create = screen.getByRole('button', { name: 'Create "roosting"' });
  expect(create.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(create);
  expect(createTag).not.toHaveBeenCalled();
  finish({});
  await vi.waitFor(() => expect(create.getAttribute('aria-disabled')).toBe('false'));
});
