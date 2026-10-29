import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { readFileSync } from 'node:fs';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import PlaceUpdateNote from './PlaceUpdateNote.svelte';
import { dismissPlaceUpdate, getPlaceUpdate, getStatus } from './api.js';
import { resetStatus, status } from './status.svelte.js';
import { forgetViewerOpen, viewerOpenedFrom } from './viewer.js';

vi.mock('./api.js', () => ({ getPlaceUpdate: vi.fn(), dismissPlaceUpdate: vi.fn(), getStatus: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  getStatus.mockImplementation(() => new Promise(() => {}));
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => {
  vi.restoreAllMocks();
  for (const h of document.querySelectorAll('h1')) h.remove();
});

const ref = (id, name, path, level = 'district', former = false) => ({ id, name, path, level, former });
const brezice = ref('P4187', 'Brežice', ['Brežice', 'Slovenia']);
const pecice = ref('P4245', 'Brežice (Pečice)', ['Brežice (Pečice) (former district)', 'Slovenia'], 'district', true);
const chittoor = ref('P300', 'Chittoor', ['Chittoor (former district)', 'Andhra Pradesh', 'India'], 'district', true);
const tirupati = ref('P301', 'Tirupati', ['Tirupati', 'Andhra Pradesh', 'India']);
const line = (n) => ({ id: `A${n}`, name: `Danaus species${n}`, common: null, place: chittoor, photos: 20 - n });
const placeNote = (over = {}) => ({
  dismissed: false, versions: ['2026-10-20'], appliedAt: '2026-10-20T09:00:00Z',
  counts: { merged: 1, split: 0, retired: 0, renamed: 2, moved: 0, added: 0, elsewhere: 40 },
  items: [{ type: 'merge', from: [pecice], to: [brezice], photos: 3 }],
  mine: [{ name: 'Tirupati', into: tirupati, photos: 12 }],
  moved: { total: 9, groups: [{ from: chittoor, to: tirupati, photos: 8 }] },
  former: { photos: 12, species: [{ id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', place: chittoor, photos: 8 }, { id: 'A6', name: 'Danaus genutia', common: null, place: chittoor, photos: 4 }] },
  notChecked: [{ photoId: 41, taxonId: 'A5', name: 'Danaus chrysippus', fileName: 'Danaus_chrysippus_041.jpg' }],
  rechecking: false,
  ...over,
});
const text = (el) => el.textContent.replace(/\s+/g, ' ').trim();

test('nothing to show: no card, and nothing is asked', () => {
  const { container } = render(PlaceUpdateNote);
  expect(container.querySelector('section')).toBeNull();
  expect(getPlaceUpdate).not.toHaveBeenCalled();
});

test('the note: title and counts, the changes, your places, the re-check\'s moves, the former places and the photos not re-checked', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote());
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('Place names were updated with this version. 1 place merged, 2 renamed and 40 other places worldwide.'));
  expect(text(card)).toContain('Brežice (Pečice) → Brežice, Slovenia · 3 photos');
  expect(text(card)).toContain('Your place Tirupati is now an official district; its 12 photos moved to it.');
  expect(text(card)).toContain('8 photos moved from Chittoor (former district) to Tirupati.');
  expect(text(card)).toContain('…and 1 more photo moved.');
  expect(text(card)).not.toContain('the numbers here will grow');
  expect(text(card)).toContain('12 of your photos are in a former district. Review them:');
  expect(text(card)).toContain("1 photo couldn't be re-checked; it keeps its old place.");
  const links = within(card).getAllByRole('link');
  expect(links.map((a) => [text(a), a.getAttribute('href')])).toEqual([
    ['Danaus chrysippus — 8 photos in Chittoor (former district)', '#/species/A5?place=P300'],
    ['Danaus genutia — 4 photos in Chittoor (former district)', '#/species/A6?place=P300'],
    ['Danaus_chrysippus_041.jpg', '#/species/A5/photo/41'],
  ]);
  expect(links[0].querySelector('i.bn').textContent).toBe('Danaus chrysippus');
  expect(within(card).queryByRole('button', { name: 'Show all' })).toBeNull();
  expect(within(card).getByRole('button', { name: 'Dismiss' })).toBeTruthy();
});

test('while the re-check runs the note says the numbers will grow, and it is read again when the status changes', async () => {
  getPlaceUpdate.mockResolvedValueOnce(placeNote({ rechecking: true, moved: { total: 8, groups: [{ from: chittoor, to: tirupati, photos: 8 }] } }))
    .mockResolvedValueOnce(placeNote({ rechecking: false, moved: { total: 20, groups: [{ from: chittoor, to: tirupati, photos: 20 }] }, former: { photos: 0, species: [] } }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 8, rechecking: true, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('Checking photo places… — the numbers here will grow.'));
  expect(text(card)).toContain('8 photos moved from Chittoor (former district) to Tirupati.');

  // the same status again (a poll) reads nothing again
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 8, rechecking: true, failed: false, lost: 0 };
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(getPlaceUpdate).toHaveBeenCalledTimes(1);

  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 20, rechecking: false, failed: false, lost: 0 };
  await vi.waitFor(() => expect(text(card)).toContain('20 photos moved from Chittoor (former district) to Tirupati.'));
  expect(text(card)).not.toContain('the numbers here will grow');
  // the photos have moved out of the former place: its lines are gone
  expect(text(card)).not.toContain('former district. Review them');
  expect(getPlaceUpdate).toHaveBeenCalledTimes(2);
});

test('more than ten species lines: the first ten, "…and N more species", and Show all lists the rest in place with focus on the first new line', async () => {
  const species = Array.from({ length: 13 }, (_, i) => line(i + 1));
  getPlaceUpdate.mockResolvedValue(placeNote({ former: { photos: 200, species }, notChecked: [] }));
  status.placeUpdate = { note: true, formerPhotos: 200, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(within(card).getAllByRole('link')).toHaveLength(10));
  expect(text(card)).toContain('…and 3 more species');
  await fireEvent.click(within(card).getByRole('button', { name: 'Show all' }));
  const links = within(card).getAllByRole('link');
  expect(links).toHaveLength(13);
  expect(document.activeElement).toBe(links[10]);
  expect(text(links[10])).toBe('Danaus species11 — 9 photos in Chittoor (former district)');
  expect(within(card).queryByRole('button', { name: 'Show all' })).toBeNull();
  expect(text(card)).not.toContain('more species');
});

test('a note that can\'t be read keeps the card: "couldn\'t be loaded" with the reason, and Try again, which reads it again', async () => {
  getPlaceUpdate.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.')).mockResolvedValue(placeNote());
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(within(card).getByRole('alert').textContent)
    .toBe("Place update details couldn't be loaded. Butterfly Atlas could not reach its service. Close the app and open it again."));
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(text(card)).toContain('1 place merged, 2 renamed'));
  expect(within(card).queryByRole('button', { name: 'Try again' })).toBeNull();
  expect(getPlaceUpdate).toHaveBeenCalledTimes(2);
});

test('Dismiss: the card goes, the status is asked again, and focus goes to the page\'s heading', async () => {
  const heading = document.createElement('h1');
  heading.tabIndex = -1;
  document.body.append(heading);
  getPlaceUpdate.mockResolvedValue(placeNote({ former: { photos: 0, species: [] } }));
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Place names updated' })).toBeNull());
  expect(dismissPlaceUpdate).toHaveBeenCalledTimes(1);
  expect(getStatus).toHaveBeenCalled();
  expect(document.activeElement).toBe(heading);
});

test('a Dismiss that fails keeps the card and says why', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote());
  dismissPlaceUpdate.mockRejectedValue(new Error('The drive with your library is full. Free some space, then try again.'));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  expect((await within(card).findByRole('alert')).textContent).toBe('The drive with your library is full. Free some space, then try again.');
  expect(screen.getByRole('region', { name: 'Place names updated' })).toBe(card);
});

test('a note the server no longer has (204) says "Nothing to show any more." and asks the status again', async () => {
  getPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalled());
  // the card stays until the status says there is nothing to show, and says why it is empty
  await vi.waitFor(() => expect(text(screen.getByRole('region', { name: 'Place names updated' }))).toContain('Nothing to show any more.'));
  status.placeUpdate = null;
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Place names updated' })).toBeNull());
});

test('after Dismiss, while photos are still in a former place: the smaller card with Review, which lists the same lines', async () => {
  const species = Array.from({ length: 12 }, (_, i) => line(i + 1));
  getPlaceUpdate.mockResolvedValueOnce(placeNote({ former: { photos: 12, species } }))
    .mockResolvedValue({ dismissed: true, former: { photos: 12, species } });
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(within(card).getAllByRole('link').length).toBeGreaterThan(0));
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));

  const still = await screen.findByRole('region', { name: 'Photos in former places' });
  expect(screen.queryByRole('region', { name: 'Place names updated' })).toBeNull();
  expect(text(still)).toBe('12 photos are still in a former district. Review');
  const review = within(still).getByRole('button', { name: 'Review' });
  // the smaller card is the next card: Dismiss leaves focus on its Review
  await vi.waitFor(() => expect(document.activeElement).toBe(review));
  expect(review.getAttribute('aria-expanded')).toBe('false');
  expect(within(still).queryAllByRole('link')).toHaveLength(0);

  await fireEvent.click(review);
  expect(review.getAttribute('aria-expanded')).toBe('true');
  const links = within(still).getAllByRole('link');
  expect(links).toHaveLength(10);
  expect(document.activeElement).toBe(links[0]);
  expect(links[0].getAttribute('href')).toBe('#/species/A1?place=P300');
  expect(text(still)).toContain('…and 2 more species');
  await fireEvent.click(within(still).getByRole('button', { name: 'Show all' }));
  expect(within(still).getAllByRole('link')).toHaveLength(12);
  expect(document.activeElement).toBe(within(still).getAllByRole('link')[10]);
});

test('the smaller card at start (a note dismissed earlier); its words follow the places\' level, and it goes when none is left', async () => {
  const state = ref('P2', 'Old', ['Old (former state)', 'India'], 'state', true);
  getPlaceUpdate.mockResolvedValueOnce({ dismissed: true, former: { photos: 3, species: [{ ...line(1), photos: 2 }, { ...line(2), place: state, photos: 1 }] } })
    .mockResolvedValue({ dismissed: true, former: { photos: 1, species: [{ ...line(2), place: state, photos: 1 }] } });
  status.placeUpdate = { note: false, formerPhotos: 3, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const still = await screen.findByRole('region', { name: 'Photos in former places' });
  expect(text(still)).toBe('3 photos are still in former places. Review');
  status.placeUpdate = { note: false, formerPhotos: 1, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  await vi.waitFor(() => expect(text(still)).toBe('1 photo is still in a former state. Review'));
  status.placeUpdate = null;
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Photos in former places' })).toBeNull());
});

test('the smaller card whose lines can\'t be read: the count, and under Review "couldn\'t be loaded" with Try again', async () => {
  getPlaceUpdate.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ dismissed: true, former: { photos: 2, species: [{ ...line(1), photos: 2 }] } });
  status.placeUpdate = { note: false, formerPhotos: 2, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const still = await screen.findByRole('region', { name: 'Photos in former places' });
  expect(text(still)).toBe('2 photos are still in former places. Review');
  await fireEvent.click(within(still).getByRole('button', { name: 'Review' }));
  expect(text(still)).toContain("Place update details couldn't be loaded.");
  await fireEvent.click(within(still).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(within(still).getAllByRole('link')).toHaveLength(1));
  expect(text(still)).toContain('2 photos are still in a former district');
});

test('keyboard: every link and button of the note is in the Tab order, in reading order, and stays focusable while busy', async () => {
  const species = Array.from({ length: 12 }, (_, i) => line(i + 1));
  getPlaceUpdate.mockResolvedValue(placeNote({ former: { photos: 200, species } }));
  dismissPlaceUpdate.mockImplementation(() => new Promise(() => {}));
  status.placeUpdate = { note: true, formerPhotos: 200, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(within(card).getAllByRole('link').length).toBeGreaterThan(0));
  const controls = [...card.querySelectorAll('a, button')];
  expect(controls.map((el) => el.textContent.trim().split(' — ')[0])).toEqual([
    ...species.slice(0, 10).map((row) => row.name), 'Show all', 'Danaus_chrysippus_041.jpg', 'Dismiss',
  ]);
  for (const el of controls) {
    expect(el.hasAttribute('tabindex')).toBe(false);
    expect(el.hasAttribute('disabled')).toBe(false);
  }
  // a Dismiss on its way leaves the button focusable (aria-disabled), not taken out of the Tab order
  const dismiss = within(card).getByRole('button', { name: 'Dismiss' });
  await fireEvent.click(dismiss);
  expect(dismiss.getAttribute('aria-disabled')).toBe('true');
  expect(dismiss.hasAttribute('disabled')).toBe(false);
});

test('motion: the lines Show all and Review add fade in, not under reduced motion; no browser-specific CSS or outside resources', async () => {
  const css = readFileSync(`${process.cwd()}/src/styles/atlas.css`, 'utf8');
  const block = css.slice(css.indexOf("/* Home's place note (Phase 5c) */"));
  expect(block).toMatch(/\.pu-new \{ animation: fade/);
  expect(block).toMatch(/@media \(prefers-reduced-motion: reduce\) \{ \.pu-new \{ animation: none; \} \}/);
  expect(block).not.toMatch(/-webkit-|-moz-|url\(|@import/);

  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 2, species: [{ ...line(1), photos: 2 }] } });
  status.placeUpdate = { note: false, formerPhotos: 2, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const still = await screen.findByRole('region', { name: 'Photos in former places' });
  await vi.waitFor(() => expect(text(still)).toContain('a former district'));
  await fireEvent.click(within(still).getByRole('button', { name: 'Review' }));
  expect(within(still).getByRole('link').closest('.pu-new')).toBeTruthy();
});

test('lines shown with Show all on the note are folded again in the smaller card after Dismiss', async () => {
  const species = Array.from({ length: 12 }, (_, i) => line(i + 1));
  getPlaceUpdate.mockResolvedValueOnce(placeNote({ former: { photos: 12, species } }))
    .mockResolvedValue({ dismissed: true, former: { photos: 12, species } });
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await fireEvent.click(await within(card).findByRole('button', { name: 'Show all' }));
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  const still = await screen.findByRole('region', { name: 'Photos in former places' });
  await fireEvent.click(within(still).getByRole('button', { name: 'Review' }));
  expect(within(still).getAllByRole('link')).toHaveLength(10);
  expect(within(still).getByRole('button', { name: 'Show all' })).toBeTruthy();
});

test('the numbers grow while the re-check runs: a status whose moved count grew reads the note again (contract §9)', async () => {
  const moving = (n) => placeNote({ rechecking: true, moved: { total: n, groups: [{ from: chittoor, to: tirupati, photos: n }] } });
  getPlaceUpdate.mockResolvedValueOnce(moving(8)).mockResolvedValueOnce(moving(15));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 8, rechecking: true, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('8 photos moved from Chittoor (former district) to Tirupati.'));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 15, rechecking: true, failed: false, lost: 0 };
  await vi.waitFor(() => expect(text(card)).toContain('15 photos moved from Chittoor (former district) to Tirupati.'));
  expect(text(card)).toContain('the numbers here will grow');
});

const failedPhoto = (id) => ({ photoId: id, taxonId: 'A5', name: 'Danaus chrysippus', fileName: `Danaus_chrysippus_0${id}.jpg` });

test('photos the re-check couldn\'t do after a Dismiss: the smaller card says so, and Review lists them (contract §9)', async () => {
  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 0, species: [] }, notChecked: [failedPhoto(41), failedPhoto(42)] });
  status.placeUpdate = { note: false, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: true, failed: false, lost: 0 };
  const { container } = render(PlaceUpdateNote);
  expect(container.querySelector('section')).toBeNull();

  status.placeUpdate = { note: false, formerPhotos: 0, notChecked: 2, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  const card = await screen.findByRole('region', { name: 'Photos not re-checked' });
  expect(text(card)).toBe("2 photos couldn't be re-checked; they keep their old place. Review");
  await fireEvent.click(within(card).getByRole('button', { name: 'Review' }));
  expect(within(card).getAllByRole('link').map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
    ['Danaus_chrysippus_041.jpg', '#/species/A5/photo/41'], ['Danaus_chrysippus_042.jpg', '#/species/A5/photo/42'],
  ]);
});

test('the smaller card with photos in former places and photos not re-checked: both lines, and Review lists both', async () => {
  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 2, species: [{ ...line(1), photos: 2 }] }, notChecked: [failedPhoto(41)] });
  status.placeUpdate = { note: false, formerPhotos: 2, notChecked: 1, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos to review' });
  await vi.waitFor(() => expect(text(card)).toBe("2 photos are still in a former district. 1 photo couldn't be re-checked; it keeps its old place. Review"));
  await fireEvent.click(within(card).getByRole('button', { name: 'Review' }));
  expect(within(card).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(['#/species/A1?place=P300', '#/species/A5/photo/41']);
});

test('Dismiss with only photos not re-checked left: the smaller card takes the note\'s place, with focus on its Review', async () => {
  getPlaceUpdate.mockResolvedValueOnce(placeNote({ former: { photos: 0, species: [] } }))
    .mockResolvedValue({ dismissed: true, former: { photos: 0, species: [] }, notChecked: [failedPhoto(41)] });
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 1, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const note = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(within(note).getAllByRole('link')).toHaveLength(1));
  await fireEvent.click(within(note).getByRole('button', { name: 'Dismiss' }));
  const card = await screen.findByRole('region', { name: 'Photos not re-checked' });
  await vi.waitFor(() => expect(document.activeElement).toBe(within(card).getByRole('button', { name: 'Review' })));
});

test('a read that fails after one that worked keeps the lines and says they couldn\'t be refreshed, with the reason and Try again', async () => {
  getPlaceUpdate.mockResolvedValueOnce(placeNote()).mockRejectedValueOnce(new Error('Butterfly Atlas answered with status 500.'))
    .mockResolvedValue(placeNote({ items: [] }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('Brežice (Pečice) → Brežice'));
  status.placeUpdate = { note: true, formerPhotos: 11, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  await vi.waitFor(() => expect(within(card).getByRole('alert').textContent)
    .toBe("These details couldn't be refreshed. Butterfly Atlas answered with status 500."));
  expect(text(card)).toContain('Brežice (Pečice) → Brežice');
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(within(card).queryByRole('alert')).toBeNull());
  expect(text(card)).not.toContain('Brežice (Pečice) → Brežice');
});

test('the smaller card says too when its lines couldn\'t be refreshed; a full drive is not said twice (the banner says it)', async () => {
  getPlaceUpdate.mockResolvedValueOnce({ dismissed: true, former: { photos: 2, species: [{ ...line(1), photos: 2 }] }, notChecked: [] })
    .mockRejectedValueOnce(Object.assign(new Error('The drive with your library is full. Free some space, then try again.'), { code: 'drive_full' }));
  status.placeUpdate = { note: false, formerPhotos: 2, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos in former places' });
  status.placeUpdate = { note: false, formerPhotos: 1, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  await vi.waitFor(() => expect(within(card).getByRole('alert').textContent).toBe("These details couldn't be refreshed."));
  expect(within(card).getByRole('button', { name: 'Try again' })).toBeTruthy();
});

test('a slow read is not dropped when the status changes meanwhile: it is shown, then read again; "Loading the details…" until the first', async () => {
  const answers = [];
  getPlaceUpdate.mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 1, rechecking: true, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  expect(text(card)).toContain('Loading the details…');
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 2, rechecking: true, failed: false, lost: 0 };
  await new Promise((resolve) => setTimeout(resolve, 0));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 3, rechecking: true, failed: false, lost: 0 };
  await new Promise((resolve) => setTimeout(resolve, 0));
  // one read at a time
  expect(answers).toHaveLength(1);
  answers[0](placeNote({ moved: { total: 1, groups: [{ from: chittoor, to: tirupati, photos: 1 }] } }));
  await vi.waitFor(() => expect(text(card)).toContain('1 photo moved from Chittoor (former district) to Tirupati.'));
  expect(text(card)).not.toContain('Loading the details…');
  // then once more, for the newest status
  await vi.waitFor(() => expect(answers).toHaveLength(2));
  answers[1](placeNote({ moved: { total: 3, groups: [{ from: chittoor, to: tirupati, photos: 3 }] } }));
  await vi.waitFor(() => expect(text(card)).toContain('3 photos moved from Chittoor (former district) to Tirupati.'));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(answers).toHaveLength(2);
});

test('"the numbers here will grow" follows the status, not a note read while the re-check still ran', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote({ rechecking: true }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('1 place merged'));
  expect(text(card)).not.toContain('the numbers here will grow');
});

test('a Try again that works moves focus to the card that now shows the details', async () => {
  getPlaceUpdate.mockRejectedValueOnce(new Error('offline')).mockResolvedValue(placeNote());
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const again = await screen.findByRole('button', { name: 'Try again' });
  again.focus();
  await fireEvent.click(again);
  const card = screen.getByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(document.activeElement).toBe(card));
  expect(card.getAttribute('tabindex')).toBe('-1');
  expect(text(card)).toContain('1 place merged');
});

test('Review over lines that couldn\'t be read moves focus to the card; its Try again that works, to the first line', async () => {
  getPlaceUpdate.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({ dismissed: true, former: { photos: 2, species: [{ ...line(1), photos: 2 }] }, notChecked: [] });
  status.placeUpdate = { note: false, formerPhotos: 2, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos in former places' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Review' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(card));
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(within(card).getByRole('link')));
});

test('Review with only photos not re-checked moves focus to the first of them', async () => {
  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 0, species: [] }, notChecked: [failedPhoto(41)] });
  status.placeUpdate = { note: false, formerPhotos: 0, notChecked: 1, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos not re-checked' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Review' }));
  expect(document.activeElement).toBe(within(card).getByRole('link'));
});

test('a Dismiss answered after Home was left moves no focus', async () => {
  const heading = document.createElement('h1');
  heading.tabIndex = -1;
  document.body.append(heading);
  let answer;
  getPlaceUpdate.mockResolvedValue(placeNote({ former: { photos: 0, species: [] } }));
  dismissPlaceUpdate.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  const { unmount } = render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  unmount();
  answer(null);
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(document.activeElement).not.toBe(heading);
});

const never = () => new Promise(() => {});

test('a read that never answers fails after 20 s in plain words, and Try again reads afresh', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  getPlaceUpdate.mockImplementationOnce(never).mockResolvedValue(placeNote());
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  expect(getPlaceUpdate).toHaveBeenCalledWith(expect.objectContaining({ signal: expect.any(AbortSignal) }));
  await vi.advanceTimersByTimeAsync(19000);
  expect(text(card)).toContain('Loading the details…');
  await vi.advanceTimersByTimeAsync(1000);
  expect(within(card).getByRole('alert').textContent)
    .toBe("Place update details couldn't be loaded. Butterfly Atlas took too long to answer. Choose Try again.");
  // the read it gave up on was stopped
  expect(getPlaceUpdate.mock.calls[0][0].signal.aborted).toBe(true);
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  await vi.advanceTimersByTimeAsync(0);
  expect(text(card)).toContain('1 place merged');
  vi.useRealTimers();
});

test('Try again while a read hangs starts a fresh one; the stuck one\'s late answer is ignored, and later reads still come', async () => {
  let late;
  getPlaceUpdate.mockRejectedValueOnce(new Error('offline'))
    .mockImplementationOnce(() => new Promise((resolve) => { late = resolve; }))
    .mockResolvedValueOnce(placeNote())
    .mockResolvedValue(placeNote({ items: [] }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await fireEvent.click(await within(card).findByRole('button', { name: 'Try again' }));
  // the second read hangs; Try again is still there and takes a fresh read
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(text(card)).toContain('Brežice (Pečice) → Brežice'));
  late(placeNote({ items: [], counts: { merged: 0, elsewhere: 9 } }));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(text(card)).toContain('1 place merged');
  // the queue is not stuck: a status change reads again
  status.placeUpdate = { note: true, formerPhotos: 11, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  await vi.waitFor(() => expect(text(card)).not.toContain('Brežice (Pečice) → Brežice'));
  expect(getPlaceUpdate).toHaveBeenCalledTimes(4);
});

test('"couldn\'t be refreshed" stays put while more reads are coming, and goes once one works with none queued', async () => {
  const answers = [];
  getPlaceUpdate.mockImplementation(() => new Promise((resolve, reject) => answers.push({ resolve, reject })));
  const poll = (moved) => { status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved, rechecking: true, failed: false, lost: 0 }; };
  const tickle = () => new Promise((resolve) => setTimeout(resolve, 0));
  poll(1);
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  answers[0].resolve(placeNote());
  await vi.waitFor(() => expect(text(card)).toContain('1 place merged'));
  poll(2);
  await vi.waitFor(() => expect(answers).toHaveLength(2));
  answers[1].reject(new Error('Butterfly Atlas answered with status 500.'));
  const alert = await within(card).findByRole('alert');

  // a read that works while another is queued keeps the alert; the queued one fails: it stays, the same alert
  poll(3);
  await vi.waitFor(() => expect(answers).toHaveLength(3));
  poll(4);
  await tickle();
  answers[2].resolve(placeNote());
  await vi.waitFor(() => expect(answers).toHaveLength(4));
  expect(within(card).getByRole('alert')).toBe(alert);
  answers[3].reject(new Error('Butterfly Atlas answered with status 500.'));
  await tickle();
  expect(within(card).getByRole('alert')).toBe(alert);

  // then one read that works with nothing queued after it: the details are current, the alert goes
  poll(5);
  await vi.waitFor(() => expect(answers).toHaveLength(5));
  answers[4].resolve(placeNote());
  await vi.waitFor(() => expect(within(card).queryByRole('alert')).toBeNull());
});

test('the last read failing and the next, final one working takes "couldn\'t be refreshed" away', async () => {
  getPlaceUpdate.mockResolvedValueOnce(placeNote()).mockRejectedValueOnce(new Error('Butterfly Atlas answered with status 500.'))
    .mockResolvedValue(placeNote());
  const poll = (moved, rechecking = true) => { status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved, rechecking, failed: false, lost: 0 }; };
  poll(1);
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('1 place merged'));
  poll(2);
  await within(card).findByRole('alert');
  poll(3, false);
  await vi.waitFor(() => expect(within(card).queryByRole('alert')).toBeNull());
  expect(getPlaceUpdate).toHaveBeenCalledTimes(3);
});

test('a Try again that works leaves focus alone when the user has moved on meanwhile', async () => {
  let answer;
  getPlaceUpdate.mockRejectedValueOnce(new Error('offline')).mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  const again = await within(card).findByRole('button', { name: 'Try again' });
  again.focus();
  await fireEvent.click(again);
  const dismiss = within(card).getByRole('button', { name: 'Dismiss' });
  dismiss.focus();
  answer(placeNote());
  await vi.waitFor(() => expect(text(card)).toContain('1 place merged'));
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(document.activeElement).toBe(dismiss);
});

test('Dismiss is held while the re-check runs, and says so; once photo places are checked it works (contract §10)', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote({ former: { photos: 0, species: [] } }));
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 3, rechecking: true, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  const dismiss = within(card).getByRole('button', { name: 'Dismiss' });
  expect(dismiss.getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(dismiss.getAttribute('aria-describedby')).textContent).toBe('You can dismiss this once photo places are checked.');
  expect(text(card)).toContain('You can dismiss this once photo places are checked.');
  await fireEvent.click(dismiss);
  expect(dismissPlaceUpdate).not.toHaveBeenCalled();

  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 3, rechecking: false, failed: false, lost: 0 };
  await vi.waitFor(() => expect(dismiss.getAttribute('aria-disabled')).toBe('false'));
  expect(text(card)).not.toContain('You can dismiss this');
  await fireEvent.click(dismiss);
  expect(dismissPlaceUpdate).toHaveBeenCalledTimes(1);
});

test('photos not checked yet while the re-check is not running: the note says it finishes at the next start (contract §10)', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote({ pending: 1200 }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 1200, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain("1,200 photos haven't been checked yet; Butterfly Atlas will finish when it next starts."));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 1200, moved: 0, rechecking: true, failed: false, lost: 0 };
  await vi.waitFor(() => expect(text(card)).not.toContain("haven't been checked yet"));
  expect(text(card)).toContain('the numbers here will grow');
});

test('counts the server couldn\'t read (status failed): a card says so, and Try again reads the status and the note again (contract §10)', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote());
  getStatus.mockResolvedValue({ placeUpdate: { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 } });
  status.placeUpdate = { note: false, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: true, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place update' });
  expect(within(card).getByRole('alert').textContent).toBe("Place update details couldn't be loaded.");
  const calls = getPlaceUpdate.mock.calls.length;
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  expect(getStatus).toHaveBeenCalled();
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Place update' })).toBeNull());
  const note = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(note)).toContain('1 place merged'));
  expect(getPlaceUpdate.mock.calls.length).toBeGreaterThan(calls);
});

test('each photo the re-check couldn\'t do says why and what next: tried again at the next start, or set by hand (contract §10)', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote({
    notChecked: [
      { photoId: 41, taxonId: 'A5', name: 'Danaus chrysippus', fileName: 'Danaus_chrysippus_041.jpg', reason: "The photo's file couldn't be read.", final: false },
      { photoId: 42, taxonId: 'A5', name: 'Danaus chrysippus', fileName: 'Danaus_chrysippus_042.jpg', reason: 'Its GPS position is not a valid one.', final: true },
    ],
  }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 2, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  const first = await within(card).findByRole('link', { name: 'Danaus_chrysippus_041.jpg' });
  expect(text(first.closest('li'))).toBe("Danaus_chrysippus_041.jpg · Danaus chrysippus — The photo's file couldn't be read. Butterfly Atlas will try again when it next starts.");
  const second = within(card).getByRole('link', { name: 'Danaus_chrysippus_042.jpg' });
  expect(text(second.closest('li'))).toBe('Danaus_chrysippus_042.jpg · Danaus chrysippus — Its GPS position is not a valid one. Set its place by hand (Place… on the species page).');
});

test('photos the re-check left with no place: a line per former place they were in, with a link per photo (contract §10)', async () => {
  const lost = (id, place) => ({ photoId: id, taxonId: 'A5', name: 'Danaus chrysippus', fileName: `Danaus_chrysippus_0${id}.jpg`, ...(place ? { place } : {}) });
  getPlaceUpdate.mockResolvedValue(placeNote({ notChecked: [], lost: [lost(51, chittoor), lost(52, chittoor), lost(53)] }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('2 photos in Chittoor (former district) no longer have a place: their GPS position is outside every place in the new list. Set their place by hand if you know it.'));
  expect(text(card)).toContain('1 photo no longer has a place: its GPS position is outside every place in the new list.');
  expect(['51', '52', '53'].map((n) => within(card).getByRole('link', { name: `Danaus_chrysippus_0${n}.jpg` }).getAttribute('href')))
    .toEqual(['#/species/A5/photo/51', '#/species/A5/photo/52', '#/species/A5/photo/53']);
});

test('your places left under a former parent are said on the note (contract §10 kept)', async () => {
  const ap = ref('P30', 'Andhra Pradesh (former state)', ['Andhra Pradesh (former state)', 'India'], 'state', true);
  getPlaceUpdate.mockResolvedValue(placeNote({ kept: [{ name: 'Tirupati', under: ap, photos: 5 }] }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('Your place Tirupati stays under Andhra Pradesh (former state) and keeps its 5 photos.'));
});

test('photos in former places: the note says what to do with them (contract §10)', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote());
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('Open each species, select the photos and choose Place… to give them a current one.'));
});

const lostPhoto = (id, place = chittoor) => ({ photoId: id, taxonId: 'A5', name: 'Danaus chrysippus', fileName: `Danaus_chrysippus_0${id}.jpg`, place });

test('photos left with no place after a Dismiss: the smaller card with their lines and links, and its own Dismiss (contract §11)', async () => {
  const heading = document.createElement('h1');
  heading.tabIndex = -1;
  document.body.append(heading);
  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 0, species: [] }, notChecked: [], lost: [lostPhoto(61), lostPhoto(62)] });
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: false, formerPhotos: 0, notChecked: 0, pending: 0, moved: 2, rechecking: false, failed: false, lost: 2 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos without a place' });
  await vi.waitFor(() => expect(text(card)).toContain('2 photos in Chittoor (former district) no longer have a place: their GPS position is outside every place in the new list.'));
  expect(within(card).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(['#/species/A5/photo/61', '#/species/A5/photo/62']);
  expect(within(card).queryByRole('button', { name: 'Review' })).toBeNull();
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  expect(dismissPlaceUpdate).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Photos without a place' })).toBeNull());
  expect(document.activeElement).toBe(heading);
});

test('the smaller card with several kinds is "Photos to review"; its Dismiss leaves the former places, which stay with Review', async () => {
  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 2, species: [{ ...line(1), photos: 2 }] }, notChecked: [], lost: [lostPhoto(61)] });
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: false, formerPhotos: 2, notChecked: 0, pending: 0, moved: 1, rechecking: false, failed: false, lost: 1 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos to review' });
  await vi.waitFor(() => expect(text(card)).toContain('1 photo in Chittoor (former district) no longer has a place'));
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  const still = await screen.findByRole('region', { name: 'Photos in former places' });
  expect(within(still).queryByRole('button', { name: 'Dismiss' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(within(still).getByRole('button', { name: 'Review' })));
});

test('photos left with no place whose lines can\'t be read: their count still says so', async () => {
  getPlaceUpdate.mockRejectedValue(new Error('offline'));
  status.placeUpdate = { note: false, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 3 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos without a place' });
  await vi.waitFor(() => expect(text(card)).toContain('3 photos no longer have a place: their GPS position is outside every place in the new list.'));
});

test('only rechecking holds Dismiss, and "will finish when it next starts" is said only for photos pending while not rechecking (contract §11)', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote({ pending: 40, former: { photos: 0, species: [] }, lost: [lostPhoto(61)] }));
  const set = (over) => { status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0, ...over }; };
  set({ pending: 40, rechecking: true });
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('the numbers here will grow'));
  const dismiss = within(card).getByRole('button', { name: 'Dismiss' });
  // before this run's places step (rechecking) nothing says it waits for the next start
  expect(text(card)).not.toContain('next starts');
  expect(dismiss.getAttribute('aria-disabled')).toBe('true');

  for (const over of [{ pending: 40 }, { pending: 0, lost: 1 }, { notChecked: 3 }, { failed: true }]) {
    set(over);
    await vi.waitFor(() => expect(dismiss.getAttribute('aria-disabled')).toBe('false'));
    expect(text(card).includes("haven't been checked yet")).toBe(over.pending > 0);
  }

  // the smaller card's Dismiss is held the same way
  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 0, species: [] }, notChecked: [], lost: [lostPhoto(61)] });
  set({ note: false, lost: 1, rechecking: true });
  const still = await screen.findByRole('region', { name: 'Photos without a place' });
  expect(within(still).getByRole('button', { name: 'Dismiss' }).getAttribute('aria-disabled')).toBe('true');
  set({ note: false, lost: 1, pending: 5 });
  await vi.waitFor(() => expect(within(still).getByRole('button', { name: 'Dismiss' }).getAttribute('aria-disabled')).toBe('false'));
});

test('the failed card\'s Try again that changes nothing says when it tried, in a status (emptied first, so it is heard each time)', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 9, 16, 14, 2));
  const failing = { note: false, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: true, lost: 0 };
  getStatus.mockResolvedValue({ placeUpdate: failing });
  getPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { ...failing };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place update' });
  const said = within(card).getByRole('status');
  expect(said.textContent).toBe('');
  const seen = [];
  new MutationObserver(() => seen.push(said.textContent)).observe(said, { childList: true, characterData: true, subtree: true });
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(said.textContent).toBe("Tried again at 14:02 — still couldn't be loaded."));
  seen.length = 0;
  await fireEvent.click(within(card).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(seen.at(-1)).toBe("Tried again at 14:02 — still couldn't be loaded."));
  expect(seen).toContain('');
  vi.useRealTimers();
});

test('while the failed card is shown, the note card does not say the same again', async () => {
  getPlaceUpdate.mockRejectedValue(new Error('Butterfly Atlas answered with status 500.'));
  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: true, lost: 0 };
  render(PlaceUpdateNote);
  const note = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(getPlaceUpdate).toHaveBeenCalled());
  await new Promise((resolve) => setTimeout(resolve, 10));
  expect(within(note).queryByRole('alert')).toBeNull();
  expect(screen.getAllByRole('alert')).toHaveLength(1);
  expect(within(screen.getByRole('region', { name: 'Place update' })).getByRole('alert')).toBeTruthy();
});

test('after a Dismiss the status keeps what else it says (pending, failed, rechecking), so the footer and the failed card stay', async () => {
  getPlaceUpdate.mockResolvedValue({ dismissed: true, former: { photos: 0, species: [] }, notChecked: [], lost: [lostPhoto(61)] });
  dismissPlaceUpdate.mockResolvedValue(null);
  status.placeUpdate = { note: false, formerPhotos: 0, notChecked: 0, pending: 40, moved: 0, rechecking: false, failed: true, lost: 1 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos without a place' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Photos without a place' })).toBeNull());
  expect(status.placeUpdate).toMatchObject({ note: false, lost: 0, pending: 40, failed: true });
  expect(screen.getByRole('region', { name: 'Place update' })).toBeTruthy();
});

test('while the failed card is shown, Review over unread lines on the smaller card does not repeat its alert', async () => {
  getPlaceUpdate.mockRejectedValue(new Error('offline'));
  status.placeUpdate = { note: false, formerPhotos: 2, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: true, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Photos in former places' });
  await fireEvent.click(within(card).getByRole('button', { name: 'Review' }));
  expect(within(card).queryByRole('alert')).toBeNull();
  expect(screen.getAllByRole('alert')).toHaveLength(1);
});

test('a re-check move to no place is not a move line: the lost lines say it, once (real app)', async () => {
  const lakshadweep = ref('P500', 'Lakshadweep District (former district)', ['Lakshadweep District (former district)', 'Lakshadweep', 'India'], 'district', true);
  // moved.total counts only moves to a place; a group to no place, should one still come, is a guard case
  getPlaceUpdate.mockResolvedValue(placeNote({
    moved: { total: 8, groups: [{ from: chittoor, to: tirupati, photos: 8 }, { from: lakshadweep, to: null, photos: 1 }] },
    lost: [{ photoId: 71, taxonId: 'A5', name: 'Danaus chrysippus', fileName: 'Danaus_chrysippus_071.jpg', place: lakshadweep }],
  }));
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 8, rechecking: false, failed: false, lost: 1 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(text(card)).toContain('8 photos moved from Chittoor (former district) to Tirupati.'));
  expect(text(card).match(/no longer has a place/g)).toHaveLength(1);
  expect(text(card)).toContain('1 photo in Lakshadweep District (former district) no longer has a place: its GPS position is outside every place in the new list.');
  // and no "…and 1 more photo moved" for it
  expect(text(card)).not.toContain('more photo');
});

test('a species line in a place that no longer exists links to the species without a place filter', async () => {
  const gone = { id: 'P9999', name: 'a place that no longer exists', path: ['a place that no longer exists'], level: null, former: true };
  getPlaceUpdate.mockResolvedValue(placeNote({ notChecked: [], former: { photos: 2, species: [{ id: 'A5', name: 'Danaus chrysippus', common: null, place: gone, photos: 2 }] } }));
  status.placeUpdate = { note: true, formerPhotos: 2, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  const link = await within(card).findByRole('link', { name: /Danaus chrysippus/ });
  expect(link.getAttribute('href')).toBe('#/species/A5');
  expect(text(link)).toBe('Danaus chrysippus — 2 photos in a place that no longer exists');
});

test('a photo link opens the viewer as opened from Home, so its title leads to the species', async () => {
  getPlaceUpdate.mockResolvedValue(placeNote());
  status.placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  forgetViewerOpen();
  window.location.hash = '#/';
  render(PlaceUpdateNote);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  const photoLink = await within(card).findByRole('link', { name: 'Danaus_chrysippus_041.jpg' });

  photoLink.addEventListener('click', (event) => event.preventDefault());
  await fireEvent.click(photoLink);

  expect(viewerOpenedFrom()).toBe('#/');
  forgetViewerOpen();
});
