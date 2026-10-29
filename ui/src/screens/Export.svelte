<script>
  import { onDestroy, onMount, tick, untrack } from 'svelte';
  import { getExports } from '../lib/api.js';
  import FilterResults from '../lib/FilterResults.svelte';
  import ExportSheet from '../lib/ExportSheet.svelte';
  import ExportProgress from '../lib/ExportProgress.svelte';
  import PastExports from '../lib/PastExports.svelte';
  import StopExportDialog from '../lib/StopExportDialog.svelte';
  import { exportHref, exportPhotoHref } from '../lib/route.js';
  import { EXPORT_CHANGED, refreshStatus, status } from '../lib/status.svelte.js';
  import { showToast } from '../lib/toast.svelte.js';
  import { DONE } from '../lib/states.js';
  import { NOTHING_MATCHES } from '../lib/messages.js';

  /**
   * The Export screen (#/export?<filter>&view=, Phase 4a contract §4): the filter and its results (lib/FilterResults,
   * read-only: edits never happen on Export), with the Export… button at the right of the view bar. With `photoId`
   * (#/export/photo/<id>?<filter>) the viewer opens over the Photos view's loaded photos. `delay` is the wait after a
   * change before asking (200 ms).
   *
   * Exports (Phase 4b contract E1–E4, §5): Export… opens the Export sheet for the filter; it is inactive while an
   * export is running or paused ("Finish or cancel the export in progress first.") and when nothing matches. The
   * running or paused export's progress card is at the top, Past exports under it, the filter and views below. After a
   * start focus goes to the card (a species list alone: to its Past exports entry, with a toast); after a stop, or when
   * the export finishes while its card has focus, to its entry. The footer and Home are told whenever this screen sees
   * the export change, and the screen reads the exports again whenever the status shows another current export. A read
   * asked for before the screen's own change (a start, a removal) is not shown: the exports are read again.
   */
  let { query: queryProp, view = 'species', photoId = null, delay = 200 } = $props();

  // The filter's count and the filter as printed, from the results (bound).
  let count = $state(null);
  let pending = $state(true);
  let countError = $state('');
  let query = $state(untrack(() => queryProp));

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
    loadExports();
    window.addEventListener(EXPORT_CHANGED, onExportChanged);
    window.addEventListener('focusin', noteFocus);
    window.addEventListener('pointerdown', notePointer);
    return () => {
      window.removeEventListener('pointerdown', notePointer);
      window.removeEventListener('focusin', noteFocus);
      window.removeEventListener(EXPORT_CHANGED, onExportChanged);
    };
  });

  onDestroy(() => {
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
<FilterResults query={queryProp} {view} {photoId} {delay} resultsHref={exportHref} photoHref={exportPhotoHref}
               bind:count bind:pending bind:countError bind:printed={query}>
  {#snippet actions()}
    <button class="btn" type="button" bind:this={exportButton} aria-disabled={!canExport} title={exportReason || undefined}
            aria-describedby={exportReason ? 'ex-export-why' : undefined} onclick={openSheet}>Export…</button>
    <span class="sr-only" id="ex-export-why">{exportReason}</span>
  {/snippet}
</FilterResults>

{#if sheetOpen}
  <ExportSheet {query} {count} returnFocus={() => focusAfter?.() ?? exportButton} onStarted={started} onClose={() => (sheetOpen = false)} />
{/if}
{#if stopping}
  <!-- the count follows the card's; refused as finished, EXPORT_CHANGED reads the exports and the effect above closes it -->
  <StopExportDialog run={current?.id === stopping.id ? current : stopping} bind:busy={stopBusy} returnFocus={() => focusAfter?.() ?? cancelButton()}
                    onStopped={stopped} onGone={() => {}} onClose={() => (stopping = null)} />
{/if}

<style>
  .ex-h { font-size: clamp(30px, 5vw, 46px); font-weight: 720; line-height: 1.05; margin-bottom: 18px; }
</style>
