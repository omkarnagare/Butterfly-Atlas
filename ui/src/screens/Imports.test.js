import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Imports from './Imports.svelte';
import Toast from '../lib/Toast.svelte';
import { discardImport, getFsRoots, getImports, getStatus, listFolder, startImport } from '../lib/api.js';
import { LIBRARY_CHANGED } from '../lib/status.svelte.js';

vi.mock('../lib/api.js', () => ({
  getImports: vi.fn(), startImport: vi.fn(), discardImport: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn(), getStatus: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getFsRoots.mockResolvedValue([{ name: 'Pictures', path: '/Users/you/Pictures', kind: 'pictures' }]);
  listFolder.mockResolvedValue({
    path: '/Users/you/Pictures', name: 'Pictures', parent: '/Users/you', crumbs: [{ name: 'Pictures', path: '/Users/you/Pictures' }],
    folders: [], photos: 12, isLibrary: false, openImport: null,
  });
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const counts = (filed, total) => ({ total, photos: total, reading: 0, unassigned: 0, suggested: 0, assigned: 0, skipped: 0, problems: 0, filed, missing: 0 });
const run = (id, rootName, extra = {}) => ({
  id, rootPath: `/Users/you/Pictures/${rootName}`, rootName, state: 'open', createdAt: '2026-10-12T08:00:00Z',
  updatedAt: '2026-10-12T09:00:00Z', folderMissing: false, phase: 'idle', progress: null, counts: counts(3412, 9870), ...extra,
});

test('with no imports the folder browser opens at once; choosing a folder starts the import and opens it', async () => {
  getImports.mockResolvedValue([]);
  startImport.mockResolvedValue({ import: run(4, 'Pictures', { counts: counts(0, 12) }), continued: false });
  render(Imports);

  const dialog = await screen.findByRole('dialog', { name: 'Choose a folder to import' });
  await within(dialog).findByText('12 photos in this folder.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Import this folder' }));

  expect(startImport).toHaveBeenCalledWith('/Users/you/Pictures');
  await vi.waitFor(() => expect(window.location.hash).toBe('#/import/4'));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('closing the browser with no imports leaves a way to choose a folder', async () => {
  getImports.mockResolvedValue([]);
  render(Imports);

  await fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
  expect(screen.getByRole('heading', { name: 'No imports yet' })).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Choose a folder' }));
  expect(await screen.findByRole('dialog', { name: 'Choose a folder to import' })).toBeTruthy();
});

test('lists unfinished imports with their progress, and Continue asks the server to go on', async () => {
  getImports.mockResolvedValue([
    run(3, 'Butterflies 2024'),
    run(2, 'Old card', { folderMissing: true, counts: counts(0, 40) }),
    run(1, 'Sinhagad', { state: 'finished', counts: counts(212, 212) }),
  ]);
  startImport.mockResolvedValue({ import: run(3, 'Butterflies 2024'), continued: true });
  render(Imports);
  render(Toast);

  const card = (await screen.findByText('Butterflies 2024')).closest('li');
  expect(card.textContent).toContain('3,412 of 9,870 photos filed, last worked on');
  expect(screen.getByText('Old card').closest('li').textContent).toContain('Folder not found');
  const done = screen.getByText('Sinhagad').closest('li');
  expect(done.textContent).toContain('212 photos filed, finished on');
  expect(within(done).getByRole('link', { name: 'Summary of Sinhagad' }).getAttribute('href')).toBe('#/import/1');
  expect(screen.getByRole('button', { name: 'Import another folder' })).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'Continue Butterflies 2024' }));
  expect(startImport).toHaveBeenCalledWith('/Users/you/Pictures/Butterflies 2024');
  await vi.waitFor(() => expect(window.location.hash).toBe('#/import/3'));
  expect(screen.getByText(/^Continuing your import from .+: 3,412 of 9,870 photos filed\.$/)).toBeTruthy();
});

test('Discard asks first, says what stays, and removes the import', async () => {
  getImports.mockResolvedValueOnce([run(3, 'Butterflies 2024')]).mockResolvedValue([]);
  discardImport.mockRejectedValueOnce(new Error('Wait for filing to finish or pause it.')).mockResolvedValue(null);
  render(Imports);

  await fireEvent.click(await screen.findByRole('button', { name: 'Discard Butterflies 2024' }));
  const dialog = screen.getByRole('dialog', { name: 'Discard the import of Butterflies 2024?' });
  expect(dialog.textContent).toContain('Photos already filed stay in your atlas, and the folder is not touched.');
  expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }));

  await fireEvent.click(within(dialog).getByRole('button', { name: 'Discard' }));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Wait for filing to finish or pause it.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Discard' }));

  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(discardImport).toHaveBeenLastCalledWith(3);
  expect(screen.getByRole('heading', { name: 'No imports yet' })).toBeTruthy();
});

test('a failed Continue says why and stays', async () => {
  getImports.mockResolvedValue([run(3, 'Butterflies 2024')]);
  startImport.mockRejectedValue(new Error("Butterfly Atlas can't open that folder."));
  render(Imports);
  render(Toast);

  await fireEvent.click(await screen.findByRole('button', { name: 'Continue Butterflies 2024' }));
  expect(await screen.findByText("Butterfly Atlas can't open that folder.")).toBeTruthy();
  expect(window.location.hash).toBe('');
});

test('shows why the imports cannot be listed', async () => {
  getImports.mockRejectedValue(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  render(Imports);

  expect((await screen.findByRole('alert')).textContent).toContain('could not reach its service');
});

test('an import\'s card says when the app\'s closing stopped it, or only Paused when the user paused it', async () => {
  getImports.mockResolvedValue([
    run(3, 'Butterflies 2024', { stopped: { phase: 'filing', done: 1200, total: 2140, at: '2026-10-12T09:00:00Z' } }),
    run(2, 'Card', { paused: true }),
  ]);
  render(Imports);

  const card = (await screen.findByText('Butterflies 2024')).closest('li');
  expect(card.textContent).toContain("stopped when Butterfly Atlas closed (filing 1,200 of 2,140). It won't carry on by itself.");
  const paused = screen.getByText('Card').closest('li');
  expect(within(paused).getByText('Paused')).toBeTruthy();
  expect(paused.textContent).not.toContain("won't carry on");

  // Continue opens the grid, which offers "Continue filing", without asking the server to read again
  await fireEvent.click(within(card).getByRole('button', { name: 'Continue Butterflies 2024' }));
  expect(window.location.hash).toBe('#/import/3');
  expect(startImport).not.toHaveBeenCalled();
});

test('an import that is filing can\'t be discarded until filing finishes or is paused', async () => {
  getImports.mockResolvedValue([run(3, 'Butterflies 2024', { phase: 'filing', progress: { done: 50, total: 200 } })]);
  render(Imports);
  render(Toast);

  const discard = await screen.findByRole('button', { name: 'Discard Butterflies 2024' });
  expect(discard.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(discard);
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.getByText('Wait for filing to finish or pause it.')).toBeTruthy();
});

test('discarding asks the footer again, so a stopped notice goes', async () => {
  getImports.mockResolvedValueOnce([run(3, 'Butterflies 2024')]).mockResolvedValue([]);
  discardImport.mockResolvedValue(null);
  getStatus.mockResolvedValue({ stoppedImports: [] });
  render(Imports);

  await fireEvent.click(await screen.findByRole('button', { name: 'Discard Butterflies 2024' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Discard' }));
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalled());
});

test('a failed Continue keeps its message on the card', async () => {
  getImports.mockResolvedValue([run(3, 'Butterflies 2024')]);
  startImport.mockRejectedValue(new Error("Butterfly Atlas can't open that folder."));
  render(Imports);

  await fireEvent.click(await screen.findByRole('button', { name: 'Continue Butterflies 2024' }));
  const card = screen.getByText('Butterflies 2024').closest('li');
  expect((await within(card).findByRole('alert')).textContent).toBe("Butterfly Atlas can't open that folder.");
});

test('a card shows its library notice', async () => {
  const full = "Your library's drive is full. Free up some space, then continue filing.";
  getImports.mockResolvedValue([run(3, 'Butterflies 2024', { notice: full })]);
  render(Imports);

  const card = (await screen.findByText('Butterflies 2024')).closest('li');
  expect(card.textContent).toContain(full);
});

test('closing the browser that opened by itself puts focus on Choose a folder', async () => {
  getImports.mockResolvedValue([]);
  render(Imports);

  await fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Choose a folder' })));
});

test('the list asks again every two seconds while an import is working, and stops once all are idle', async () => {
  vi.useFakeTimers();
  getImports.mockResolvedValueOnce([run(3, 'Butterflies 2024', { phase: 'reading', progress: { done: 1, total: 9 } })])
    .mockResolvedValueOnce([run(3, 'Butterflies 2024', { phase: 'filing', progress: { done: 1, total: 9 } })])
    .mockResolvedValue([run(3, 'Butterflies 2024')]);
  render(Imports);
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByText('Reading photos…')).toBeTruthy();
  await vi.advanceTimersByTimeAsync(2000);
  expect(screen.getByText('Filing photos…')).toBeTruthy();
  await vi.advanceTimersByTimeAsync(2000);
  expect(screen.queryByText('Filing photos…')).toBeNull();
  await vi.advanceTimersByTimeAsync(10000);
  expect(getImports).toHaveBeenCalledTimes(3);
  vi.useRealTimers();
});

test('Remove takes a finished import off the list, and focus goes to Import another folder', async () => {
  getImports.mockResolvedValueOnce([run(3, 'Butterflies 2024'), run(1, 'Sinhagad', { state: 'finished', counts: counts(212, 212) })])
    .mockResolvedValue([run(3, 'Butterflies 2024')]);
  discardImport.mockResolvedValue(null);
  render(Imports);
  render(Toast);

  await fireEvent.click(await screen.findByRole('button', { name: 'Remove Sinhagad from this list' }));
  const dialog = screen.getByRole('dialog', { name: 'Remove Sinhagad from this list?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
  expect(discardImport).toHaveBeenCalledWith(1);
  expect(await screen.findByText('Sinhagad was removed from this list.')).toBeTruthy();
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.queryByText('Sinhagad')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Import another folder' })));
});

test('a card shows the taxonomy update\'s note as its own note, not as a problem', async () => {
  getImports.mockResolvedValue([run(3, 'Butterflies 2024', { updateNote: '2 photos were assigned to Danaus chrysippus, which was split. Choose their species again.' })]);
  render(Imports);
  const card = (await screen.findByText('Butterflies 2024')).closest('li');
  const shown = card.querySelector('.imp-update');
  expect(shown.textContent).toBe('Taxonomy update: 2 photos were assigned to Danaus chrysippus, which was split. Choose their species again.');
  expect(card.querySelector('.imp-notice')).toBeNull();
});

test('reads the list again on every LIBRARY_CHANGED, e.g. an import filed in another window (audit A9)', async () => {
  getImports.mockResolvedValueOnce([run(3, 'Butterflies 2024')]).mockResolvedValue([run(3, 'Butterflies 2024', { state: 'finished', counts: counts(9870, 9870) })]);
  render(Imports);
  await screen.findByText('Butterflies 2024');
  expect(screen.getByRole('button', { name: /Continue/ })).toBeTruthy();
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  await vi.waitFor(() => expect(getImports).toHaveBeenCalledTimes(2));
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: /Continue/ })).toBeNull());
});
