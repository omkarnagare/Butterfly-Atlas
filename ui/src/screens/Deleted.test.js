import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import Deleted from './Deleted.svelte';
import Toast from '../lib/Toast.svelte';
import { emptyDeleted, getDeleted, restorePhotos } from '../lib/api.js';
import { LIBRARY_CHANGED, resetStatus, status } from '../lib/status.svelte.js';

vi.mock('../lib/api.js', () => ({
  getDeleted: vi.fn(), restorePhotos: vi.fn(), emptyDeleted: vi.fn(), getStatus: vi.fn(),
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
}));

beforeEach(() => vi.resetAllMocks());

const gone = (id, daysLeft, taxon) => ({
  id, taxonId: taxon.id, fileName: `${taxon.name.replace(' ', '_')}_0${id}.jpg`, relPath: '', tags: [], addedAt: 'v',
  width: 4, height: 3, isCover: false, place: null, deletedAt: '2026-10-10T09:00:00Z', daysLeft, taxon,
});
const plainTiger = { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger' };
const psyche = { id: 'A9', name: 'Leptosia nina', common: null };

test('lists deleted photos with their species and the days left', async () => {
  getDeleted.mockResolvedValue([gone(1, 29, plainTiger), gone(2, 1, psyche), gone(3, 0, plainTiger)]);
  render(Deleted);

  expect(await screen.findByRole('heading', { level: 1, name: 'Recently deleted' })).toBeTruthy();
  const items = await screen.findAllByRole('listitem');
  expect(items).toHaveLength(3);
  expect(within(items[0]).getByRole('link', { name: 'Plain Tiger' }).getAttribute('href')).toBe('#/species/A5');
  expect(items[0].textContent).toContain('29 days left');
  expect(items[1].textContent).toContain('Leptosia nina');
  expect(items[1].textContent).toContain('Last day');
  expect(items[2].textContent).toContain('Last day');
});

test('restores a photo to its species', async () => {
  getDeleted.mockResolvedValueOnce([gone(1, 29, plainTiger)]).mockResolvedValue([]);
  restorePhotos.mockResolvedValue([]);
  render(Deleted);
  render(Toast);

  await fireEvent.click(await screen.findByRole('button', { name: 'Restore Danaus_chrysippus_01.jpg' }));

  expect(restorePhotos).toHaveBeenCalledWith([1]);
  expect(await screen.findByText('Restored to Plain Tiger.')).toBeTruthy();
  expect(await screen.findByRole('heading', { name: 'Nothing here' })).toBeTruthy();
});

test('deleting one photo for good asks first', async () => {
  getDeleted.mockResolvedValue([gone(1, 29, plainTiger), gone(2, 29, plainTiger)]);
  emptyDeleted.mockResolvedValue(1);
  render(Deleted);

  await fireEvent.click(await screen.findByRole('button', { name: 'Delete Danaus_chrysippus_02.jpg for good' }));
  const dialog = screen.getByRole('dialog', { name: 'Delete Danaus_chrysippus_02.jpg for good?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(emptyDeleted).not.toHaveBeenCalled();

  await fireEvent.click(screen.getByRole('button', { name: 'Delete Danaus_chrysippus_02.jpg for good' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete for good' }));
  expect(emptyDeleted).toHaveBeenCalledWith([2]);
});

test('Empty removes everything after saying how many', async () => {
  getDeleted.mockResolvedValueOnce([gone(1, 29, plainTiger), gone(2, 3, psyche)]).mockResolvedValue([]);
  emptyDeleted.mockResolvedValue(2);
  render(Deleted);
  render(Toast);

  await fireEvent.click(await screen.findByRole('button', { name: 'Empty Recently deleted' }));
  const dialog = screen.getByRole('dialog', { name: 'Delete all 2 photos for good?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete for good' }));

  expect(emptyDeleted).toHaveBeenCalledWith([1, 2]);
  expect(await screen.findByText('2 photos deleted for good.')).toBeTruthy();
});

test('says when there is nothing to restore', async () => {
  getDeleted.mockResolvedValue([]);
  render(Deleted);

  expect(await screen.findByRole('heading', { name: 'Nothing here' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Empty Recently deleted' })).toBeNull();
});

test('a restore that fails says so inside that photo', async () => {
  getDeleted.mockResolvedValue([gone(1, 29, plainTiger), gone(2, 29, psyche)]);
  restorePhotos.mockRejectedValue(new Error('The species folder is missing.'));
  render(Deleted);

  await fireEvent.click(await screen.findByRole('button', { name: 'Restore Leptosia_nina_02.jpg' }));

  const items = await screen.findAllByRole('listitem');
  expect(within(items[1]).getByRole('alert').textContent).toBe('The species folder is missing.');
  expect(within(items[0]).queryByRole('alert')).toBeNull();
});

test('after deleting for good, focus goes to the heading', async () => {
  getDeleted.mockResolvedValueOnce([gone(1, 29, plainTiger)]).mockResolvedValue([]);
  emptyDeleted.mockResolvedValue(1);
  render(Deleted);

  await fireEvent.click(await screen.findByRole('button', { name: 'Delete Danaus_chrysippus_01.jpg for good' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete for good' }));

  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Recently deleted' })));
});

test('a RAW photo carries a RAW badge, and one whose thumbnail cannot be made says so (Phase 7a)', async () => {
  getDeleted.mockResolvedValue([{ ...gone(1, 29, plainTiger), fileType: 'raw' }, { ...gone(2, 29, psyche), unreadable: true }]);
  render(Deleted);
  const items = await screen.findAllByRole('listitem');
  expect(items[0].querySelector('.raw').textContent).toBe('RAW');
  expect(items[1].querySelector('.raw')).toBeNull();
  expect(items[1].querySelector('img')).toBeNull();
  expect(items[1].querySelector('.cant').textContent).toBe("Can't show this photo");
});

test('Recently deleted says when its HEIC photos can\'t be shown on this computer (7b review fix U8)', async () => {
  status.heic = { state: 'missing', decoder: null, system: 'linux', reason: 'absent' };
  getDeleted.mockResolvedValue([{ ...gone(1, 29, plainTiger), fileName: 'Danaus_chrysippus_01.heic', fileType: 'heic', unreadable: true }]);
  render(Deleted);
  expect(await screen.findByText("This device can't show HEIC photos yet.")).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Check again' })).toBeTruthy();
  resetStatus();
});

test('reads the list again on every LIBRARY_CHANGED, e.g. a photo deleted in another window (audit A9)', async () => {
  getDeleted.mockResolvedValueOnce([gone(1, 29, plainTiger)]).mockResolvedValue([gone(1, 29, plainTiger), gone(2, 29, psyche)]);
  render(Deleted);
  expect(await screen.findAllByRole('listitem')).toHaveLength(1);
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  await vi.waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(2));
  expect(getDeleted).toHaveBeenCalledTimes(2);
});

test('a background reload that fails keeps the list on screen (review fix)', async () => {
  getDeleted.mockResolvedValueOnce([gone(1, 29, plainTiger)]).mockRejectedValue(Object.assign(new Error('Something went wrong.'), { code: 'internal', status: 500 }));
  render(Deleted);
  expect(await screen.findAllByRole('listitem')).toHaveLength(1);
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  await vi.waitFor(() => expect(getDeleted).toHaveBeenCalledTimes(2));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(screen.getAllByRole('listitem')).toHaveLength(1);
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
});

test('only the newest reload lands: an older answer arriving late is dropped (review fix)', async () => {
  let first;
  getDeleted.mockResolvedValueOnce([gone(1, 29, plainTiger)])
    .mockImplementationOnce(() => new Promise((resolve) => { first = resolve; }))
    .mockResolvedValueOnce([gone(1, 29, plainTiger), gone(2, 29, psyche), gone(3, 1, psyche)]);
  render(Deleted);
  expect(await screen.findAllByRole('listitem')).toHaveLength(1);
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  await vi.waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(3));
  first([]);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(screen.getAllByRole('listitem')).toHaveLength(3);
});

test('a reload that takes away the row holding focus moves focus to the heading (review fix)', async () => {
  getDeleted.mockResolvedValueOnce([gone(1, 29, plainTiger), gone(2, 29, psyche)]).mockResolvedValue([gone(1, 29, plainTiger)]);
  render(Deleted);
  expect(await screen.findAllByRole('listitem')).toHaveLength(2);
  screen.getByRole('button', { name: 'Restore Leptosia_nina_02.jpg' }).focus();
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  await vi.waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(1));
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: 'Recently deleted' })));
});
