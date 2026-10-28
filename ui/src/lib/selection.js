/**
 * Selection in the import grid (design B7), as pure functions over the grid's rows (importView.layoutRows):
 * a click selects one photo, Shift adds the range from the anchor, Ctrl/Cmd adds or removes one; the
 * arrow keys move between tiles as they are laid out, Up/Down to the same column of the row above or below.
 */

/** Item ids in the order the grid shows them. */
export const orderOf = (rows) => rows.flatMap((row) => (row.kind === 'tiles' ? row.items.map((item) => item.id) : []));

/** The ids from `a` to `b` inclusive, either way round; just `b` when `a` is not shown. */
export function rangeBetween(order, a, b) {
  const from = order.indexOf(a);
  const to = order.indexOf(b);
  if (to === -1) return [];
  if (from === -1) return [b];
  return from <= to ? order.slice(from, to + 1) : order.slice(to, from + 1);
}

/**
 * What a click (or Space) does. `base` is the selection as it was when the anchor was set, so Shift-clicking
 * again replaces the earlier range instead of growing it. Returns `{ selected, anchor, base }`.
 *  - plain: only this photo (or nothing, when it was the only one selected)
 *  - toggle (Ctrl/Cmd, Space): add or remove this photo
 *  - shift: `base` plus the range from the anchor
 */
export function select(state, order, id, { shift = false, toggle = false } = {}) {
  const { selected, anchor, base } = state;
  if (shift && anchor != null) {
    const next = new Set(base);
    for (const each of rangeBetween(order, anchor, id)) next.add(each);
    return { selected: next, anchor, base };
  }
  let next;
  if (toggle) {
    next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
  } else {
    next = selected.size === 1 && selected.has(id) ? new Set() : new Set([id]);
  }
  return { selected: next, anchor: id, base: new Set(next) };
}

/** Where each tile is: id → { row, col } (row is the index into rows). */
export function positions(rows) {
  const at = new Map();
  rows.forEach((row, r) => {
    if (row.kind === 'tiles') row.items.forEach((item, col) => at.set(item.id, { row: r, col }));
  });
  return at;
}

/**
 * The tile an arrow key (or Home/End) moves to from `id`, or `id` itself at an edge. Up/Down skip header
 * rows and keep the column, or take the last tile of a shorter row.
 */
export function moveFrom(rows, at, id, key) {
  const order = orderOf(rows);
  if (!order.length) return null;
  const here = at.get(id);
  if (!here) return order[0];
  const index = order.indexOf(id);
  if (key === 'ArrowLeft') return order[Math.max(0, index - 1)];
  if (key === 'ArrowRight') return order[Math.min(order.length - 1, index + 1)];
  if (key === 'Home') return order[0];
  if (key === 'End') return order.at(-1);
  const step = key === 'ArrowUp' ? -1 : key === 'ArrowDown' ? 1 : 0;
  if (!step) return id;
  for (let r = here.row + step; r >= 0 && r < rows.length; r += step) {
    const row = rows[r];
    if (row.kind === 'tiles') return row.items[Math.min(here.col, row.items.length - 1)].id;
  }
  return id;
}

/** `select`, then only the photos `can(id)` allows: Shift ranges over the others but leaves them out. */
export function selectOnly(state, order, id, how, can) {
  const next = select(state, order, id, how);
  const only = (set) => new Set([...set].filter(can));
  return { selected: only(next.selected), anchor: next.anchor, base: only(next.base) };
}

/** The selection with only the ids in `keep` (a Set), or `state` itself when it holds no others. */
export function pruneTo(state, keep) {
  if (![...state.selected].some((id) => !keep.has(id))) return state;
  const only = (set) => new Set([...set].filter((id) => keep.has(id)));
  return { selected: only(state.selected), anchor: state.anchor, base: only(state.base) };
}
