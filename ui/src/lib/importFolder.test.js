import { afterEach, expect, test, vi } from 'vitest';
import { importFolder } from './importFolder.svelte.js';
import { emptyItems } from './importItems.js';
import { getImportItems, relocateImport, startImport } from './api.js';
import { showToast } from './toast.svelte.js';
import { refreshStatus } from './status.svelte.js';

vi.mock('./api.js', () => ({ getImportItems: vi.fn(), relocateImport: vi.fn(), startImport: vi.fn() }));
// showProblem says a refusal through showToast (its taxonomy link is checked in Toast.test.js)
vi.mock('./toast.svelte.js', () => {
  const showToast = vi.fn();
  return { showToast, showProblem: (e) => showToast(e.message) };
});
vi.mock('./status.svelte.js', () => ({ refreshStatus: vi.fn() }));
afterEach(() => vi.resetAllMocks());

const where = { id: 3, rootPath: '/Volumes/Card/Butterflies', rootName: 'Butterflies', folderMissing: true };

/** The screen's state as importFolder sees it; `act` runs the work at once, as importSync's does when nothing is in flight. */
function fakeRun(extra = {}) {
  let t = 0;
  const run = {
    id: 3, current: (wanted) => wanted === run.id, busy: false, summary: where, data: emptyItems(), continuing: false, autoFailed: '',
    relocating: true, foundFocus: false, heading: { focus: vi.fn() }, act: async (work) => work(run.id), resync: vi.fn(),
    ticket: () => ++t, fresh: () => true, took: vi.fn(), read: vi.fn(), ...extra,
  };
  Object.assign(run, importFolder(run));
  return run;
}

test('reading cut short carries on by itself; when it can not, the page says why', async () => {
  const run = fakeRun({ summary: { ...where, folderMissing: false } });
  startImport.mockResolvedValue({ import: { ...where, folderMissing: false, phase: 'walking' } });
  const going = run.carryOn();
  expect(run.busy).toBe(true);
  expect(run.continuing).toBe(true);
  await going;
  expect(startImport).toHaveBeenCalledWith('/Volumes/Card/Butterflies');
  expect(run.summary.phase).toBe('walking');
  expect(showToast).toHaveBeenCalledWith('Reading Butterflies again where it stopped.');
  expect(refreshStatus).toHaveBeenCalled();
  expect(run.busy).toBe(false);
  expect(run.continuing).toBe(false);

  startImport.mockRejectedValue(new Error('The folder could not be read.'));
  await run.carryOn();
  expect(run.autoFailed).toBe('The folder could not be read.');
  expect(run.busy).toBe(false);
});

test('Look again says whether the folder is there now, and focuses the heading when it is', async () => {
  const run = fakeRun();
  startImport.mockResolvedValue({ import: where });
  await run.lookAgain();
  expect(showToast).toHaveBeenCalledWith("Butterflies still can't be found. Connect the drive it is on, or choose where it moved.");
  expect(run.heading.focus).not.toHaveBeenCalled();

  startImport.mockResolvedValue({ import: { ...where, folderMissing: false } });
  await run.lookAgain();
  expect(showToast).toHaveBeenCalledWith('Found Butterflies. Looking through it again.');
  expect(run.heading.focus).toHaveBeenCalled();
});

test('choosing where the folder moved points the import there and loads every item again', async () => {
  const run = fakeRun();
  relocateImport.mockResolvedValue({ ...where, rootPath: '/Volumes/New/Butterflies', folderMissing: false });
  const items = { version: 4, items: [{ id: 9 }], revision: 12 };
  getImportItems.mockResolvedValue(items);
  await run.relocate({ path: '/Volumes/New/Butterflies' });
  expect(relocateImport).toHaveBeenCalledWith(3, '/Volumes/New/Butterflies');
  expect(run.foundFocus).toBe(true);
  expect(run.relocating).toBe(false);
  expect(showToast).toHaveBeenCalledWith('Found Butterflies at /Volumes/New/Butterflies.');
  expect(getImportItems).toHaveBeenCalledWith(3);
  expect([...run.data.items.keys()]).toEqual([9]);
  // every item read again: the page keeps the revision they were read at (A21)
  expect(run.read).toHaveBeenCalledWith(items);
  expect(run.busy).toBe(false);
});

test('a refusal to move is thrown to the folder browser, catching up when the page is out of date; busy refuses at once', async () => {
  const run = fakeRun();
  relocateImport.mockRejectedValue(Object.assign(new Error('This import is finished.'), { code: 'import_finished' }));
  await expect(run.relocate({ path: '/x' })).rejects.toThrow('This import is finished.');
  expect(run.resync).toHaveBeenCalledWith(3);
  expect(run.busy).toBe(false);

  run.busy = true;
  await expect(run.relocate({ path: '/x' })).rejects.toThrow('Wait for the last change to be saved.');
  expect(relocateImport).toHaveBeenCalledTimes(1);

  run.busy = false;
  relocateImport.mockResolvedValue({ ...where, folderMissing: false });
  getImportItems.mockRejectedValue(new Error('The app could not be reached.'));
  await run.relocate({ path: '/y' });
  expect(showToast).toHaveBeenCalledWith('The app could not be reached.');
});
