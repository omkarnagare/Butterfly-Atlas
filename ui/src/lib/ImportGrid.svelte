<script>
  import { tick, untrack } from 'svelte';
  import ImportTile from './ImportTile.svelte';
  import { burstTime } from './bursts.js';
  import { layoutRows, measureRows, photoCount, sectionSuggestion, taxonTitle, visibleRange } from './importView.js';
  import { moveFrom, orderOf, positions } from './selection.js';
  import { trackView } from './windowRows.js';

  /**
   * The import grid, virtualised (design §4.3, docs/PLAN.md §8a): sections and bursts become rows of fixed
   * height (section header, burst header, a row of square tiles), and only the rows near the window's view
   * are in the page, so ten thousand photos scroll as smoothly as ten. The page itself scrolls (the top bar
   * stays); the grid works out which rows are in view from its own position on every scroll and resize.
   * The focused tile's row is always kept, so keyboard focus is never lost when it scrolls out of view.
   *
   * Keys on a tile: arrows (Up/Down keep the column), Home and End move focus (`onMove(id, shift)`); Space
   * toggles it (`onSpace`); Enter or a double-click opens it (`onOpen`). Clicks go to `onTileClick(item, event)`.
   * The page owns the selection and the actions; `focusItem(id)` moves focus to a tile, scrolling it into view.
   *
   * For screen readers it is a grid named `label` (aria-multiselectable): every row of the layout is a row with
   * its index among all rows (most are not in the page), headers are one cell across the grid, and each tile is
   * a cell with its column and, when it can be selected, aria-selected. When the rows around the focused tile
   * are laid out anew (a burst grew, a photo left the filter), focus stays on that tile, or on the one now in
   * its place, as long as focus was in the grid.
   */
  let {
    importId, sections, taxa, label = 'Photos', selected = new Set(), focusId = null, busy = false,
    onTileClick, onOpen, onMove, onSpace, onFocusTile = () => {}, onSelectSection, onSelectBurst, onAcceptSection,
  } = $props();

  const GAP = 10;
  const MIN_TILE = 150;
  const FALLBACK_WIDTH = 1140; // jsdom and the first paint have no layout; the real width follows at once
  const HEIGHTS = { section: 64, burst: 40 };

  let el = $state(null);
  let width = $state(0);
  let view = $state({ from: 0, to: 0 });

  let usable = $derived(width || FALLBACK_WIDTH);
  let cols = $derived(Math.max(2, Math.floor((usable + GAP) / (MIN_TILE + GAP))));
  let tile = $derived(Math.floor((usable - GAP * (cols - 1)) / cols));
  let rows = $derived(layoutRows(sections, cols));
  let measured = $derived(measureRows(rows, { ...HEIGHTS, tiles: tile + GAP }));
  let at = $derived(positions(rows));
  let order = $derived(orderOf(rows));
  let suggestions = $derived(new Map(sections.map((section) => [section.key, sectionSuggestion(section.items)])));
  let tabbableId = $derived(focusId != null && at.has(focusId) ? focusId : order[0] ?? null);

  let shown = $derived.by(() => {
    const { start, end } = visibleRange(measured.tops, measured.total, view.from, view.to, 3);
    const indexes = [];
    for (let i = start; i < end; i++) indexes.push(i);
    // The focused tile's row stays in the page even out of view, in its place among the rows (DOM order is reading order).
    const focused = at.get(tabbableId)?.row;
    if (focused != null && focused < start) indexes.unshift(focused);
    else if (focused != null && focused >= end) indexes.push(focused);
    return indexes;
  });

  // Follow the window's scroll and size, the grid's own width (not its height, which it sets itself) and the page's
  // size, which changes when content above the grid comes or goes (lib/windowRows.js trackView).
  let tracker = null;
  const measure = () => tracker?.measure();
  $effect(() => {
    if (!el) return undefined;
    // untrack: the first measure runs inside this effect, which must not come to depend on the view it sets.
    tracker = trackView(el, (next) => untrack(() => {
      width = next.width;
      // An unchanged view is not assigned again, so nothing that follows it is worked out again.
      if (next.from !== view.from || next.to !== view.to) view = { from: next.from, to: next.to };
    }));
    return () => {
      tracker();
      tracker = null;
    };
  });

  // Content above the grid (a banner, the filters) can move it: measure again whenever the rows change.
  $effect(() => {
    rows;
    measure();
  });

  // Keep focus in the grid when its rows are laid out anew: note whether it was here before the page changes...
  let hadFocus = false;
  let previousOrder = [];
  $effect.pre(() => {
    rows;
    hadFocus = Boolean(el?.contains(document.activeElement));
  });
  // ...and, if the focused tile's row was rebuilt (focus fell to the page), give it back.
  $effect(() => {
    const now = order;
    const before = previousOrder;
    previousOrder = now;
    if (!hadFocus || !el || el.contains(document.activeElement)) return;
    if (document.activeElement && document.activeElement !== document.body) return;
    let target = focusId;
    if (!at.has(target)) {
      const was = Math.max(0, before.indexOf(focusId));
      target = now[Math.min(was, now.length - 1)] ?? null;
    }
    if (target != null) focusItem(target);
  });

  // While reading reshuffles bursts above the focused tile (or the columns change), scroll by as much as the
  // tile moved, so it stays where it was on screen. Only when the tile was in view and focus is in the grid when
  // the scroll would happen (after a rebuilt row has had focus given back). The browser's own scroll anchoring
  // is off for the grid (overflow-anchor: none), so the two never add up.
  let anchorId = null;
  let anchorTop = null;
  $effect(() => {
    const tops = measured.tops;
    const row = at.get(focusId)?.row;
    const top = row != null ? tops[row] : null;
    const seen = untrack(() => view);
    if (anchorId === focusId && top != null && anchorTop != null && top !== anchorTop && anchorTop >= seen.from && anchorTop < seen.to) {
      const by = top - anchorTop;
      // Two ticks: a rebuilt row gets focus back after one (focusItem waits a tick before focusing).
      tick().then(tick).then(() => {
        if (el?.contains(document.activeElement)) window.scrollBy(0, by);
      });
    }
    anchorId = focusId;
    anchorTop = top;
  });

  /** Scrolls back to the grid's top when the page is scrolled past it (after a change of filter or view). */
  export function toTop() {
    if (el && el.getBoundingClientRect().top < 0) el.scrollIntoView?.({ block: 'start' });
  }

  export async function focusItem(id) {
    const where = at.get(id);
    if (!where) return;
    onFocusTile(id);
    await tick();
    const node = el?.querySelector(`[data-item-id="${id}"]`);
    node?.focus({ preventScroll: true });
    node?.scrollIntoView?.({ block: 'nearest' });
    measure();
  }

  function onKey(item, event) {
    if (event.isComposing || event.altKey) return;
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const next = moveFrom(rows, at, item.id, event.key);
      if (next != null) onMove(next, event.shiftKey);
    } else if (event.key === ' ') {
      event.preventDefault();
      onSpace(item, event.shiftKey);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      onOpen(item);
    }
  }

  const heightOf = (row) => (row.kind === 'tiles' ? tile + GAP : HEIGHTS[row.kind]);
</script>

<div class="ig" bind:this={el} role="grid" aria-label={label} aria-multiselectable="true" aria-rowcount={rows.length} aria-colcount={cols}
     style="height:{measured.total}px;--tile:{tile}px;--gap:{GAP}px">
  {#each shown as index (rows[index].key)}
    {@const row = rows[index]}
    <div class="ig-row r-{row.kind}" role="row" aria-rowindex={index + 1} style="transform:translateY({measured.tops[index]}px);height:{heightOf(row)}px">
      {#if row.kind === 'section'}
        {@const suggestion = suggestions.get(row.section.key)}
        <div class="ig-cell" role="gridcell" aria-colspan={cols}>
          <h3 class:bn={row.section.taxonId && !taxa.get(row.section.taxonId)?.common}>{row.section.title}</h3>
          <span class="ct">{photoCount(row.section.items.length)}</span>
          <button class="btn ghost" type="button" aria-label="Select all in {row.section.title}" onclick={() => onSelectSection(row.section)}>Select all</button>
          {#if suggestion.ids.length && !row.section.taxonId}
            <span class="ig-sug">{suggestion.taxonId ? `Suggested: ${taxonTitle(taxa, suggestion.taxonId)}` : `${suggestion.ids.length.toLocaleString()} suggested`}</span>
            <button class="btn ghost" type="button" aria-disabled={busy} aria-label="Accept all suggestions in {row.section.title}"
                    onclick={() => { if (!busy) onAcceptSection(row.section, suggestion.ids); }}>Accept all</button>
          {/if}
        </div>
      {:else if row.kind === 'burst'}
        <div class="ig-cell" role="gridcell" aria-colspan={cols}>
          <span>{burstTime(row.group.start)} · {photoCount(row.group.items.length)}</span>
          <button class="ig-link" type="button" aria-label="Select burst of {photoCount(row.group.items.length)} from {burstTime(row.group.start)}"
                  onclick={() => onSelectBurst(row.group)}>Select burst</button>
        </div>
      {:else}
        {#each row.items as item, k (item.id)}
          <ImportTile {importId} {item} {taxa} col={k + 1} selected={selected.has(item.id)} tabbable={item.id === tabbableId}
                      onClick={onTileClick} {onOpen} {onKey} onFocus={(it) => onFocusTile(it.id)} />
        {/each}
      {/if}
    </div>
  {/each}
</div>
