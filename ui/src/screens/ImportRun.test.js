import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import ImportRun from './ImportRun.svelte';
import Toast from '../lib/Toast.svelte';
import {
  acceptSuggestions, assignItems, discardImport, fileImport, finishImport, getFsRoots, getImportResult, getImport, getImportItems, getStatus, getTags, listFolder, pauseImport, relocateImport, search,
  skipItems, startImport, tagItems, getRecentPlaces, placeItems, unplaceItems, getMyPlaces, deletePlace,
  addCustomSpecies, checkCustomName, getChildren, putImportPairs, checkHeic,
} from '../lib/api.js';
import { folderSections } from '../lib/importView.js';
import { HEIC_CHANGED, resetStatus, status } from '../lib/status.svelte.js';

vi.mock('../lib/importView.js', async (importOriginal) => {
  const real = await importOriginal();
  return { ...real, folderSections: vi.fn(real.folderSections) };
});
vi.mock('../lib/api.js', () => ({
  getImport: vi.fn(), getImportItems: vi.fn(), getTags: vi.fn(), assignItems: vi.fn(), tagItems: vi.fn(), skipItems: vi.fn(),
  acceptSuggestions: vi.fn(), search: vi.fn(), fileImport: vi.fn(), pauseImport: vi.fn(), relocateImport: vi.fn(), startImport: vi.fn(),
  getFsRoots: vi.fn(), listFolder: vi.fn(), getStatus: vi.fn(), getImportResult: vi.fn(), finishImport: vi.fn(), discardImport: vi.fn(),
  placeItems: vi.fn(), unplaceItems: vi.fn(), getRecentPlaces: vi.fn(), searchPlaces: vi.fn(), addPlace: vi.fn(), getMyPlaces: vi.fn(),
  renamePlace: vi.fn(), deletePlace: vi.fn(),
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), getChildren: vi.fn(), renameCustomSpecies: vi.fn(), putImportPairs: vi.fn(),
  checkHeic: vi.fn(), getBackup: vi.fn(),
  importPreviewUrl: (importId, item) => (item.v == null ? null : `./api/imports/${importId}/items/${item.id}/preview?v=${item.v}`),
  importDisplayUrl: (importId, item) => `./api/imports/${importId}/items/${item.id}/display?v=${item.v}`,
}));

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getTags.mockResolvedValue([{ id: 6, key: 'male', label: 'male', source: 'builtin', photoCount: 0 }]);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  resetStatus();
});

const summary = (extra = {}) => ({
  id: 3, rootPath: '/Users/you/Pictures/Butterflies 2024', rootName: 'Butterflies 2024', state: 'open', createdAt: '2026-10-12T08:00:00Z',
  updatedAt: '2026-10-12T09:00:00Z', folderMissing: false, paused: false, phase: 'idle', progress: null, stopped: null, notice: null,
  counts: { total: 6, photos: 6, reading: 0, unassigned: 0, suggested: 0, assigned: 0, skipped: 0, problems: 0, filed: 0, missing: 0 },
  filings: 0, ...extra,
});
const item = (id, extra = {}) => ({
  id, path: `Sinhagad/IMG_${id}.JPG`, folder: 'Sinhagad', name: `IMG_${id}.JPG`, size: 1, modifiedAt: '2024-08-12T10:00:00Z',
  capturedAt: new Date(Date.UTC(2024, 7, 12) + id * 3600000).toISOString(), state: 'read', problem: null, suggestedTaxonId: null,
  libraryTaxonId: null, copyOf: null, taxonId: null, tags: [], skipped: false, outcome: null, imageId: null, error: null, v: 'v1',
  place: null, ...extra,
});
const taxa = { A5: { name: 'Danaus chrysippus', common: 'Plain Tiger' }, A6: { name: 'Danaus genutia', common: 'Common Tiger' } };
const items = () => [
  item(1, { folder: '', path: 'IMG_1.JPG' }),
  item(2, { suggestedTaxonId: 'A5' }), item(3, { suggestedTaxonId: 'A5' }),
  item(4, { taxonId: 'A6' }), item(5, { libraryTaxonId: 'A5' }),
  item(6, { state: 'unsupported', problem: "AVIF photos aren't supported yet.", v: null }),
];
const page = (list, version = 10) => ({ version, items: list, taxa });
const tile = (id) => document.querySelector(`[data-item-id="${id}"]`);

async function open(list = items(), s = summary(), revision = undefined) {
  getImport.mockResolvedValue(s);
  getImportItems.mockResolvedValue(revision === undefined ? page(list) : { ...page(list), revision });
  const result = render(ImportRun, { id: 3 });
  render(Toast);
  await screen.findByRole('heading', { level: 1, name: 'Butterflies 2024' });
  return result;
}

test('shows the folder by subfolder with counts on every filter, and filters and regroups', async () => {
  await open();

  expect(screen.getByText('/Users/you/Pictures/Butterflies 2024')).toBeTruthy();
  expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Butterflies 2024', 'Sinhagad']);
  const chips = within(screen.getByRole('group', { name: 'Show photos' })).getAllByRole('button').map((b) => b.getAttribute('aria-label'));
  expect(chips).toEqual([
    'All, 6 photos', 'Unassigned, 2 photos', 'Suggested, 2 photos', 'Assigned, 1 photo', 'Skipped, 0 photos', 'Problems, 1 photo', 'Unknown place, 5 photos',
  ]);
  expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Suggested: Plain Tiger');

  await fireEvent.click(screen.getByRole('button', { name: 'Suggested, 2 photos' }));
  expect([...document.querySelectorAll('[data-item-id]')].map((t) => t.dataset.itemId)).toEqual(['2', '3']);

  await fireEvent.click(screen.getByRole('button', { name: 'All, 6 photos' }));
  await fireEvent.click(screen.getByRole('button', { name: 'By species' }));
  expect(screen.getAllByRole('heading', { level: 3 }).map((h) => h.textContent)).toEqual(['Common Tiger', 'No species yet']);
});

test('click, Shift-click and Ctrl-click select; A selects all; Escape clears', async () => {
  await open();

  await fireEvent.click(tile(2));
  expect(screen.getByRole('group', { name: 'Selected photos' }).textContent).toContain('1 photo selected');
  await fireEvent.click(tile(4), { shiftKey: true });
  expect(screen.getByText('3 photos selected')).toBeTruthy();
  expect(tile(3).getAttribute('aria-selected')).toBe('true');
  await fireEvent.click(tile(3), { ctrlKey: true });
  expect(screen.getByText('2 photos selected')).toBeTruthy();

  // A selects every photo that can still be changed: not the unreadable HEIC
  await fireEvent.keyDown(window, { key: 'a' });
  expect(screen.getByText('5 photos selected')).toBeTruthy();
  expect(tile(6).hasAttribute('aria-selected')).toBe(false);
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('group', { name: 'Selected photos' })).toBeNull();
});

test('arrows move focus, Shift extends the selection, Space toggles and Enter opens the large view', async () => {
  await open();

  tile(2).focus();
  await fireEvent.keyDown(tile(2), { key: 'ArrowRight' });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(3)));
  await fireEvent.keyDown(tile(3), { key: ' ' });
  expect(tile(3).getAttribute('aria-selected')).toBe('true');
  await fireEvent.keyDown(tile(3), { key: 'ArrowRight', shiftKey: true });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(4)));
  expect(screen.getByText('2 photos selected')).toBeTruthy();

  await fireEvent.keyDown(tile(4), { key: 'Enter' });
  expect(window.location.hash).toBe('#/import/3/photo/4');
});

test('S assigns the selected photos to a species, saved at once', async () => {
  search.mockResolvedValue([{ id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae' }]);
  assignItems.mockResolvedValue(page([item(2, { taxonId: 'A5', suggestedTaxonId: 'A5' }), item(3, { taxonId: 'A5', suggestedTaxonId: 'A5' })], 11));
  await open();

  await fireEvent.click(tile(2));
  await fireEvent.click(tile(3), { shiftKey: true });
  await fireEvent.keyDown(window, { key: 's' });
  const dialog = screen.getByRole('dialog', { name: 'Assign 2 photos to a species' });
  await fireEvent.input(within(dialog).getByRole('combobox'), { target: { value: 'plain' } });
  await fireEvent.click(await within(dialog).findByRole('option', { name: /Plain Tiger/ }));

  expect(assignItems).toHaveBeenCalledWith(3, [2, 3], 'A5');
  expect(await screen.findByText('2 photos assigned to Plain Tiger.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Plain Tiger');
  expect(screen.queryByRole('group', { name: 'Selected photos' })).toBeNull();
});

test('T tags the selected photos; X skips them and X again files them after all', async () => {
  tagItems.mockResolvedValue(page([item(4, { taxonId: 'A6', tags: ['male'] })]));
  skipItems.mockResolvedValueOnce(page([item(4, { taxonId: 'A6', tags: ['male'], skipped: true })]))
    .mockResolvedValueOnce(page([item(4, { taxonId: 'A6', tags: ['male'] })]));
  await open();

  await fireEvent.click(tile(4));
  await fireEvent.keyDown(window, { key: 't' });
  const dialog = screen.getByRole('dialog', { name: 'Tag 1 photo' });
  await fireEvent.click(within(dialog).getByRole('checkbox', { name: 'male' }));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Apply' }));
  expect(tagItems).toHaveBeenCalledWith(3, [4], ['male'], []);
  expect(await screen.findByText('Tags updated on 1 photo.')).toBeTruthy();

  await fireEvent.click(tile(4));
  await fireEvent.keyDown(window, { key: 'x' });
  expect(skipItems).toHaveBeenCalledWith(3, [4], true);
  await vi.waitFor(() => expect(tile(4).getAttribute('aria-label')).toBe('IMG_4.JPG, Skipped'));
  await fireEvent.click(tile(4));
  expect(screen.getByRole('button', { name: /^Don't skip/ })).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: /^Don't skip/ }));
  expect(skipItems).toHaveBeenLastCalledWith(3, [4], false);
});

test('accepts a folder\'s suggestions, and skips the photos already in the library in one go', async () => {
  acceptSuggestions.mockResolvedValue(page([item(2, { taxonId: 'A5', suggestedTaxonId: 'A5' }), item(3, { taxonId: 'A5', suggestedTaxonId: 'A5' })]));
  skipItems.mockResolvedValue(page([item(5, { libraryTaxonId: 'A5', skipped: true })]));
  await open();

  await fireEvent.click(screen.getByRole('button', { name: 'Accept all suggestions in Sinhagad' }));
  expect(acceptSuggestions).toHaveBeenCalledWith(3, [2, 3]);
  expect(await screen.findByText('2 suggestions accepted in Sinhagad.')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Assigned, 3 photos' })).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'Skip 1 photo already in the library' }));
  expect(skipItems).toHaveBeenCalledWith(3, [5], true);
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: /already in the library/ })).toBeNull());
});

test('while it reads, it asks about once a second for what changed, and stops once idle', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValueOnce(summary({ phase: 'reading', progress: { done: 1, total: 6 } }))
    .mockResolvedValueOnce(summary({ phase: 'reading', progress: { done: 3, total: 6 } }))
    .mockResolvedValue(summary());
  getImportItems.mockResolvedValueOnce(page([item(1, { state: 'pending', v: null }), item(2, { state: 'pending', v: null })], 4))
    .mockResolvedValueOnce(page([item(1)], 6))
    .mockResolvedValueOnce(page([item(2, { suggestedTaxonId: 'A5' })], 7));
  render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  expect(tile(1).querySelector('img')).toBeNull();

  await vi.advanceTimersByTimeAsync(1000);
  expect(getImportItems).toHaveBeenLastCalledWith(3, 4);
  expect(tile(1).querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/1/preview?v=v1');

  await vi.advanceTimersByTimeAsync(1000);
  expect(getImportItems).toHaveBeenLastCalledWith(3, 6);
  expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Suggested: Plain Tiger');
  await vi.advanceTimersByTimeAsync(5000);
  expect(getImportItems).toHaveBeenCalledTimes(3);
});

test('while filing runs the selection can\'t be changed, and says why', async () => {
  await open(items(), summary({ phase: 'filing', progress: { done: 0, total: 1 } }));

  await fireEvent.click(tile(2));
  const assign = screen.getByRole('button', { name: /^Assign species/ });
  expect(assign.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.keyDown(window, { key: 's' });
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.getAllByText('Wait for filing to finish or pause it.').length).toBeGreaterThan(0);
});

test('says why an import cannot be opened', async () => {
  getImport.mockRejectedValue(new Error('That import can\'t be found.'));
  getImportItems.mockRejectedValue(new Error('That import can\'t be found.'));
  render(ImportRun, { id: 9 });

  expect((await screen.findByRole('alert')).textContent).toBe("That import can't be found.");
  expect(screen.getByRole('link', { name: 'Back to your imports' }).getAttribute('href')).toBe('#/import');
});

test('files the assigned photos, shows the progress and pauses', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValueOnce(summary()).mockResolvedValueOnce(summary({ phase: 'filing', progress: { done: 1, total: 1 } }))
    .mockResolvedValue(summary({ paused: true }));
  getImportItems.mockResolvedValueOnce(page(items())).mockResolvedValue(page([], 10));
  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  pauseImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 1, total: 1 } }));
  getStatus.mockResolvedValue({});
  render(ImportRun, { id: 3 });
  render(Toast);
  await vi.advanceTimersByTimeAsync(0);

  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenCalledWith(3, null));
  await vi.waitFor(() => expect(screen.getByRole('group', { name: 'Filing' }).textContent).toContain('Filing photos… 0 of 1'));
  expect(getStatus).toHaveBeenCalled();

  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(pauseImport).toHaveBeenCalledWith(3);
  expect(screen.getByText('Filing will pause after the photos it is filing now.')).toBeTruthy();
  await vi.advanceTimersByTimeAsync(2000);
  expect(screen.getByText('Paused')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'File 1 assigned photo' })).toBeTruthy();
});

test('a missing folder can be looked for again or chosen where it moved', async () => {
  getFsRoots.mockResolvedValue([{ name: 'Photos SSD', path: '/Volumes/Photos SSD', kind: 'drive' }]);
  listFolder.mockResolvedValue({
    path: '/Volumes/Photos SSD', name: 'Photos SSD', parent: '/Volumes', crumbs: [{ name: 'Photos SSD', path: '/Volumes/Photos SSD' }],
    folders: [], photos: 6, isLibrary: false, openImport: null,
  });
  relocateImport.mockRejectedValueOnce(new Error("None of this import's photos are in that folder."))
    .mockResolvedValue(summary({ rootPath: '/Volumes/Photos SSD' }));
  startImport.mockResolvedValue({ import: summary({ folderMissing: true }), continued: true });
  getStatus.mockResolvedValue({});
  await open(items(), summary({ folderMissing: true }));

  const banner = screen.getByRole('alert');
  expect(banner.textContent).toContain('Folder not found.');
  expect(screen.getByRole('button', { name: 'File 1 assigned photo' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(within(banner).getByRole('button', { name: 'Look again' }));
  expect(startImport).toHaveBeenCalledWith('/Users/you/Pictures/Butterflies 2024');

  await fireEvent.click(within(banner).getByRole('button', { name: 'Choose where it moved' }));
  const dialog = await screen.findByRole('dialog', { name: 'Where is Butterflies 2024 now?' });
  await within(dialog).findByText('6 photos in this folder.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Use this folder' }));
  expect((await within(dialog).findByRole('alert')).textContent).toBe("None of this import's photos are in that folder.");
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Use this folder' }));

  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(relocateImport).toHaveBeenLastCalledWith(3, '/Volumes/Photos SSD');
  expect(screen.getByText('/Volumes/Photos SSD')).toBeTruthy();
  expect(screen.queryByText(/Folder not found/)).toBeNull();
  expect(await screen.findByText('Found Butterflies 2024 at /Volumes/Photos SSD.')).toBeTruthy();
});

test('reading cut short by closing the app carries on when the import is opened', async () => {
  startImport.mockResolvedValue({ import: summary({ phase: 'reading', progress: { done: 812, total: 9870 } }), continued: true });
  getStatus.mockResolvedValue({});
  await open(items(), summary({ stopped: { phase: 'reading', done: 812, total: 9870, at: '2026-10-12T09:00:00Z' } }));

  await vi.waitFor(() => expect(startImport).toHaveBeenCalledWith('/Users/you/Pictures/Butterflies 2024'));
  expect(await screen.findByText('Reading Butterflies 2024 again where it stopped.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.queryByText(/won't carry on by itself/)).toBeNull());
});

test('filing cut short by closing the app waits for Continue filing', async () => {
  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 1200, total: 2140 } }));
  getStatus.mockResolvedValue({});
  await open(items(), summary({ stopped: { phase: 'filing', done: 1200, total: 2140, at: '2026-10-12T09:00:00Z' } }));

  expect(screen.getByText(/stopped when Butterfly Atlas closed \(filing 1,200 of 2,140\)/)).toBeTruthy();
  expect(startImport).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: 'Continue filing' }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenCalledWith(3, null));
});

test('the address of one of its photos opens the large view; paging replaces the address and closing goes back to the grid', async () => {
  getImport.mockResolvedValue(summary());
  getImportItems.mockResolvedValue(page(items()));
  window.location.hash = '#/import/3/photo/2';
  // an earlier test opened a photo from its tile, which closes with Back: here Back leads to the grid too
  vi.spyOn(window.history, 'back').mockImplementation(() => window.location.replace('#/import/3'));
  render(ImportRun, { id: 3, itemId: 2 });

  const dialog = await screen.findByRole('dialog', { name: 'Photo: IMG_2.JPG' });
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/import/3/photo/3');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Close' }));
  expect(window.location.hash).toBe('#/import/3');
});

test('an address naming a photo the import does not have shows the grid', async () => {
  getImport.mockResolvedValue(summary());
  getImportItems.mockResolvedValue(page(items()));
  window.location.hash = '#/import/3/photo/99';
  render(ImportRun, { id: 3, itemId: 99 });

  await vi.waitFor(() => expect(window.location.hash).toBe('#/import/3'));
  expect(screen.queryByRole('dialog')).toBeNull();
});

const result = (extra = {}) => ({
  filed: 40, newSpecies: 3, speciesCount: 12, milestone: 10,
  bySpecies: [{ taxonId: 'A6', name: 'Danaus genutia', common: 'Common Tiger', count: 40, isNew: true }],
  duplicates: 0, alsoElsewhere: 0, copies: 0, skipped: 0, failed: 0, problems: [], left: 5, lastFiling: null, ...extra,
});

async function fileUntilDone() {
  vi.useFakeTimers();
  getImport.mockResolvedValueOnce(summary()).mockResolvedValue(summary());
  getImportItems.mockResolvedValueOnce(page(items())).mockResolvedValue(page([], 11));
  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  getStatus.mockResolvedValue({});
  render(ImportRun, { id: 3 });
  render(Toast);
  await vi.advanceTimersByTimeAsync(0);
  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  await vi.advanceTimersByTimeAsync(1000);
}

test('when filing ends the result shows, with a quiet toast for the species it added under reduced motion', async () => {
  getImportResult.mockResolvedValue(result({ lastFiling: { filed: 30, newSpecies: 2, milestone: 10, speciesCount: 12 } }));
  await fileUntilDone();

  const dialog = screen.getByRole('dialog', { name: 'Filed so far' });
  expect(dialog.textContent).toContain('40 photos filed, 3 new species');
  expect(screen.getByText('2 new species photographed! Milestone reached: 10 species.')).toBeTruthy();
  expect(screen.queryByRole('dialog', { name: 'New species in your atlas' })).toBeNull();
  expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Leave the rest for later' }));

  await fireEvent.click(within(dialog).getByRole('button', { name: 'Leave the rest for later' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(finishImport).not.toHaveBeenCalled();
});

test('with motion allowed one celebration covers all the new species', async () => {
  window.matchMedia = () => ({ matches: false });
  getImportResult.mockResolvedValue(result({ newSpecies: 30, lastFiling: { filed: 900, newSpecies: 27, milestone: 250, speciesCount: 312 } }));
  await fileUntilDone();

  // the celebration comes first and alone: it is the top overlay and its button works
  const party = screen.getByRole('dialog', { name: 'New species in your atlas' });
  expect(party.textContent).toContain('27 new species photographed! Your atlas now has 312 species.');
  expect(screen.queryByRole('dialog', { name: 'Filed so far' })).toBeNull();
  const keepGoing = within(party).getByRole('button', { name: 'Keep going' });
  expect(keepGoing.closest('[inert]')).toBeNull();
  expect(document.activeElement).toBe(keepGoing);

  // closing it opens the result, usable, with focus inside it
  await fireEvent.click(keepGoing);
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.queryByRole('dialog', { name: 'New species in your atlas' })).toBeNull();
  const shown = screen.getByRole('dialog', { name: 'Filed so far' });
  const leave = within(shown).getByRole('button', { name: 'Leave the rest for later' });
  expect(leave.closest('[inert]')).toBeNull();
  expect(document.activeElement).toBe(leave);

  // Escape closes it too, and focus goes back to Finish…
  await fireEvent.keyDown(window, { key: 'Escape' });
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Finish…' }));
  expect(document.querySelector('[inert]')).toBeNull();
});

test('Finish marks what still waits as skipped and goes back to the imports; with nothing left it just finishes', async () => {
  getImport.mockResolvedValue(summary());
  getImportItems.mockResolvedValue(page(items()));
  getImportResult.mockResolvedValueOnce(result()).mockResolvedValue(result({ left: 0 }));
  finishImport.mockResolvedValue({ import: summary({ state: 'finished' }), result: result() });
  render(ImportRun, { id: 3 });
  render(Toast);

  await fireEvent.click(await screen.findByRole('button', { name: 'Finish…' }));
  const dialog = await screen.findByRole('dialog', { name: 'Filed so far' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Finish and leave the rest in the folder' }));
  expect(finishImport).toHaveBeenCalledWith(3, true);
  await vi.waitFor(() => expect(window.location.hash).toBe('#/import'));
  expect(screen.getByText('The import of Butterflies 2024 is finished.')).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'Finish…' }));
  const done = await screen.findByRole('dialog', { name: 'Everything is filed' });
  await fireEvent.click(within(done).getByRole('button', { name: 'Finish' }));
  expect(finishImport).toHaveBeenLastCalledWith(3, false);
});

test('a finished import shows its result and can be removed from the list', async () => {
  getImport.mockResolvedValue(summary({ state: 'finished' }));
  getImportItems.mockResolvedValue(page(items()));
  getImportResult.mockResolvedValue(result({ left: 0 }));
  discardImport.mockResolvedValue(null);
  render(ImportRun, { id: 3 });

  await vi.waitFor(() => expect(document.querySelector('.ir-big').textContent).toBe('40 photos filed, 3 new species'));
  expect(document.querySelector('.ig')).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: 'Remove from your imports' }));
  expect(discardImport).toHaveBeenCalledWith(3);
  await vi.waitFor(() => expect(window.location.hash).toBe('#/import'));
});

test('filed, missing and unreadable photos open but are not selected; one whose filing failed can be assigned again', async () => {
  search.mockResolvedValue([{ id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae' }]);
  assignItems.mockResolvedValue(page([item(8, { taxonId: 'A5' })]));
  await open([
    item(7, { taxonId: 'A6', outcome: 'filed', error: 'Already in your atlas.' }),
    item(8, { taxonId: 'A6', outcome: 'failed', error: 'The photo was locked.' }),
    item(9, { state: 'missing', taxonId: 'A6' }),
  ]);

  expect(tile(7).getAttribute('aria-label')).toBe('IMG_7.JPG, Already in your atlas.');
  await fireEvent.click(tile(7));
  await fireEvent.click(tile(9), { ctrlKey: true });
  expect(screen.queryByRole('group', { name: 'Selected photos' })).toBeNull();
  await fireEvent.keyDown(tile(7), { key: 'Enter' });
  expect(window.location.hash).toBe('#/import/3/photo/7');

  await fireEvent.click(tile(8));
  expect(screen.getByText('1 photo selected')).toBeTruthy();
  await fireEvent.keyDown(window, { key: 's' });
  const dialog = screen.getByRole('dialog', { name: 'Assign 1 photo to a species' });
  await fireEvent.input(within(dialog).getByRole('combobox'), { target: { value: 'plain' } });
  await fireEvent.click(await within(dialog).findByRole('option', { name: /Plain Tiger/ }));
  expect(assignItems).toHaveBeenCalledWith(3, [8], 'A5');
  await vi.waitFor(() => expect(tile(8).getAttribute('aria-label')).toBe('IMG_8.JPG, Plain Tiger'));
});

test('accepting suggestions updates the counts at once, even when a poll that started earlier answers after it', async () => {
  vi.useFakeTimers();
  let answerPoll;
  getImport.mockResolvedValue(summary({ phase: 'reading', progress: { done: 1, total: 6 } }));
  getImportItems.mockResolvedValueOnce(page(items(), 10))
    .mockImplementationOnce(() => new Promise((resolve) => { answerPoll = resolve; }))
    .mockResolvedValue(page([], 12));
  acceptSuggestions.mockResolvedValue(page([item(2, { taxonId: 'A5', suggestedTaxonId: 'A5' }), item(3, { taxonId: 'A5', suggestedTaxonId: 'A5' })], 11));
  render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(1000); // a poll is now waiting for its answer

  await fireEvent.click(screen.getByRole('button', { name: 'Accept all suggestions in Sinhagad' }));
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByRole('button', { name: 'Suggested, 0 photos' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Assigned, 3 photos' })).toBeTruthy();

  // the earlier poll's answer still holds the photos as suggested: it must not undo the change
  answerPoll(page([item(2, { suggestedTaxonId: 'A5' }), item(3, { suggestedTaxonId: 'A5' })], 10));
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByRole('button', { name: 'Suggested, 0 photos' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Assigned, 3 photos' })).toBeTruthy();
  // and the next poll asks again from the version before it
  await vi.advanceTimersByTimeAsync(1000);
  expect(getImportItems).toHaveBeenLastCalledWith(3, 10);
});

/* ---------- correctness (polish) ---------- */

const apiError = (code, message, status = 409) => Object.assign(new Error(message), { code, status });

test('a poll answer dropped for the user\'s own change does not end polling before the last items arrive', async () => {
  vi.useFakeTimers();
  let answerPoll;
  getImport.mockResolvedValueOnce(summary({ phase: 'reading', progress: { done: 1, total: 6 } }))
    .mockResolvedValue(summary()); // every poll from now says idle
  getImportItems.mockResolvedValueOnce(page(items(), 10))
    .mockImplementationOnce(() => new Promise((resolve) => { answerPoll = resolve; }))
    .mockResolvedValue(page([item(1, { suggestedTaxonId: 'A6' })], 12));
  acceptSuggestions.mockResolvedValue(page([item(2, { taxonId: 'A5', suggestedTaxonId: 'A5' }), item(3, { taxonId: 'A5', suggestedTaxonId: 'A5' })], 11));
  render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(1000);
  await fireEvent.click(screen.getByRole('button', { name: 'Accept all suggestions in Sinhagad' }));
  await vi.advanceTimersByTimeAsync(0);

  // the idle answer is dropped with its page: polling goes on and brings item 1's change
  answerPoll(page([item(2, { suggestedTaxonId: 'A5' })], 11));
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(1000);
  expect(getImportItems).toHaveBeenLastCalledWith(3, 10);
  expect(tile(1).getAttribute('aria-label')).toBe('IMG_1.JPG, Suggested: Common Tiger');
  await vi.advanceTimersByTimeAsync(5000);
  expect(getImportItems).toHaveBeenCalledTimes(3);
});

test('a photo filed while it was selected leaves the selection and loses its selected look', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValueOnce(summary({ phase: 'filing', progress: { done: 0, total: 1 } }))
    .mockResolvedValue(summary({ phase: 'filing', progress: { done: 1, total: 2 } }));
  getImportItems.mockResolvedValueOnce(page(items(), 10)).mockResolvedValue(page([item(4, { taxonId: 'A6', outcome: 'filed' })], 11));
  render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  await fireEvent.click(tile(4));
  await fireEvent.click(tile(2), { ctrlKey: true });
  expect(screen.getByText('2 photos selected')).toBeTruthy();

  await vi.advanceTimersByTimeAsync(1000);
  expect(screen.getByText('1 photo selected')).toBeTruthy();
  expect(tile(4).classList.contains('sel')).toBe(false);
});

test('when filing ends the open screens are told the library changed, and the footer asks again', async () => {
  const changed = vi.fn();
  window.addEventListener('atlas:library-changed', changed);
  getImportResult.mockResolvedValue(result({ lastFiling: { filed: 1, newSpecies: 0, milestone: null, speciesCount: 12 } }));
  await fileUntilDone();
  window.removeEventListener('atlas:library-changed', changed);

  expect(changed).toHaveBeenCalledOnce();
  expect(getStatus.mock.calls.length).toBeGreaterThanOrEqual(2); // after File, and after filing ended
});

test('a filing that began and ended between two polls still shows its result, once, and tells the library changed', async () => {
  vi.useFakeTimers();
  const changed = vi.fn();
  window.addEventListener('atlas:library-changed', changed);
  getImportResult.mockResolvedValue(result({ lastFiling: { filed: 1, newSpecies: 0, milestone: null, speciesCount: 12 } }));
  getStatus.mockResolvedValue({});
  // Reading gave way to a small filing and carried on, all inside one poll interval: the phase never read "filing".
  getImport.mockResolvedValueOnce(summary({ phase: 'reading', progress: { done: 1, total: 6 } }))
    .mockResolvedValueOnce(summary({ phase: 'reading', progress: { done: 2, total: 6 }, filings: 1 }))
    .mockResolvedValue(summary({ filings: 1 }));
  getImportItems.mockResolvedValueOnce(page(items())).mockResolvedValue(page([], 11));
  render(ImportRun, { id: 3 });
  render(Toast);
  await vi.advanceTimersByTimeAsync(0);

  await vi.advanceTimersByTimeAsync(1000);

  expect(screen.getByRole('dialog', { name: 'Filed so far' })).toBeTruthy();
  expect(changed).toHaveBeenCalledOnce();
  await vi.advanceTimersByTimeAsync(3000);
  window.removeEventListener('atlas:library-changed', changed);
  expect(changed).toHaveBeenCalledOnce();
  expect(getImportResult).toHaveBeenCalledOnce();
});

test('a catch-up after a change refused for filing that finds the filing already over shows its result', async () => {
  skipItems.mockRejectedValue(apiError('import_filing', 'Wait for filing to finish or pause it.'));
  getImportResult.mockResolvedValue(result({ lastFiling: { filed: 1, newSpecies: 0, milestone: null, speciesCount: 12 } }));
  getStatus.mockResolvedValue({});
  await open(items(), summary({ phase: 'reading', progress: { done: 1, total: 6 } }));
  getImport.mockResolvedValue(summary({ phase: 'reading', progress: { done: 2, total: 6 }, filings: 1 }));
  getImportItems.mockResolvedValue(page([], 11));

  await fireEvent.click(tile(4));
  await fireEvent.keyDown(window, { key: 'x' });

  expect(await screen.findByRole('dialog', { name: 'Filed so far' })).toBeTruthy();
});

test('Pause answered after the filing already ended shows its result instead of pausing', async () => {
  getImportResult.mockResolvedValue(result({ lastFiling: { filed: 1, newSpecies: 0, milestone: null, speciesCount: 12 } }));
  getStatus.mockResolvedValue({});
  pauseImport.mockResolvedValue(summary({ filings: 1 }));
  await open(items(), summary({ phase: 'filing', progress: { done: 0, total: 1 }, filings: 1 }));

  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));

  expect(await screen.findByRole('dialog', { name: 'Filed so far' })).toBeTruthy();
  expect(screen.queryByText('Filing will pause after the photos it is filing now.')).toBeNull();
});

test('an answer for the import that was open before is ignored, and the new one starts free', async () => {
  let answerFile;
  fileImport.mockImplementation(() => new Promise((resolve) => { answerFile = resolve; }));
  getStatus.mockResolvedValue({});
  const { rerender } = await open();
  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));

  getImport.mockResolvedValue(summary({ id: 4, rootName: 'Card', rootPath: '/Volumes/Card' }));
  await rerender({ id: 4 });
  await screen.findByRole('heading', { level: 1, name: 'Card' });
  answerFile(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenCalled());
  await new Promise((r) => setTimeout(r, 0));
  expect(screen.getByRole('heading', { level: 1, name: 'Card' })).toBeTruthy();
  expect(screen.getByRole('group', { name: 'Filing' }).textContent).not.toContain('Filing photos');
  expect(screen.getByRole('button', { name: 'File 1 assigned photo' }).getAttribute('aria-disabled')).toBe('false');
});

test('a library notice shows as an alert, and filing that stopped for it is not shown as a normal end', async () => {
  vi.useFakeTimers();
  const full = "Your library's drive is full. Free up some space, then continue filing.";
  getImport.mockResolvedValueOnce(summary()).mockResolvedValue(summary({ notice: full }));
  getImportItems.mockResolvedValueOnce(page(items())).mockResolvedValue(page([], 11));
  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  getStatus.mockResolvedValue({});
  getImportResult.mockResolvedValue(result());
  render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  await vi.advanceTimersByTimeAsync(1000);

  expect(screen.getByRole('alert').textContent).toBe(full);
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('the result says what still waits to be filed, and Finish warns about photos with a species not filed yet', async () => {
  getImportResult.mockResolvedValue(result());
  await open();

  await fireEvent.click(screen.getByRole('button', { name: 'Finish…' }));
  const dialog = await screen.findByRole('dialog', { name: 'Filed so far' });
  expect(dialog.textContent).toContain('5 photos are still waiting to be filed.');
  expect(dialog.textContent).toContain("1 photo has a species but isn't filed yet. File it first, or finishing leaves it in the folder.");
});

test('Look again says whether the folder is still missing, and moves focus on when it is found', async () => {
  startImport.mockResolvedValueOnce({ import: summary({ folderMissing: true }), continued: true })
    .mockResolvedValue({ import: summary({ phase: 'walking' }), continued: true });
  getStatus.mockResolvedValue({});
  await open(items(), summary({ folderMissing: true }));

  await fireEvent.click(screen.getByRole('button', { name: 'Look again' }));
  expect(await screen.findByText("Butterflies 2024 still can't be found. Connect the drive it is on, or choose where it moved.")).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Look again' }));
  expect(await screen.findByText('Found Butterflies 2024. Looking through it again.')).toBeTruthy();
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 })));
});

test('after Pause, Pause reads Pausing… and can\'t be pressed until filing has stopped', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValueOnce(summary({ phase: 'filing', progress: { done: 0, total: 3 } }))
    .mockResolvedValueOnce(summary({ phase: 'filing', progress: { done: 1, total: 3 } }))
    .mockResolvedValue(summary({ paused: true }));
  getImportItems.mockResolvedValueOnce(page(items())).mockResolvedValue(page([], 11));
  pauseImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 3 } }));
  getStatus.mockResolvedValue({});
  render(ImportRun, { id: 3 });
  render(Toast);
  await vi.advanceTimersByTimeAsync(0);

  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  await vi.advanceTimersByTimeAsync(0);
  const pausing = screen.getByRole('button', { name: 'Pausing…' });
  expect(pausing.getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(pausing.getAttribute('aria-describedby')).textContent).toBe('Filing stops after the photos it is filing now.');
  await vi.advanceTimersByTimeAsync(1000);
  expect(screen.getByRole('button', { name: 'Pausing…' })).toBeTruthy();
  await vi.advanceTimersByTimeAsync(1000);
  expect(screen.queryByRole('button', { name: /Paus/ })).toBeNull();
  expect(screen.getByText('Paused')).toBeTruthy();
});

test('File refused because the folder went missing shows the missing-folder banner', async () => {
  fileImport.mockRejectedValue(apiError('folder_missing', 'The folder can\'t be found.'));
  await open();
  getImport.mockResolvedValue(summary({ folderMissing: true }));
  getImportItems.mockResolvedValue(page([], 11));

  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  expect(await screen.findByText("The folder can't be found.")).toBeTruthy();
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Folder not found.'));
});

test('a change refused for filing shows filing', async () => {
  skipItems.mockRejectedValue(apiError('import_filing', 'Wait for filing to finish or pause it.'));
  await open();
  getImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  getImportItems.mockResolvedValue(page([], 11));

  await fireEvent.click(tile(4));
  await fireEvent.keyDown(window, { key: 'x' });
  expect(await screen.findByText('Wait for filing to finish or pause it.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.getByRole('group', { name: 'Filing' }).textContent).toContain('Filing photos'));
});

test('a change refused because the import is finished shows it read-only', async () => {
  skipItems.mockRejectedValue(apiError('import_finished', 'This import is finished.'));
  getImportResult.mockResolvedValue(result({ left: 0 }));
  await open();
  getImport.mockResolvedValue(summary({ state: 'finished' }));
  getImportItems.mockResolvedValue(page([], 11));

  await fireEvent.click(screen.getByRole('button', { name: 'Skip 1 photo already in the library' }));
  expect(await screen.findByText('This import is finished.')).toBeTruthy();
  expect(await screen.findByRole('button', { name: 'Remove from your imports' })).toBeTruthy();
  expect(document.querySelector('.ig')).toBeNull();
});

test('File with nothing left to file asks for the items again', async () => {
  fileImport.mockRejectedValue(apiError('nothing_to_file', 'Assign a species to some photos first.'));
  await open();
  getImport.mockResolvedValue(summary());
  getImportItems.mockResolvedValue(page([item(4, { taxonId: 'A6', skipped: true })], 11));

  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  expect(await screen.findByText('Assign a species to some photos first.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'File 0 assigned photos' })).toBeTruthy());
});

test('when reading can\'t carry on by itself it says so, with Continue reading', async () => {
  startImport.mockRejectedValueOnce(apiError('folder_unreadable', "Butterfly Atlas can't open that folder.", 403))
    .mockResolvedValue({ import: summary({ phase: 'reading', progress: { done: 812, total: 9870 } }), continued: true });
  getStatus.mockResolvedValue({});
  await open(items(), summary({ stopped: { phase: 'reading', done: 812, total: 9870, at: '2026-10-12T09:00:00Z' } }));

  expect((await screen.findByRole('alert')).textContent).toBe("Butterfly Atlas can't open that folder.");
  expect(screen.getByText(/stopped when Butterfly Atlas closed \(reading 812 of 9,870\)\. It won't carry on by itself\./)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Continue reading' }));
  expect(startImport).toHaveBeenCalledTimes(2);
  await vi.waitFor(() => expect(screen.queryByText(/won't carry on by itself/)).toBeNull());
});

test('polling backs off when the app can\'t be reached, says so quietly after three misses, and stops when the import is gone', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValueOnce(summary({ phase: 'reading', progress: { done: 1, total: 6 } }))
    .mockRejectedValueOnce(apiError('offline', 'x', 0)).mockRejectedValueOnce(apiError('offline', 'x', 0))
    .mockRejectedValueOnce(apiError('offline', 'x', 0))
    .mockRejectedValue(apiError('import_not_found', "That import can't be found.", 404));
  getImportItems.mockResolvedValue(page(items()));
  render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);

  await vi.advanceTimersByTimeAsync(1000); // miss 1
  await vi.advanceTimersByTimeAsync(2000); // miss 2
  expect(screen.queryByText(/Can't reach Butterfly Atlas/)).toBeNull();
  await vi.advanceTimersByTimeAsync(3999);
  expect(getImport).toHaveBeenCalledTimes(3);
  await vi.advanceTimersByTimeAsync(1); // miss 3, after 4 s
  expect(document.querySelector('.imp-poll').textContent).toBe("Can't reach Butterfly Atlas; trying again…");
  await vi.advanceTimersByTimeAsync(8000); // gone
  expect(screen.getByRole('alert').textContent).toBe("That import can't be found.");
  await vi.advanceTimersByTimeAsync(60000);
  expect(getImport).toHaveBeenCalledTimes(5);
});

test('a folder found where it moved closes the browser even if its photos then fail to load', async () => {
  getFsRoots.mockResolvedValue([{ name: 'Photos SSD', path: '/Volumes/Photos SSD', kind: 'drive' }]);
  listFolder.mockResolvedValue({
    path: '/Volumes/Photos SSD', name: 'Photos SSD', parent: '/Volumes', crumbs: [{ name: 'Photos SSD', path: '/Volumes/Photos SSD' }],
    folders: [], photos: 6, isLibrary: false, openImport: null,
  });
  relocateImport.mockResolvedValue(summary({ rootPath: '/Volumes/Photos SSD' }));
  getStatus.mockResolvedValue({});
  await open(items(), summary({ folderMissing: true }));
  getImportItems.mockRejectedValue(apiError('offline', 'Butterfly Atlas could not reach its service. Close the app and open it again.', 0));

  await fireEvent.click(screen.getByRole('button', { name: 'Choose where it moved' }));
  const dialog = await screen.findByRole('dialog', { name: 'Where is Butterflies 2024 now?' });
  await within(dialog).findByText('6 photos in this folder.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Use this folder' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.getByText('/Volumes/Photos SSD')).toBeTruthy();
  expect(screen.queryByText(/Folder not found/)).toBeNull();
  // the toast says the photos couldn't be loaded; the browser, already closed, shows no relocate error
  expect(await screen.findByText(/could not reach its service/)).toBeTruthy();
});

test('finishing and removing ask the footer again, so a stopped notice goes', async () => {
  getImport.mockResolvedValue(summary());
  getImportItems.mockResolvedValue(page(items()));
  getImportResult.mockResolvedValue(result());
  finishImport.mockResolvedValue({ import: summary({ state: 'finished' }), result: result() });
  getStatus.mockResolvedValue({});
  render(ImportRun, { id: 3 });
  await fireEvent.click(await screen.findByRole('button', { name: 'Finish…' }));
  await fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Finish and leave the rest in the folder' }));
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalled());
});

test('removing a finished import asks the footer again', async () => {
  getImport.mockResolvedValue(summary({ state: 'finished' }));
  getImportItems.mockResolvedValue(page(items()));
  getImportResult.mockResolvedValue(result({ left: 0 }));
  discardImport.mockResolvedValue(null);
  getStatus.mockResolvedValue({});
  render(ImportRun, { id: 3 });
  await fireEvent.click(await screen.findByRole('button', { name: 'Remove from your imports' }));
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalled());
});

test('Finish refused while the last step is still stopping says so in the dialog and changes nothing', async () => {
  getImportResult.mockResolvedValue(result());
  finishImport.mockRejectedValue(apiError('busy', 'Butterfly Atlas is still finishing the last step. Try again in a moment.', 503));
  await open();

  await fireEvent.click(screen.getByRole('button', { name: 'Finish…' }));
  const dialog = await screen.findByRole('dialog', { name: 'Filed so far' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Finish and leave the rest in the folder' }));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Butterfly Atlas is still finishing the last step. Try again in a moment.');
  expect(window.location.hash).toBe('');
  expect(within(dialog).getByRole('button', { name: 'Finish and leave the rest in the folder' }).getAttribute('aria-disabled')).toBe('false');
});

/* ---------- keyboard and screen readers (polish) ---------- */

test('the grid is named for the folder, and one status region says how many are selected', async () => {
  await open();
  expect(screen.getByRole('grid', { name: 'Photos in Butterflies 2024' })).toBeTruthy();
  const region = document.querySelector('.imp-selstatus');
  expect(region.getAttribute('role')).toBe('status');
  expect(region.textContent).toBe('');
  await fireEvent.click(tile(2));
  expect(region.textContent).toBe('1 photo selected.');
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(region.textContent).toBe('Selection cleared.');
  expect(document.querySelector('.selbar [aria-live]')).toBeNull();
});

test('a shortcut pressed while a change is being saved says why nothing happened', async () => {
  let answer;
  skipItems.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  await open();
  await fireEvent.click(tile(4));
  await fireEvent.keyDown(window, { key: 'x' });
  await fireEvent.keyDown(window, { key: 's' });
  expect(screen.getByText('Wait for the last change to be saved.')).toBeTruthy();
  expect(screen.queryByRole('dialog')).toBeNull();
  answer(page([item(4, { taxonId: 'A6', skipped: true })]));
});

test('Escape with the species picker open closes the picker and keeps the selection', async () => {
  await open();
  await fireEvent.click(tile(2));
  await fireEvent.keyDown(window, { key: 's' });
  expect(screen.getByRole('dialog')).toBeTruthy();
  await fireEvent.keyDown(window, { key: 'Escape' });
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.getByText('1 photo selected')).toBeTruthy();
});

test('a finished import\'s result has its own heading', async () => {
  getImport.mockResolvedValue(summary({ state: 'finished' }));
  getImportItems.mockResolvedValue(page(items()));
  getImportResult.mockResolvedValue(result({ left: 0 }));
  render(ImportRun, { id: 3 });
  expect(await screen.findByRole('heading', { level: 2, name: 'What this import did' })).toBeTruthy();
});

test('polling stops when the page goes', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValue(summary({ phase: 'reading', progress: { done: 1, total: 6 } }));
  getImportItems.mockResolvedValue(page(items()));
  const { unmount } = render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(1000);
  const asked = getImport.mock.calls.length;
  unmount();
  await vi.advanceTimersByTimeAsync(10000);
  expect(getImport.mock.calls.length).toBe(asked);
});

/* ---------- performance and small fixes (polish) ---------- */

test('a poll that changes nothing does not lay the grid out again', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValue(summary({ phase: 'reading', progress: { done: 1, total: 6 } }));
  getImportItems.mockResolvedValueOnce(page(items(), 10)).mockResolvedValue(page([], 11));
  render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  const before = folderSections.mock.calls.length;
  await vi.advanceTimersByTimeAsync(3000);
  expect(getImport.mock.calls.length).toBeGreaterThan(2);
  expect(folderSections.mock.calls.length).toBe(before);
});

test('one new species under reduced motion says so in the singular, with a milestone in thousands', async () => {
  getImportResult.mockResolvedValue(result({ lastFiling: { filed: 1, newSpecies: 1, milestone: 1000, speciesCount: 1000 } }));
  await fileUntilDone();
  expect(screen.getByText('1 new species photographed! Milestone reached: 1,000 species.')).toBeTruthy();
});

test('Finish says what still waits can\'t be filed from this import later', async () => {
  getImportResult.mockResolvedValue(result());
  await open();
  await fireEvent.click(screen.getByRole('button', { name: 'Finish…' }));
  const dialog = await screen.findByRole('dialog', { name: 'Filed so far' });
  expect(dialog.textContent).toContain("Finishing leaves the 5 photos still waiting in the folder; they can't be filed from this import later.");
});

test('Continue import from the footer on its own grid continues filing there', async () => {
  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 1200, total: 2140 } }));
  getStatus.mockResolvedValue({});
  await open(items(), summary({ stopped: { phase: 'filing', done: 1200, total: 2140, at: '2026-10-12T09:00:00Z' } }));
  window.dispatchEvent(new CustomEvent('atlas:continue-import', { detail: { id: 3 } }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenCalledWith(3, null));
  window.dispatchEvent(new CustomEvent('atlas:continue-import', { detail: { id: 9 } }));
  expect(fileImport).toHaveBeenCalledTimes(1);
});

test('the address of a photo waits for the folder to be looked through before showing the grid instead', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValueOnce(summary({ phase: 'walking', progress: null })).mockResolvedValue(summary({ phase: 'reading', progress: { done: 0, total: 6 } }));
  getImportItems.mockResolvedValueOnce(page([], 1)).mockResolvedValue(page(items(), 2));
  window.location.hash = '#/import/3/photo/2';
  render(ImportRun, { id: 3, itemId: 2 });
  await vi.advanceTimersByTimeAsync(0);
  expect(window.location.hash).toBe('#/import/3/photo/2');
  await vi.advanceTimersByTimeAsync(1000);
  expect(screen.getByRole('dialog', { name: 'Photo: IMG_2.JPG' })).toBeTruthy();
});

test('the large view keeps paging from where it was when a change takes its photo out of the filter', async () => {
  vi.useFakeTimers();
  getImport.mockResolvedValue(summary({ phase: 'reading', progress: { done: 1, total: 6 } }));
  getImportItems.mockResolvedValueOnce(page(items(), 10)).mockResolvedValueOnce(page([item(2, { taxonId: 'A5', suggestedTaxonId: 'A5' })], 11))
    .mockResolvedValue(page([], 12));
  const { rerender } = render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  await fireEvent.click(screen.getByRole('button', { name: 'Suggested, 2 photos' }));
  await rerender({ itemId: 2 });
  const dialog = screen.getByRole('dialog', { name: 'Photo: IMG_2.JPG' });
  expect(within(dialog).getByText('1 / 2')).toBeTruthy();

  await vi.advanceTimersByTimeAsync(1000); // photo 2 is assigned now: no longer suggested
  expect(within(dialog).getByText('1 / 2')).toBeTruthy();
  await fireEvent.keyDown(window, { key: 'ArrowRight' });
  expect(window.location.hash).toBe('#/import/3/photo/3');
});

test('Shift extends the selection across photos that can\'t be selected, leaving them out', async () => {
  await open([item(1), item(2), item(3, { outcome: 'filed', taxonId: 'A5' }), item(4)].map((i) => ({ ...i, folder: 'Sinhagad' })));
  await fireEvent.click(tile(1));
  await fireEvent.click(tile(3), { shiftKey: true });
  expect(screen.getByText('2 photos selected')).toBeTruthy();
  await fireEvent.click(tile(4), { shiftKey: true });
  expect(screen.getByText('3 photos selected')).toBeTruthy();

  tile(2).focus();
  await fireEvent.click(tile(2));
  await fireEvent.keyDown(tile(2), { key: 'ArrowRight', shiftKey: true });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(3)));
  expect(screen.getByText('1 photo selected')).toBeTruthy();
  await fireEvent.keyDown(tile(3), { key: 'ArrowRight', shiftKey: true });
  await vi.waitFor(() => expect(document.activeElement).toBe(tile(4)));
  expect(screen.getByText('2 photos selected')).toBeTruthy();
});

test('a change asked for while another is being saved is refused with the reason, and the picker stays open', async () => {
  let answer;
  skipItems.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  search.mockResolvedValue([{ id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae' }]);
  await open();
  await fireEvent.click(tile(2));
  await fireEvent.keyDown(window, { key: 's' });
  const dialog = screen.getByRole('dialog', { name: 'Assign 1 photo to a species' });
  // another change starts underneath (a click the overlay would normally keep out)
  await fireEvent.click(screen.getByRole('button', { name: 'Skip 1 photo already in the library' }));
  await fireEvent.input(within(dialog).getByRole('combobox'), { target: { value: 'plain' } });
  await fireEvent.click(await within(dialog).findByRole('option', { name: /Plain Tiger/ }));

  expect(assignItems).not.toHaveBeenCalled();
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Wait for the last change to be saved.');
  expect(screen.getByRole('dialog', { name: 'Assign 1 photo to a species' })).toBeTruthy();
  expect(document.querySelector('.imp-selstatus').textContent).toBe('1 photo selected.');
  answer(page([item(5, { libraryTaxonId: 'A5', skipped: true })]));
});

test('changing the filter or the view while scrolled down goes back to the top of the grid', async () => {
  await open();
  const grid = document.querySelector('.ig');
  vi.spyOn(grid, 'getBoundingClientRect').mockReturnValue({ top: -800, left: 0, width: 1000, height: 3000, right: 1000, bottom: 2200 });
  const scroll = vi.fn();
  grid.scrollIntoView = scroll;
  await fireEvent.click(screen.getByRole('button', { name: 'By species' }));
  await vi.waitFor(() => expect(scroll).toHaveBeenCalledWith({ block: 'start' }));
});

test('after finishing, the footer\'s stopped notice for it goes', async () => {
  status.stoppedImports = [{ id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 1, total: 2 }];
  getImport.mockResolvedValue(summary());
  getImportItems.mockResolvedValue(page(items()));
  getImportResult.mockResolvedValue(result());
  finishImport.mockResolvedValue({ import: summary({ state: 'finished' }), result: result() });
  getStatus.mockResolvedValue({ stoppedImports: [] });
  render(ImportRun, { id: 3 });
  await fireEvent.click(await screen.findByRole('button', { name: 'Finish…' }));
  await fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Finish and leave the rest in the folder' }));
  await vi.waitFor(() => expect(status.stoppedImports).toEqual([]));
});

/* ---------- follow-ups ---------- */

test('a new route object with the same id (opening a photo) keeps the page: no reload, selection and filter stay', async () => {
  const RouteHost = (await import('./fixtures/RouteHost.svelte')).default;
  getImport.mockResolvedValue(summary());
  getImportItems.mockResolvedValue(page(items()));
  const { component } = render(RouteHost, { initial: { id: 3 } });
  render(Toast);
  await screen.findByRole('heading', { level: 1, name: 'Butterflies 2024' });
  await fireEvent.click(screen.getByRole('button', { name: 'Suggested, 2 photos' }));
  await fireEvent.click(tile(2));

  component.go({ id: 3, itemId: 2 });
  expect(await screen.findByRole('dialog', { name: 'Photo: IMG_2.JPG' })).toBeTruthy();
  expect(getImport).toHaveBeenCalledTimes(1);
  expect(screen.getByRole('button', { name: 'Suggested, 2 photos' }).getAttribute('aria-pressed')).toBe('true');
  expect(document.querySelector('.imp-selstatus').textContent).toBe('1 photo selected.');
});

test('after the folder is found where it moved, focus goes to the heading', async () => {
  getFsRoots.mockResolvedValue([{ name: 'Photos SSD', path: '/Volumes/Photos SSD', kind: 'drive' }]);
  listFolder.mockResolvedValue({
    path: '/Volumes/Photos SSD', name: 'Photos SSD', parent: '/Volumes', crumbs: [{ name: 'Photos SSD', path: '/Volumes/Photos SSD' }],
    folders: [], photos: 6, isLibrary: false, openImport: null,
  });
  relocateImport.mockResolvedValue(summary({ rootPath: '/Volumes/Photos SSD' }));
  getStatus.mockResolvedValue({});
  await open(items(), summary({ folderMissing: true }));
  await fireEvent.click(screen.getByRole('button', { name: 'Choose where it moved' }));
  const dialog = await screen.findByRole('dialog', { name: 'Where is Butterflies 2024 now?' });
  await within(dialog).findByText('6 photos in this folder.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Use this folder' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1 })));
});

test('a poll answer asked for before a catch-up answered can\'t undo it', async () => {
  vi.useFakeTimers();
  let answerPoll;
  getImport.mockResolvedValue(summary({ phase: 'reading', progress: { done: 1, total: 6 } }));
  getImportItems.mockResolvedValueOnce(page(items(), 10))
    .mockImplementationOnce(() => new Promise((resolve) => { answerPoll = resolve; }))
    .mockResolvedValueOnce(page([item(4, { taxonId: 'A6', skipped: true })], 12)) // the catch-up
    .mockResolvedValue(page([], 12));
  fileImport.mockRejectedValue(apiError('nothing_to_file', 'Assign a species to some photos first.'));
  render(ImportRun, { id: 3 });
  render(Toast);
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(1000); // a poll waits for its answer
  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  await vi.advanceTimersByTimeAsync(0);
  expect(tile(4).getAttribute('aria-label')).toBe('IMG_4.JPG, Skipped');

  answerPoll(page([item(4, { taxonId: 'A6' })], 11));
  await vi.advanceTimersByTimeAsync(0);
  expect(tile(4).getAttribute('aria-label')).toBe('IMG_4.JPG, Skipped');
});

test('the Unknown place chip shows the read photos without a place', async () => {
  const pune = { id: 'P412', path: ['Pune', 'Maharashtra', 'India'], source: 'gps' };
  const list = items();
  list[1] = { ...list[1], place: pune };
  list[3] = { ...list[3], place: pune };
  await open(list);

  await fireEvent.click(screen.getByRole('button', { name: 'Unknown place, 3 photos' }));
  expect([...document.querySelectorAll('[data-item-id]')].map((t) => t.dataset.itemId)).toEqual(['1', '3', '5']);
  expect(tile(2)).toBeNull();
});

test('Place… sets a place on the selected photos and Remove place takes it off, each saved at once', async () => {
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false };
  const userPune = { id: 'P414', path: pune.path, source: 'user' };
  getRecentPlaces.mockResolvedValue([pune]);
  placeItems.mockResolvedValue(page([item(2, { suggestedTaxonId: 'A5', place: userPune }), item(3, { suggestedTaxonId: 'A5', place: userPune })], 11));
  unplaceItems.mockResolvedValue(page([item(2, { suggestedTaxonId: 'A5', place: null })], 12));
  await open();

  await fireEvent.click(tile(2));
  await fireEvent.click(tile(3), { shiftKey: true });
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  const dialog = screen.getByRole('dialog', { name: 'Place for 2 photos' });
  await fireEvent.click(await within(dialog).findByRole('option', { name: /^Pune/ }));

  expect(placeItems).toHaveBeenCalledWith(3, [2, 3], 'P414');
  expect(await screen.findByText('Pune set on 2 photos.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Suggested: Plain Tiger, Pune');
  expect(screen.queryByRole('group', { name: 'Selected photos' })).toBeNull();

  await fireEvent.click(tile(2));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove place' }));
  expect(unplaceItems).toHaveBeenCalledWith(3, [2]);
  expect(await screen.findByText('Place removed from 1 photo.')).toBeTruthy();
  expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Suggested: Plain Tiger');
});

test('a refused place for import photos is shown in the picker', async () => {
  getRecentPlaces.mockResolvedValue([{ id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false }]);
  placeItems.mockRejectedValue(Object.assign(new Error('This import is finished.'), { code: 'import_finished' }));
  getImport.mockResolvedValue(summary());
  await open();

  await fireEvent.click(tile(2));
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));
  expect((await screen.findByRole('alert')).textContent).toBe('This import is finished.');
  expect(screen.getByRole('dialog', { name: 'Place for this photo' })).toBeTruthy();
});

test('photos given a place under the Unknown place chip leave the grid, and focus stays on a tile', async () => {
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false };
  const userPune = { id: 'P414', path: pune.path, source: 'user' };
  getRecentPlaces.mockResolvedValue([pune]);
  placeItems.mockResolvedValue(page([item(2, { suggestedTaxonId: 'A5', place: userPune }), item(3, { suggestedTaxonId: 'A5', place: userPune })], 11));
  await open();

  await fireEvent.click(screen.getByRole('button', { name: 'Unknown place, 5 photos' }));
  await fireEvent.click(tile(2));
  await fireEvent.click(tile(3), { shiftKey: true });
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));

  expect(placeItems).toHaveBeenCalledWith(3, [2, 3], 'P414');
  expect(await screen.findByText('Pune set on 2 photos.')).toBeTruthy();
  await vi.waitFor(() => expect(tile(2)).toBeNull());
  expect(tile(3)).toBeNull();
  expect(screen.getByRole('button', { name: 'Unknown place, 3 photos' }).getAttribute('aria-pressed')).toBe('true');
  expect([...document.querySelectorAll('[data-item-id]')].map((t) => t.dataset.itemId)).toEqual(['1', '4', '5']);
  await vi.waitFor(() => expect(document.activeElement?.dataset.itemId).toBe('4'));
});

test('a place deleted in Your places reads the photos again, so the grid shows its unknown place', async () => {
  const tamhini = { id: 'PU3', name: 'Tamhini', path: ['Tamhini', 'Maharashtra', 'India'], level: 'district', mine: true, photos: 1 };
  getRecentPlaces.mockResolvedValue([]);
  getMyPlaces.mockResolvedValue([{ ...tamhini, photos: 0 }]);
  deletePlace.mockResolvedValue(0);
  const list = items();
  list[1] = { ...list[1], place: { id: 'PU3', path: tamhini.path, source: 'user' } };
  await open(list);
  expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Suggested: Plain Tiger, Tamhini');
  const reads = getImportItems.mock.calls.length;
  getImportItems.mockResolvedValue(page([item(2, { suggestedTaxonId: 'A5', place: null })], 11));

  await fireEvent.click(tile(2));
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Your places…' }));
  await fireEvent.click(await screen.findByRole('button', { name: 'Delete Tamhini' }));

  await vi.waitFor(() => expect(getImportItems.mock.calls.length).toBeGreaterThan(reads));
  expect(getImportItems).toHaveBeenLastCalledWith(3, 10);
  await vi.waitFor(() => expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Suggested: Plain Tiger'));
});

test('when a place takes the last photos out of the Unknown place filter, focus goes to that chip', async () => {
  const pune = { id: 'P414', name: 'Pune', path: ['Pune', 'Maharashtra', 'India'], level: 'district', mine: false };
  const userPune = { id: 'P414', path: pune.path, source: 'user' };
  getRecentPlaces.mockResolvedValue([pune]);
  const list = items().map((it) => (it.id === 2 || it.id === 3 ? it : { ...it, place: userPune }));
  placeItems.mockResolvedValue(page([item(2, { suggestedTaxonId: 'A5', place: userPune }), item(3, { suggestedTaxonId: 'A5', place: userPune })], 11));
  await open(list);

  await fireEvent.click(screen.getByRole('button', { name: 'Unknown place, 2 photos' }));
  await fireEvent.click(tile(2));
  await fireEvent.click(tile(3), { shiftKey: true });
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  await fireEvent.click(await screen.findByRole('option', { name: /^Pune/ }));

  expect(await screen.findByText('Pune set on 2 photos.')).toBeTruthy();
  await vi.waitFor(() => expect(tile(2)).toBeNull());
  expect(screen.getByText('No photos here with this filter.')).toBeTruthy();
  const chip = screen.getByRole('button', { name: 'Unknown place, 0 photos' });
  await vi.waitFor(() => expect(document.activeElement).toBe(chip));
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

test('a species added from the Assign picker whose assignment then fails is named in the picker’s message', async () => {
  customSetup();
  assignItems.mockRejectedValueOnce(new Error('The import was changed elsewhere.'));
  await open();
  await fireEvent.click(tile(2));
  await fireEvent.keyDown(window, { key: 's' });
  const dialog = screen.getByRole('dialog', { name: 'Assign 1 photo to a species' });
  await addFromPicker(dialog);

  await vi.waitFor(() => expect(assignItems).toHaveBeenCalledWith(3, [2], 'U3'));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Neptis sankara was added to Custom additions. The import was changed elsewhere.');
  expect(dialog.isConnected).toBe(true);
});

test('the taxonomy update\'s note shows above the grid as its own note, not as an alert', async () => {
  getImport.mockResolvedValue(summary({ updateNote: '2 photos were assigned to Danaus chrysippus, which was split. Choose their species again.' }));
  getImportItems.mockResolvedValue(page(items()));
  getStatus.mockResolvedValue({});
  render(ImportRun, { id: 3 });
  const shown = await screen.findByRole('note');
  expect(shown.textContent).toBe('Taxonomy update: 2 photos were assigned to Danaus chrysippus, which was split. Choose their species again.');
  expect(screen.queryByRole('alert')).toBeNull();
});

/* ---------- RAW + JPEG pairs (Phase 7a) ---------- */

const jpgOf = (id, pairId, extra = {}) => item(id, { name: `DSC_${id}.JPG`, path: `Sinhagad/DSC_${id}.JPG`, fileType: 'jpeg', pairId, ...extra });
const nefOf = (id, pairId, extra = {}) => item(id, {
  name: `DSC_${id}.NEF`, path: `Sinhagad/DSC_${id}.NEF`, fileType: 'raw', pairId, rawPreview: { width: 6000, height: 4000, orientation: 1 }, ...extra,
});

test('a RAW + JPEG pair is one tile, counted once; assigning it sends one id and the server answers both halves', async () => {
  search.mockResolvedValue([{ id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae' }]);
  assignItems.mockResolvedValue(page([jpgOf(21, 22, { taxonId: 'A5' }), nefOf(22, 21, { taxonId: 'A5' })], 11));
  await open([jpgOf(21, 22), nefOf(22, 21), nefOf(23, null)]);

  expect(tile(22)).toBeNull();
  expect(tile(21).querySelector('.it-b').textContent.trim()).toBe('RAW + JPEG');
  expect(tile(23).querySelector('.it-b').textContent.trim()).toBe('RAW');
  expect(screen.getByRole('button', { name: 'All, 2 photos' })).toBeTruthy();

  await fireEvent.click(tile(21));
  await fireEvent.keyDown(window, { key: 's' });
  const dialog = screen.getByRole('dialog', { name: 'Assign 1 photo to a species' });
  await fireEvent.input(within(dialog).getByRole('combobox'), { target: { value: 'plain' } });
  await fireEvent.click(await within(dialog).findByRole('option', { name: /Plain Tiger/ }));

  expect(assignItems).toHaveBeenCalledWith(3, [21], 'A5');
  await vi.waitFor(() => expect(tile(21).getAttribute('aria-label')).toBe('DSC_21.JPG, RAW + JPEG, Plain Tiger'));
  expect(tile(22)).toBeNull();
});

test('pairs waiting for a choice: the bar sits above the grid, File waits with the reason, and the choice is saved (Phase 7a)', async () => {
  const waiting = summary({ pairs: { count: 1, toFile: 1, choice: null, locked: false } });
  putImportPairs.mockResolvedValue({ ...waiting, pairs: { count: 1, toFile: 1, choice: 'both', locked: false } });
  await open([jpgOf(21, 22, { taxonId: 'A5' }), nefOf(22, 21, { taxonId: 'A5' })], waiting);

  const file = screen.getByRole('button', { name: 'File 1 assigned photo' });
  expect(file.getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(file.getAttribute('aria-describedby')).textContent).toBe('Choose how to file the 1 RAW + JPEG pair first.');
  expect(document.querySelector('.imp-pairs').compareDocumentPosition(document.querySelector('.ig')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

  await fireEvent.click(screen.getByRole('radio', { name: 'Both' }));
  expect(putImportPairs).toHaveBeenCalledWith(3, 'both');
  // Both files the two halves: the button counts files and shots (review fix U7)
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'File 2 photos (1 shot)' }).getAttribute('aria-disabled')).toBe('false'));
  expect(screen.getByText(/RAW \+ JPEG pairs: Both/)).toBeTruthy();
});

test('File refused for a missing pair choice, or a change refused once locked, says why and catches up (Phase 7a)', async () => {
  fileImport.mockRejectedValue(apiError('pair_choice_needed', 'Choose how to file the 1 RAW + JPEG pair first.'));
  await open([jpgOf(21, 22, { taxonId: 'A5' }), nefOf(22, 21, { taxonId: 'A5' })]);
  getImport.mockResolvedValue(summary({ pairs: { count: 1, toFile: 1, choice: null, locked: false } }));
  getImportItems.mockResolvedValue(page([], 11));

  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  // the refusal shows (a toast), and the catch-up brings the bar and File's reason
  await vi.waitFor(() => expect(screen.getAllByText('Choose how to file the 1 RAW + JPEG pair first.').length).toBe(2));
  await vi.waitFor(() => expect(screen.getByRole('radio', { name: 'Both' })).toBeTruthy());

  putImportPairs.mockRejectedValue(apiError('pair_choice_locked', 'Some pairs were already filed as you chose. Start a new import to file them differently.'));
  getImport.mockResolvedValue(summary({ pairs: { count: 1, toFile: 0, choice: 'jpeg', locked: true } }));
  await fireEvent.click(screen.getByRole('radio', { name: 'RAW only' }));
  expect(await screen.findByText('Some pairs were already filed as you chose. Start a new import to file them differently.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.getByText('RAW + JPEG pairs filed: JPEG only.')).toBeTruthy());
});

/* ---------- Phase 7b: HEIC photos ---------- */

const heicItem = (id, extra = {}) => item(id, { name: `IMG_${id}.HEIC`, path: `Sinhagad/IMG_${id}.HEIC`, fileType: 'heic', heic: { tenBit: false, auxiliary: false }, ...extra });
const BAR = "This computer can't show the 2 HEIC photos in this folder. They can still be assigned and filed; they'll appear once heif-convert is installed.";

test('without a decoder, a bar above the grid says how many HEIC photos can\'t be shown, with How and Check again (contract §1)', async () => {
  status.heic = { state: 'missing', decoder: null, system: 'linux', reason: 'absent' };
  checkHeic.mockResolvedValue({ state: 'missing', decoder: null, system: 'linux', reason: 'absent' });
  await open([item(1), heicItem(2), heicItem(3)], summary({ heic: { count: 2, unshowable: 2, making: 0 } }));

  expect(screen.getByText(BAR)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'How' }));
  expect(screen.getByText(/Install heif-convert from your distribution's packages/)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
  expect(await screen.findByText(/^Still not available\. Install heif-convert/)).toBeTruthy();
  // Filing is never blocked: the HEIC photos can be assigned.
  expect(tile(2).getAttribute('aria-selected')).toBe('false');
});

test('no bar while the decoder is there or being checked', async () => {
  status.heic = { state: 'checking', decoder: null, system: 'linux', reason: null };
  await open([heicItem(2)], summary({ heic: { count: 1, unshowable: 1, making: 0 } }));
  expect(screen.queryByText(/HEIC photo in this folder/)).toBeNull();
});

test('while HEIC previews are being made the import is asked about, and the footer counts them (audit 10)', async () => {
  vi.useFakeTimers();
  status.heic = { state: 'available', decoder: 'sips', system: 'mac', reason: null };
  getImport.mockResolvedValueOnce(summary({ heic: { count: 2, unshowable: 0, making: 2 } }))
    .mockResolvedValueOnce(summary({ heic: { count: 2, unshowable: 0, making: 1 } }))
    .mockResolvedValue(summary({ heic: { count: 2, unshowable: 0, making: 0 } }));
  getImportItems.mockResolvedValueOnce(page([heicItem(2, { v: null }), heicItem(3, { v: null })], 4))
    .mockResolvedValueOnce(page([heicItem(2)], 5))
    .mockResolvedValue(page([heicItem(3)], 6));
  const { unmount } = render(ImportRun, { id: 3 });
  await vi.advanceTimersByTimeAsync(0);
  expect(status.importPreviews).toEqual({ count: 2, unshowable: 0, making: 2 });

  await vi.advanceTimersByTimeAsync(1000);
  expect(tile(2).querySelector('img').getAttribute('src')).toBe('./api/imports/3/items/2/preview?v=v1');
  await vi.advanceTimersByTimeAsync(1000);
  expect(status.importPreviews.making).toBe(0);
  await vi.advanceTimersByTimeAsync(5000);
  expect(getImport).toHaveBeenCalledTimes(3);
  unmount();
  expect(status.importPreviews).toBeNull();
});

test('a change of the decoder\'s state reads the import again (audit 1)', async () => {
  await open([heicItem(2)], summary({ heic: { count: 1, unshowable: 1, making: 0 } }));
  getImport.mockResolvedValue(summary({ heic: { count: 1, unshowable: 0, making: 0 } }));
  getImportItems.mockResolvedValue(page([], 11));
  window.dispatchEvent(new CustomEvent(HEIC_CHANGED, { detail: { from: 'missing', to: 'available' } }));
  await vi.waitFor(() => expect(getImport).toHaveBeenCalledTimes(2));
});

const CHANGED = 'This import was changed in another window. Check the photos, then press File again.';

test('File sends the revision the items were read at, moved on by its own edits, without reading the summary first (contract §5, A2, A3, A21)', async () => {
  skipItems.mockResolvedValue({ ...page([item(2, { suggestedTaxonId: 'A5', skipped: true })], 11), revision: 5, previousRevision: 4 });
  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 }, revision: 5 }));
  getStatus.mockResolvedValue({});
  // the summary, read alongside, may already be past what the items show: the items' revision is the one kept
  await open(items(), summary({ revision: 9 }), 4);

  await fireEvent.click(tile(2));
  await fireEvent.keyDown(window, { key: 'x' });
  await vi.waitFor(() => expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Skipped'));
  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenCalledWith(3, 5));
  expect(getImport).toHaveBeenCalledTimes(1);
});

test('File refused because another window changed the import: the words stay above the grid, which reads again, until the next File (A2)', async () => {
  fileImport.mockRejectedValueOnce(apiError('import_changed', CHANGED)).mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 2 }, revision: 6 }));
  getStatus.mockResolvedValue({});
  await open(items(), summary({ revision: 4 }), 4);
  getImport.mockResolvedValue(summary({ revision: 7 }));
  getImportItems.mockResolvedValue({ ...page([item(3, { suggestedTaxonId: 'A5', taxonId: 'A5' })], 11), revision: 6 });

  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  const note = await screen.findByText(CHANGED);
  expect(note.getAttribute('role')).toBe('alert');
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'File 2 assigned photos' })).toBeTruthy());
  expect(screen.getByText(CHANGED)).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'File 2 assigned photos' }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenLastCalledWith(3, 6));
  await vi.waitFor(() => expect(screen.queryByText(CHANGED)).toBeNull());
});

test('the status naming this import in another state (filing from another window) reads it again, once per change (A10)', async () => {
  await open();
  getImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  getImportItems.mockResolvedValue(page([], 11));
  status.import = { id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 0, total: 1 };
  await vi.waitFor(() => expect(screen.getByRole('group', { name: 'Filing' })).toBeTruthy());
  expect(getImport).toHaveBeenCalledTimes(2);
  // the same state again, or another import working, reads nothing more
  status.import = { id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 1, total: 1 };
  status.import = { id: 9, rootName: 'Elsewhere', phase: 'reading', done: 0, total: 5 };
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(getImport).toHaveBeenCalledTimes(2);
});

test('a second filing of the same import, started in another window, reads it again too (A10, review fix)', async () => {
  await open();
  getImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  getImportItems.mockResolvedValue(page([], 11));
  status.import = { id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 0, total: 1 };
  await vi.waitFor(() => expect(screen.getByText(/^Filing photos…/)).toBeTruthy());

  // the first filing ends: the status names no import, and the page reads itself back to idle
  status.import = null;
  getImport.mockResolvedValue(summary());
  window.dispatchEvent(new CustomEvent(HEIC_CHANGED, { detail: { from: 'missing', to: 'available' } }));
  await vi.waitFor(() => expect(screen.queryByText(/^Filing photos…/)).toBeNull());

  // filed again in another window: the same state as the first time, read again all the same
  getImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 } }));
  status.import = { id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 0, total: 1 };
  await vi.waitFor(() => expect(screen.getByText(/^Filing photos…/)).toBeTruthy());
});

test('an edit that lands while the page reads itself again keeps its revision: the read, older than the edit, is dropped, and File sends the edit\'s (A20, A21, review fix)', async () => {
  getStatus.mockResolvedValue({});
  await open(items(), summary({ revision: 4 }), 4);
  let answerItems;
  getImport.mockResolvedValue(summary({ revision: 4 }));
  getImportItems.mockImplementationOnce(() => new Promise((resolve) => { answerItems = resolve; }));
  window.dispatchEvent(new CustomEvent(HEIC_CHANGED, { detail: { from: 'missing', to: 'available' } }));
  await vi.waitFor(() => expect(answerItems).toBeTypeOf('function'));

  skipItems.mockResolvedValue({ ...page([item(2, { suggestedTaxonId: 'A5', skipped: true })], 11), revision: 5, previousRevision: 4 });
  await fireEvent.click(tile(2));
  await fireEvent.keyDown(window, { key: 'x' });
  await vi.waitFor(() => expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Skipped'));
  // the read asked before the skip was saved answers now, with the photo as it was
  answerItems({ ...page([item(2, { suggestedTaxonId: 'A5' })], 11), revision: 4 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(tile(2).getAttribute('aria-label')).toBe('IMG_2.JPG, Skipped');

  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 }, revision: 5 }));
  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenCalledWith(3, 5));
});

test('opening the import holds the lower of the summary\'s and the items\' revisions, read together (review fix)', async () => {
  getStatus.mockResolvedValue({});
  fileImport.mockResolvedValue(summary({ phase: 'filing', progress: { done: 0, total: 1 }, revision: 4 }));
  await open(items(), summary({ revision: 3 }), 4);
  await fireEvent.click(screen.getByRole('button', { name: 'File 1 assigned photo' }));
  await vi.waitFor(() => expect(fileImport).toHaveBeenCalledWith(3, 3));
});
