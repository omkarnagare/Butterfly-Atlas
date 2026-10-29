<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import { filterCount, filterPlaces, filterSpecies, getTags } from './api.js';
  import FilterRow from './FilterRow.svelte';
  import Presets from './Presets.svelte';
  import CountLine from './CountLine.svelte';
  import TaxaPopover from './TaxaPopover.svelte';
  import TagsPopover from './TagsPopover.svelte';
  import PlacePopover from './PlacePopover.svelte';
  import ChoicePopover from './ChoicePopover.svelte';
  import SpeciesResults from './SpeciesResults.svelte';
  import PhotoResults from './PhotoResults.svelte';
  import PlaceResults from './PlaceResults.svelte';
  import ExportViewer from './ExportViewer.svelte';
  import FileTypePopover from './FileTypePopover.svelte';
  import { SOURCE_OPTIONS, mergeNames, parseFilter, placeNoteLines, printFilter } from './filter.js';
  import { liveCount } from './liveCount.js';
  import { PhotoPages } from './photoPages.svelte.js';
  import { LIBRARY_CHANGED } from './status.svelte.js';
  import { loadProblem } from './problems.js';
  import { closeViewerTo } from './viewer.js';
  import { showToast } from './toast.svelte.js';
  import { NOT_PHOTOGRAPHED, PHOTOGRAPHED, PHOTO_STATUS } from './messages.js';

  /**
   * A filter and its results (Phase 4a contract §4; moved out of the Export screen for Explore, filters-in-Explore
   * design §5): the filter row and its popovers, the presets, the live count line, and the Species · Photos · Places
   * views of the result, with the screen's own `actions` at the right of the view bar. The address holds the filter
   * (`query`, as printed by lib/filter.js) and the view: every change of the filter is a new address (a popover's
   * changes while it is open count as one), so Back and Forward step through filters and a reload restores one. The
   * screen says how they are written: `resultsHref(query, view)` and `photoHref(photoId, query)`. The count answer's
   * `filter.query` replaces the address when the server dropped something (a place deleted since); the places it
   * changed (merged into another, or gone: Phase 5c) are said in lines under the count. With `photoId` the viewer opens
   * over the Photos view's loaded photos. `delay` is the wait after a change before asking (200 ms).
   *
   * The component's own `filter` is the one everything shows and every change starts from: a change sets it at once and
   * then writes the address, which comes back a moment later; a different address (Back, Forward, the count's rewrite)
   * sets it in turn. So a second change made before the address is back starts from the first.
   *
   * `count`, `pending`, `countError` and `printed` (the filter as printed) are bound by the screen for its own actions.
   * `editable` (Explore) lets the viewer edit the photos: after an edit the results and the count are read again, and a
   * photo the edit took out of the filter (or moved, or deleted) gives way to the next one, chosen before the reload.
   */
  let {
    query: queryProp, view: viewProp = 'species', photoId: photoProp = null, delay = 200, resultsHref, photoHref, actions, editable = false,
    count = $bindable(null), pending = $bindable(true), countError = $bindable(''), printed = $bindable(''),
  } = $props();

  // The props come from the route, which is a new object on every change of the address: as deriveds, an unchanged
  // filter or view does not count as a change (opening the viewer must not load everything again).
  let address = $derived(queryProp);
  let view = $derived(viewProp);
  let photoId = $derived(photoProp);

  const STATUS = [{ value: 'all', label: 'All' }, { value: 'photographed', label: PHOTOGRAPHED }, { value: 'not', label: NOT_PHOTOGRAPHED }];
  const VIEWS = [{ key: 'species', label: 'Species' }, { key: 'photos', label: 'Photos' }, { key: 'places', label: 'Places' }];
  const wait = untrack(() => delay);

  let filter = $state(parseFilter(untrack(() => queryProp)));
  let query = $derived(printFilter(filter));
  $effect(() => {
    printed = query;
  });
  let names = $state(mergeNames(undefined, {}));
  let tags = $state([]);
  // What the server changed in the filter's places (Phase 5c `placeNotes`: merged into a survivor, or gone), with the
  // filter they belong to (the one it used): they stay while the address is that filter, after its rewrite too.
  let placeNotes = $state([]);
  let notesFor = null;
  let noteLines = $derived(placeNoteLines(placeNotes));
  let manualSpecies = $derived(count?.manualSpecies ?? false);

  // Each view's rows with the query they answer, and the query the views were last asked for (`viewAsked`): rows that
  // answer another are not shown (their links would be wrong).
  let species = $state(null);
  let speciesFor = $state(null);
  let places = $state(null);
  let placesFor = $state(null);
  let viewAsked = $state(null);
  let viewError = $state('');
  const pages = new PhotoPages();

  let editing = $state(null);
  let opener = $state(null);
  let editPushed = false;

  /* ---------- the address ---------- */

  // An address with another filter than the screen's (Back, Forward, a link, the count's rewrite): show that one.
  $effect(() => {
    const fromAddress = address;
    untrack(() => {
      if (fromAddress !== printFilter(filter)) filter = parseFilter(fromAddress);
    });
  });

  /** A new filter: shown at once, then a new address, except for further changes in the popover that made the last one. */
  function go(nextFilter) {
    filter = nextFilter;
    const href = resultsHref(printFilter(nextFilter), view);
    if (editing && editPushed) window.location.replace(href);
    else window.location.hash = href;
    if (editing) editPushed = true;
  }

  function edit(nextFilter, learned = {}) {
    names = mergeNames(names, learned);
    go(nextFilter);
  }

  function openEditor(kind, from) {
    opener = from;
    editPushed = false;
    editing = kind;
  }

  const closeEditor = () => (editing = null);
  const back = () => (opener?.isConnected ? opener : document.querySelector('[data-add-filter]'));
  const showView = (key) => {
    if (key !== view) window.location.hash = resultsHref(query, key);
  };

  /* ---------- the count ---------- */

  const counter = liveCount(filterCount, {
    delay: wait,
    onAnswer(answer, asked) {
      count = answer;
      countError = '';
      pending = false;
      names = mergeNames(names, answer.filter ?? {});
      const used = answer.filter?.query;
      const notes = answer.filter?.placeNotes ?? [];
      if (notes.length) {
        placeNotes = notes;
        notesFor = used ?? asked;
      } else if (asked !== notesFor) {
        placeNotes = [];
        notesFor = null;
      }
      // The server dropped something it does not know (a place deleted since the address was made). The answers for
      // the address, here and on their way, are those of the filter it used: the screen takes that filter without
      // asking again (`adopted`, consumed by the query's effect).
      if (used != null && used !== asked && asked === query) {
        const taken = parseFilter(used);
        if (printFilter(taken) !== asked) {
          adopted = printFilter(taken);
          filter = taken;
        }
        window.location.replace(photoId !== null ? photoHref(photoId, used) : resultsHref(used, view));
      }
    },
    onError(e, asked) {
      countError = loadProblem(e);
      pending = false;
      // The place lines belong to the filter they came with: a failed count of another one takes them away.
      if (asked !== notesFor) {
        placeNotes = [];
        notesFor = null;
      }
    },
  });

  let adopted = null;

  /* ---------- the views ---------- */

  const speciesLive = liveCount(filterSpecies, {
    delay: wait,
    onAnswer: (rows, text) => { species = rows; speciesFor = text; viewError = ''; },
    onError: (e) => (viewError = loadProblem(e)),
  });
  const placesLive = liveCount(filterPlaces, {
    delay: wait,
    onAnswer: (rows, text) => { places = rows; placesFor = text; viewError = ''; },
    onError: (e) => {
      viewError = loadProblem(e);
      // The chosen row has left the page and no rows are coming: focus goes to Try again.
      if (placesFocus) tick().then(() => document.querySelector('.ex-problem button')?.focus());
      placesFocus = false;
    },
  });

  // Photos come in pages; a new filter starts again (after the same pause), and a page for an older one is dropped.
  // Under an open viewer the old photos stay until the first page comes, so the viewer is not torn down.
  let photoTimer = 0;
  const loadPhotos = (fromStart) => pages.load(query, fromStart, { keep: viewing });

  let shownView = untrack(() => view);

  function loadView(which) {
    // The view effect then skips a view already loaded (a filter and a view changed together load it once).
    if (which !== shownView) placesFocus = false;
    shownView = which;
    speciesLive.stop();
    placesLive.stop();
    clearTimeout(photoTimer);
    viewError = '';
    viewAsked = query;
    if (which === 'species') speciesLive.ask(query);
    else if (which === 'places') placesLive.ask(query);
    else {
      pages.forget();
      photoTimer = setTimeout(() => loadPhotos(true), wait);
    }
  }

  // A new filter: count it and load the view; but not the filter the count just rewrote the address to (the answers
  // for the old address are its own).
  $effect(() => {
    const now = query;
    untrack(() => {
      if (now === adopted) {
        adopted = null;
        return;
      }
      pending = true;
      counter.ask(now);
      loadView(view);
    });
  });

  // Another view: load it (the first, and one that comes with a new filter, are loaded by the query's effect).
  $effect(() => {
    const which = view;
    untrack(() => {
      if (which !== shownView) loadView(which);
    });
  });

  // In the viewer, Try again asks for the failed page again, unless it was refused as out of date: then the photos
  // start again and the photo's address loads on until it is there. A failed reload is loaded again in place.
  const retryViewerPage = () => (pages.reloadFailed ? pages.reload(query) : pages.stale ? loadPhotos(true) : loadPhotos(false));

  // A failed page starts the photos again (its cursor may be out of date); a failed reload is loaded again in place,
  // so the grid keeps its tiles and the page its scroll position.
  const morePhotos = () => (pages.reloadFailed ? pages.reload(query) : pages.error ? loadPhotos(true) : loadPhotos(false));

  // The pressed Try again leaves the page: focus goes to the photo in the tab order when there is one, else to the
  // view switch.
  const focusAfterRetry = () => tick().then(() => (document.querySelector('.fgrid [tabindex="0"]') ?? document.querySelector('.ex-bar .seg button.on'))?.focus());
  function retryView() {
    loadView(view);
    focusAfterRetry();
  }
  function retryPhotos() {
    morePhotos();
    focusAfterRetry();
  }

  // A Places row narrows the filter to it (its name known at once); when its rows come, focus goes to the first, or to
  // the view switch when there are none (the pressed row has left the page).
  let placesFocus = false;
  function choosePlace(row) {
    if (filter.places.length === 1 && filter.places[0] === row.id) return; // already the filter: nothing changes
    placesFocus = true;
    edit({ ...filter, places: [row.id] }, { places: [row] });
  }

  $effect(() => {
    if (placesFor !== viewAsked || view !== 'places') return;
    untrack(() => {
      if (!placesFocus) return;
      placesFocus = false;
      tick().then(() => {
        const target = document.querySelector('.fplaces button') ?? document.querySelector('.ex-bar .seg button.on');
        target?.focus();
      });
    });
  });

  /* ---------- the viewer ---------- */

  // While an edit's reload decides where the viewer goes, it keeps the photos it showed (`held`, for photo `heldFor`).
  let held = $state(null);
  let heldFor = null;
  let viewerPhotos = $derived(held ?? pages.photos);
  let viewing = $derived(photoId !== null && viewerPhotos.some((photo) => photo.id === photoId));

  // The viewer has moved on (or closed): it shows the results as they are.
  $effect(() => {
    const now = photoId;
    untrack(() => {
      if (held && now !== heldFor) held = null;
    });
  });

  /**
   * After an edit in the viewer: the results and the count are read again. A photo no longer in them (`left`: moved or
   * deleted, already said; else the edit took it out of the filter, said here) gives way to the next one, or the one
   * before when it was the last, or the viewer closes.
   */
  async function edited({ left = false } = {}) {
    const shown = photoId;
    const before = pages.photos;
    const at = before.findIndex((photo) => photo.id === shown);
    const order = [...before.slice(at + 1), ...before.slice(0, Math.max(0, at)).reverse()].map((photo) => photo.id);
    held = before;
    heldFor = shown;
    counter.ask(query);
    await pages.reload(query);
    const now = pages.photos;
    if (pages.reloadFailed || (!left && now.some((photo) => photo.id === shown))) {
      held = null;
      return;
    }
    if (!left) showToast('This photo no longer matches the filter.');
    const next = order.find((id) => now.some((photo) => photo.id === id));
    if (next !== undefined) window.location.replace(photoHref(next, query));
    else closeViewerTo(resultsHref(query, 'photos'));
  }

  // A photo not loaded yet: load on until it is there; one the results do not have shows the Photos view.
  $effect(() => {
    if (photoId === null || viewing || held || !pages.loaded || pages.loading || pages.error) return;
    untrack(() => {
      if (pages.next !== null) loadPhotos(false);
      else window.location.replace(resultsHref(query, 'photos'));
    });
  });

  /* ---------- the page ---------- */

  onMount(() => {
    getTags()
      .then((all) => {
        tags = all;
        names = mergeNames(names, { tags: all });
      })
      .catch(() => {});
    // The library changed: count again and load the view again; loaded photos are loaded again in place, so the grid
    // and the scroll position stay.
    const onChange = () => {
      counter.ask(query);
      if (view === 'photos' && shownView === 'photos' && pages.loaded) pages.reload(query);
      else loadView(view);
    };
    window.addEventListener(LIBRARY_CHANGED, onChange);
    return () => window.removeEventListener(LIBRARY_CHANGED, onChange);
  });

  onDestroy(() => {
    counter.stop();
    speciesLive.stop();
    placesLive.stop();
    clearTimeout(photoTimer);
    pages.stop();
  });
</script>

<section class="ex-filter" aria-label="Filter">
  <FilterRow {filter} {names} {manualSpecies} onChange={go} onEdit={openEditor} />
  <Presets {filter} onChange={go} />
  <CountLine {count} {pending} error={countError} />
  {#each noteLines as line (line)}<p class="ex-note">{line}</p>{/each}
</section>

<div class="ex-bar">
  <div class="seg" role="group" aria-label="View">
    {#each VIEWS as each (each.key)}
      <button type="button" class:on={view === each.key} aria-pressed={view === each.key} onclick={() => showView(each.key)}>{each.label}</button>
    {/each}
  </div>
  {@render actions?.()}
</div>

{#if viewError}
  <div class="ex-problem"><p class="problem" role="alert">{viewError}</p><button class="btn ghost" type="button" onclick={retryView}>Try again</button></div>
{:else if view === 'species'}
  {#if species === null || speciesFor !== viewAsked}<p class="muted loading" role="status">Opening…</p>{:else}<SpeciesResults rows={species} {filter} query={speciesFor} />{/if}
{:else if view === 'places'}
  {#if places === null || placesFor !== viewAsked}<p class="muted loading" role="status">Opening…</p>{:else}<PlaceResults rows={places} onChoose={choosePlace} />{/if}
{:else}
  {#if pages.loaded && !pages.photos.length && !pages.error}
    <p class="muted">No matching photos.</p>
  {:else}
    <PhotoResults photos={pages.photos} {query} {photoHref} hasMore={pages.next !== null} loading={pages.loading} error={pages.error} focusId={photoId} onMore={morePhotos} onRetry={retryPhotos} />
  {/if}
{/if}

{#if editing === 'taxa'}
  <TaxaPopover {filter} {names} anchor={opener} returnFocus={back} onChange={edit} onClose={closeEditor} />
{:else if editing === 'tags'}
  <TagsPopover {filter} {tags} anchor={opener} returnFocus={back} onChange={edit} onClose={closeEditor} />
{:else if editing === 'places'}
  <PlacePopover {filter} {names} anchor={opener} returnFocus={back} onChange={edit} onClose={closeEditor} />
{:else if editing === 'status'}
  <ChoicePopover title={PHOTO_STATUS} options={STATUS} value={filter.status} anchor={opener} returnFocus={back}
                 onPick={(status) => edit({ ...filter, status })} onClose={closeEditor} />
{:else if editing === 'source'}
  <ChoicePopover title="Source" options={SOURCE_OPTIONS} value={filter.source} anchor={opener} returnFocus={back}
                 onPick={(source) => edit({ ...filter, source })} onClose={closeEditor} />
{:else if editing === 'types'}
  <FileTypePopover {filter} anchor={opener} returnFocus={back} onChange={edit} onClose={closeEditor} />
{/if}

{#if viewing}
  <ExportViewer photos={viewerPhotos} {photoId} {query} {tags} {editable} onEdited={edited} {photoHref} total={count?.photos ?? pages.photos.length} hasMore={pages.next !== null} loading={pages.loading}
                error={pages.error} onMore={() => loadPhotos(false)} onRetry={retryViewerPage}
                returnFocus={(id) => document.querySelector(`.fgrid [data-photo-id="${id}"]`)} onClose={() => closeViewerTo(resultsHref(query, 'photos'))} />
{/if}

<style>
  .ex-filter { display: grid; gap: 12px; padding: 16px; border-radius: 20px; background: var(--surface); border: 1px solid var(--line); }
  .ex-problem { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
  .ex-note { margin: 0; font-size: 14px; }
  .ex-bar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin: 22px 0 14px; }
  /* inactive, but still shows its title on hover */
  .ex-bar :global(.btn[aria-disabled='true']) { pointer-events: auto; cursor: not-allowed; }
  .ex-bar :global(.btn[aria-disabled='true']:hover) { transform: none; box-shadow: none; }
</style>
