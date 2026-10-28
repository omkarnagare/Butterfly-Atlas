<script>
  import { photoCount } from '../lib/importView.js';
  import { tick } from 'svelte';
  import { getSplit, getTags, sortSplit } from '../lib/api.js';
  import SplitGrid from '../lib/SplitGrid.svelte';
  import SplitViewer from '../lib/SplitViewer.svelte';
  import { select } from '../lib/selection.js';
  import { anyModalOpen } from '../lib/modal.js';
  import { rememberRect } from '../lib/flip.js';
  import { closeViewerTo, noteViewerOpen } from '../lib/viewer.js';
  import { famStyle } from '../lib/families.js';
  import { splitHref, splitPhotoHref } from '../lib/route.js';
  import { LIBRARY_CHANGED, heicBarOn, refreshStatus } from '../lib/status.svelte.js';
  import HeicBar from '../lib/HeicBar.svelte';
  import { isHeic } from '../lib/heic.js';
  import LoadError from '../lib/LoadError.svelte';
  import { loadProblem } from '../lib/problems.js';
  import { showProblem, showToast } from '../lib/toast.svelte.js';

  /**
   * Sorting a split species' photos (#/split/<id>, Phase 5b contract §5, §6, design §4.4): its header with "28 of 40
   * left to sort", the photos still to sort in a grid (lib/SplitGrid.svelte) selected as in an import (click, Shift,
   * Ctrl/⌘, A for all, Escape to clear, arrows; Space opens a photo large, Enter adds or removes it), and the target
   * bar: each new species (scientific and common name, keys 1–9) sorts the selection to it, and Leave for later goes to
   * the species' page. The sorted tiles glide out and the rest close up (none of it under reduced motion), with a toast
   * "3 photos moved to Euploea coreta."; when none is left, "All sorted." with a link to each new species. A refusal
   * (409 while the taxonomy updates, 400) shows its message. With `photoId` the photo is shown large over the page
   * (lib/SplitViewer.svelte), where it can be sorted too.
   */
  let { id, photoId = null } = $props();

  let split = $state(null);
  let tags = $state([]);
  let error = $state('');
  let problem = $state('');
  let busy = $state(false);
  let sel = $state({ selected: new Set(), anchor: null, base: new Set() });
  let focusId = $state(null);
  let grid = $state(null);
  let done = $state(null);

  let photos = $derived(split?.photos ?? []);
  let order = $derived(photos.map((p) => p.id));
  let chosen = $derived(order.filter((each) => sel.selected.has(each)));
  let targets = $derived(split?.targets ?? []);

  const clear = () => (sel = { selected: new Set(), anchor: null, base: new Set() });

  // The species, as a value: opening and closing a photo changes the address (and the route object), not the species,
  // so the page is not read again (and the grid not rebuilt, which would lose the tile focus goes back to).
  let splitId = $derived(id);
  // One counter for every answer that sets the split (the first load, a reload, a sort): an older answer that arrives
  // after a newer one is dropped, so a reload asked before a sort cannot bring its sorted photos back.
  let loads = 0;
  $effect(() => {
    const wanted = splitId;
    const mine = ++loads;
    let cancelled = false;
    split = null;
    error = '';
    problem = '';
    clear();
    Promise.all([getSplit(wanted), getTags().catch(() => [])])
      .then(([s, g]) => {
        if (cancelled || mine !== loads) return;
        split = s;
        tags = g;
      })
      .catch((e) => {
        if (!cancelled && mine === loads) error = loadProblem(e);
      });
    return () => {
      cancelled = true;
    };
  });

  async function reload() {
    const wanted = id;
    const mine = ++loads;
    try {
      const s = await getSplit(wanted);
      if (wanted !== id || mine !== loads) return;
      split = s;
      error = '';
      const live = new Set(s.photos.map((p) => p.id));
      sel = { selected: new Set([...sel.selected].filter((each) => live.has(each))), anchor: null, base: new Set() };
    } catch (e) {
      if (mine !== loads) return;
      if (error) error = loadProblem(e);
      else showProblem(e);
    }
  }

  $effect(() => {
    const onChange = () => reload();
    window.addEventListener(LIBRARY_CHANGED, onChange);
    return () => window.removeEventListener(LIBRARY_CHANGED, onChange);
  });


  /**
   * Sorts `ids` to `target`: the tiles fade out (`glide`; not for a sort from the viewer, whose tiles are hidden behind
   * it), the rest close up; focus stays where it was, or goes to the next tile. `beforeApply(answer)` runs just before
   * the page takes the answer.
   */
  async function sortTo(ids, target, { glide = true, beforeApply = () => {} } = {}) {
    if (busy || !ids.length) return false;
    busy = true;
    problem = '';
    let answer;
    try {
      answer = await sortSplit(id, ids, target.id);
    } catch (e) {
      problem = e.message;
      busy = false;
      if (e?.code === 'invalid') reload();
      return false;
    }
    const lostFocus = () => !document.activeElement || document.activeElement === document.body;
    const firstAt = Math.max(0, order.indexOf(ids[0]));
    if (glide) await grid?.glideOut(ids);
    const before = glide ? grid?.measureTiles() : null;
    beforeApply(answer);
    loads += 1;
    split = answer;
    clear();
    busy = false;
    if (!answer.photos.length) done = target;
    await tick();
    if (before) grid?.playTiles(before);
    showToast(`${photoCount(ids.length)} moved to ${target.name}.`);
    refreshStatus();
    if (answer.photos.length && lostFocus()) grid?.focusItem(answer.photos[Math.min(firstAt, answer.photos.length - 1)].id);
    else if (!answer.photos.length && lostFocus()) document.querySelector('.split-done h2')?.focus();
    return true;
  }

  const sortSelected = (target) => sortTo(chosen, target);

  // The viewer's own sort: on to the next photo still to sort, or back to the grid when none is left; not when the
  // viewer was closed (or moved on) while the sort was asked. Until the address names the next photo, the viewer shows
  // it already (`moving`), so it neither closes nor shows another photo in between.
  let moving = $state(null);
  async function sortShown(photo, target) {
    const at = order.indexOf(photo.id);
    const rest = order.filter((each) => each !== photo.id);
    const next = rest[at] ?? rest[0] ?? null;
    const shown = splitPhotoHref(id, photo.id);
    await sortTo([photo.id], target, {
      glide: false,
      beforeApply: (answer) => {
        if (window.location.hash !== shown) return;
        const to = next != null && answer.photos.some((p) => p.id === next) ? next : null;
        moving = { from: photo.id, to };
        if (to != null) window.location.replace(splitPhotoHref(id, to));
        else closeViewerTo(splitHref(id));
      },
    });
  }
  $effect(() => {
    if (moving && photoId !== moving.from) moving = null;
  });

  function onTileClick(photo, event) {
    focusId = photo.id;
    sel = select(sel, order, photo.id, { shift: event.shiftKey, toggle: event.ctrlKey || event.metaKey });
  }

  function onToggle(photo, shift) {
    sel = select(sel, order, photo.id, shift ? { shift: true } : { toggle: true });
  }

  function onMove(next, shift) {
    if (shift) {
      const from = sel.anchor ?? focusId;
      sel = select(sel.anchor == null ? { ...sel, anchor: from, base: new Set(sel.selected) } : sel, order, next, { shift: true });
    }
    focusId = next;
    grid?.focusItem(next);
  }

  function open(photo, tile) {
    focusId = photo.id;
    rememberRect(tile?.querySelector('img'));
    noteViewerOpen();
    window.location.hash = splitPhotoHref(id, photo.id);
  }

  function selectAll() {
    sel = { selected: new Set(order), anchor: sel.anchor, base: new Set(order) };
  }

  function onKey(event) {
    // A held key acts once: a repeated 1–9 never sorts the next selection.
    if (!split || anyModalOpen() || event.repeat || event.isComposing || event.metaKey || event.ctrlKey || event.altKey) return;
    if (/^(INPUT|TEXTAREA|SELECT)$/.test(event.target?.tagName ?? '') || event.target?.isContentEditable) return;
    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key;
    if (key === 'a' && photos.length) {
      event.preventDefault();
      selectAll();
    } else if (key === 'Escape' && sel.selected.size) {
      event.preventDefault();
      clear();
    } else if (/^[1-9]$/.test(key) && Number(key) <= targets.length && chosen.length) {
      event.preventDefault();
      sortSelected(targets[Number(key) - 1]);
    }
  }

  let shownId = $derived(moving && photoId === moving.from ? moving.to : photoId);
  let viewing = $derived(split !== null && shownId !== null && photos.some((p) => p.id === shownId));
  // An address naming a photo that is not to sort (sorted already, or another species') shows the grid.
  $effect(() => {
    if (split !== null && photoId !== null && !(moving && photoId === moving.from) && !photos.some((p) => p.id === photoId)) {
      window.location.replace(splitHref(id));
    }
  });
  const tileFor = (photo) => document.querySelector(`.sg [data-key="${photo}"]`);
</script>

<svelte:window onkeydown={onKey} />

{#if error}
  <LoadError message={error} onRetry={reload} />
{:else if split}
  {@const taxon = split.taxon}
  <div class="split" style={famStyle(taxon.family)}>
    <div class="xh">
      <div>
        <div class="eyebrow">Split species{taxon.family ? ` · ${taxon.family}` : ''}</div>
        <h1 class="bn" tabindex="-1">{taxon.name}</h1>
        {#if taxon.common}<div class="cn">{taxon.common}</div>{/if}
        {#if targets.length}<p class="split-why">
          The {taxon.version} list splits it into {#each targets as target, k (target.id)}{k ? (k === targets.length - 1 ? ' and ' : ', ') : ''}<i class="bn">{target.name}</i>{/each}.
          Choose which one each photo shows.
        </p>{/if}
      </div>
      <p class="split-left" aria-live="polite"><b>{split.left.toLocaleString()} of {split.total.toLocaleString()}</b> left to sort</p>
    </div>

    {#if split.missingTargets?.length && photos.length}
      <!-- targets no longer on the list (contract §7): said, with what to do -->
      <div class="problem split-missing" role="note">
        <p>
          Some species this was split into are no longer on the list ({#each split.missingTargets as gone, k (gone.id)}{k ? ', ' : ''}<i class="bn">{gone.name}</i>{/each}).
          Use Move on the species page for those photos.
        </p>
        {#if !targets.length}<a class="btn" href="#/species/{encodeURIComponent(taxon.id)}">Open the species page</a>{/if}
      </div>
    {/if}
    {#if photos.length}
      {#if targets.length}
      <div class="split-bar" role="toolbar" aria-label="Sort the selected photos to">
        <span class="split-count" role="status">{chosen.length ? `${photoCount(chosen.length)} selected` : 'Select photos, then choose their species.'}</span>
        <button class="btn ghost" type="button" aria-keyshortcuts="A" onclick={selectAll}>Select all</button>
        {#each targets as target, k (target.id)}
          <button class="btn split-target" type="button" style={famStyle(target.family)} aria-disabled={busy || !chosen.length}
                  aria-keyshortcuts={k < 9 ? String(k + 1) : undefined} onclick={() => { if (!busy && chosen.length) sortSelected(target); }}>
            {#if k < 9}<kbd>{k + 1}</kbd>{/if}<i class="bn">{target.name}</i>{#if target.common}<span>{target.common}</span>{/if}
          </button>
        {/each}
        <a class="btn ghost" href="#/species/{encodeURIComponent(taxon.id)}">Leave for later</a>
      </div>
      {/if}
      {#if problem}<p class="problem" role="alert">{problem}</p>{/if}
      <!-- 7b review fix U8: HEIC photos to sort this computer can't show are said, with How and Check again. -->
      {#if heicBarOn() && photos.some(isHeic)}<HeicBar />{/if}
      <SplitGrid bind:this={grid} {photos} selected={sel.selected} {focusId} label="Photos of {taxon.name} to sort"
                 {onTileClick} {onMove} onOpen={open} {onToggle} onFocusTile={(each) => (focusId = each)} />
      <p class="muted keys">Click, Shift-click or Ctrl/⌘-click to select · A all · Space open{targets.length ? ` · 1–${Math.min(9, targets.length)} sort` : ''} · Esc clear</p>
    {:else}
      <div class="emptyp split-done">
        <h2 tabindex="-1">All sorted.</h2>
        <p>{done ? `The last photos went to ${done.name}.` : `Every photo of ${taxon.name} has its species.`}</p>
        <p class="split-links">
          {#each targets as target (target.id)}
            <a class="btn ghost" href="#/species/{encodeURIComponent(target.id)}">Open <i class="bn">{target.name}</i></a>
          {/each}
        </p>
      </div>
    {/if}
    {#if viewing}
      <SplitViewer split={taxon} {targets} {photos} photoId={shownId} {tags} {busy} error={problem} onSort={sortShown}
                   returnFocus={tileFor} onClose={() => closeViewerTo(splitHref(id))} />
    {/if}
  </div>
{:else}
  <p class="muted loading" role="status">Opening…</p>
{/if}
