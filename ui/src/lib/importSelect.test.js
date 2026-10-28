import { afterEach, expect, test, vi } from 'vitest';
import { importSelect } from './importSelect.svelte.js';
import { emptyItems, mergePage } from './importItems.js';
import { anyModalOpen } from './modal.js';

vi.mock('./modal.js', () => ({ anyModalOpen: vi.fn(() => false) }));
afterEach(() => vi.resetAllMocks());

const item = (id, extra = {}) => ({ id, state: 'read', ...extra });
const none = () => ({ selected: new Set(), anchor: null, base: new Set() });

/** The screen's state as importSelect sees it: five photos shown, the fourth filed (it can't be selected). */
function fakeRun(extra = {}) {
  const list = [item(1), item(2), item(3), item(4, { outcome: 'filed' }), item(5)];
  const run = {
    summary: { id: 3 }, data: mergePage(emptyItems(), { items: list }), sel: none(), focusId: null, order: [1, 2, 3, 4, 5],
    picks: [1, 2, 3, 5], get chosen() { return run.order.filter((each) => run.sel.selected.has(each)); },
    grid: { focusItem: vi.fn() }, startAction: vi.fn(), ...extra,
  };
  Object.assign(run, importSelect(run));
  return run;
}
const key = (k, extra = {}) => ({ key: k, target: document.body, preventDefault: vi.fn(), ...extra });

test('a click selects one photo, Ctrl/Cmd adds one, Shift ranges over the shown ones leaving out what cannot be selected', () => {
  const run = fakeRun();
  run.onTileClick(item(2), {});
  expect([...run.sel.selected]).toEqual([2]);
  expect(run.focusId).toBe(2);
  run.onTileClick(item(5), { shiftKey: true });
  expect([...run.sel.selected]).toEqual([2, 3, 5]);
  run.onTileClick(item(1), { metaKey: true });
  expect([...run.sel.selected].sort()).toEqual([1, 2, 3, 5]);
  run.onTileClick(item(4, { outcome: 'filed' }), {});
  expect(run.sel.selected.size).toBe(4); // a filed photo is focused, not selected
  expect(run.focusId).toBe(4);
  run.clear();
  expect(run.sel).toEqual(none());
});

test('Space toggles, arrows move focus and Shift+arrow extends from the focused photo; a section adds what can be selected', () => {
  const run = fakeRun({ focusId: 1 });
  run.onSpace(item(1), false);
  expect([...run.sel.selected]).toEqual([1]);
  run.onMove(3, true);
  expect([...run.sel.selected]).toEqual([1, 2, 3]);
  expect(run.focusId).toBe(3);
  expect(run.grid.focusItem).toHaveBeenCalledWith(3);
  run.onSpace(item(4, { outcome: 'filed' }), false);
  expect([...run.sel.selected]).toEqual([1, 2, 3]);

  const other = fakeRun({ focusId: 2 });
  other.onMove(3, true); // no anchor yet: the range starts at the focused photo
  expect([...other.sel.selected]).toEqual([2, 3]);
  other.add([4, 5]);
  expect([...other.sel.selected]).toEqual([2, 3, 5]);
  expect([...other.sel.base]).toEqual([2, 3, 5]);
});

test('A selects all, S T X start the bar\'s actions on a selection, Escape clears; typing, modifiers and open overlays are left alone', () => {
  const run = fakeRun();
  const a = key('A');
  run.onKey(a);
  expect(a.preventDefault).toHaveBeenCalled();
  expect([...run.sel.selected]).toEqual([1, 2, 3, 5]);
  for (const [k, what] of [['s', 'species'], ['t', 'tags'], ['x', 'skip']]) {
    run.onKey(key(k));
    expect(run.startAction).toHaveBeenLastCalledWith(what);
  }
  run.onKey(key('a', { ctrlKey: true }));
  run.onKey(key('s', { target: document.createElement('input') }));
  anyModalOpen.mockReturnValue(true);
  run.onKey(key('Escape'));
  expect(run.sel.selected.size).toBe(4);
  expect(run.startAction).toHaveBeenCalledTimes(3);
  anyModalOpen.mockReturnValue(false);
  run.onKey(key('Escape'));
  expect(run.sel.selected.size).toBe(0);

  const s = key('s');
  run.onKey(s); // nothing selected: S does nothing
  expect(s.preventDefault).not.toHaveBeenCalled();
  expect(run.startAction).toHaveBeenCalledTimes(3);
  const opening = fakeRun({ summary: null });
  opening.onKey(key('a'));
  expect(opening.sel.selected.size).toBe(0);
});
