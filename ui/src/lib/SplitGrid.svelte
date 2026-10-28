<script>
  import { tick } from 'svelte';
  import { photoThumb } from './api.js';
  import { fadeOut, measure, play } from './flip.js';
  import { moveFrom, positions } from './selection.js';
  import { thumbRetry } from './thumbRetry.js';

  /**
   * The photos of a split species still to sort (Phase 5b contract §6, design §4.4): square tiles in rows, as the
   * import grid's (lib/selection.js). Clicks go to `onTileClick(photo, event)` (the page selects: plain, Shift,
   * Ctrl/⌘); keys on a tile: arrows (Up/Down keep the column), Home and End move focus (`onMove(id, shift)`), Space
   * opens the photo large (`onOpen`), Enter adds or removes it from the selection (`onToggle`); a double-click opens it.
   * Only the focused tile is in the Tab order. For screen readers a grid named `label` (aria-multiselectable) of rows
   * and cells with aria-selected. `glideOut(ids)` fades those tiles out; `measure()` and `play(before)` let the rest
   * glide to their new places (FLIP; nothing moves under reduced motion). `focusItem(id)` focuses a tile.
   */
  let { photos, selected = new Set(), focusId = null, label = 'Photos to sort', onTileClick, onMove, onOpen, onToggle, onFocusTile = () => {} } = $props();

  const GAP = 10;
  const MIN_TILE = 150;
  const FALLBACK_WIDTH = 1140; // jsdom and the first paint have no layout

  let el = $state(null);
  let width = $state(0);
  let usable = $derived(width || FALLBACK_WIDTH);
  let cols = $derived(Math.max(2, Math.floor((usable + GAP) / (MIN_TILE + GAP))));
  let tile = $derived(Math.floor((usable - GAP * (cols - 1)) / cols));
  let rows = $derived.by(() => {
    const out = [];
    for (let i = 0; i < photos.length; i += cols) out.push({ kind: 'tiles', key: i, items: photos.slice(i, i + cols) });
    return out;
  });
  let at = $derived(positions(rows));
  let tabbableId = $derived(focusId != null && at.has(focusId) ? focusId : photos[0]?.id ?? null);

  // The grid's width sets its columns (ResizeObserver where there is one, else the window's resize).
  $effect(() => {
    if (!el) return undefined;
    const note = () => {
      width = el.clientWidth;
    };
    note();
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(note) : null;
    observer?.observe(el);
    window.addEventListener('resize', note);
    return () => {
      observer?.disconnect();
      window.removeEventListener('resize', note);
    };
  });

  const tiles = () => el?.querySelectorAll('[data-key]') ?? [];
  const tileOf = (id) => el?.querySelector(`[data-key="${id}"]`);

  export function glideOut(ids) {
    return fadeOut(ids.map(tileOf).filter(Boolean));
  }

  export function measureTiles() {
    return measure(el, tiles());
  }

  export function playTiles(before) {
    play(el, before, tiles());
  }

  export async function focusItem(id) {
    if (!at.has(id)) return;
    onFocusTile(id);
    await tick();
    const node = tileOf(id);
    node?.focus({ preventScroll: true });
    node?.scrollIntoView?.({ block: 'nearest' });
  }

  function onKey(photo, event) {
    if (event.isComposing || event.altKey || event.metaKey || event.ctrlKey) return;
    if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) {
      event.preventDefault();
      const next = moveFrom(rows, at, photo.id, event.key);
      if (next != null) onMove(next, event.shiftKey);
    } else if (event.key === ' ') {
      event.preventDefault();
      onOpen(photo, event.currentTarget);
    } else if (event.key === 'Enter') {
      event.preventDefault();
      onToggle(photo, event.shiftKey);
    }
  }

  const described = (photo) => (photo.place ? `${photo.fileName}, ${photo.place.path[0]}` : photo.fileName);
</script>

<div class="sg" bind:this={el} role="grid" aria-label={label} aria-multiselectable="true"
     aria-rowcount={rows.length} aria-colcount={cols} style="--tile:{tile}px;--gap:{GAP}px">
  {#each rows as row, r (row.key)}
    <div class="sg-row" role="row" aria-rowindex={r + 1}>
      {#each row.items as photo, k (photo.id)}
        <button type="button" class="it" class:sel={selected.has(photo.id)} data-key={photo.id} role="gridcell" aria-colindex={k + 1}
                tabindex={photo.id === tabbableId ? 0 : -1} aria-selected={selected.has(photo.id)} aria-label={described(photo)}
                onclick={(event) => onTileClick(photo, event)} ondblclick={(event) => onOpen(photo, event.currentTarget)}
                onkeydown={(event) => onKey(photo, event)} onkeyup={(event) => { if (event.key === ' ') event.preventDefault(); }}
                onfocus={() => onFocusTile(photo.id)}>
          <img src={photoThumb(photo)} alt="" loading="lazy" decoding="async" draggable="false" use:thumbRetry />
          {#if photo.place}<span class="it-p" aria-hidden="true">{photo.place.path[0]}</span>{/if}
          <span class="check" aria-hidden="true"></span>
        </button>
      {/each}
    </div>
  {/each}
</div>
