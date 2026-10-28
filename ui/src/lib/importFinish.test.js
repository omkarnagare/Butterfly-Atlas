import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { filingOver, importFinish } from './importFinish.svelte.js';
import { discardImport, fileImport, finishImport, getImportResult, pauseImport } from './api.js';
import { reducedMotion } from './motion.js';
import { showToast } from './toast.svelte.js';
import { LIBRARY_CHANGED, refreshStatus } from './status.svelte.js';

vi.mock('./api.js', () => ({ discardImport: vi.fn(), fileImport: vi.fn(), finishImport: vi.fn(), getImportResult: vi.fn(), pauseImport: vi.fn() }));
vi.mock('./motion.js', () => ({ reducedMotion: vi.fn(() => false) }));
vi.mock('./toast.svelte.js', () => ({ showToast: vi.fn() }));
vi.mock('./status.svelte.js', () => ({ LIBRARY_CHANGED: 'atlas:library-changed', refreshStatus: vi.fn() }));
beforeEach(() => {
  window.location.hash = '#/import/3';
});
afterEach(() => vi.resetAllMocks());

/** The screen's state as importFinish sees it; `act` runs the work at once, as importSync's does when nothing is in flight. */
function fakeRun(extra = {}) {
  const run = {
    id: 3, current: (wanted) => wanted === run.id, busy: false, summary: { id: 3, rootName: 'Butterflies 2024', phase: 'filing' },
    pausing: false, result: null, resultError: '', showingResult: false, celebrating: null, revision: 5, changedNote: '',
    took: vi.fn(), act: async (work) => work(run.id), ...extra,
  };
  Object.assign(run, importFinish(run));
  return run;
}

test('filing is over when it stops, or when one began and ended between two answers', () => {
  expect(filingOver(null, { id: 3, phase: 'idle' })).toBe(false);
  expect(filingOver({ id: 3, phase: 'filing' }, { id: 3, phase: 'idle' })).toBe(true);
  expect(filingOver({ id: 3, phase: 'filing' }, { id: 3, phase: 'filing' })).toBe(false);
  expect(filingOver({ id: 3, phase: 'reading', filings: 1 }, { id: 3, phase: 'reading', filings: 2 })).toBe(true);
  expect(filingOver({ id: 3, phase: 'reading', filings: 1 }, { id: 3, phase: 'reading', filings: 1 })).toBe(false);
  expect(filingOver({ id: 4, phase: 'filing' }, { id: 3, phase: 'idle' })).toBe(false);
});

test('when filing ends with new species the celebration shows first; under reduced motion a toast says it and the result opens', async () => {
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  const run = fakeRun({ pausing: true });
  getImportResult.mockResolvedValue({ left: 0, lastFiling: { newSpecies: 2, speciesCount: 40, milestone: null } });
  await run.filingEnded({ id: 3, phase: 'idle' });
  expect(run.pausing).toBe(false);
  expect(refreshStatus).toHaveBeenCalled();
  expect(changed).toHaveBeenCalledTimes(1);
  expect(run.celebrating).toEqual({ count: 40, added: 2, milestone: null });
  expect(run.showingResult).toBe(false);

  reducedMotion.mockReturnValue(true);
  getImportResult.mockResolvedValue({ left: 0, lastFiling: { newSpecies: 1, speciesCount: 50, milestone: 50 } });
  const quiet = fakeRun();
  await quiet.filingEnded({ id: 3, phase: 'idle' });
  expect(showToast).toHaveBeenCalledWith('1 new species photographed! Milestone reached: 50 species.');
  expect(quiet.celebrating).toBe(null);
  expect(quiet.showingResult).toBe(true);
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('a pause or a stop with a notice is not the end of filing, and a result that fails to load is a toast', async () => {
  const run = fakeRun();
  await run.filingEnded({ id: 3, phase: 'idle', paused: true });
  await run.filingEnded({ id: 3, phase: 'idle', notice: { code: 'library_missing' } });
  expect(getImportResult).not.toHaveBeenCalled();

  getImportResult.mockRejectedValue(new Error('The app could not be reached.'));
  await run.filingEnded({ id: 3, phase: 'idle' });
  expect(run.resultError).toBe('The app could not be reached.');
  expect(showToast).toHaveBeenCalledWith('The app could not be reached.');
  expect(run.showingResult).toBe(false);
});

test('File starts filing; Pause says it will pause, or shows the result when filing ended first', async () => {
  const run = fakeRun({ summary: { id: 3, phase: 'idle' }, pausing: true, changedNote: 'This import was changed in another window. Check the photos, then press File again.' });
  fileImport.mockResolvedValue({ id: 3, phase: 'filing' });
  await run.file();
  // the latest revision this window holds goes with File, and File takes the refusal's note away (contract §5, A2)
  expect(fileImport).toHaveBeenCalledWith(3, 5);
  expect(run.changedNote).toBe('');
  expect(run.took).toHaveBeenCalledWith({ id: 3, phase: 'filing' });
  expect(run.summary).toEqual({ id: 3, phase: 'filing' });
  expect(run.pausing).toBe(false);

  pauseImport.mockResolvedValue({ id: 3, phase: 'filing' });
  await run.pause();
  expect(run.pausing).toBe(true);
  expect(showToast).toHaveBeenCalledWith('Filing will pause after the photos it is filing now.');

  run.pausing = false;
  pauseImport.mockResolvedValue({ id: 3, phase: 'idle', paused: true });
  await run.pause();
  expect(run.pausing).toBe(false);
  expect(getImportResult).not.toHaveBeenCalled(); // paused: filingEnded loads nothing
});

test('File whose filing ended before its answer came shows the result, as a poll that sees filing end does', async () => {
  // a small import on a fast computer: both photos are filed before File answers (seen on Windows)
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  const run = fakeRun({ summary: { id: 3, phase: 'idle', filings: 0 } });
  reducedMotion.mockReturnValue(true);
  getImportResult.mockResolvedValue({ left: 0, lastFiling: { newSpecies: 0, speciesCount: 40, milestone: null } });
  fileImport.mockResolvedValue({ id: 3, phase: 'idle', filings: 1 });
  await run.file();
  expect(run.summary).toEqual({ id: 3, phase: 'idle', filings: 1 });
  await vi.waitFor(() => expect(run.showingResult).toBe(true));
  expect(getImportResult).toHaveBeenCalledWith(3);
  expect(changed).toHaveBeenCalledTimes(1);
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('Finish marks what waits as skipped and goes back to the imports; a failure shows in the dialog', async () => {
  const run = fakeRun({ result: { left: 2 }, showingResult: true });
  finishImport.mockResolvedValue({});
  await run.finish();
  expect(finishImport).toHaveBeenCalledWith(3, true);
  expect(run.showingResult).toBe(false);
  expect(showToast).toHaveBeenCalledWith('The import of Butterflies 2024 is finished.');
  expect(window.location.hash).toBe('#/import');
  expect(run.busy).toBe(false);

  window.location.hash = '#/import/3';
  finishImport.mockRejectedValue(new Error('Filing is still running.'));
  const failing = fakeRun({ result: { left: 0 }, showingResult: true });
  await failing.finish();
  expect(finishImport).toHaveBeenLastCalledWith(3, false);
  expect(failing.resultError).toBe('Filing is still running.');
  expect(failing.showingResult).toBe(true);
  expect(window.location.hash).toBe('#/import/3');
});

test('Finish… opens the result once it is loaded; Remove from your imports discards a finished import', async () => {
  const run = fakeRun();
  getImportResult.mockResolvedValue({ left: 1 });
  await run.openResult();
  expect(run.result).toEqual({ left: 1 });
  expect(run.showingResult).toBe(true);

  discardImport.mockResolvedValue(undefined);
  await run.removeFinished();
  expect(discardImport).toHaveBeenCalledWith(3);
  expect(showToast).toHaveBeenCalledWith('Butterflies 2024 was removed from your imports.');
  expect(window.location.hash).toBe('#/import');
});
