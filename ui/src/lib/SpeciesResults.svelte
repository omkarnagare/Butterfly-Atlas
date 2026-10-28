<script>
  import { onDestroy, tick, untrack } from 'svelte';
  import { famStyle } from './families.js';
  import { speciesHref } from './filter.js';
  import { rowRange, trackView } from './windowRows.js';
  import { NOT_PHOTOGRAPHED } from './messages.js';

  /**
   * The Export screen's Species view (contract §4): every species of the result in taxonomic order, virtualised (only
   * the rows near the view are in the page). A row shows the scientific name (italic serif), the common name, the
   * family's colour, the Custom badge on a custom species, the photo count ("24 photos", "Not photographed") and, with Missing life stages, "Missing: egg,
   * pupa"; it links to the species page with its chips pre-set (lib/filter.js `speciesHref`). One row is in the tab
   * order; arrow keys, Home and End move between rows (the focused one always stays in the page). Rows rise in with a
   * short stagger when a new result arrives (CSS, off under reduced motion): a new `query` (the filter the rows answer),
   * not new rows of the same one (the same result fetched again keeps its place and does not rise again).
   */
  let { rows, filter, query = '' } = $props();

  const ROW = 64;
  let list = $state(null);
  let view = $state({ from: 0, to: 800 });
  let focusAt = $state(0);
  let entering = $state(true);

  let range = $derived(rowRange(rows.length, ROW, view.from, view.to));
  let shown = $derived.by(() => {
    const indexes = [];
    for (let i = range.start; i < range.end; i++) indexes.push(i);
    if (focusAt < rows.length && (focusAt < range.start || focusAt >= range.end)) {
      if (focusAt < range.start) indexes.unshift(focusAt);
      else indexes.push(focusAt);
    }
    return indexes;
  });

  $effect(() => {
    if (!list) return undefined;
    // untrack: the first measure runs inside this effect, which must not come to depend on the view it sets.
    return trackView(list, (next) => untrack(() => {
      if (next.from !== view.from || next.to !== view.to) view = next;
    }));
  });

  // A new result: back to the first row, which rises in with the rest. Only for another query: the effect can run
  // again with the same one (its props were set anew), which must change nothing.
  let shownQuery = null;
  let timer = 0;
  $effect(() => {
    const now = query;
    if (now === shownQuery) return;
    shownQuery = now;
    untrack(() => {
      focusAt = 0;
      entering = true;
    });
    clearTimeout(timer);
    timer = setTimeout(() => (entering = false), 900);
  });
  onDestroy(() => clearTimeout(timer));

  // The same filter answered with fewer rows: the row in the tab order is still one of them.
  $effect(() => {
    if (focusAt >= rows.length && rows.length) untrack(() => (focusAt = rows.length - 1));
  });

  const count = (row) => (row.photos ? `${row.photos.toLocaleString()} ${row.photos === 1 ? 'photo' : 'photos'}` : NOT_PHOTOGRAPHED);

  async function onKey(event, at) {
    const to = { ArrowDown: at + 1, ArrowUp: at - 1, Home: 0, End: rows.length - 1, PageDown: at + 10, PageUp: at - 10 }[event.key];
    if (to === undefined) return;
    event.preventDefault();
    focusAt = Math.max(0, Math.min(rows.length - 1, to));
    await tick();
    const link = list?.querySelector(`[data-index="${focusAt}"]`);
    link?.focus();
    link?.scrollIntoView?.({ block: 'nearest' });
  }
</script>

<div class="flist" role="list" aria-label="Species" bind:this={list} style="height: {rows.length * ROW}px">
  {#each shown as i (rows[i].id)}
    {@const row = rows[i]}
    <div class="fitem" class:in={entering} role="listitem" aria-setsize={rows.length} aria-posinset={i + 1}
         style="top: {i * ROW}px; --i: {Math.min(Math.max(0, i - range.start), 12)}">
      <a class="frow" href={speciesHref(row.id, filter)} data-index={i} tabindex={i === focusAt ? 0 : -1} style={famStyle(row.family)}
         onkeydown={(e) => onKey(e, i)} onfocus={() => (focusAt = i)}>
        <span class="dotf"></span>
        <span class="tx">
          <i class="bn">{row.name}</i>
          {#if row.common}<span class="cn">{row.common}</span>{/if}
        </span>
        {#if row.source === 'manual'}<span class="badge custom">Custom</span>{/if}
        <span class="ct">
          <span class:muted={!row.photos}>{count(row)}</span>
          {#if row.missing?.length}<span class="miss">Missing: {row.missing.join(', ')}</span>{/if}
        </span>
      </a>
    </div>
  {/each}
</div>

<style>
  .flist { position: relative; }
  .fitem { position: absolute; left: 0; right: 0; height: 64px; }
  .fitem.in { animation: rise .55s cubic-bezier(.2,.75,.2,1) backwards; animation-delay: calc(var(--i, 0) * 45ms); }
  .frow { display: flex; align-items: center; gap: 12px; height: 58px; padding: 0 12px; border-radius: 14px; color: inherit; text-decoration: none;
    border: 1px solid transparent; transition: background .2s, border-color .2s; }
  .frow:hover, .frow:focus-visible { background: var(--surface); border-color: var(--line); }
  .dotf { width: 12px; height: 12px; border-radius: 50%; background: var(--fam, var(--accent)); flex: 0 0 auto; }
  .badge { flex: 0 0 auto; }
  .tx { flex: 1; min-width: 0; display: grid; }
  .tx > * { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .bn { font: italic 500 17px/1.2 var(--latin); }
  .cn { font-size: 13px; color: var(--muted); }
  .ct { display: grid; justify-items: end; font-size: 13px; font-weight: 600; white-space: nowrap; font-variant-numeric: tabular-nums; }
  .miss { font: 500 11px var(--mono); color: var(--muted); }
</style>
