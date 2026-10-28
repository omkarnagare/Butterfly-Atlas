<script>
  import { tick, untrack } from 'svelte';
  import { getImport, getImportItems, getTags } from '../lib/api.js';
  import ImportResult from '../lib/ImportResult.svelte';
  import ImportFinishDialog from '../lib/ImportFinishDialog.svelte';
  import Celebration from '../lib/Celebration.svelte';
  import ImportGrid from '../lib/ImportGrid.svelte';
  import ImportFiling from '../lib/ImportFiling.svelte';
  import FolderBrowser from '../lib/FolderBrowser.svelte';
  import ImportViewer from '../lib/ImportViewer.svelte';
  import ImportNotices from '../lib/ImportNotices.svelte';
  import ImportPairs from '../lib/ImportPairs.svelte';
  import ImportTools from '../lib/ImportTools.svelte';
  import ImportHeader from '../lib/ImportHeader.svelte';
  import ImportSelectionBar from '../lib/ImportSelectionBar.svelte';
  import SpeciesPicker from '../lib/SpeciesPicker.svelte';
  import BulkTagDialog from '../lib/BulkTagDialog.svelte';
  import PlacePicker from '../lib/PlacePicker.svelte';
  import { emptyItems, mergePage } from '../lib/importItems.js';
  import { pollImport } from '../lib/importPoll.js';
  import { importSync } from '../lib/importSync.svelte.js';
  import { importFinish } from '../lib/importFinish.svelte.js';
  import { importFolder } from '../lib/importFolder.svelte.js';
  import { importEdits } from '../lib/importEdits.svelte.js';
  import { importSelect } from '../lib/importSelect.svelte.js';
  import {
    applyFilter, editable, fileable, filesToFile, filterCounts, folderSections, followed, inLibrary, keepPlace, photoCount, shots, speciesSections,
  } from '../lib/importView.js';
  import { pruneTo } from '../lib/selection.js';
  import { importHref, importPhotoHref } from '../lib/route.js';
  import { closeViewerTo, noteViewerOpen } from '../lib/viewer.js';
  import { CONTINUE_IMPORT, HEIC_CHANGED, heicBarOn, setImportPreviews, status as appStatus } from '../lib/status.svelte.js';
  import HeicBar from '../lib/HeicBar.svelte';
  import { heicMissing, importBarLine } from '../lib/heic.js';
  import { IMPORT_OPEN } from '../lib/states.js';
  import { WAIT_FOR_FILING } from '../lib/messages.js';

  /**
   * One import (#/import/<id>, design §4.3): the folder's photos in a virtualised grid, by subfolder and
   * burst or by species, with filter chips and counts. Select photos (click, Shift, Ctrl/Cmd, A, a section or
   * a burst; arrows and Space) and assign a species, tag, place (or take the place off), skip or clear them; accept a folder's suggestions;
   * skip what is already in the library. Every change is saved at once. While the import is looking through,
   * reading or filing its folder, the items that changed are asked for about once a second.
   *
   * "File N assigned photos" files in the background, with progress and Pause. A missing folder can be looked
   * for again or chosen where it moved. Reading cut short by the app closing carries on when the import is
   * opened; filing cut short offers "Continue filing" (contract §3).
   *
   * When filing ends, the result shows (design §4.5) with one celebration for all the new species (a quiet toast
   * under reduced motion); "Finish…" shows it at any time. Finishing marks what still waits as skipped, or
   * "Leave the rest for later" keeps the import open. A finished import shows only its result.
   *
   * With `itemId` (#/import/<id>/photo/<itemId>) the large view of that photo opens over the grid.
   *
   * Phase 7b: while the computer has no HEIC decoder and HEIC photos here have no preview (`summary.heic.unshowable`), a
   * bar above the grid says how many, that they can still be assigned and filed, with How and Check again. While their
   * previews are being made (`heic.making`, after the decoder appeared) the import is followed as when it reads, and the
   * footer counts them (audit 10); a change of the decoder's state reads the import again (audit 1).
   *
   * This screen holds the state and wires it up. What the actions do is in lib/importSync.svelte.js (one change at a
   * time, polls and catch-ups), importFolder.svelte.js (the folder), importFinish.svelte.js (filing and the result),
   * importEdits.svelte.js (changes to the photos) and importSelect.svelte.js (selecting and the page's keys). They are `.svelte.js` modules so Svelte compiles their awaits
   * as it did here, and answers land in the same order as before.
   */
  let { id, itemId = null } = $props();

  let summary = $state(null);
  let data = $state.raw(emptyItems());
  let tags = $state([]);
  let error = $state('');
  let filter = $state('all');
  let view = $state('folder');
  let sel = $state.raw({ selected: new Set(), anchor: null, base: new Set() });
  let focusId = $state(null);
  let tools = $state(null);
  let choosing = $state(null);
  let busy = $state(false);
  let grid = $state(null);
  let relocating = $state(false);
  let result = $state(null);
  let showingResult = $state(false);
  let resultError = $state('');
  let celebrating = $state(null);
  let foundFocus = false; // focus goes to the heading once the folder is found where it moved
  let finishButton = $state(null);
  let heading = $state(null);
  let pausing = $state(false);
  let continuing = $state(false);
  let autoFailed = $state('');
  let unreachable = $state(false);
  let selStatus = $state('');
  let revision = null; // the latest the import's answers carried, sent with File (importSync.svelte.js)
  let changedNote = $state('');

  // The sections are worked out again only when the items (or the taxa, the name, the filter or the view) change:
  // a poll that brings nothing keeps the same Map, and a new summary with the same name changes nothing here.
  let itemMap = $derived(data.items);
  let taxaMap = $derived(data.taxa);
  let rootName = $derived(summary?.rootName ?? null);
  // One entry per shot: a RAW + JPEG pair is one tile (Phase 7a), so the counts, the selection and filing are per shot.
  let list = $derived(shots([...itemMap.values()]));
  let counts = $derived(filterCounts(list));
  let shown = $derived(applyFilter(list, filter));
  let sections = $derived(rootName === null ? [] : view === 'folder' ? folderSections(shown, rootName) : speciesSections(shown, taxaMap));
  let order = $derived(sections.flatMap((section) => section.items.map((item) => item.id)));
  // What can be selected: photos that can still be changed (a failed filing can; filed, missing, unreadable can't).
  let picks = $derived(order.filter((itemId) => editable(data.items.get(itemId))));
  let chosen = $derived(order.filter((itemId) => sel.selected.has(itemId)).map((itemId) => data.items.get(itemId)));
  let libraryIds = $derived(list.filter(inLibrary).map((item) => item.id));
  let filing = $derived(summary?.phase === 'filing');
  let blocked = $derived(filing ? WAIT_FOR_FILING : '');
  let ready = $derived(list.filter(fileable).length);
  let readyFiles = $derived(filesToFile(list));
  let loaded = $derived(summary !== null && summary.id === id);
  let viewing = $derived(loaded && itemId !== null && data.items.has(itemId));

  // The page's state as the actions see it (`current(wanted)`: is `wanted` still the import on screen? Answers for
  // one that was open before are ignored). The modules add their functions to it.
  const run = {
    get id() { return id; },
    current: (wanted) => wanted === id,
    get summary() { return summary; }, set summary(v) { summary = v; },
    get data() { return data; }, set data(v) { data = v; },
    get busy() { return busy; }, set busy(v) { busy = v; },
    get sel() { return sel; }, set sel(v) { sel = v; },
    get focusId() { return focusId; }, set focusId(v) { focusId = v; },
    get choosing() { return choosing; }, set choosing(v) { choosing = v; },
    get relocating() { return relocating; }, set relocating(v) { relocating = v; },
    get foundFocus() { return foundFocus; }, set foundFocus(v) { foundFocus = v; },
    get result() { return result; }, set result(v) { result = v; },
    get resultError() { return resultError; }, set resultError(v) { resultError = v; },
    get showingResult() { return showingResult; }, set showingResult(v) { showingResult = v; },
    get celebrating() { return celebrating; }, set celebrating(v) { celebrating = v; },
    get pausing() { return pausing; }, set pausing(v) { pausing = v; },
    get continuing() { return continuing; }, set continuing(v) { continuing = v; },
    get autoFailed() { return autoFailed; }, set autoFailed(v) { autoFailed = v; },
    get revision() { return revision; }, set revision(v) { revision = v; },
    get changedNote() { return changedNote; }, set changedNote(v) { changedNote = v; },
    get order() { return order; },
    get picks() { return picks; },
    get shown() { return shown; },
    get chosen() { return chosen; },
    get libraryIds() { return libraryIds; },
    get blocked() { return blocked; },
    get heading() { return heading; },
    get grid() { return grid; },
    get tools() { return tools; },
  };
  Object.assign(run, importSync(run), importFinish(run), importFolder(run), importEdits(run), importSelect(run));
  const {
    resync, carryOn, lookAgain, relocate, file, choosePairs, pause, loadResult, openResult, finish, removeFinished,
    assignTo, tagChosen, placeChosen, acceptSection, skipLibrary, startAction, clear, add, onTileClick, onSpace, onMove, onKey,
  } = run;

  // One status region, always in the page, says how many are selected (and when the selection is cleared).
  let selectedBefore = 0;
  $effect(() => {
    const n = chosen.length;
    if (n) selStatus = `${photoCount(n)} selected.`;
    else if (selectedBefore) selStatus = 'Selection cleared.';
    selectedBefore = n;
  });

  // The selection only ever holds photos that are shown and can still be changed: one filed, gone or hidden
  // by a change while selected leaves it.
  $effect(() => {
    const keep = new Set(picks);
    untrack(() => {
      sel = pruneTo(sel, keep);
    });
  });

  // An address naming a photo this import does not have shows the grid, once the folder has been looked through
  // (while it is, the photo may not be listed yet).
  $effect(() => {
    if (loaded && itemId !== null && !viewing && summary.phase !== 'walking') window.location.replace(importHref(id));
  });

  // The large view pages through the photos shown. One a change takes out of the filter keeps its place among
  // them, so paging goes on from where it was.
  let lastViewOrder = [];
  let viewOrder = $derived.by(() => {
    const next = keepPlace(order, lastViewOrder, itemId);
    if (next === order) lastViewOrder = order;
    return next;
  });

  // The footer counts this import's HEIC previews being made; none once it is closed.
  $effect(() => {
    setImportPreviews(summary?.state === IMPORT_OPEN ? summary.heic ?? null : null);
  });
  $effect(() => () => setImportPreviews(null));

  // The HEIC decoder came or went: which photos have previews changed, so the summary and the items are read again.
  $effect(() => {
    const onHeic = () => {
      if (summary) resync(id);
    };
    window.addEventListener(HEIC_CHANGED, onHeic);
    return () => window.removeEventListener(HEIC_CHANGED, onHeic);
  });

  // Several windows (A10): the status naming this import in another state than the page's (filing started in another
  // window, say) reads it again, so the grid, the summary and File follow. Once per state the status says, so a
  // status a moment behind the page's own answers can't read it over and over. Once the status names no import,
  // another one, or the page's own state, that is forgotten, so a second filing of the same import reads it again.
  let statusSaid = null;
  $effect(() => {
    const said = appStatus.import;
    const phase = summary?.id === id ? summary.phase : undefined;
    if (!said || said.id !== id || said.phase === phase) {
      statusSaid = null;
      return;
    }
    if (phase === undefined) return;
    const key = `${said.id}:${said.phase}`;
    if (key === statusSaid) return;
    statusSaid = key;
    untrack(() => resync(id));
  });

  // "Continue import" in the footer while this import is open: the address would not change, so it asks here.
  $effect(() => {
    const onContinue = (event) => {
      if (event.detail?.id !== id || !summary || summary.state !== IMPORT_OPEN || !summary.stopped) return;
      if (summary.stopped.phase === 'filing') file();
      else carryOn();
    };
    window.addEventListener(CONTINUE_IMPORT, onContinue);
    return () => window.removeEventListener(CONTINUE_IMPORT, onContinue);
  });

  // Only a different id is a new import: a parent passing the same id again (a new route object for the large
  // view's address) must not start the page afresh.
  let importId = $derived(id);

  // A new import starts afresh; its summary and every item are loaded, then the changes are polled.
  $effect(() => {
    const wanted = importId;
    untrack(() => {
      summary = null;
      data = emptyItems();
      error = '';
      filter = 'all';
      view = 'folder';
      clear();
      choosing = null;
      relocating = false;
      result = null;
      showingResult = false;
      celebrating = null;
      busy = false;
      pausing = false;
      continuing = false;
      autoFailed = '';
      unreachable = false;
      revision = null;
      changedNote = '';
    });
    let cancelled = false;
    Promise.all([getImport(wanted), getImportItems(wanted), getTags().catch(() => [])])
      .then(([s, page, t]) => {
        if (cancelled) return;
        summary = s;
        data = mergePage(emptyItems(), page);
        run.read(page, s);
        tags = t;
        if (s.state !== IMPORT_OPEN) loadResult();
        // Opening an import whose reading the app's closing cut short carries on reading it.
        if (s.stopped && s.stopped.phase !== 'filing' && !s.folderMissing) carryOn();
      })
      .catch((e) => {
        if (!cancelled) error = e.message;
      });
    return () => {
      cancelled = true;
    };
  });

  // About once a second while the import is busy: its summary, then the items changed since the last answer.
  // The answer that says it is idle again also brings the last changes. Requests never overlap. When the app
  // can't be reached it waits longer each time (up to 15 s) and says so quietly after three misses; an import
  // that is gone (404) stops it.
  let working = $derived(summary !== null && summary.id === importId && followed(summary));
  $effect(() => {
    if (!working) return undefined;
    return pollImport(untrack(() => importId), {
      since: () => data.version,
      begin: run.begin,
      answer: run.answer,
      onReach: (reached) => (unreachable = !reached),
      onGone: (e) => (error = e.message),
    });
  });

  // A change of filter or view starts a new selection (only what is shown can be acted on) and, when the page is
  // scrolled down, goes back to the top of the grid.
  let regrouped = false;
  $effect(() => {
    filter;
    view;
    untrack(clear);
    if (regrouped) tick().then(() => grid?.toTop());
    regrouped = true;
  });

  function open(item) {
    focusId = item.id;
    noteViewerOpen();
    window.location.hash = importPhotoHref(id, item.id);
  }

  // Paging in the large view replaces the address (Back closes it) and keeps that tile in the grid for focus.
  function show(next) {
    focusId = next;
    window.location.replace(importPhotoHref(id, next));
  }
</script>

<svelte:window onkeydown={onKey} />

<span class="sr-only imp-selstatus" role="status">{selStatus}</span>

{#if error}
  <p class="problem" role="alert">{error}</p>
  <p><a href="#/import">Back to your imports</a></p>
{:else if summary && summary.state !== IMPORT_OPEN}
  <ImportHeader {summary} finished>
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={removeFinished}>Remove from your imports</button>
  </ImportHeader>
  <h2 class="imp-rh">What this import did</h2>
  {#if result}<ImportResult {result} kinds={summary.pairs?.kinds} />{:else if resultError}<p class="problem" role="alert">{resultError}</p>{/if}
{:else if summary}
  <ImportHeader {summary} bind:heading>
    <div class="imp-side">
      <ImportFiling {summary} count={ready} files={readyFiles} {busy} pausing={pausing && filing} onFile={file} onPause={pause} />
      {#if !filing}<button class="btn ghost" type="button" bind:this={finishButton} aria-disabled={busy} onclick={() => { if (!busy) openResult(); }}>Finish…</button>{/if}
    </div>
  </ImportHeader>
  <ImportNotices {summary} changed={changedNote} {busy} {continuing} {autoFailed} {unreachable} onLookAgain={lookAgain}
                 onRelocate={() => { foundFocus = false; relocating = true; }} onContinueReading={carryOn} />
  {#if heicBarOn() && summary.heic?.unshowable > 0}<HeicBar line={importBarLine(summary.heic.unshowable, appStatus.heic)} />{/if}
  <ImportPairs pairs={summary.pairs ?? null} busy={busy || filing} heicMissing={heicMissing(appStatus.heic)} onChoose={choosePairs} />
  <ImportTools bind:this={tools} {counts} bind:filter bind:view library={libraryIds.length} off={busy || filing} onSkipLibrary={skipLibrary} />
  {#if shown.length}
    <ImportGrid bind:this={grid} importId={id} label="Photos in {summary.rootName}" {sections} taxa={data.taxa} selected={sel.selected} {focusId} busy={busy || filing}
                {onTileClick} onOpen={open} {onMove} {onSpace} onFocusTile={(itemId) => (focusId = itemId)}
                onSelectSection={(section) => add(section.items.map((item) => item.id))}
                onSelectBurst={(group) => add(group.items.map((item) => item.id))} onAcceptSection={acceptSection} />
  {:else}
    <p class="muted imp-none">{list.length ? 'No photos here with this filter.' : summary.phase === 'idle' ? 'No photos were found in this folder.' : 'Looking for photos…'}</p>
  {/if}
  {#if chosen.length}
    <ImportSelectionBar count={chosen.length} allSkipped={chosen.every((item) => item.skipped)} {busy} {blocked}
                        onAssign={() => startAction('species')} onTags={() => startAction('tags')} onPlace={() => startAction('place')}
                        onRemovePlace={() => startAction('unplace')} onSkip={() => startAction('skip')} onClear={clear} />
  {/if}
  {#if viewing}
    <ImportViewer importId={id} items={data.items} taxa={data.taxa} order={viewOrder} {itemId}
                  onShow={show} onClose={() => closeViewerTo(importHref(id))} />
  {/if}
  {#if showingResult && result}
    <ImportFinishDialog {result} kinds={summary.pairs?.kinds} {ready} {busy} error={resultError} returnFocus={() => finishButton} onFinish={finish}
                        onClose={() => (showingResult = false)} />
  {/if}
  {#if celebrating}
    <Celebration count={celebrating.count} added={celebrating.added} milestone={celebrating.milestone}
                 onClose={() => { celebrating = null; showingResult = true; }} />
  {/if}
  {#if relocating}
    <FolderBrowser title="Where is {summary.rootName} now?" relocating returnFocus={() => (foundFocus ? heading : null)} onChoose={relocate}
                   onClose={() => (relocating = false)} />
  {/if}
  {#if choosing === 'species'}
    <SpeciesPicker title="Assign {photoCount(chosen.length)} to a species" onPick={assignTo} onClose={() => (choosing = null)} />
  {:else if choosing === 'tags'}
    <BulkTagDialog photos={chosen} {tags} onApply={tagChosen} onClose={() => (choosing = null)} />
  {:else if choosing === 'place'}
    <PlacePicker count={chosen.length} onChoose={placeChosen} onPlacesChanged={() => resync(id)} onClose={() => (choosing = null)} />
  {/if}
{:else}
  <p class="muted loading" role="status">Opening…</p>
{/if}
