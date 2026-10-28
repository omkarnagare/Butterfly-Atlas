<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import { filterCount, filterPlaces, filterSpecies, getExports, getTags } from '../lib/api.js';
  import FilterRow from '../lib/FilterRow.svelte';
  import Presets from '../lib/Presets.svelte';
  import CountLine from '../lib/CountLine.svelte';
  import TaxaPopover from '../lib/TaxaPopover.svelte';
  import TagsPopover from '../lib/TagsPopover.svelte';
  import PlacePopover from '../lib/PlacePopover.svelte';
  import ChoicePopover from '../lib/ChoicePopover.svelte';
  import SpeciesResults from '../lib/SpeciesResults.svelte';
  import PhotoResults from '../lib/PhotoResults.svelte';
  import PlaceResults from '../lib/PlaceResults.svelte';
  import ExportViewer from '../lib/ExportViewer.svelte';
  import ExportSheet from '../lib/ExportSheet.svelte';
  import ExportProgress from '../lib/ExportProgress.svelte';
  import PastExports from '../lib/PastExports.svelte';
  import StopExportDialog from '../lib/StopExportDialog.svelte';
  import FileTypePopover from '../lib/FileTypePopover.svelte';
  import { SOURCE_OPTIONS, mergeNames, parseFilter, placeNoteLines, printFilter } from '../lib/filter.js';
  import { liveCount } from '../lib/liveCount.js';
  import { PhotoPages } from '../lib/photoPages.svelte.js';
  import { exportHref, exportPhotoHref } from '../lib/route.js';
  import { EXPORT_CHANGED, LIBRARY_CHANGED, refreshStatus, status } from '../lib/status.svelte.js';
  import { loadProblem } from '../lib/problems.js';
  import { showToast } from '../lib/toast.svelte.js';
  import { closeViewerTo } from '../lib/viewer.js';
  import { DONE } from '../lib/states.js';
  import { NOTHING_MATCHES, NOT_PHOTOGRAPHED, PHOTOGRAPHED, PHOTO_STATUS } from '../lib/messages.js';

  /**
   * The Export screen (#/export?<filter>&view=, Phase 4a contract §4): the filter row and its popovers, the presets,
   * the live count line, and the Species · Photos · Places views of the result, with the Export… button. The address
   * holds the filter (`query`, as printed by lib/filter.js) and the view: every change of the filter is a new address
   * (a popover's changes while it is open count as one), so Back and Forward step through filters and a reload
   * restores one. The count answer's `filter.query` replaces the address when the server dropped
   * something (a place deleted since); the places it changed (merged into another, or gone: Phase 5c) are said in lines
   * under the count, read-only like the rest of the screen. With `photoId` (#/export/photo/<id>?<filter>) the viewer opens over the
   * Photos view's loaded photos. `delay` is the wait after a change before asking (200 ms).
   *
   * The screen's own `filter` is the one everything shows and every change starts from: a change sets it at once and
   * then writes the address, which comes back a moment later; a different address (Back, Forward, the count's rewrite)
   * sets it in turn. So a second change made before the address is back starts from the first.
   *
   * Exports (Phase 4b contract E1–E4, §5): Export… opens the Export sheet for the filter; it is inactive while an
   * export is running or paused ("Finish or cancel the export in progress first.") and when nothing matches. The
   * running or paused export's progress card is at the top, Past exports under it, the filter and views below. After a
   * start focus goes to the card (a species list alone: to its Past exports entry, with a toast); after a stop, or when
   * the export finishes while its card has focus, to its entry. The footer and Home are told whenever this screen sees
   * the export change, and the screen reads the exports again whenever the status shows another current export. A read
   * asked for before the screen's own change (a start, a removal) is not shown: the exports are read again.
   */
  let { query: queryProp, view: viewProp = 'species', photoId: photoProp = null, delay = 200 } = $props();

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
  let names = $state(mergeNames(undefined, {}));
  let tags = $state([]);
  let count = $state(null);
  let countError = $state('');
  let pending = $state(true);
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

  // The running or paused export and the past ones (GET /api/exports); until they are read, the status says whether
  // there is a current one.
  let current = $state(null);
  let past = $state([]);
  let listed = $state(false);
  let sheetOpen = $state(false);
  let stopping = $state(null);
  let stopBusy = $state(false);
  let exportButton = $state(null);
  let cardBox = $state(null);
  let progress = $state(null);
  let focusAfter = null;

  let busyExport = $derived(listed ? current !== null : status.export !== null);
  let counted = $derived(!pending && !countError && count !== null);
  let nothing = $derived(counted && !count.photos && !count.species);
  let canExport = $derived(!busyExport && counted && !nothing);
  // Why Export… is inactive: its title, and (for screen readers) its description.
  let exportReason = $derived(busyExport ? 'Finish or cancel the export in progress first.'
    : nothing ? NOTHING_MATCHES : !counted ? countError || 'Counting…' : '');

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
    const href = exportHref(printFilter(nextFilter), view);
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
    if (key !== view) window.location.hash = exportHref(query, key);
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
        window.location.replace(photoId !== null ? exportPhotoHref(photoId, used) : exportHref(used, view));
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

  let viewing = $derived(photoId !== null && pages.photos.some((photo) => photo.id === photoId));

  // A photo not loaded yet: load on until it is there; one the results do not have shows the Photos view.
  $effect(() => {
    if (photoId === null || viewing || !pages.loaded || pages.loading || pages.error) return;
    untrack(() => {
      if (pages.next !== null) loadPhotos(false);
      else window.location.replace(exportHref(query, 'photos'));
    });
  });

  /* ---------- exports ---------- */

  const entry = (exportId) => document.querySelector(`[data-export-id="${exportId}"]`);

  /** A new list of exports: shown, and the footer and Home are told when the current export changed. */
  async function showExports(list) {
    const before = current;
    const at = document.activeElement;
    // In the card, or fallen from it to the page (the button that had it went away).
    const cardFocused = Boolean(cardBox?.contains(at)) || ((!at || at === document.body) && cardHadFocus);
    current = list.current ?? null;
    past = list.past ?? [];
    listed = true;
    if ((current?.id ?? null) !== (status.export?.id ?? null) || (current?.state ?? null) !== (status.export?.state ?? null)) refreshStatus();
    // Finished (or stopped elsewhere) while its card had focus: focus goes to its entry, else to Export….
    if (before && !current && cardFocused) {
      await tick();
      (entry(before.id) ?? exportButton)?.focus();
    }
  }

  // The export the stop dialog is about has ended meanwhile (and the dialog is not stopping it: its answer closes it):
  // the dialog closes, and focus goes to its entry.
  $effect(() => {
    const asked = stopping;
    if (!asked || stopBusy || !listed || current?.id === asked.id) return;
    untrack(() => {
      focusAfter = () => entry(asked.id) ?? exportButton;
      stopping = null;
    });
  });

  // Whether focus was last in the card: focus that falls to the page sends no event, so this stays as it was, until
  // focus goes elsewhere or the page is clicked outside the card (a click on the background moves no focus either).
  let cardHadFocus = false;
  const noteFocus = (event) => (cardHadFocus = Boolean(cardBox?.contains(event.target)));
  const notePointer = (event) => {
    if (!cardBox?.contains(event.target)) cardHadFocus = false;
  };

  // Only the newest read is shown (`listSeq`); `reading` says one is on its way.
  let listSeq = 0;
  let reading = false;
  async function loadExports() {
    const mine = ++listSeq;
    reading = true;
    try {
      const list = await getExports();
      if (mine === listSeq) showExports(list);
    } catch {
      // the status still says whether one is running
    } finally {
      if (mine === listSeq) reading = false;
    }
  }

  // The screen changed the exports itself (`changeSeq`): a read on its way was asked for before, so it is asked again;
  // a poll of the card's asked for before is dropped (the card's next poll comes a second later).
  let changeSeq = 0;
  function changedHere() {
    changeSeq += 1;
    if (reading) loadExports();
  }

  async function readFromCard() {
    const asked = changeSeq;
    const list = await getExports();
    return asked === changeSeq ? list : null;
  }

  // The status (the footer's polls) shows another current export, or another state, than this screen: an export
  // started or paused itself while the screen did not know. Read the exports again, once for each new thing the status
  // says (what it said when the screen opened is not news: the screen reads the exports itself then).
  const exportKey = (run) => `${run?.id ?? ''}:${run?.state ?? ''}`;
  let followed = exportKey(untrack(() => status.export));
  $effect(() => {
    const said = exportKey(status.export);
    if (!listed) return;
    untrack(() => {
      if (said === followed) return;
      followed = said;
      // The read EXPORT_CHANGED asked for is still on its way: it was asked after this change, and will show it.
      if (reading && listSeq === changeRead) return;
      if (said !== exportKey(current)) loadExports();
    });
  });

  const fromCard = (list) => {
    listSeq += 1;
    reading = false;
    showExports(list);
  };

  // This page changed the export (the card, the footer, Home): read the exports once. What the status now says is
  // then not news for the effect above, nor is what it says while that read is on its way (`changeRead`; after a
  // refusal, exportGone, the status learns the change only when it is asked again).
  let changeRead = 0;
  const onExportChanged = () => {
    followed = exportKey(status.export);
    loadExports();
    changeRead = listSeq;
  };

  function openSheet() {
    if (!canExport) return;
    focusAfter = null;
    sheetOpen = true;
  }

  // The card or the entry is in the page before the sheet closes, so its focus can go there. The status follows a
  // started export at once (as exportChanged does, without EXPORT_CHANGED), so one that is over before the status
  // answers is still said as finished.
  async function started(run) {
    if (run.state === DONE) {
      past = [run, ...past.filter((r) => r.id !== run.id)];
      focusAfter = () => entry(run.id);
      showToast('Species list saved.');
    } else {
      current = run;
      focusAfter = () => progress?.cardElement() ?? null;
      // The finished notice counts the RAW and HEIC photos that went out with their location (7b review fix U9).
      const { id, state, done, total, pausedBy = null, notice = null, path, rawCount = 0, heicCount = 0 } = run;
      status.export = { id, state, done, total, pausedBy, notice, path, rawCount, heicCount };
      refreshStatus();
    }
    changedHere();
    await tick();
    sheetOpen = false;
  }

  // The stop's answer has already sent EXPORT_CHANGED, so the newest read was asked for after it.
  async function stopped(run) {
    current = null;
    past = [run, ...past.filter((r) => r.id !== run.id)];
    focusAfter = () => entry(run.id);
    await tick();
    stopping = null;
  }

  function removed(exportId) {
    past = past.filter((r) => r.id !== exportId);
    changedHere();
  }

  function cleared() {
    past = [];
    changedHere();
  }

  // Go back: to Cancel… (WebKit does not focus a button that is clicked).
  const cancelButton = () => progress?.cancelElement() ?? null;

  function askStop() {
    focusAfter = null;
    stopping = current;
  }

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
    loadExports();
    window.addEventListener(EXPORT_CHANGED, onExportChanged);
    window.addEventListener('focusin', noteFocus);
    window.addEventListener('pointerdown', notePointer);
    return () => {
      window.removeEventListener('pointerdown', notePointer);
      window.removeEventListener('focusin', noteFocus);
      window.removeEventListener(LIBRARY_CHANGED, onChange);
      window.removeEventListener(EXPORT_CHANGED, onExportChanged);
    };
  });

  onDestroy(() => {
    counter.stop();
    speciesLive.stop();
    placesLive.stop();
    clearTimeout(photoTimer);
    pages.stop();
    listSeq += 1;
  });
</script>

<h1 class="ex-h">Export</h1>
{#if current}
  <div bind:this={cardBox}>
    <ExportProgress bind:this={progress} run={current} onRun={(run) => (current = run)} onList={fromCard} read={readFromCard}
                    onCancel={askStop} />
  </div>
{/if}
<PastExports {past} onRemoved={removed} onCleared={cleared} fallbackFocus={() => exportButton} />
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
  <button class="btn" type="button" bind:this={exportButton} aria-disabled={!canExport} title={exportReason || undefined}
          aria-describedby={exportReason ? 'ex-export-why' : undefined} onclick={openSheet}>Export…</button>
  <span class="sr-only" id="ex-export-why">{exportReason}</span>
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
    <PhotoResults photos={pages.photos} {query} hasMore={pages.next !== null} loading={pages.loading} error={pages.error} focusId={photoId} onMore={morePhotos} onRetry={retryPhotos} />
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

{#if sheetOpen}
  <ExportSheet {query} {count} returnFocus={() => focusAfter?.() ?? exportButton} onStarted={started} onClose={() => (sheetOpen = false)} />
{/if}
{#if stopping}
  <!-- the count follows the card's; refused as finished, EXPORT_CHANGED reads the exports and the effect above closes it -->
  <StopExportDialog run={current?.id === stopping.id ? current : stopping} bind:busy={stopBusy} returnFocus={() => focusAfter?.() ?? cancelButton()}
                    onStopped={stopped} onGone={() => {}} onClose={() => (stopping = null)} />
{/if}

{#if viewing}
  <ExportViewer photos={pages.photos} {photoId} {query} {tags} total={count?.photos ?? pages.photos.length} hasMore={pages.next !== null} loading={pages.loading}
                error={pages.error} onMore={() => loadPhotos(false)} onRetry={retryViewerPage}
                returnFocus={(id) => document.querySelector(`.fgrid [data-photo-id="${id}"]`)} onClose={() => closeViewerTo(exportHref(query, 'photos'))} />
{/if}

<style>
  .ex-h { font-size: clamp(30px, 5vw, 46px); font-weight: 720; line-height: 1.05; margin-bottom: 18px; }
  .ex-filter { display: grid; gap: 12px; padding: 16px; border-radius: 20px; background: var(--surface); border: 1px solid var(--line); }
  .ex-problem { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
  .ex-note { margin: 0; font-size: 14px; }
  .ex-bar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 12px; margin: 22px 0 14px; }
  /* inactive, but still shows its title on hover */
  .ex-bar .btn[aria-disabled='true'] { pointer-events: auto; cursor: not-allowed; }
  .ex-bar .btn[aria-disabled='true']:hover { transform: none; box-shadow: none; }
</style>
