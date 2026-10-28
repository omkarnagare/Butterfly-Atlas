import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { importEdits } from './importEdits.svelte.js';
import { emptyItems, mergePage } from './importItems.js';
import { acceptSuggestions, assignItems, placeItems, skipItems, tagItems, unplaceItems } from './api.js';
import { showToast } from './toast.svelte.js';

vi.mock('./api.js', () => ({
  acceptSuggestions: vi.fn(), assignItems: vi.fn(), placeItems: vi.fn(), skipItems: vi.fn(), tagItems: vi.fn(), unplaceItems: vi.fn(),
}));
// showProblem says a refusal through showToast (its taxonomy link is checked in Toast.test.js)
vi.mock('./toast.svelte.js', () => {
  const showToast = vi.fn();
  return { showToast, showProblem: (e) => showToast(e.message) };
});
beforeEach(() => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => {
  vi.resetAllMocks();
  vi.restoreAllMocks();
});

const item = (id, extra = {}) => ({ id, skipped: false, ...extra });

/**
 * The screen's state as importEdits sees it. What is shown is the photos not skipped (as under a filter a skip
 * takes them out of); `chosen` is the selected ones among them.
 */
function fakeRun(list = [item(1), item(2), item(3), item(4)], extra = {}) {
  const run = {
    id: 3, current: (wanted) => wanted === run.id, busy: false, data: mergePage(emptyItems(), { version: 10, items: list }),
    selected: new Set([2, 3]), focusId: 2, choosing: null, blocked: '', libraryIds: [4],
    get order() { return [...run.data.items.values()].filter((each) => !each.skipped).map((each) => each.id); },
    get shown() { return run.order.map((each) => run.data.items.get(each)); },
    get chosen() { return run.shown.filter((each) => run.selected.has(each.id)); },
    grid: { focusItem: vi.fn() }, tools: { focusFilter: vi.fn() }, clear: vi.fn(() => { run.selected = new Set(); }),
    edited: vi.fn(), resync: vi.fn(), took: vi.fn(), ...extra,
  };
  Object.assign(run, importEdits(run));
  return run;
}

test('a saved change merges what it changed, says so, and hands focus on from a photo it took out of the shown ones', async () => {
  const run = fakeRun();
  const answer = { version: 11, items: [item(2, { skipped: true }), item(3, { skipped: true })], revision: 8 };
  skipItems.mockResolvedValue(answer);
  run.startAction('skip');
  await vi.waitFor(() => expect(run.clear).toHaveBeenCalled());
  expect(skipItems).toHaveBeenCalledWith(3, [2, 3], true);
  // its revision is kept, so this page's own change doesn't refuse its next File (A3)
  expect(run.took).toHaveBeenCalledWith(answer);
  expect(run.data.version).toBe(10); // a change's answer does not move the poll version on
  expect(run.order).toEqual([1, 4]);
  expect(run.focusId).toBe(4);
  expect(run.edited).toHaveBeenCalledTimes(1);
  expect(showToast).toHaveBeenCalledWith('2 photos skipped. They stay in the folder.');
  expect(run.choosing).toBe(null);
  await vi.waitFor(() => expect(run.grid.focusItem).toHaveBeenCalledWith(4));
  expect(run.busy).toBe(false);
});

test('Skip on photos all skipped files them again; with none shown afterwards focus goes to the filter chip', async () => {
  const run = fakeRun([item(1, { skipped: false })], { selected: new Set([1]), focusId: null });
  skipItems.mockResolvedValue({ items: [item(1, { skipped: true })] });
  run.startAction('skip');
  await vi.waitFor(() => expect(run.tools.focusFilter).toHaveBeenCalled());
  expect(skipItems).toHaveBeenCalledWith(3, [1], true);

  const back = fakeRun([item(1, { skipped: true }), item(2, { skipped: true })], {
    order: [1, 2], selected: new Set([1, 2]), focusId: null,
  });
  skipItems.mockResolvedValue({ items: [item(1), item(2)] });
  back.startAction('skip');
  await vi.waitFor(() => expect(showToast).toHaveBeenCalledWith('2 photos will be filed again.'));
  expect(skipItems).toHaveBeenLastCalledWith(3, [1, 2], false);
});

test('an action waits for the last change, refuses while filing, and opens the chooser for species, tags and place', () => {
  const run = fakeRun();
  run.busy = true;
  run.startAction('species');
  expect(showToast).toHaveBeenCalledWith('Wait for the last change to be saved.');
  run.busy = false;
  run.blocked = 'Wait for filing to finish or pause it.';
  run.startAction('tags');
  expect(showToast).toHaveBeenCalledWith('Wait for filing to finish or pause it.');
  expect(run.choosing).toBe(null);
  run.blocked = '';
  for (const what of ['species', 'tags', 'place']) {
    run.startAction(what);
    expect(run.choosing).toBe(what);
  }
  const none = fakeRun(undefined, { selected: new Set() });
  none.startAction('place');
  expect(none.choosing).toBe(null);
});

test('assign, tags and place save for the chosen photos with their messages; a refusal is thrown to the open dialog', async () => {
  const run = fakeRun();
  assignItems.mockResolvedValue({ items: [item(2, { taxonId: 'A5' })] });
  await run.assignTo({ id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger' });
  expect(assignItems).toHaveBeenCalledWith(3, [2, 3], 'A5');
  expect(showToast).toHaveBeenCalledWith('2 photos assigned to Plain Tiger.');

  run.selected = new Set([2]);
  tagItems.mockResolvedValue({ items: [] });
  await run.tagChosen([6], [7]);
  expect(tagItems).toHaveBeenCalledWith(3, [2], [6], [7]);
  expect(showToast).toHaveBeenCalledWith('Tags updated on 1 photo.');

  run.selected = new Set([1, 2]);
  placeItems.mockRejectedValue(Object.assign(new Error('This import is finished.'), { code: 'import_finished' }));
  await expect(run.placeChosen({ id: 'P414', name: 'Pune' })).rejects.toThrow('This import is finished.');
  expect(run.resync).toHaveBeenCalledWith(3);
  expect(run.busy).toBe(false);

  placeItems.mockResolvedValue({ items: [] });
  await run.placeChosen({ id: 'P414', name: 'Pune' });
  expect(placeItems).toHaveBeenLastCalledWith(3, [1, 2], 'P414');
  expect(showToast).toHaveBeenCalledWith('Pune set on 2 photos.');

  run.busy = true;
  await expect(run.assignTo({ id: 'A6', name: 'x' })).rejects.toThrow('Wait for the last change to be saved.');
});

test('Remove place, a section\'s suggestions and Skip what is in your atlas show a refusal as a toast', async () => {
  const run = fakeRun();
  unplaceItems.mockRejectedValue(new Error('Those photos are filed.'));
  run.startAction('unplace');
  await vi.waitFor(() => expect(showToast).toHaveBeenCalledWith('Those photos are filed.'));
  expect(unplaceItems).toHaveBeenCalledWith(3, [2, 3]);

  acceptSuggestions.mockResolvedValue({ items: [] });
  await run.acceptSection({ title: 'Sinhagad' }, [2, 3]);
  expect(acceptSuggestions).toHaveBeenCalledWith(3, [2, 3]);
  expect(showToast).toHaveBeenCalledWith('2 suggestions accepted in Sinhagad.');
  expect(run.clear).not.toHaveBeenCalled(); // the selection stays

  skipItems.mockRejectedValue(new Error('Wait for filing to finish.'));
  await run.skipLibrary();
  expect(skipItems).toHaveBeenCalledWith(3, [4], true);
  expect(showToast).toHaveBeenCalledWith('Wait for filing to finish.');
});
