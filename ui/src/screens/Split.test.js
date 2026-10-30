import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Split from './Split.svelte';
import { getSplit, getStatus, getTags, sortSplit } from '../lib/api.js';
import { LIBRARY_CHANGED, resetStatus, status } from '../lib/status.svelte.js';
import { toast } from '../lib/toast.svelte.js';

vi.mock('../lib/api.js', () => ({
  getSplit: vi.fn(), getTags: vi.fn(), sortSplit: vi.fn(), getStatus: vi.fn(),
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
  displayUrl: (id) => `./api/photos/${id}/display`,
}));

const photo = (id, over = {}) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.jpg`, relPath: `images/x/Danaus_chrysippus_0${id}.jpg`, tags: [], width: 4, height: 3,
  isCover: false, place: null, addedAt: '2026-10-01T10:00:00Z', v: 'x', ...over,
});
const brief = { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', photos: 9, version: '2026-12-01', targets: [] };
const targets = [
  { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', species: { got: 1, total: 1 } },
  { id: 'A90', name: 'Danaus alcippus', common: 'African Tiger', family: 'Nymphalidae', species: { got: 0, total: 1 } },
];
const all = [1, 2, 3, 4, 5, 6, 7, 8, 9].map((id) => photo(id, id === 2 ? { place: { id: 'P1', path: ['Pune', 'India'] }, tags: ['larva'] } : {}));
const splitWith = (photos, total = 12) => ({ taxon: brief, targets, left: photos.length, total, photos });

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  getStatus.mockImplementation(() => new Promise(() => {}));
  getTags.mockResolvedValue([{ id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 1 }]);
  getSplit.mockResolvedValue(splitWith(all));
  window.location.hash = '#/split/A5';
});
afterEach(() => {
  vi.restoreAllMocks();
  delete Element.prototype.animate;
});

const tile = (id) => document.querySelector(`.sg [data-key="${id}"]`);
const target = (name) => screen.getByRole('toolbar', { name: 'Sort the selected photos to' }).querySelector(`button[aria-keyshortcuts="${name}"]`);

test('the header: names, how many are left of all, what it split into; the targets with their keys and Leave for later', async () => {
  render(Split, { id: 'A5' });
  expect(await screen.findByRole('heading', { level: 1, name: 'Danaus chrysippus' })).toBeTruthy();
  expect(screen.getByText('Plain Tiger', { selector: '.cn' })).toBeTruthy();
  expect(screen.getByText('Split species · Nymphalidae')).toBeTruthy();
  expect(document.querySelector('.split-left').textContent.replace(/\s+/g, ' ').trim()).toBe('9 of 12 left to sort');
  expect(document.querySelector('.split-why').textContent.replace(/\s+/g, ' ').trim())
    .toBe('The 2026-12-01 list splits it into Danaus chrysippus and Danaus alcippus. Choose which one each photo shows.');

  const bar = screen.getByRole('toolbar', { name: 'Sort the selected photos to' });
  const buttons = within(bar).getAllByRole('button').filter((b) => b.classList.contains('split-target'));
  expect(buttons.map((b) => b.textContent.trim())).toEqual(['1Danaus chrysippusPlain Tiger', '2Danaus alcippusAfrican Tiger']);
  expect(buttons.map((b) => b.getAttribute('aria-disabled'))).toEqual(['true', 'true']);
  expect(within(bar).getByRole('link', { name: 'Leave for later' }).getAttribute('href')).toBe('#/species/A5');
  expect(screen.getByRole('grid', { name: 'Photos of Danaus chrysippus to sort' }).querySelectorAll('[role="gridcell"]')).toHaveLength(9);
  expect(tile(2).getAttribute('aria-label')).toBe('Danaus_chrysippus_02.jpg, Pune');
  expect(screen.getByRole('status').textContent).toBe('Select photos, then choose their species.');
});

test('selecting: a click, Ctrl/⌘-click, Shift-click, and a plain click on the only one selected clears it', async () => {
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  await fireEvent.click(tile(2));
  expect(tile(2).getAttribute('aria-selected')).toBe('true');
  await fireEvent.click(tile(4), { ctrlKey: true });
  await fireEvent.click(tile(6), { metaKey: true });
  expect(screen.getByRole('status').textContent).toBe('3 photos selected');
  await fireEvent.click(tile(3));
  await fireEvent.click(tile(5), { shiftKey: true });
  expect([1, 2, 3, 4, 5, 6].map((id) => tile(id).getAttribute('aria-selected'))).toEqual(['false', 'false', 'true', 'true', 'true', 'false']);
  expect(target('1').getAttribute('aria-disabled')).toBe('false');
  await fireEvent.click(tile(9));
  await fireEvent.click(tile(9));
  expect(screen.getByRole('status').textContent).toBe('Select photos, then choose their species.');
});

test('keys: A selects all, Escape clears; arrows move focus by column and row, Shift extends, Enter adds or removes', async () => {
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  expect(tile(1).tabIndex).toBe(0);
  await fireEvent.keyDown(window, { key: 'a' });
  expect(screen.getByRole('status').textContent).toBe('9 photos selected');
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.getByRole('status').textContent).toBe('Select photos, then choose their species.');

  tile(1).focus();
  await fireEvent.keyDown(tile(1), { key: 'ArrowRight' });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(2)));
  // 7 columns at the fallback width: Down goes to the same column of the next row.
  await fireEvent.keyDown(tile(2), { key: 'ArrowDown' });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(9)));
  expect(tile(9).tabIndex).toBe(0);
  await fireEvent.keyDown(tile(9), { key: 'Enter' });
  expect(tile(9).getAttribute('aria-selected')).toBe('true');
  await fireEvent.keyDown(tile(9), { key: 'ArrowLeft', shiftKey: true });
  await fireEvent.keyDown(tile(8), { key: 'ArrowLeft', shiftKey: true });
  expect([7, 8, 9].map((id) => tile(id).getAttribute('aria-selected'))).toEqual(['true', 'true', 'true']);
  await fireEvent.keyDown(tile(7), { key: 'Enter' });
  expect(tile(7).getAttribute('aria-selected')).toBe('false');
  await fireEvent.keyDown(tile(7), { key: 'Home' });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(1)));
});

test('choosing a target sorts the selection in the grid\'s order: inactive while asked, then the tiles go with a toast', async () => {
  let answer;
  sortSplit.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  await fireEvent.click(tile(5));
  await fireEvent.click(tile(2), { ctrlKey: true });
  await fireEvent.click(tile(3), { ctrlKey: true });
  await fireEvent.click(target('2'));
  expect(sortSplit).toHaveBeenCalledWith('A5', [2, 3, 5], 'A90');
  expect(target('1').getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(target('2'));
  await fireEvent.keyDown(window, { key: '1' });
  expect(sortSplit).toHaveBeenCalledTimes(1);

  answer(splitWith(all.filter((p) => ![2, 3, 5].includes(p.id))));
  await vi.waitFor(() => expect(tile(2)).toBeNull());
  expect(tile(5)).toBeNull();
  expect(document.querySelector('.split-left').textContent.replace(/\s+/g, ' ').trim()).toBe('6 of 12 left to sort');
  expect(toast.message).toBe('3 photos moved to Danaus alcippus.');
  expect(screen.getByRole('status').textContent).toBe('Select photos, then choose their species.');
  expect(getStatus).toHaveBeenCalled();
});

test('the keys 1–9 sort the selection to that target; a key past the targets does nothing', async () => {
  sortSplit.mockResolvedValue(splitWith(all.slice(1)));
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  await fireEvent.keyDown(window, { key: '1' });
  expect(sortSplit).not.toHaveBeenCalled();
  await fireEvent.click(tile(1));
  await fireEvent.keyDown(window, { key: '3' });
  expect(sortSplit).not.toHaveBeenCalled();
  await fireEvent.keyDown(window, { key: '1' });
  expect(sortSplit).toHaveBeenCalledWith('A5', [1], 'A5');
  await vi.waitFor(() => expect(toast.message).toBe('1 photo moved to Danaus chrysippus.'));
});

test('the sorted tiles fade out and the rest glide to their places', async () => {
  window.matchMedia = () => ({ matches: false });
  const animate = vi.fn(function () { return { finished: Promise.resolve(), cancel() {} }; });
  Element.prototype.animate = animate;
  // jsdom lays nothing out: each tile is placed 160 px after the one before it, in the order the grid shows them.
  const place = vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function () {
    const at = [...document.querySelectorAll('.sg [data-key]')].indexOf(this);
    return { left: Math.max(0, at) * 160, top: 0, width: 150, height: 150, right: 0, bottom: 150 };
  });
  sortSplit.mockResolvedValueOnce(splitWith(all.filter((p) => p.id !== 1)));
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  const first = tile(1);
  const second = tile(2);
  await fireEvent.click(first);
  await fireEvent.click(target('2'));
  await vi.waitFor(() => expect(tile(1)).toBeNull());
  const faded = animate.mock.contexts.indexOf(first);
  expect(faded).toBeGreaterThanOrEqual(0);
  expect(animate.mock.calls[faded][0]).toEqual([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.85)' }]);
  // the tile that took its place glides there from where it was
  expect(tile(2)).toBe(second);
  const glided = animate.mock.contexts.indexOf(second);
  expect(glided).toBeGreaterThanOrEqual(0);
  expect(animate.mock.calls[glided][0]).toEqual([{ transform: 'translate(160px, 0px)' }, { transform: 'none' }]);
  place.mockRestore();
});

test('under reduced motion the tiles just go', async () => {
  const animate = vi.fn(() => ({ finished: Promise.resolve(), cancel() {} }));
  Element.prototype.animate = animate;
  sortSplit.mockResolvedValueOnce(splitWith(all.filter((p) => p.id !== 1)));
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  await fireEvent.click(tile(1));
  await fireEvent.click(target('2'));
  await vi.waitFor(() => expect(tile(1)).toBeNull());
  expect(animate).not.toHaveBeenCalled();
});

test('a refusal shows its message and the photos stay selected', async () => {
  sortSplit.mockRejectedValue(Object.assign(new Error('Wait for the taxonomy update to finish.'), { code: 'taxonomy_updating', status: 409 }));
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  await fireEvent.click(tile(1));
  await fireEvent.click(target('2'));
  expect((await screen.findByRole('alert')).textContent).toBe('Wait for the taxonomy update to finish.');
  expect(tile(1).getAttribute('aria-selected')).toBe('true');
  expect(target('2').getAttribute('aria-disabled')).toBe('false');
});

test('the last photos sorted: "All sorted." with a link to each new species, focus on it', async () => {
  getSplit.mockResolvedValue(splitWith([photo(1)], 3));
  sortSplit.mockResolvedValue(splitWith([], 3));
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  await fireEvent.click(tile(1));
  tile(1).focus();
  await fireEvent.keyDown(window, { key: '2' });
  const heading = await screen.findByRole('heading', { name: 'All sorted.' });
  expect(screen.getByText('The last photos went to Danaus alcippus.')).toBeTruthy();
  expect(screen.getAllByRole('link', { name: /^Open/ }).map((a) => a.getAttribute('href'))).toEqual(['#/species/A5', '#/species/A90']);
  await vi.waitFor(() => expect(document.activeElement).toBe(heading));
  expect(document.querySelector('.split-left').textContent.replace(/\s+/g, ' ').trim()).toBe('0 of 3 left to sort');
});

test('opened with none left: "All sorted."', async () => {
  getSplit.mockResolvedValue(splitWith([], 3));
  render(Split, { id: 'A5' });
  expect(await screen.findByRole('heading', { name: 'All sorted.' })).toBeTruthy();
  expect(screen.getByText('Every photo of Danaus chrysippus has its species.')).toBeTruthy();
});

test('Space on a tile opens it large at its own address', async () => {
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  tile(3).focus();
  await fireEvent.keyDown(tile(3), { key: ' ' });
  expect(window.location.hash).toBe('#/split/A5/photo/3');
  await fireEvent.dblClick(tile(4));
  expect(window.location.hash).toBe('#/split/A5/photo/4');
});

test('the large photo: its tags, place and file; 1–9 sort it and the next photo shows', async () => {
  window.location.hash = '#/split/A5/photo/2';
  sortSplit.mockResolvedValue(splitWith(all.filter((p) => p.id !== 2)));
  render(Split, { id: 'A5', photoId: 2 });
  const viewer = await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' });
  expect(viewer.textContent).toContain('2 / 9');
  // the counter is not a live region: paging is not announced on every arrow
  expect(viewer.querySelector('.c').hasAttribute('aria-live')).toBe(false);
  expect(viewer.textContent).toContain('Pune, India');
  expect(viewer.textContent).toContain('images/x/Danaus_chrysippus_02.jpg');
  expect(within(viewer).getByText('larva')).toBeTruthy();
  const group = within(viewer).getByRole('group', { name: 'Sort this photo to' });
  expect(within(group).getAllByRole('button').map((b) => b.textContent.trim())).toEqual(['1Danaus chrysippusPlain Tiger', '2Danaus alcippusAfrican Tiger']);

  await fireEvent.keyDown(window, { key: '2' });
  expect(sortSplit).toHaveBeenCalledWith('A5', [2], 'A90');
  await vi.waitFor(() => expect(window.location.hash).toBe('#/split/A5/photo/3'));
  expect(toast.message).toBe('1 photo moved to Danaus alcippus.');
  // the same viewer, showing the next photo at once (not closed and opened again)
  expect(screen.getByRole('dialog')).toBe(viewer);
  expect(viewer.textContent).toContain('images/x/Danaus_chrysippus_03.jpg');
  expect(viewer.textContent).toContain('2 / 8');
});

test('Escape closes the large photo opened from its address to the grid\'s address', async () => {
  window.location.hash = '#/split/A5/photo/3';
  render(Split, { id: 'A5', photoId: 3 });
  await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' });
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(window.location.hash).toBe('#/split/A5');
});

test('a large photo that is not to sort shows the grid instead', async () => {
  window.location.hash = '#/split/A5/photo/77';
  render(Split, { id: 'A5', photoId: 77 });
  await vi.waitFor(() => expect(window.location.hash).toBe('#/split/A5'));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('a split that cannot be read says why', async () => {
  getSplit.mockRejectedValue(new Error('No species has the id A5.'));
  render(Split, { id: 'A5' });
  expect((await screen.findByRole('alert')).textContent).toBe('No species has the id A5.');
});

test('a sort from the large photo leaves the tiles behind it as they are, and a viewer closed meanwhile stays closed', async () => {
  window.matchMedia = () => ({ matches: false });
  const animate = vi.fn(() => ({ finished: Promise.resolve(), cancel() {} }));
  Element.prototype.animate = animate;
  window.location.hash = '#/split/A5/photo/2';
  let answer;
  sortSplit.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  render(Split, { id: 'A5', photoId: 2 });
  await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' });
  animate.mockClear();
  await fireEvent.keyDown(window, { key: '1' });
  expect(sortSplit).toHaveBeenCalledWith('A5', [2], 'A5');
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(window.location.hash).toBe('#/split/A5');
  answer(splitWith(all.filter((p) => p.id !== 2)));
  await vi.waitFor(() => expect(toast.message).toBe('1 photo moved to Danaus chrysippus.'));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(window.location.hash).toBe('#/split/A5');
  expect(animate.mock.calls.some(([frames]) => frames?.[1]?.opacity === 0)).toBe(false);
});

test('a held key sorts once: a repeated 1–9 sorts nothing, on the grid and in the large photo', async () => {
  sortSplit.mockResolvedValue(splitWith(all.slice(2)));
  const { unmount } = render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  await fireEvent.click(tile(1));
  await fireEvent.click(tile(2), { ctrlKey: true });
  await fireEvent.keyDown(window, { key: '2', repeat: true });
  expect(sortSplit).not.toHaveBeenCalled();
  await fireEvent.keyDown(window, { key: '2' });
  await vi.waitFor(() => expect(tile(1)).toBeNull());
  await fireEvent.click(tile(3));
  await fireEvent.keyDown(window, { key: '2', repeat: true });
  await fireEvent.keyDown(window, { key: 'a', repeat: true });
  expect(sortSplit).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('status').textContent).toBe('1 photo selected');
  unmount();

  sortSplit.mockClear();
  window.location.hash = '#/split/A5/photo/3';
  render(Split, { id: 'A5', photoId: 3 });
  await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' });
  await fireEvent.keyDown(window, { key: '1', repeat: true });
  expect(sortSplit).not.toHaveBeenCalled();
});

test('a reload asked before a sort but answered after it does not bring the sorted photos back', async () => {
  render(Split, { id: 'A5' });
  await screen.findByRole('grid');
  let stale;
  getSplit.mockImplementationOnce(() => new Promise((resolve) => { stale = resolve; }));
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  sortSplit.mockResolvedValue(splitWith(all.slice(1)));
  await fireEvent.click(tile(1));
  await fireEvent.keyDown(window, { key: '2' });
  await vi.waitFor(() => expect(tile(1)).toBeNull());
  stale(splitWith(all));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(tile(1)).toBeNull();
  expect(document.querySelector('.split-left').textContent.replace(/\s+/g, ' ').trim()).toBe('8 of 12 left to sort');

  // a reload asked after the sort is applied as usual
  getSplit.mockResolvedValueOnce(splitWith(all.slice(2)));
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  await vi.waitFor(() => expect(tile(2)).toBeNull());
});

test('the large photo of a species without a common name titles it by its scientific name, in italics', async () => {
  getSplit.mockResolvedValue({ ...splitWith(all), taxon: { ...brief, common: null } });
  window.location.hash = '#/split/A5/photo/2';
  render(Split, { id: 'A5', photoId: 2 });
  const viewer = await screen.findByRole('dialog', { name: 'Photo viewer: Danaus chrysippus' });
  const name = viewer.querySelector('.lb-name');
  expect(name.textContent.trim()).toBe('Danaus chrysippus');
  expect(name.querySelector('.bn').textContent).toBe('Danaus chrysippus');
});

test('a target no longer on the list is said, with what to do; the others still sort', async () => {
  getSplit.mockResolvedValue({ ...splitWith(all), targets: [targets[0]], missingTargets: [{ id: 'A90', name: 'Danaus alcippus', common: null }] });
  render(Split, { id: 'A5' });
  const note = await screen.findByRole('note');
  expect(note.textContent.replace(/\s+/g, ' ').trim())
    .toBe('Some species this was split into are no longer on the list (Danaus alcippus). Use Move on the species page for those photos.');
  expect(note.querySelector('i.bn').textContent).toBe('Danaus alcippus');
  expect(within(note).queryByRole('link')).toBeNull();
  expect(target('1')).toBeTruthy();
});

test('none of its targets left: that sentence and a link to the species page, no target bar', async () => {
  getSplit.mockResolvedValue({ ...splitWith(all), targets: [], missingTargets: [{ id: 'A90', name: 'Danaus alcippus', common: null }, { id: 'A91', name: 'Danaus dorippus', common: null }] });
  render(Split, { id: 'A5' });
  const note = await screen.findByRole('note');
  expect(note.textContent).toContain('(Danaus alcippus, Danaus dorippus)');
  expect(within(note).getByRole('link', { name: 'Open the species page' }).getAttribute('href')).toBe('#/species/A5');
  expect(screen.queryByRole('toolbar')).toBeNull();
  // the photos are still to sort: not "All sorted.", and no sentence naming no species
  expect(screen.queryByRole('heading', { name: 'All sorted.' })).toBeNull();
  expect(screen.getByRole('grid').querySelectorAll('[role="gridcell"]')).toHaveLength(9);
  expect(document.querySelector('.split-why')).toBeNull();
  expect(document.body.textContent).not.toContain('splits it into .');
});

test('the photos to sort say when their HEIC photos can\'t be shown on this computer (7b review fix U8)', async () => {
  status.heic = { state: 'missing', decoder: null, system: 'windows', reason: 'absent' };
  getSplit.mockResolvedValue(splitWith([photo(1, { fileType: 'heic', unreadable: true }), photo(2)]));
  render(Split, { id: 'A5' });
  expect(await screen.findByText("This device can't show HEIC photos yet.")).toBeTruthy();
});
