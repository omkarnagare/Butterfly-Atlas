import { editable } from './importView.js';
import { selectOnly } from './selection.js';
import { anyModalOpen } from './modal.js';

/**
 * Selecting an import's photos (design B7), for screens/ImportRun.svelte: click, Shift, Ctrl/Cmd, a section or a
 * burst, arrows and Space, and the page's keys (A selects all, S T X start the bar's actions, Escape clears). Only
 * photos that can still be changed are selected. `run` is the page's state, read and written through accessors;
 * `run.startAction` comes from importEdits.svelte.js.
 */
export function importSelect(run) {
  function clear() {
    run.sel = { selected: new Set(), anchor: null, base: new Set() };
  }

  function add(ids) {
    const selected = new Set(run.sel.selected);
    for (const each of ids) if (editable(run.data.items.get(each))) selected.add(each);
    run.sel = { ...run.sel, selected, base: new Set(selected) };
  }

  /** Selects over the photos shown, leaving out those that can't be selected (Shift ranges over them). */
  const pick = (state, itemId, how) => {
    run.sel = selectOnly(state, run.order, itemId, how, (each) => editable(run.data.items.get(each)));
  };

  function onTileClick(item, event) {
    run.focusId = item.id;
    const shift = event.shiftKey && run.sel.anchor != null;
    if (editable(item) || shift) pick(run.sel, item.id, { shift: event.shiftKey, toggle: event.ctrlKey || event.metaKey });
  }

  function onSpace(item, shift) {
    if (editable(item)) pick(run.sel, item.id, shift ? { shift: true } : { toggle: true });
  }

  function onMove(next, shift) {
    if (shift) {
      const { sel } = run;
      const from = sel.anchor ?? run.focusId;
      pick(sel.anchor == null ? { ...sel, anchor: from, base: new Set(sel.selected) } : sel, next, { shift: true });
    }
    run.focusId = next;
    run.grid?.focusItem(next);
  }

  function onKey(event) {
    if (!run.summary || anyModalOpen() || event.isComposing || event.metaKey || event.ctrlKey || event.altKey) return;
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(event.target?.tagName ?? '') || event.target?.isContentEditable) return;
    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
    if (key === 'a') {
      event.preventDefault();
      run.sel = { selected: new Set(run.picks), anchor: run.sel.anchor, base: new Set(run.picks) };
    } else if (key === 's' || key === 't' || key === 'x') {
      if (!run.chosen.length) return;
      event.preventDefault();
      run.startAction({ s: 'species', t: 'tags', x: 'skip' }[key]);
    } else if (key === 'Escape' && run.sel.selected.size) {
      event.preventDefault();
      clear();
    }
  }

  return { clear, add, onTileClick, onSpace, onMove, onKey };
}
