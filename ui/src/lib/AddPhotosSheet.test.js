import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import AddPhotosSheet from './AddPhotosSheet.svelte';
import { uploadPhoto } from './api.js';
import { resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({ uploadPhoto: vi.fn(), PHOTO_ACCEPT: 'image/jpeg,.jpg', getStatus: vi.fn(), checkHeic: vi.fn(), getBackup: vi.fn() }));

const taxon = {
  id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae',
  folder: 'images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/',
};
const tags = [
  { id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 0 },
  { id: 6, key: 'male', label: 'male', source: 'builtin', photoCount: 0 },
];
const jpeg = (name) => new File([new Uint8Array(3)], name, { type: 'image/jpeg' });
const added = (id) => ({ status: 'added', photo: { id }, otherTaxon: null, firstForSpecies: id === 1, milestone: null });

beforeEach(() => {
  vi.resetAllMocks();
  URL.createObjectURL = vi.fn((file) => `blob:${file.name}`);
  URL.revokeObjectURL = vi.fn();
});
afterEach(() => {
  resetStatus();
  delete URL.createObjectURL;
  delete URL.revokeObjectURL;
});

async function choose(...files) {
  await fireEvent.change(screen.getByLabelText('Choose photos'), { target: { files } });
}

test('previews the chosen photos, sends them with the batch tags and closes when all are added', async () => {
  uploadPhoto.mockImplementation(async (taxonId, file) => added(file.name === 'a.jpg' ? 1 : 2));
  const onAdded = vi.fn();
  const onClose = vi.fn();
  const { container } = render(AddPhotosSheet, { taxon, tags, onAdded, onClose });

  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Choose photos' }));
  await choose(jpeg('a.jpg'), jpeg('b.jpg'));
  expect([...container.querySelectorAll('.pend img')].map((img) => img.getAttribute('src'))).toEqual(['blob:a.jpg', 'blob:b.jpg']);
  await fireEvent.click(screen.getByRole('button', { name: 'larva' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 2 photos' }));

  await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(uploadPhoto).toHaveBeenCalledWith('A5', expect.objectContaining({ name: 'a.jpg' }), expect.objectContaining({ tags: ['larva'] }));
  expect(onAdded.mock.calls[0][0]).toMatchObject({ added: 2, addedIds: [1, 2], firstForSpecies: true });
});

test('reports skipped, elsewhere and refused photos and waits for Done', async () => {
  uploadPhoto
    .mockResolvedValueOnce({ status: 'skipped', photo: null, otherTaxon: taxon, firstForSpecies: false, milestone: null })
    .mockResolvedValueOnce({ ...added(5), status: 'addedElsewhereToo', otherTaxon: { id: 'A6', name: 'Danaus genutia', common: 'Striped Tiger' } })
    .mockRejectedValueOnce(Object.assign(new Error("AVIF photos aren't supported yet."), { code: 'unsupported_photo' }));
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });

  await choose(jpeg('a.jpg'), jpeg('b.jpg'), new File(['x'], 'c.avif'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 3 photos' }));

  const report = await screen.findByText('1 photo added.');
  expect(report.parentElement.textContent).toContain('1 photo was already in Plain Tiger and was skipped.');
  expect(report.parentElement.textContent).toContain('1 photo is also filed under Striped Tiger.');
  expect(report.parentElement.textContent).toContain("c.avif — AVIF photos aren't supported yet.");
  expect(onClose).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  expect(onClose).toHaveBeenCalledOnce();
});

test('takes dropped photos and photos dropped on the page; a photo can be left out', async () => {
  const { container } = render(AddPhotosSheet, { taxon, tags, files: [jpeg('page.jpg')], onClose: vi.fn() });

  await fireEvent.drop(screen.getByRole('group', { name: 'Drop photos here' }), { dataTransfer: { files: [jpeg('drop.jpg')], types: ['Files'] } });
  expect(container.querySelectorAll('.pend li')).toHaveLength(2);
  await fireEvent.click(screen.getByRole('button', { name: 'Leave out page.jpg' }));

  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:page.jpg');
  expect(screen.getByRole('button', { name: 'Add 1 photo' })).toBeTruthy();
});

test('Escape closes it and frees the previews', async () => {
  const onClose = vi.fn();
  const { unmount } = render(AddPhotosSheet, { taxon, tags, onClose });
  await choose(jpeg('a.jpg'));

  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();
  unmount();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:a.jpg');
});

test('Stop leaves the rest unsent', async () => {
  uploadPhoto.mockImplementation((taxonId, file, { signal }) => new Promise((resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('stopped', 'AbortError')));
  }));
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 3 photos' }));

  await fireEvent.click(await screen.findByRole('button', { name: 'Stop' }));

  expect(await screen.findByText('No photos were added.')).toBeTruthy();
  expect(screen.getByText('3 photos were not added because you stopped.')).toBeTruthy();
  expect(uploadPhoto).toHaveBeenCalledTimes(2);
});

test('Escape while sending works like Stop: what was added is still reported, then it closes', async () => {
  let finish;
  uploadPhoto
    .mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }))
    .mockImplementationOnce((id, file, { signal }) => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(new DOMException('stopped', 'AbortError')));
    }));
  const calls = [];
  const onAdded = vi.fn((s) => calls.push('added'));
  const onClose = vi.fn(() => calls.push('close'));
  render(AddPhotosSheet, { taxon, tags, onAdded, onClose });
  await choose(jpeg('a.jpg'), jpeg('b.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 2 photos' }));
  await screen.findByRole('button', { name: 'Stop' });

  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).not.toHaveBeenCalled();
  finish(added(1));

  await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(calls).toEqual(['added', 'close']);
  expect(onAdded.mock.calls[0][0]).toMatchObject({ added: 1, addedIds: [1], cancelled: 1 });
});

test('focus follows the buttons: Stop while sending, Done when finished', async () => {
  let finish;
  uploadPhoto.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(jpeg('a.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));

  const stop = await screen.findByRole('button', { name: 'Stop' });
  await vi.waitFor(() => expect(document.activeElement).toBe(stop));
  finish({ status: 'skipped', photo: null, otherTaxon: taxon, firstForSpecies: false, milestone: null });
  const done = await screen.findByRole('button', { name: 'Done' });
  await vi.waitFor(() => expect(document.activeElement).toBe(done));
});

test('only the first 30 photos get previews; the rest are counted', async () => {
  const { container } = render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(...Array.from({ length: 32 }, (_, i) => jpeg(`p${i}.jpg`)));

  expect(URL.createObjectURL).toHaveBeenCalledTimes(30);
  expect(container.querySelectorAll('.pend img')).toHaveLength(30);
  expect(container.querySelector('.pend img').getAttribute('loading')).toBe('lazy');
  expect(screen.getByText('+2 more')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Add 32 photos' })).toBeTruthy();
});

test('the dialog is named from its heading and has one status region from the start', () => {
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Add photos to Danaus chrysippus' })).toBeTruthy();
  expect(screen.getAllByRole('status')).toHaveLength(1);
});

test('the drop zone stays lit while the drag moves over its children', async () => {
  const { container } = render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  const zone = screen.getByRole('group', { name: 'Drop photos here' });
  const dataTransfer = { types: ['Files'], files: [] };

  await fireEvent.dragEnter(zone, { dataTransfer });
  await fireEvent.dragEnter(zone.querySelector('b'), { dataTransfer });
  await fireEvent.dragLeave(zone, { dataTransfer });
  expect(container.querySelector('.drop.over')).not.toBeNull();
  await fireEvent.dragLeave(zone.querySelector('b'), { dataTransfer });
  expect(container.querySelector('.drop.over')).toBeNull();
});

test('leaving a photo out gives the next one its preview', async () => {
  const { container } = render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(...Array.from({ length: 32 }, (_, i) => jpeg(`p${i}.jpg`)));

  await fireEvent.click(screen.getByRole('button', { name: 'Leave out p0.jpg' }));

  expect(container.querySelectorAll('.pend img')).toHaveLength(30);
  expect(container.querySelector('.pend .nopv')).toBeNull();
  expect(screen.getByText('+1 more')).toBeTruthy();
});

test('closes by itself after a clean add even when some photos have an unknown place, and passes them on', async () => {
  const pune = { id: 'P414', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  uploadPhoto.mockImplementation(async (taxonId, file) => {
    const id = Number(file.name[0]);
    return { ...added(id), photo: { id, place: id === 2 ? pune : null } };
  });
  const onAdded = vi.fn();
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onAdded, onClose });

  await choose(jpeg('1.jpg'), jpeg('2.jpg'), jpeg('3.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 3 photos' }));

  await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(onAdded.mock.calls[0][0].unknownPlaceIds).toEqual([1, 3]);
  expect(screen.queryByRole('button', { name: 'Select them' })).toBeNull();
});

test('a batch that does not close by itself reports how many have an unknown place', async () => {
  uploadPhoto.mockImplementation(async (taxonId, file) => {
    const id = Number(file.name[0]);
    if (id === 3) throw new Error('nope');
    return { ...added(id), photo: { id, place: null } };
  });
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });

  await choose(jpeg('1.jpg'), jpeg('2.jpg'), jpeg('3.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 3 photos' }));

  expect(await screen.findByText('2 have an unknown place.')).toBeTruthy();
  expect(screen.getByRole('status').textContent).toContain('2 have an unknown place.');
  expect(onClose).not.toHaveBeenCalled();
});

test('a failed batch with one added photo of unknown place says "It has an unknown place."', async () => {
  uploadPhoto.mockImplementation(async (taxonId, file) => {
    const id = Number(file.name[0]);
    if (id === 2) throw new Error('nope');
    return { ...added(id), photo: { id, place: null } };
  });
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });

  await choose(jpeg('1.jpg'), jpeg('2.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 2 photos' }));

  expect(await screen.findByText('It has an unknown place.')).toBeTruthy();
});

test('still closes by itself when every added photo has a place', async () => {
  const pune = { id: 'P414', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  uploadPhoto.mockImplementation(async (taxonId, file) => ({ ...added(Number(file.name[0])), photo: { id: Number(file.name[0]), place: pune } }));
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });

  await choose(jpeg('1.jpg'), jpeg('2.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 2 photos' }));

  await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
  expect(screen.queryByText(/unknown place/)).toBeNull();
});

test('photos refused while the taxonomy updates are said once, not file by file', async () => {
  uploadPhoto.mockRejectedValue(Object.assign(new Error('Wait for the taxonomy update to finish.'), { code: 'taxonomy_updating', status: 409 }));
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 3 photos' }));

  const line = await screen.findByText('Wait for the taxonomy update to finish. 3 photos were not added.');
  expect(line.closest('.report').querySelector('ul')).toBeNull();
  // two are on their way at once; the third is not sent
  expect(uploadPhoto).toHaveBeenCalledTimes(2);
  expect(screen.getByRole('status').textContent).toContain('Wait for the taxonomy update to finish. 3 photos were not added.');
});

/* ---------- HEIC photos added without a decoder (Phase 7b audit 20) ---------- */

const heicFile = (name) => new File([new Uint8Array(3)], name, { type: 'image/heic' });
const addedHeic = (id) => ({ status: 'added', photo: { id, fileType: 'heic', place: null }, otherTaxon: null, firstForSpecies: false, milestone: null });

test('HEIC photos added on a computer without a decoder are said, with what to do, and the sheet waits for Done', async () => {
  status.heic = { state: 'missing', decoder: null, system: 'windows', reason: 'absent' };
  uploadPhoto.mockImplementation(async (taxonId, file) => (file.name.endsWith('.HEIC') ? addedHeic(file.name.length) : { ...added(9), photo: { id: 9, fileType: 'jpeg', place: null } }));
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });
  await choose(heicFile('IMG_1.HEIC'), heicFile('IMG_22.HEIC'), heicFile('IMG_333.HEIC'), jpeg('a.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 4 photos' }));

  expect(await screen.findByText("3 HEIC photos were added, but this computer can't show them yet. Install Windows' HEVC decoder, then choose Check again in Settings → About.")).toBeTruthy();
  expect(onClose).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  expect(onClose).toHaveBeenCalledOnce();
});

test('with a decoder, HEIC photos are added like any other and the sheet closes by itself', async () => {
  status.heic = { state: 'available', decoder: 'wic', system: 'windows', reason: null };
  uploadPhoto.mockImplementation(async () => addedHeic(1));
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });
  await choose(heicFile('IMG_1.HEIC'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));
  await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
});

test('the drop area names every format the library takes', () => {
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  expect(screen.getByText('JPEG, PNG, TIFF, WebP, RAW or HEIC')).toBeTruthy();
});

test('HEIC photos added while the decoder is still being checked: the sheet says so and decides once the check is over (7b review fix U11)', async () => {
  status.heic = { state: 'checking', decoder: null, system: 'linux', reason: null };
  uploadPhoto.mockImplementation(async () => addedHeic(1));
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });
  await choose(heicFile('IMG_1.HEIC'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));

  expect(await screen.findByText('Checking for a HEIC decoder…')).toBeTruthy();
  expect(onClose).not.toHaveBeenCalled();
  status.heic = { state: 'missing', decoder: null, system: 'linux', reason: 'absent' };
  expect(await screen.findByText(/^1 HEIC photo was added, but this computer can't show it yet\./)).toBeTruthy();
  expect(onClose).not.toHaveBeenCalled();
});

test('…and closes by itself when the check finds a decoder', async () => {
  status.heic = { state: 'checking', decoder: null, system: 'linux', reason: null };
  uploadPhoto.mockImplementation(async () => addedHeic(1));
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });
  await choose(heicFile('IMG_1.HEIC'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));
  await screen.findByText('Checking for a HEIC decoder…');
  status.heic = { state: 'available', decoder: 'heif-convert', system: 'linux', reason: null };
  await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
});

/* ---------- a backup holding the photos (several windows, A4/A5/A6) ---------- */

const COPYING = "A backup is copying your photos, so photos can't be changed right now. Wait for it to finish, or cancel it, change your photos, then start a new backup.";
const PAUSED_HOLD = "A paused backup still holds your photos, so they can't be changed yet. Resume the backup and let it finish, or cancel it, change your photos, then start a new backup.";
const STOPPED_HOLD = "A paused backup still holds your photos, so they can't be changed yet. Continue the backup and let it finish, or discard it, change your photos, then start a new backup.";

test('photos refused because a backup is copying are said once, not file by file, and the rest are not sent', async () => {
  uploadPhoto.mockRejectedValue(Object.assign(new Error(COPYING), { code: 'backup_copying', status: 409 }));
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(jpeg('a.jpg'), jpeg('b.jpg'), jpeg('c.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 3 photos' }));
  const line = await screen.findByText(`${COPYING} 3 photos were not added.`);
  expect(line.closest('.report').querySelector('ul')).toBeNull();
  expect(uploadPhoto).toHaveBeenCalledTimes(2);
});

test('while a backup holds the photos the sheet refuses up front, sending nothing, in words that follow the backup\'s buttons (A5, A22)', async () => {
  status.backup = { id: 2, state: 'paused', done: 10, total: 90, pausedBy: 'you', notice: null, path: '/b', holdsPhotos: true };
  const first = render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(jpeg('a.jpg'), jpeg('b.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 2 photos' }));
  expect(await screen.findByText(`${PAUSED_HOLD} 2 photos were not added.`)).toBeTruthy();
  expect(uploadPhoto).not.toHaveBeenCalled();
  first.unmount();

  // stopped by closing the app: its buttons are Continue backup and Discard…
  status.backup = { ...status.backup, pausedBy: 'closed' };
  const second = render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(jpeg('a.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));
  expect(await screen.findByText(`${STOPPED_HOLD} 1 photo was not added.`)).toBeTruthy();
  second.unmount();

  status.backup = { ...status.backup, state: 'running', pausedBy: null };
  render(AddPhotosSheet, { taxon, tags, onClose: vi.fn() });
  await choose(jpeg('a.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));
  expect(await screen.findByText(`${COPYING} 1 photo was not added.`)).toBeTruthy();
  expect(uploadPhoto).not.toHaveBeenCalled();
});

test('a backup that does not hold the photos (a database only) lets them go', async () => {
  status.backup = { id: 2, state: 'running', done: 0, total: 0, pausedBy: null, notice: null, path: '/b', holdsPhotos: false };
  uploadPhoto.mockResolvedValue(added(1));
  const onClose = vi.fn();
  render(AddPhotosSheet, { taxon, tags, onClose });
  await choose(jpeg('a.jpg'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));
  await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
  expect(uploadPhoto).toHaveBeenCalledTimes(1);
});
