import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import PhotoViewer from './PhotoViewer.svelte';
import { resetStatus, status } from './status.svelte.js';
import { forgetViewerOpen, noteViewerOpen } from './viewer.js';
import {
  addCustomSpecies, checkCustomName, deletePhotos, getChildren, getRecentPlaces, movePhotos, placePhotos, search, setCover, setPhotoTags, unplacePhotos,
} from './api.js';

vi.mock('./api.js', () => ({
  deletePhotos: vi.fn(), movePhotos: vi.fn(), search: vi.fn(), setCover: vi.fn(), setPhotoTags: vi.fn(),
  placePhotos: vi.fn(), unplacePhotos: vi.fn(), getRecentPlaces: vi.fn(), searchPlaces: vi.fn(), addPlace: vi.fn(),
  getMyPlaces: vi.fn(), renamePlace: vi.fn(), deletePlace: vi.fn(),
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), getChildren: vi.fn(), renameCustomSpecies: vi.fn(),
  displayUrl: (id) => `./api/photos/${id}/display`,
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
}));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
});

const taxon = { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae' };
const tags = [
  { id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 1 },
  { id: 6, key: 'male', label: 'male', source: 'builtin', photoCount: 1 },
];
const photo = (id, over) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.jpg`, relPath: `images/Nymphalidae/Danaus_chrysippus_[A5]/Danaus_chrysippus_0${id}.jpg`,
  tags: [], addedAt: 'v', width: 4000, height: 3000, isCover: false, ...over,
});
const photos = [photo(3, { tags: ['male'] }), photo(2), photo(1, { isCover: true })];

function open(photoId = 3, extra = {}) {
  const onClose = vi.fn();
  const onChanged = vi.fn(async () => {});
  const view = render(PhotoViewer, { taxon, photos, photoId, tags, onClose, onChanged, ...extra });
  return { ...view, onClose, onChanged };
}

test('shows the photo, where it is in the species and its file', () => {
  const { container } = open();

  expect(screen.getByRole('dialog', { name: 'Photo viewer: Plain Tiger' })).toBeTruthy();
  expect(container.querySelector('.c').textContent).toBe('1 / 3');
  expect(container.querySelector('img.orig').getAttribute('src')).toBe('./api/photos/3/display');
  expect(screen.getByText('images/Nymphalidae/Danaus_chrysippus_[A5]/Danaus_chrysippus_03.jpg')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'male' }).getAttribute('aria-pressed')).toBe('true');
  expect(screen.getByRole('button', { name: 'Photo 1 of 3' }).getAttribute('aria-current')).toBe('true');
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
});

test('arrow keys and the buttons move through the photos without filling the history', async () => {
  window.location.hash = '#/species/A5/photo/3';
  open();

  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/species/A5/photo/2');
  await fireEvent.keyDown(window, { key: 'ArrowLeft' });
  expect(window.location.hash).toBe('#/species/A5/photo/1');
  await fireEvent.click(screen.getByRole('button', { name: 'Photo 2 of 3' }));
  expect(window.location.hash).toBe('#/species/A5/photo/2');
});

test('pages only through the photos with the gallery filter tag', async () => {
  window.location.hash = '#/species/A5/photo/3?tag=larva';
  const { container } = open(3, { tag: 'larva', photos: [photo(3, { tags: ['larva'] }), photo(2), photo(1, { tags: ['larva'] })] });

  expect(container.querySelector('.c').textContent).toBe('1 / 2 · larva');
  expect(screen.queryByRole('button', { name: 'Photo 3 of 3' })).toBeNull();
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/species/A5/photo/1?tag=larva');
});

test('pages only through the photos at the gallery\'s place, with its tag too', async () => {
  const pune = { id: 'P414', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  const list = [photo(4, { place: pune, tags: ['larva'] }), photo(3, { place: pune }), photo(2, { place: null }), photo(1, { place: pune, tags: ['larva'] })];
  window.location.hash = '#/species/A5/photo/4?place=P414';
  const { container, unmount } = open(4, { place: 'P414', photos: list });

  expect(container.querySelector('.c').textContent).toBe('1 / 3 · Pune');
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/species/A5/photo/3?place=P414');
  unmount();

  const both = open(4, { tag: 'larva', place: 'P414', photos: list });
  expect(both.container.querySelector('.c').textContent).toBe('1 / 2 · larva · Pune');
  await fireEvent.click(screen.getByRole('button', { name: 'Photo 2 of 2' }));
  expect(window.location.hash).toBe('#/species/A5/photo/1?tag=larva&place=P414');
  both.unmount();

  const unknown = open(2, { place: 'unknown', photos: list });
  expect(unknown.container.querySelector('.c').textContent).toBe('1 / 1 · Unknown place');
  unknown.unmount();

  const stale = open(3, { place: 'unknown', photos: list });
  expect(stale.container.querySelector('.c').textContent).toBe('2 / 4');
});

test('a filter the photo does not match falls back to all photos', async () => {
  window.location.hash = '#/species/A5/photo/2?tag=larva';
  const { container } = open(2, { tag: 'larva', photos: [photo(3, { tags: ['larva'] }), photo(2), photo(1)] });

  expect(container.querySelector('.c').textContent).toBe('2 / 3');
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/species/A5/photo/1');
});

test('Escape closes it, and + - 0 zoom the photo', async () => {
  const { container, onClose } = open();
  const box = container.querySelector('.zp-photo');

  await fireEvent.keyDown(window, { key: '+' });
  expect(box.style.transform).toContain('scale(1.25)');
  await fireEvent.keyDown(window, { key: '0' });
  expect(box.style.transform).toContain('scale(1)');
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();
});

test('edits the tags and the cover', async () => {
  setPhotoTags.mockResolvedValue(photo(3, { tags: ['male', 'larva'] }));
  setCover.mockResolvedValue(3);
  const { onChanged } = open();

  await fireEvent.click(screen.getByRole('button', { name: 'larva' }));
  expect(setPhotoTags).toHaveBeenCalledWith(3, ['male', 'larva']);
  await fireEvent.click(screen.getByRole('button', { name: 'Set as cover' }));
  await vi.waitFor(() => expect(setCover).toHaveBeenCalledWith('A5', 3));
  expect(onChanged).toHaveBeenCalledTimes(2);
});

test('the cover photo can hand the choice back', async () => {
  setCover.mockResolvedValue(null);
  open(1);

  expect(screen.getByText('Species cover')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Choose the cover automatically' }));
  expect(setCover).toHaveBeenCalledWith('A5', null);
});

test('Delete moves the photo to Recently deleted and shows the next one', async () => {
  deletePhotos.mockResolvedValue(1);
  window.location.hash = '#/species/A5/photo/2';
  const { onChanged } = open(2);

  await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

  await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
  expect(deletePhotos).toHaveBeenCalledWith([2]);
  expect(window.location.hash).toBe('#/species/A5/photo/1');
});

test('deleting the only photo closes the viewer', async () => {
  deletePhotos.mockResolvedValue(1);
  const { onClose } = open(3, { photos: [photos[0]] });

  await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

  await vi.waitFor(() => expect(onClose).toHaveBeenCalledOnce());
});

test('focus stays on a tag chip while its change is being saved, and a second press is ignored', async () => {
  let finish;
  setPhotoTags.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  open();
  const chip = screen.getByRole('button', { name: 'larva' });

  chip.focus();
  await fireEvent.click(chip);
  expect(chip.getAttribute('aria-disabled')).toBe('true');
  expect(document.activeElement).toBe(chip);
  await fireEvent.click(chip);
  expect(setPhotoTags).toHaveBeenCalledOnce();
  finish(photo(3, { tags: ['male', 'larva'] }));
  await vi.waitFor(() => expect(chip.getAttribute('aria-disabled')).toBe('false'));
  expect(document.activeElement).toBe(chip);
});

test('after a delete the focus is on Close', async () => {
  deletePhotos.mockResolvedValue(1);
  window.location.hash = '#/species/A5/photo/2';
  const { onChanged } = open(2);

  screen.getByRole('button', { name: 'Delete' }).focus();
  await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
  await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close' }));
});

test('Move files the photo under another species', async () => {
  search.mockResolvedValue([{ id: 'A6', rank: 'species', name: 'Danaus genutia', common: 'Striped Tiger', family: 'Nymphalidae' }]);
  movePhotos.mockResolvedValue([photo(3, { taxonId: 'A6' })]);
  window.location.hash = '#/species/A5/photo/3';
  const { onChanged } = open();

  await fireEvent.click(screen.getByRole('button', { name: 'Move…' }));
  const input = screen.getByRole('combobox', { name: 'Species' });
  await fireEvent.input(input, { target: { value: 'striped' } });
  await fireEvent.click(await screen.findByRole('option', { name: /Striped Tiger/ }));

  await vi.waitFor(() => expect(onChanged).toHaveBeenCalled());
  expect(movePhotos).toHaveBeenCalledWith([3], 'A6');
  expect(window.location.hash).toBe('#/species/A5/photo/2');
  expect(screen.queryByRole('combobox')).toBeNull();
});

test('a failed Move closes the picker so the error is shown in the viewer', async () => {
  search.mockResolvedValue([{ id: 'A6', rank: 'species', name: 'Danaus genutia', common: 'Striped Tiger', family: 'Nymphalidae' }]);
  movePhotos.mockRejectedValue(new Error('That species folder is read-only.'));
  open();

  await fireEvent.click(screen.getByRole('button', { name: 'Move…' }));
  await fireEvent.input(screen.getByRole('combobox', { name: 'Species' }), { target: { value: 'striped' } });
  await fireEvent.click(await screen.findByRole('option', { name: /Striped Tiger/ }));

  expect((await screen.findByRole('alert')).textContent).toBe('That species folder is read-only.');
  expect(screen.queryByRole('combobox')).toBeNull();
});

test('shows where the photo was taken, above its file, and how that place was set', () => {
  const pune = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  const cases = [
    [{ place: pune }, 'Pune, Maharashtra, India from GPS', 'from GPS'],
    [{ place: { id: 'P20', path: ['Maharashtra', 'India'], source: 'user' } }, 'Maharashtra, India set by you', 'set by you'],
    [{ place: null }, 'Unknown place', 'Unknown place'],
  ];
  for (const [over, line, muted] of cases) {
    const { unmount } = open(3, { photos: [photo(3, over)] });
    const side = screen.getByRole('complementary', { name: 'About this photo' });

    expect([...side.querySelectorAll('h3')].map((h) => h.textContent)).toEqual(['Tags', 'Place', 'File']);
    const place = side.querySelector('h3:nth-of-type(2)').nextElementSibling;
    expect(place.textContent).toBe(line);
    expect(screen.getByText(muted).classList.contains('muted')).toBe(true);
    expect(place.querySelector('button')).toBeNull();
    unmount();
  }
});

test('Change sets the photo\'s place with the picker, and the panel and gallery are read again', async () => {
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false };
  getRecentPlaces.mockResolvedValue([pune]);
  placePhotos.mockResolvedValue([photo(3, { place: { id: 'P414', path: pune.path, source: 'user' } })]);
  const { onChanged } = open(3, { photos: [photo(3, { place: null })] });

  expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull();
  const change = screen.getByRole('button', { name: 'Change' });
  await fireEvent.click(change);
  expect(screen.getByRole('dialog', { name: 'Place for this photo' })).toBeTruthy();
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));

  expect(placePhotos).toHaveBeenCalledWith([3], 'P414');
  await vi.waitFor(() => expect(onChanged).toHaveBeenCalledOnce());
  await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Place for this photo' })).toBeNull());
});

test('Remove takes the place off; a refusal is shown in the panel', async () => {
  const gps = { id: 'P414', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  unplacePhotos.mockResolvedValueOnce([photo(3, { place: null })]).mockRejectedValueOnce(new Error('That photo is in Recently deleted.'));
  const { onChanged } = open(3, { photos: [photo(3, { place: gps })] });

  const remove = screen.getByRole('button', { name: 'Remove' });
  await fireEvent.click(remove);
  expect(unplacePhotos).toHaveBeenCalledWith([3]);
  await vi.waitFor(() => expect(onChanged).toHaveBeenCalledOnce());

  await fireEvent.click(remove);
  expect((await screen.findByRole('alert')).textContent).toBe('That photo is in Recently deleted.');
});

test('after Remove the focus is on Change, beside where Remove was', async () => {
  const gps = { id: 'P414', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  unplacePhotos.mockResolvedValue([photo(3, { place: null })]);
  let view;
  const onChanged = vi.fn(async () => view.rerender({ photos: [photo(3, { place: null })] }));
  view = render(PhotoViewer, { taxon, photos: [photo(3, { place: gps })], photoId: 3, tags, onClose: vi.fn(), onChanged });

  const remove = screen.getByRole('button', { name: 'Remove' });
  remove.focus();
  await fireEvent.click(remove);
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'Remove' })).toBeNull());
  expect(screen.getByText('Unknown place')).toBeTruthy();
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Change' })));
});

test('a refused place closes the picker so the error is shown in the viewer', async () => {
  getRecentPlaces.mockResolvedValue([{ id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false }]);
  placePhotos.mockRejectedValue(new Error('That photo is in Recently deleted.'));
  open(3, { photos: [photo(3, { place: null })] });

  await fireEvent.click(screen.getByRole('button', { name: 'Change' }));
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));

  expect((await screen.findByRole('alert')).textContent).toBe('That photo is in Recently deleted.');
  expect(screen.queryByRole('dialog', { name: 'Place for this photo' })).toBeNull();
});

test('pages through the photos below a wider place from the address, named after it', async () => {
  const at = (id, path, placeIds) => ({ id, path, ids: placeIds, source: 'gps' });
  const list = [
    photo(3, { place: at('P414', ['Pune', 'Maharashtra', 'India'], ['P414', 'P20', 'P1']) }),
    photo(2, { place: at('P900', ['Kenya'], ['P900']) }),
    photo(1, { place: at('P420', ['Satara', 'Maharashtra', 'India'], ['P420', 'P20', 'P1']) }),
  ];
  window.location.hash = '#/species/A5/photo/3?place=P20';
  const { container } = open(3, { place: 'P20', photos: list });
  expect(container.querySelector('.c').textContent).toBe('1 / 2 · Maharashtra');
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/species/A5/photo/1?place=P20');
});

test('a species added from Move, whose move then fails, is named in the viewer’s message', async () => {
  search.mockResolvedValue([]);
  getChildren.mockResolvedValue([{ id: 'A1', rank: 'family', name: 'Nymphalidae', common: null, family: 'Nymphalidae', species: { got: 0, total: 1 } }]);
  checkCustomName.mockResolvedValue({ ok: true, name: 'Neptis sankara' });
  addCustomSpecies.mockResolvedValue({ id: 'U3', rank: 'species', name: 'Neptis sankara', common: null, family: 'Nymphalidae', source: 'manual' });
  movePhotos.mockRejectedValue(new Error('That species folder is read-only.'));
  open();

  await fireEvent.click(screen.getByRole('button', { name: 'Move…' }));
  await fireEvent.input(screen.getByRole('combobox', { name: 'Species' }), { target: { value: 'Neptis sankara' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a custom species/ }));
  const dialog = await screen.findByRole('dialog', { name: 'Add a species' });
  await fireEvent.click((await within(dialog).findAllByRole('radio'))[0]);
  await vi.waitFor(() => expect(within(dialog).getByRole('button', { name: 'Add' }).getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));

  expect((await screen.findByRole('alert')).textContent).toBe('Neptis sankara was added to Custom additions. That species folder is read-only.');
  expect(movePhotos).toHaveBeenCalledWith([3], 'U3');
  expect(screen.queryByRole('combobox')).toBeNull();
});

const RAW_NOTE = "This RAW photo's preview can't be read any more. The file may have changed or be damaged. Add it again from your camera's card, or save it as JPEG from your RAW software and add that.";

test('a RAW photo is turned upright, says what it shows, and a broken one says why at once (Phase 7a)', async () => {
  const nef = photo(3, { fileName: 'Danaus_chrysippus_03.nef', fileType: 'raw', width: 1080, height: 1616, rawPreview: { width: 1080, height: 1616, orientation: 8 } });
  const { container, unmount } = render(PhotoViewer, { taxon, photos: [nef], photoId: 3, tags, onClose: vi.fn(), onChanged: vi.fn() });
  expect(container.querySelector('img.orig').style.transform).toContain('rotate(-90deg)');
  expect(screen.getByText('RAW preview, 1080 × 1616')).toBeTruthy();
  unmount();

  const lost = photo(3, { fileType: 'raw', rawPreview: null, unreadable: true });
  const again = render(PhotoViewer, { taxon, photos: [lost], photoId: 3, tags, onClose: vi.fn(), onChanged: vi.fn() });
  expect(again.container.querySelector('img.orig')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe(RAW_NOTE);
  expect(screen.queryByText(/RAW preview,/)).toBeNull();
});

test('any photo that can\'t be shown says why and what to do at once, never "showing a preview" (review fix U2)', () => {
  const broken = photo(3, { fileType: 'png', rawPreview: null, unreadable: true });
  const { container } = render(PhotoViewer, { taxon, photos: [broken], photoId: 3, tags, onClose: vi.fn(), onChanged: vi.fn() });
  expect(container.querySelector('img.orig')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe(
    "This photo can't be shown: its file may be damaged, or in a form Butterfly Atlas can't read. Replace the file, or delete the photo here and add it again as a JPEG.");
});

test('a JPEG has no RAW line and no turn', () => {
  const { container } = open();
  expect(container.querySelector('img.orig').style.transform).toBe('');
  expect(screen.queryByText(/RAW preview/)).toBeNull();
});

const heicPhoto = (over) => photo(3, { fileName: 'Danaus_chrysippus_03.heic', fileType: 'heic', width: 3024, height: 4032, heic: { tenBit: true, auxiliary: false }, ...over });

test('a HEIC photo on a computer without a decoder says the install note at once, and its lines (Phase 7b)', () => {
  status.heic = { state: 'missing', decoder: null, system: 'windows', reason: 'absent' };
  const { container } = render(PhotoViewer, { taxon, photos: [heicPhoto({ unreadable: true })], photoId: 3, tags, onClose: vi.fn(), onChanged: vi.fn() });
  expect(container.querySelector('img.orig')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe(
    "This computer can't show HEIC photos yet. Install Windows' HEVC decoder, then choose Check again in Settings → About. Your photo is safely in the library.");
  expect(screen.getByText('HEIC, 3024 × 4032')).toBeTruthy();
  expect(screen.getByText('Shown in standard range (the file keeps its full range).')).toBeTruthy();
});

test('a HEIC photo waits while the decoder is checked, then loads; one the decoder failed on says why (audit 7)', async () => {
  status.heic = { state: 'checking', decoder: null, system: 'mac', reason: null };
  const { container } = render(PhotoViewer, { taxon, photos: [heicPhoto()], photoId: 3, tags, onClose: vi.fn(), onChanged: vi.fn() });
  expect(container.querySelector('img.orig')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe('Checking for a HEIC decoder…');

  status.heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  await vi.waitFor(() => expect(container.querySelector('img.orig').getAttribute('src')).toBe('./api/photos/3/display'));
});

test('a HEIC photo the decoder could not read says the decode note at once', () => {
  status.heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  render(PhotoViewer, { taxon, photos: [heicPhoto({ unreadable: true })], photoId: 3, tags, onClose: vi.fn(), onChanged: vi.fn() });
  expect(screen.getByRole('status').textContent).toBe(
    "This HEIC photo can't be shown: the computer's decoder couldn't read it. The file may be damaged, or use a HEIC feature the decoder doesn't support. Save it as JPEG on the device it came from and add that.");
});

function openedFrom(address) {
  forgetViewerOpen();
  window.location.hash = address;
  noteViewerOpen();
  window.location.hash = '#/species/A5/photo/3';
}

test('opened from another page, the title links to the species page, replacing the viewer\'s address', async () => {
  const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  openedFrom('#/');
  const { onClose } = open();

  const link = screen.getByRole('link', { name: /Plain Tiger/ });
  expect(link.getAttribute('href')).toBe('#/species/A5');
  expect(link.getAttribute('title')).toBe('Open Plain Tiger');
  expect(link.textContent).toContain('Danaus chrysippus');
  await fireEvent.click(link);

  expect(window.location.hash).toBe('#/species/A5');
  expect(back).not.toHaveBeenCalled();
  expect(onClose).not.toHaveBeenCalled();
  back.mockRestore();
});

test('opened from the species\' own page, or from its address, the title is plain text', () => {
  openedFrom('#/species/A5?tag=male');
  const { unmount } = open();
  expect(screen.queryByRole('link', { name: /Plain Tiger/ })).toBeNull();
  expect(screen.getByText('Plain Tiger')).toBeTruthy();
  unmount();

  forgetViewerOpen();
  window.location.hash = '#/species/A5/photo/3';
  open();
  expect(screen.queryByRole('link', { name: /Plain Tiger/ })).toBeNull();
});

test('a species with no common name is opened by its scientific name', () => {
  openedFrom('#/export?view=photos');
  open(3, { taxon: { ...taxon, common: null } });

  expect(screen.getByRole('link', { name: 'Danaus chrysippus' }).getAttribute('title')).toBe('Open Danaus chrysippus');
});
