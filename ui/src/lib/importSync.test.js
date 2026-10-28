import { afterEach, expect, test, vi } from 'vitest';
import { RESYNC, importSync } from './importSync.svelte.js';
import { emptyItems, mergePage } from './importItems.js';
import { getImport, getImportItems } from './api.js';
import { showToast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ getImport: vi.fn(), getImportItems: vi.fn() }));
// showProblem says a refusal through showToast (its taxonomy link is checked in Toast.test.js)
vi.mock('./toast.svelte.js', () => {
  const showToast = vi.fn();
  return { showToast, showProblem: (e) => showToast(e.message) };
});
afterEach(() => vi.resetAllMocks());

const item = (id, extra = {}) => ({ id, state: 'read', ...extra });

/** The screen's state as importSync sees it, with the functions it calls on other modules stubbed. */
function fakeRun(extra = {}) {
  const run = {
    id: 3, current: (wanted) => wanted === run.id, busy: false, summary: { id: 3, phase: 'reading', state: 'open' },
    data: mergePage(emptyItems(), { version: 10, items: [item(1)] }), filingEnded: vi.fn(), loadResult: vi.fn(), revision: null, changedNote: '', ...extra,
  };
  Object.assign(run, importSync(run));
  return run;
}

test('act runs one action at a time and frees the page when it ends, failing or not', async () => {
  const run = fakeRun();
  let release;
  const work = vi.fn(() => new Promise((resolve) => { release = resolve; }));
  const first = run.act(work);
  expect(run.busy).toBe(true);
  await run.act(vi.fn()); // ignored while the first is in flight
  release();
  await first;
  expect(work).toHaveBeenCalledWith(3);
  expect(run.busy).toBe(false);

  await run.act(async () => { throw Object.assign(new Error('That photo is gone.'), { code: 'not_found' }); });
  expect(showToast).toHaveBeenCalledWith('That photo is gone.');
  expect(getImport).not.toHaveBeenCalled();
  expect(run.busy).toBe(false);
});

test('a refusal that shows the page is out of date catches up; one for an import no longer shown changes nothing', async () => {
  expect([...RESYNC]).toEqual(['folder_missing', 'import_finished', 'import_filing', 'nothing_to_file', 'pair_choice_needed', 'pair_choice_locked', 'import_changed']);
  const run = fakeRun({ summary: { id: 3, phase: 'filing', state: 'open' } });
  getImport.mockResolvedValue({ id: 3, phase: 'idle', state: 'open', filings: 1 });
  getImportItems.mockResolvedValue({ version: 12, items: [item(2)] });
  run.failed(Object.assign(new Error('Filing has finished.'), { code: 'import_filing' }), 3);
  await vi.waitFor(() => expect(run.summary.phase).toBe('idle'));
  expect(getImportItems).toHaveBeenCalledWith(3, 10);
  expect([...run.data.items.keys()]).toEqual([1, 2]);
  expect(run.data.version).toBe(12);
  expect(run.filingEnded).toHaveBeenCalledWith({ id: 3, phase: 'idle', state: 'open', filings: 1 });
  expect(run.loadResult).not.toHaveBeenCalled();

  showToast.mockClear();
  run.failed(Object.assign(new Error('Old'), { code: 'import_filing' }), 4);
  expect(showToast).not.toHaveBeenCalled();
});

test('a finished import found on catching up loads its result', async () => {
  const run = fakeRun();
  getImport.mockResolvedValue({ id: 3, phase: 'idle', state: 'finished' });
  getImportItems.mockResolvedValue({ version: 11, items: [] });
  await run.resync(3);
  expect(run.loadResult).toHaveBeenCalled();
  expect(run.filingEnded).not.toHaveBeenCalled();
});

test('a poll answer older than one applied is dropped, and one asked before a saved change keeps the items as they are', () => {
  const run = fakeRun();
  const older = run.begin();
  const newer = run.begin();
  expect(run.answer({ id: 3, phase: 'reading', state: 'open' }, { version: 11, items: [item(1, { state: 'x' })] }, newer)).toBe(true);
  expect(run.data.items.get(1).state).toBe('x');
  expect(run.answer({ id: 3, phase: 'idle', state: 'open' }, { version: 12, items: [item(1, { state: 'old' })] }, older)).toBe(true);
  expect(run.summary.phase).toBe('reading');

  const before = run.begin();
  run.edited();
  expect(run.answer({ id: 3, phase: 'filing', state: 'open' }, { version: 13, items: [item(1, { state: 'stale' })] }, before)).toBe(true);
  expect(run.summary.phase).toBe('filing');
  expect(run.data.items.get(1).state).toBe('x');
  expect(run.data.version).toBe(11);
  const idle = run.begin();
  run.edited();
  expect(run.answer({ id: 3, phase: 'idle', state: 'open', filings: 1 }, { version: 14, items: [] }, idle)).toBe(true);
  expect(run.summary.phase).toBe('filing'); // not applied: polling asks once more

  expect(run.answer({ id: 3, phase: 'idle', state: 'open', filings: 1 }, { version: 14, items: [] }, run.begin())).toBe(false);
  expect(run.data.version).toBe(14);
  expect(run.filingEnded).toHaveBeenCalledTimes(1);
});

test('a change\'s answer is taken only when it follows the revision held: one after another window\'s change, or a late one, is not (A20)', () => {
  const run = fakeRun({ revision: 4 });
  run.took({ revision: 5, previousRevision: 4 });
  expect(run.revision).toBe(5);
  // another window changed it to 6 meanwhile: this window never saw that, so File must be refused and the grid resync
  run.took({ revision: 7, previousRevision: 6 });
  expect(run.revision).toBe(5);
  // an older answer arriving late, or one that says nothing of what it followed, changes nothing
  run.took({ revision: 5, previousRevision: 4 });
  run.took({ revision: 9 });
  run.took({});
  run.took(null);
  expect(run.revision).toBe(5);
  run.took({ revision: 6, previousRevision: 5 });
  expect(run.revision).toBe(6);
});

test('a poll keeps the revision its items carry, not the summary\'s; one dropped for the user\'s own change meanwhile keeps none (A2, A21)', () => {
  const run = fakeRun({ revision: 6 });
  const t = run.begin();
  run.edited();
  run.answer({ id: 3, phase: 'reading', state: 'open', revision: 9 }, { version: 11, items: [], revision: 9 }, t);
  expect(run.revision).toBe(6);
  run.answer({ id: 3, phase: 'reading', state: 'open', revision: 9 }, { version: 11, items: [], revision: 8 }, run.begin());
  expect(run.revision).toBe(8);
});

test('import_changed says its words in the import\'s notice area and catches up, keeping the revision its items carry (A2, A21)', async () => {
  const run = fakeRun({ revision: 3 });
  // read in parallel, the summary may already be past the items: what the user sees is the items, so theirs is kept
  getImport.mockResolvedValue({ id: 3, phase: 'idle', state: 'open', revision: 8 });
  getImportItems.mockResolvedValue({ version: 12, items: [item(2)], revision: 7 });
  const message = 'This import was changed in another window. Check the photos, then press File again.';
  run.failed(Object.assign(new Error(message), { code: 'import_changed', status: 409 }), 3);
  expect(run.changedNote).toBe(message);
  // said once, where it stays: not in a toast as well
  expect(showToast).not.toHaveBeenCalled();
  await vi.waitFor(() => expect(run.revision).toBe(7));
  expect([...run.data.items.keys()]).toEqual([1, 2]);
});

test('a read of both the summary and the items keeps the lower of their revisions; items alone keep theirs (review fix)', async () => {
  const run = fakeRun({ revision: 3 });
  getImport.mockResolvedValue({ id: 3, phase: 'idle', state: 'open', revision: 6 });
  getImportItems.mockResolvedValue({ version: 12, items: [], revision: 7 });
  await run.resync(3);
  expect(run.revision).toBe(6);
  run.answer({ id: 3, phase: 'reading', state: 'open', revision: 8 }, { version: 13, items: [], revision: 9 }, run.begin());
  expect(run.revision).toBe(8);
  run.read({ version: 14, items: [], revision: 10 });
  expect(run.revision).toBe(10);
  // a summary from an older server, without one, leaves the items' alone
  run.read({ version: 15, items: [], revision: 11 }, { id: 3 });
  expect(run.revision).toBe(11);
});
