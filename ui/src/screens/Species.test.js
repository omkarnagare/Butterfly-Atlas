import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import Species from './Species.svelte';
import Toast from '../lib/Toast.svelte';
import {
  addCustomSpecies, getChildren, checkCustomName, deleteCustomSpecies, getDeleted, renameCustomSpecies,
  deletePhotos, editTags, getMyPlaces, getPhotos, getRecentPlaces, getTags, getTaxon, movePhotos, placePhotos, search, unplacePhotos, uploadPhoto,
} from '../lib/api.js';

vi.mock('../lib/api.js', () => ({
  getTaxon: vi.fn(), getPhotos: vi.fn(), getTags: vi.fn(), uploadPhoto: vi.fn(),
  deletePhotos: vi.fn(), editTags: vi.fn(), movePhotos: vi.fn(), search: vi.fn(), getStatus: vi.fn(),
  addSearchWord: vi.fn(), removeSearchWord: vi.fn(), PHOTO_ACCEPT: 'image/jpeg',
  isPhotoSequence: (file) => /\.hei[cf]s$/i.test(file.name),
  getMyPlaces: vi.fn(), renamePlace: vi.fn(), deletePlace: vi.fn(), getRecentPlaces: vi.fn(), searchPlaces: vi.fn(), addPlace: vi.fn(),
  placePhotos: vi.fn(), unplacePhotos: vi.fn(),
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), deleteCustomSpecies: vi.fn(), getChildren: vi.fn(), getDeleted: vi.fn(), renameCustomSpecies: vi.fn(),
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
  displayUrl: (id) => `./api/photos/${id}/display`,
}));

beforeEach(() => {
  vi.resetAllMocks();
  getPhotos.mockResolvedValue([]);
  getTags.mockResolvedValue(TAGS);
});

const TAGS = [
  { id: 1, key: 'egg', label: 'egg', source: 'builtin', photoCount: 0 },
  { id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 1 },
  { id: 6, key: 'male', label: 'male', source: 'builtin', photoCount: 1 },
];

const plainTiger = (over) => ({
  id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true,
  path: [
    { id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null },
    { id: 'A1', rank: 'family', name: 'Nymphalidae', common: 'Brush-footed Butterflies' },
    { id: 'A4', rank: 'genus', name: 'Danaus', common: 'Tigers' },
  ],
  species: { got: 0, total: 1 }, ranks: [], synonyms: ['Limnas klugii'], searchWords: [],
  folder: 'images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/',
  photoCount: 0, stages: { egg: false, larva: false, pupa: false, adult: false }, cover: null, ...over,
});

const photo = (id, tags, over) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.jpg`, relPath: `images/x/Danaus_chrysippus_0${id}.jpg`, tags,
  addedAt: '2026-10-11T09:00:00Z', width: 4000, height: 3000, isCover: false,
  place: { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' }, ...over,
});

test('shows the scientific name, common name, Atlas id, other names and folder', async () => {
  getTaxon.mockResolvedValue(plainTiger());
  const { container } = render(Species, { id: 'A5' });

  expect(await screen.findByRole('heading', { level: 1, name: 'Danaus chrysippus' })).toBeTruthy();
  expect(screen.getByText('Plain Tiger')).toBeTruthy();
  expect(screen.getByText('Atlas ID A5')).toBeTruthy();
  expect(screen.getByText('Limnas klugii')).toBeTruthy();
  expect(container.textContent).toContain('Folder created with the first photo: images/Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus_[A5]/');
  expect(screen.getByRole('heading', { name: 'Not photographed yet' })).toBeTruthy();
  expect(screen.getByRole('button', { name: /Search words/ })).toBeTruthy();
});

test('marks a species that is no longer on the India list', async () => {
  getTaxon.mockResolvedValue(plainTiger({ active: false, species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, [])]);
  render(Species, { id: 'A5' });

  expect(await screen.findByText('Not in the current India list')).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Not photographed yet' })).toBeNull();
});

test('shows the photo count, life stages and the gallery', async () => {
  getTaxon.mockResolvedValue(plainTiger({
    species: { got: 1, total: 1 }, photoCount: 2, stages: { egg: false, larva: true, pupa: false, adult: true },
  }));
  getPhotos.mockResolvedValue([photo(2, ['male']), photo(1, ['larva'])]);
  const { container } = render(Species, { id: 'A5' });

  expect(await screen.findByRole('link', { name: 'Open Danaus_chrysippus_02.jpg, male' })).toBeTruthy();
  expect(container.querySelector('.facts').textContent).toContain('2photos');
  expect(container.querySelector('.facts').textContent).toContain('2/4life stages');
  expect(screen.getByRole('img', { name: 'Life stages photographed: larva, adult. Not yet: egg, pupa.' })).toBeTruthy();
  expect(container.textContent).toContain('Stored in images/');
  expect(getPhotos).toHaveBeenCalledWith('A5');
});

const jpeg = (name) => new File([new Uint8Array(3)], name, { type: 'image/jpeg' });

async function addFirstPhoto(milestone) {
  getTaxon.mockResolvedValue(plainTiger());
  uploadPhoto.mockResolvedValue({ status: 'added', photo: photo(1, []), otherTaxon: null, firstForSpecies: true, milestone, speciesCount: 10 });
  render(Species, { id: 'A5' });
  render(Toast);

  await fireEvent.click(await screen.findByRole('button', { name: '+ Add first photos' }));
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, [])]);
  await fireEvent.change(screen.getByLabelText('Choose photos'), { target: { files: [jpeg('a.jpg')] } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));
}

test('the first photo of a species gets a quiet message under reduced motion, and its tile says New', async () => {
  await addFirstPhoto(10);

  expect(await screen.findByText('Plain Tiger is species number 10 in your atlas. Milestone reached: 10 species.')).toBeTruthy();
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.getByText('New')).toBeTruthy();
});

test('the first photo of a species is celebrated when motion is allowed', async () => {
  window.matchMedia = () => ({ matches: false });
  await addFirstPhoto(null);

  const dialog = await screen.findByRole('dialog', { name: 'New species in your atlas' });
  expect(dialog.textContent).toContain('Plain Tiger is species number 10.');
  expect(document.querySelector('.gal').hasAttribute('inert')).toBe(true);
});

test('closing the sheet with Escape while a photo is still being sent still reports it; reopening shows nothing stale', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 2 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, [])]);
  let finish;
  uploadPhoto.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
  render(Species, { id: 'A5' });
  render(Toast);

  await fireEvent.click(await screen.findByRole('button', { name: '+ Add photos' }));
  await fireEvent.change(screen.getByLabelText('Choose photos'), { target: { files: [jpeg('a.jpg')] } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add 1 photo' }));
  await screen.findByRole('button', { name: 'Stop' });
  await fireEvent.keyDown(window, { key: 'Escape' });
  finish({ status: 'added', photo: photo(2, []), otherTaxon: null, firstForSpecies: false, milestone: null, speciesCount: 1 });

  expect(await screen.findByText('1 photo added to Plain Tiger.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

  await fireEvent.click(screen.getByRole('button', { name: '+ Add photos' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.getAllByRole('status').map((el) => el.textContent)).toContain('1 photo added to Plain Tiger.');
});

test('a file dropped on the page is never opened by the web view; unsupported files are named and left out', async () => {
  getTaxon.mockResolvedValue(plainTiger());
  render(Species, { id: 'A5' });
  render(Toast);
  await screen.findByRole('button', { name: '+ Add first photos' });

  const event = new Event('drop', { bubbles: true, cancelable: true });
  event.dataTransfer = { types: ['Files'], files: [jpeg('ok.jpg'), new File(['x'], 'raw.cr2')] };
  window.dispatchEvent(event);

  expect(event.defaultPrevented).toBe(true);
  expect(await screen.findByText('raw.cr2 not added: only JPEG, PNG, TIFF, WebP, RAW and HEIC photos can be added.')).toBeTruthy();
  expect(await screen.findByRole('dialog', { name: 'Add photos to Danaus chrysippus' })).toBeTruthy();
});

test('a dropped file is still kept from the web view while the species is loading', () => {
  getTaxon.mockReturnValue(new Promise(() => {}));
  render(Species, { id: 'A5' });

  const event = new Event('drop', { bubbles: true, cancelable: true });
  event.dataTransfer = { types: ['Files'], files: [jpeg('ok.jpg')] };
  window.dispatchEvent(event);

  expect(event.defaultPrevented).toBe(true);
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('a photo address opens the viewer over the page; an unknown photo shows the page alone', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, ['larva'])]);
  const { rerender } = render(Species, { id: 'A5', photoId: 1 });

  expect(await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Open Danaus_chrysippus_01.jpg, larva' })).toBeTruthy();
  // the place is in the viewer's side panel and the Where photographed line, not on the gallery's tile
  expect(screen.getByRole('complementary', { name: 'About this photo' }).textContent).toContain('Pune, Maharashtra, India from GPS');
  expect(screen.getByRole('link', { name: 'Open Danaus_chrysippus_01.jpg, larva' }).textContent).not.toContain('Pune');

  await rerender({ id: 'A5', photoId: 99 });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/species/A5'));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('an unknown photo with the gallery chips in its address shows the page with those chips', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, ['larva'])]);
  render(Species, { id: 'A5', photoId: 99, tag: 'larva', place: 'P414' });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/species/A5?tag=larva&place=P414'));
});

test('a photo address with a place pages through the photos at that place', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([photo(3, []), photo(2, [], { place: null }), photo(1, [], { place: null })]);
  const { container } = render(Species, { id: 'A5', photoId: 2, place: 'unknown' });

  await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' });
  expect(container.ownerDocument.querySelector('.lb .c').textContent).toBe('1 / 2 · Unknown place');
});

async function selectTwo() {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([photo(3, []), photo(2, []), photo(1, [])]);
  render(Species, { id: 'A5' });
  render(Toast);
  await fireEvent.click(await screen.findByRole('button', { name: 'Select' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select Danaus_chrysippus_03.jpg' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select Danaus_chrysippus_02.jpg' }));
  expect(screen.getByText('2 photos selected')).toBeTruthy();
}

test('Select all selects what the gallery shows under its filter', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([photo(3, ['larva']), photo(2, []), photo(1, ['larva'])]);
  const { container } = render(Species, { id: 'A5' });

  await fireEvent.click(await screen.findByRole('button', { name: 'larva, 2 photos' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select all' }));

  expect(screen.getByText('2 photos selected')).toBeTruthy();
  expect([...container.querySelectorAll('.tile.sel')].map((b) => b.getAttribute('aria-label'))).toEqual([
    'Select Danaus_chrysippus_03.jpg, larva', 'Select Danaus_chrysippus_01.jpg, larva',
  ]);
});

test('selected photos can be deleted together', async () => {
  deletePhotos.mockResolvedValue(2);
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Delete' }));

  expect(deletePhotos).toHaveBeenCalledWith([3, 2]);
  expect(await screen.findByText('2 photos moved to Recently deleted.')).toBeTruthy();
  expect(screen.queryByRole('toolbar')).toBeNull();
});

test('selected photos can be moved to another species', async () => {
  movePhotos.mockResolvedValue([]);
  search.mockResolvedValue([{ id: 'A6', rank: 'species', name: 'Danaus genutia', common: 'Striped Tiger', family: 'Nymphalidae' }]);
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Move' }));
  expect(screen.getByRole('dialog', { name: 'Move 2 photos to another species' })).toBeTruthy();
  await fireEvent.input(screen.getByRole('combobox', { name: 'Species' }), { target: { value: 'striped' } });
  await fireEvent.click(await screen.findByRole('option', { name: /Striped Tiger/ }));

  expect(movePhotos).toHaveBeenCalledWith([3, 2], 'A6');
  expect(await screen.findByText('2 photos moved to Striped Tiger.')).toBeTruthy();
});

test('a failed bulk tag shows its error in the dialog and the dialog stays usable', async () => {
  editTags.mockRejectedValueOnce(new Error('The library is read-only.'));
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Tag' }));
  const chip = screen.getByRole('checkbox', { name: 'larva' });
  await fireEvent.click(chip);
  await fireEvent.click(screen.getByRole('button', { name: 'Apply' }));

  expect((await screen.findByRole('alert')).textContent).toBe('The library is read-only.');
  expect(screen.getByRole('dialog', { name: 'Tag 2 photos' })).toBeTruthy();
  await fireEvent.click(chip);
  expect(chip.getAttribute('aria-checked')).toBe('false');
  expect(screen.getByText('2 photos selected')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Delete' }).getAttribute('aria-disabled')).toBe('false');
});

test('a failed move shows its error in the picker', async () => {
  movePhotos.mockRejectedValueOnce(new Error('That species folder is read-only.'));
  search.mockResolvedValue([{ id: 'A6', rank: 'species', name: 'Danaus genutia', common: 'Striped Tiger', family: 'Nymphalidae' }]);
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Move' }));
  await fireEvent.input(screen.getByRole('combobox', { name: 'Species' }), { target: { value: 'striped' } });
  await fireEvent.click(await screen.findByRole('option', { name: /Striped Tiger/ }));

  expect((await screen.findByRole('alert')).textContent).toBe('That species folder is read-only.');
  expect(screen.getByRole('dialog', { name: 'Move 2 photos to another species' })).toBeTruthy();
});

test('changing the filter clears the selection', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([photo(3, ['larva']), photo(2, []), photo(1, ['larva'])]);
  render(Species, { id: 'A5' });
  await fireEvent.click(await screen.findByRole('button', { name: 'Select' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select Danaus_chrysippus_03.jpg, larva' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select Danaus_chrysippus_02.jpg' }));
  expect(screen.getByText('2 photos selected')).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'larva, 2 photos' }));

  expect(screen.getByText('0 photos selected')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Select all' })).toBeTruthy();
});

test('focus goes to Select when the selection ends', async () => {
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Select' })));
});

test('reloads its photos in place when the photo check finds new ones', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, [])]);
  render(Species, { id: 'A5' });
  await screen.findByRole('link', { name: 'Open Danaus_chrysippus_01.jpg' });

  getPhotos.mockResolvedValue([photo(2, []), photo(1, [])]);
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));

  expect(await screen.findByRole('link', { name: 'Open Danaus_chrysippus_02.jpg' })).toBeTruthy();
});

test('the gallery shows where its photos were taken; a place chip and a tag chip filter it together', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([photo(3, ['larva']), photo(2, ['larva'], { place: null }), photo(1, [])]);
  render(Species, { id: 'A5' });

  const where = await screen.findByRole('group', { name: 'Where photographed' });
  expect(where.textContent.replace(/\s+/g, ' ').trim()).toBe('Where photographed Pune 2 1 unknown');
  await fireEvent.click(screen.getByRole('button', { name: 'Pune, 2 photos' }));
  await fireEvent.click(screen.getByRole('button', { name: 'larva, 2 photos' }));
  expect(screen.getAllByRole('link', { name: /^Open/ }).map((a) => a.getAttribute('aria-label'))).toEqual(['Open Danaus_chrysippus_03.jpg, larva']);

  await fireEvent.click(screen.getByRole('button', { name: 'Select' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select all' }));
  expect(screen.getByText('1 photo selected')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Pune, 2 photos' }));
  expect(screen.getByText('0 photos selected')).toBeTruthy();
});

test('a species without photos has no Where photographed line', async () => {
  getTaxon.mockResolvedValue(plainTiger());
  render(Species, { id: 'A5' });
  await screen.findByText('Not photographed yet');
  expect(screen.queryByRole('group', { name: 'Where photographed' })).toBeNull();
});

test('Places, next to Tags, opens Your places; a change there reads the photos again', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, [])]);
  getMyPlaces.mockResolvedValue([{ id: 'PU3', name: 'Tamhini', path: ['Tamhini', 'Maharashtra', 'India'], level: 'district', mine: true, photos: 0 }]);
  render(Species, { id: 'A5' });

  const places = await screen.findByRole('button', { name: 'Places' });
  expect(places.previousElementSibling.textContent).toBe('Tags');
  await fireEvent.click(places);
  expect(await screen.findByRole('dialog', { name: 'Your places' })).toBeTruthy();
  await fireEvent.click(await screen.findByRole('button', { name: 'Delete Tamhini' }));
  await vi.waitFor(() => expect(getPhotos).toHaveBeenCalledTimes(2));
  await fireEvent.click(screen.getByRole('button', { name: 'Done' }));
  expect(screen.queryByRole('dialog', { name: 'Your places' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(places));
});

test('selected photos get a place from the picker, with a toast', async () => {
  getRecentPlaces.mockResolvedValue([{ id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false }]);
  placePhotos.mockResolvedValue([]);
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  expect(screen.getByRole('dialog', { name: 'Place for 2 photos' })).toBeTruthy();
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));

  expect(placePhotos).toHaveBeenCalledWith([3, 2], 'P414');
  expect(await screen.findByText('Pune set on 2 photos.')).toBeTruthy();
  expect(screen.queryByRole('dialog', { name: 'Place for 2 photos' })).toBeNull();
  expect(screen.queryByText('2 photos selected')).toBeNull();
  expect(getPhotos).toHaveBeenCalledTimes(2);
});

test('a refused place shows its error in the picker', async () => {
  getRecentPlaces.mockResolvedValue([{ id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false }]);
  placePhotos.mockRejectedValue(new Error('No such place.'));
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));
  expect((await screen.findByRole('alert')).textContent).toBe('No such place.');
  expect(screen.getByRole('dialog', { name: 'Place for 2 photos' })).toBeTruthy();
});

test('Remove place takes the place off the selected photos without asking; the photos are read again', async () => {
  unplacePhotos.mockResolvedValue([]);
  await selectTwo();
  getPhotos.mockResolvedValue([photo(3, [], { place: null }), photo(2, [], { place: null }), photo(1, [])]);

  await fireEvent.click(screen.getByRole('button', { name: 'Remove place' }));

  expect(unplacePhotos).toHaveBeenCalledWith([3, 2]);
  expect(await screen.findByText('Place removed from 2 photos.')).toBeTruthy();
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.queryByText('2 photos selected')).toBeNull();
  await vi.waitFor(() => expect(getPhotos).toHaveBeenCalledTimes(2));
  expect(await screen.findByRole('button', { name: 'Unknown place, 2 photos' })).toBeTruthy();
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Select' })));
});

test('a refused Remove place shows a toast and keeps the selection', async () => {
  unplacePhotos.mockRejectedValue(new Error('That photo is in Recently deleted.'));
  await selectTwo();

  await fireEvent.click(screen.getByRole('button', { name: 'Remove place' }));

  expect(await screen.findByText('That photo is in Recently deleted.')).toBeTruthy();
  expect(screen.getByText('2 photos selected')).toBeTruthy();
  expect(getPhotos).toHaveBeenCalledTimes(1);
});

test('changing the place filter clears the selection', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([photo(3, []), photo(2, [], { place: null }), photo(1, [])]);
  render(Species, { id: 'A5' });
  await fireEvent.click(await screen.findByRole('button', { name: 'Select' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Select Danaus_chrysippus_02.jpg' }));
  expect(screen.getByText('1 photo selected')).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'Unknown place, 1 photo' }));

  expect(screen.getByText('0 photos selected')).toBeTruthy();
});

async function addPhotos(places, { first = false } = {}) {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, [])]);
  uploadPhoto.mockImplementation(async (taxonId, file) => {
    const id = Number(file.name[0]);
    return { status: 'added', photo: photo(id, [], { place: places[id] }), otherTaxon: null, firstForSpecies: first, milestone: null, speciesCount: null };
  });
  render(Species, { id: 'A5' });
  render(Toast);
  await fireEvent.click(await screen.findByRole('button', { name: '+ Add photos' }));
  const ids = Object.keys(places);
  await fireEvent.change(screen.getByLabelText('Choose photos'), { target: { files: ids.map((i) => jpeg(`${i}.jpg`)) } });
  await fireEvent.click(screen.getByRole('button', { name: ids.length === 1 ? 'Add 1 photo' : `Add ${ids.length} photos` }));
}

test('after a clean add the sheet closes and the toast says how many have an unknown place', async () => {
  await addPhotos({ 2: null, 3: null, 4: null });

  expect(await screen.findByText('3 photos added to Plain Tiger. 3 have an unknown place.')).toBeTruthy();
  expect(screen.queryByRole('dialog', { name: /Add photos/ })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Select them' })).toBeNull();
});

test('the unknown-place sentence is singular, and "It" when the only photo added has none', async () => {
  await addPhotos({ 2: null, 3: { id: 'P1', path: ['Pune'], source: 'gps' } });
  expect(await screen.findByText('2 photos added to Plain Tiger. 1 has an unknown place.')).toBeTruthy();
});

test('one added photo with an unknown place: "It has an unknown place."', async () => {
  await addPhotos({ 2: null });
  expect(await screen.findByText('1 photo added to Plain Tiger. It has an unknown place.')).toBeTruthy();
});

test('photos that all have a place get the plain toast', async () => {
  await addPhotos({ 2: { id: 'P1', path: ['Pune'], source: 'gps' } });
  expect(await screen.findByText('1 photo added to Plain Tiger.')).toBeTruthy();
});

test('the address can pre-set the tag and place chips (from the Export results); a wider place filters by the photos\' place ids', async () => {
  const at = (id, path, placeIds) => ({ id, path, ids: placeIds, source: 'gps' });
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([
    photo(3, ['larva'], { place: at('P412', ['Pune', 'Maharashtra', 'India'], ['P412', 'P20', 'P1']) }),
    photo(2, ['larva'], { place: at('P900', ['Kenya'], ['P900']) }),
    photo(1, [], { place: at('P420', ['Satara', 'Maharashtra', 'India'], ['P420', 'P20', 'P1']) }),
  ]);
  render(Species, { id: 'A5', tag: 'larva', place: 'P20' });

  expect(await screen.findByRole('button', { name: 'in Maharashtra, 2 photos' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'larva, 2 photos' }).getAttribute('aria-pressed')).toBe('true');
  expect(screen.getAllByRole('link', { name: /^Open/ }).map((a) => a.getAttribute('aria-label'))).toEqual(['Open Danaus_chrysippus_03.jpg, larva']);
});

test('a pre-set place chip whose place is on none of the photos falls back to every place; so does one whose place goes away', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 2 }));
  getPhotos.mockResolvedValue([photo(2, []), photo(1, [], { place: null })]);
  render(Species, { id: 'A5', place: 'P999', tag: 'egg' });

  await screen.findByRole('group', { name: 'Where photographed' });
  await vi.waitFor(() => expect(screen.getAllByRole('link', { name: /^Open/ })).toHaveLength(2));
  expect(screen.getByRole('button', { name: 'All 2' }).getAttribute('aria-pressed')).toBe('true');
  expect(screen.getAllByRole('button', { pressed: true }).map((b) => b.textContent.trim())).toEqual(['All 2']);

  // the place is deleted while its chip is on: its photos come back with no place
  await fireEvent.click(screen.getByRole('button', { name: 'Pune, 1 photo' }));
  expect(screen.getAllByRole('link', { name: /^Open/ })).toHaveLength(1);
  getPhotos.mockResolvedValue([photo(2, [], { place: null }), photo(1, [], { place: null })]);
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  await vi.waitFor(() => expect(screen.getAllByRole('link', { name: /^Open/ })).toHaveLength(2));
  expect(screen.queryByRole('button', { name: /^Pune/ })).toBeNull();
});

const custom = (over) => plainTiger({
  id: 'U3', name: 'Neptis sankara', common: null, source: 'manual', familyId: 'A1', synonyms: [],
  path: [
    { id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null },
    { id: 'U:butterflies', rank: 'additions', name: 'Custom additions', common: null },
  ],
  folder: 'images/Custom_additions/Neptis_sankara_[U3]/', ...over,
});

test('only a custom species has Edit…; deleting it goes to Custom additions with a toast', async () => {
  getTaxon.mockResolvedValue(plainTiger());
  const { unmount } = render(Species, { id: 'A5' });
  await screen.findByRole('heading', { level: 1, name: 'Danaus chrysippus' });
  expect(screen.queryByRole('button', { name: 'Edit…' })).toBeNull();
  unmount();

  getTaxon.mockResolvedValue(custom());
  getDeleted.mockResolvedValue([]);
  deleteCustomSpecies.mockResolvedValue(null);
  render(Toast);
  render(Species, { id: 'U3' });
  await screen.findByRole('heading', { level: 1, name: 'Neptis sankara' });
  expect(screen.getByText('Custom')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Edit…' }));
  await fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete…' }));
  await fireEvent.click(within(await screen.findByRole('dialog', { name: 'Delete Neptis sankara?' })).getByRole('button', { name: 'Delete' }));

  await vi.waitFor(() => expect(window.location.hash).toBe('#/explore/U%3Abutterflies'));
  expect(await screen.findByText('Neptis sankara deleted.')).toBeTruthy();
});

test('deleting a custom moth goes to the moths’ Custom additions', async () => {
  getTaxon.mockResolvedValue(custom({
    id: 'U4', name: 'Tyria jacobaeae',
    path: [
      { id: 'G:moths', rank: 'group', name: 'Moths', common: null },
      { id: 'U:moths', rank: 'additions', name: 'Custom additions', common: null },
    ],
    folder: 'images/Custom_additions/Tyria_jacobaeae_[U4]/',
  }));
  getDeleted.mockResolvedValue([]);
  deleteCustomSpecies.mockResolvedValue(null);
  render(Species, { id: 'U4' });
  await screen.findByRole('heading', { level: 1, name: 'Tyria jacobaeae' });
  await fireEvent.click(screen.getByRole('button', { name: 'Edit…' }));
  await fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete…' }));
  await fireEvent.click(within(await screen.findByRole('dialog', { name: 'Delete Tyria jacobaeae?' })).getByRole('button', { name: 'Delete' }));

  await vi.waitFor(() => expect(window.location.hash).toBe('#/explore/U%3Amoths'));
});

test('Delete… on a custom species counts its gallery’s photos', async () => {
  getTaxon.mockResolvedValue(custom({ photoCount: 2, species: { got: 1, total: 1 } }));
  getPhotos.mockResolvedValue([photo(1, [], { taxonId: 'U3' }), photo(2, [], { taxonId: 'U3' })]);
  getDeleted.mockResolvedValue([]);
  render(Species, { id: 'U3' });
  await fireEvent.click(await screen.findByRole('button', { name: 'Edit…' }));
  const del = await screen.findByRole('menuitem', { name: 'Delete…' });
  await vi.waitFor(() => expect(del.getAttribute('aria-disabled')).toBe('true'));
  expect(document.getElementById(del.getAttribute('aria-describedby')).textContent).toBe('Move its 2 photos first.');
});

test('a renamed custom species is read again, with a toast', async () => {
  getTaxon.mockResolvedValueOnce(custom()).mockResolvedValue(custom({ name: 'Neptis soma', folder: 'images/Custom_additions/Neptis_soma_[U3]/' }));
  getDeleted.mockResolvedValue([]);
  checkCustomName.mockImplementation(async (name) => ({ ok: true, name }));
  renameCustomSpecies.mockResolvedValue(custom({ name: 'Neptis soma' }));
  render(Toast);
  render(Species, { id: 'U3' });
  await fireEvent.click(await screen.findByRole('button', { name: 'Edit…' }));
  await fireEvent.click(await screen.findByRole('menuitem', { name: 'Rename…' }));
  const dialog = await screen.findByRole('dialog', { name: 'Rename species' });
  await fireEvent.input(within(dialog).getByRole('textbox', { name: 'Scientific name' }), { target: { value: 'Neptis soma' } });
  const rename = within(dialog).getByRole('button', { name: 'Rename' });
  await vi.waitFor(() => expect(rename.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(rename);

  expect(await screen.findByRole('heading', { level: 1, name: 'Neptis soma' })).toBeTruthy();
  expect(screen.getByText('Renamed to Neptis soma.')).toBeTruthy();
  expect(getTaxon).toHaveBeenCalledTimes(2);
});

/** Through the picker's add row: "Neptis sankara" added in Nymphalidae, then picked. */
async function addFromPicker(dialog) {
  await fireEvent.input(within(dialog).getByRole('combobox'), { target: { value: 'Neptis sankara' } });
  await fireEvent.click(await within(dialog).findByRole('option', { name: 'Add “Neptis sankara” as a custom species' }));
  const add = await screen.findByRole('dialog', { name: 'Add a species' });
  await fireEvent.click((await within(add).findAllByRole('radio'))[0]);
  await vi.waitFor(() => expect(within(add).getByRole('button', { name: 'Add' }).getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(within(add).getByRole('button', { name: 'Add' }));
}

const customSetup = () => {
  search.mockResolvedValue([]);
  getChildren.mockResolvedValue([{ id: 'A1', rank: 'family', name: 'Nymphalidae', common: null, family: 'Nymphalidae', species: { got: 0, total: 1 } }]);
  checkCustomName.mockResolvedValue({ ok: true, name: 'Neptis sankara' });
  addCustomSpecies.mockResolvedValue({ id: 'U3', rank: 'species', name: 'Neptis sankara', common: null, family: 'Nymphalidae', source: 'manual' });
};

test('a species added from the bulk Move picker whose move then fails is named in the picker’s message', async () => {
  customSetup();
  movePhotos.mockRejectedValueOnce(new Error('That species folder is read-only.'));
  await selectTwo();
  await fireEvent.click(screen.getByRole('button', { name: 'Move' }));
  const dialog = screen.getByRole('dialog', { name: 'Move 2 photos to another species' });
  await addFromPicker(dialog);

  await vi.waitFor(() => expect(movePhotos).toHaveBeenCalledWith([3, 2], 'U3'));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Neptis sankara was added to Custom additions. That species folder is read-only.');
  expect(dialog.isConnected).toBe(true);
});

test('with photos of more than one file type, a File type line filters the gallery with the others; a type from the address is pre-set (Phase 7a)', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  const raw = { fileType: 'raw', rawPreview: { width: 4, height: 3, orientation: 1 } };
  getPhotos.mockResolvedValue([photo(3, ['larva'], { ...raw, fileName: 'Danaus_chrysippus_03.nef' }), photo(2, ['larva'], { fileType: 'jpeg' }), photo(1, [], { fileType: 'jpeg' })]);
  render(Species, { id: 'A5', type: 'raw' });

  const types = await screen.findByRole('group', { name: 'File type' });
  expect(within(types).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['JPEG, 2 photos', 'RAW, 1 photo']);
  expect(screen.getByRole('button', { name: 'RAW, 1 photo' }).getAttribute('aria-pressed')).toBe('true');
  expect(screen.getAllByRole('link', { name: /^Open/ }).map((a) => a.getAttribute('href'))).toEqual(['#/species/A5/photo/3?type=raw']);

  await fireEvent.click(screen.getByRole('button', { name: 'RAW, 1 photo' }));
  expect(screen.getAllByRole('link', { name: /^Open/ })).toHaveLength(3);
  await fireEvent.click(screen.getByRole('button', { name: 'JPEG, 2 photos' }));
  expect(screen.getAllByRole('link', { name: /^Open/ }).map((a) => a.getAttribute('aria-label'))).toEqual(['Open Danaus_chrysippus_02.jpg, larva', 'Open Danaus_chrysippus_01.jpg']);
});

test('photos of one file type have no File type line', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 2 }));
  getPhotos.mockResolvedValue([photo(2, [], { fileType: 'jpeg' }), photo(1, [], { fileType: 'jpeg' })]);
  render(Species, { id: 'A5' });
  await screen.findByRole('group', { name: 'Where photographed' });
  expect(screen.queryByRole('group', { name: 'File type' })).toBeNull();
});

test('a photo address with a file type pages through the photos of that type, and an unknown photo keeps it (Phase 7a)', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 3 }));
  getPhotos.mockResolvedValue([photo(3, [], { fileType: 'raw', rawPreview: { width: 4, height: 3, orientation: 1 } }), photo(2, [], { fileType: 'jpeg' }), photo(1, [], { fileType: 'raw', rawPreview: { width: 4, height: 3, orientation: 1 } })]);
  const { container, unmount } = render(Species, { id: 'A5', photoId: 1, type: 'raw' });
  await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' });
  expect(container.ownerDocument.querySelector('.lb .c').textContent).toBe('2 / 2 · RAW');
  unmount();

  render(Species, { id: 'A5', photoId: 99, type: 'raw' });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/species/A5?type=raw'));
});

test('a dropped HEIC image sequence goes to the sheet, which says why it can\'t be added (7b review fix U4)', async () => {
  getTaxon.mockResolvedValue(plainTiger());
  render(Species, { id: 'A5' });
  render(Toast);
  await screen.findByRole('button', { name: '+ Add first photos' });

  const event = new Event('drop', { bubbles: true, cancelable: true });
  event.dataTransfer = { types: ['Files'], files: [new File(['x'], 'burst.HEICS'), new File(['x'], 'notes.txt')] };
  window.dispatchEvent(event);

  expect(await screen.findByText('notes.txt not added: only JPEG, PNG, TIFF, WebP, RAW and HEIC photos can be added.')).toBeTruthy();
  // The sheet takes it (and refuses it with the sequence words when it is added).
  expect(within(await screen.findByRole('dialog', { name: 'Add photos to Danaus chrysippus' })).getByRole('button', { name: 'Add 1 photo' })).toBeTruthy();
});

test('the gallery takes the family colour, for its selected tag chip (Meadow §4)', async () => {
  getTaxon.mockResolvedValue(plainTiger({ species: { got: 1, total: 1 }, photoCount: 1 }));
  getPhotos.mockResolvedValue([photo(1, ['larva'])]);
  const { container } = render(Species, { id: 'A5' });

  const chips = await screen.findByRole('group', { name: 'Show photos tagged' });
  const tools = container.querySelector('.tools');
  expect(tools.contains(chips)).toBe(true);
  expect(tools.style.getPropertyValue('--fam')).toBe('var(--f-nym)');
  expect(tools.style.getPropertyValue('--fam-deep')).toBe('var(--f-nym-deep)');
});

test('a species not photographed yet: its outline over a small meadow', async () => {
  getTaxon.mockResolvedValue(plainTiger());
  const { container } = render(Species, { id: 'A5' });

  await screen.findByRole('heading', { name: 'Not photographed yet' });
  expect(container.querySelector('.emptyp > svg.bf-out')).not.toBeNull();
  expect(container.querySelector('.emptyp > svg.meadow').getAttribute('aria-hidden')).toBe('true');
});
