<script>
  import { onMount } from 'svelte';
  import { NOT_ANSWERING, NOT_ANSWERING_PLAIN, refreshStatus, status } from './status.svelte.js';
  import StoppedImports from './StoppedImports.svelte';
  import PausedExport from './PausedExport.svelte';
  import PausedBackup from './PausedBackup.svelte';
  import PausedUpdate from './PausedUpdate.svelte';
  import { progressLine } from './exports.js';
  import { backupLine, holdsPhotosLine } from './backups.js';
  import { updateLine } from './updates.js';
  import { pendingLine } from './placeUpdates.js';
  import { session } from './session.svelte.js';
  import { DECODER_CHECKING, PAUSED, RUNNING, WAITING } from './states.js';
  import { PHOTOS_BACKED_UP, PHOTOS_EXPORTED, PHOTOS_FILED, PHOTOS_MOVED } from './messages.js';

  /**
   * Quiet page footer: progress of the startup photo check, of photo details and places being caught up, of an import
   * (looking through, reading or filing a folder) and of previews still being made (each with a thin bar; a bar
   * runs without an end while its total is still unknown), and the Recently deleted link. GET /api/status is
   * asked on load and then every 2 s, but only while there is work going on. The one status region is in the
   * page from the start and holds only the phase ("Checking your photos.", "Reading / Updating photo details.",
   * "Finding places.", "Reading Butterflies 2024.", "Filing photos.", "Preparing previews."), so a screen reader hears work begin
   * and end but not every count. When several run at once it names them in the order the startup work runs:
   * the check, then photo details, then the import; previews are made alongside all of them, so they are named
   * only once nothing else is going on. The counts and bars sit outside it; the bars keep their accessible values.
   * Imports cut short by the app closing get a quiet line each (they won't carry on by themselves). A running export
   * (Phase 4b) shows "Exporting photos… 3,200 of 50,000" with a bar ("Exporting photos." in the status region, after an
   * import and before previews); a paused one a quiet line with Continue export and Discard….
   * A running backup (Phase 6) shows "Backing up your library — 3,210 of 12,400 photos" with a bar, right after the export
   * ("Backing up your library." in the status region); a paused one, or one the app's closing stopped ("Backup stopped —
   * the app was closed"), a quiet line with its notice, Continue backup and Discard….
   * A taxonomy update (Phase 5b) running or waiting shows "Updating taxonomy… 120 of 340 photos" (or what it waits for)
   * with a bar, named first in the status region ("Updating the taxonomy.": it runs before the startup's check); a paused
   * one the quiet line "Taxonomy update paused." with Retry.
   * After a place-data update (Phase 5c) the GPS re-check shows "Checking photo places… 1,200 of 20,000" (bar "Places
   * checked") while `placeUpdate.rechecking` and the step is `places`; photos it left unchecked with the re-check not
   * running say "1,200 photos haven't been checked yet; Butterfly Atlas will finish when it next starts." (contract §10).
   * Two standing notes (Phase 6, not dismissible; lib/session.svelte.js): "Running in a browser tab. Closing it quits the
   * app." and, for a drive that cannot take the library's lock file, "This drive cannot prevent a second copy from opening
   * this library. Open only one."
   * Phase 7b: while the HEIC decoder check runs (`heic.state` 'checking') the status is followed as work, and it is read
   * again whenever the window comes back to the front (audit 1). An open import's HEIC previews being made
   * (`status.importPreviews`) count in "Preparing previews… n of m" with the library's (audit 10).
   */
  const POLL_MS = 2000;
  // While the library's drive is full or gone and nothing works, the status is still asked now and then, so the banner
  // goes by itself once the drive is back.
  const LIBRARY_POLL_MS = 5000;

  let exporting = $derived(status.export?.state === RUNNING ? status.export : null);
  let backingUp = $derived(status.backup?.state === RUNNING ? status.backup : null);
  let backupPaused = $derived(status.backup?.state === PAUSED ? status.backup : null);
  let paused = $derived(status.export?.state === PAUSED ? status.export : null);
  let updating = $derived(status.taxonomyUpdate?.state === RUNNING || status.taxonomyUpdate?.state === WAITING ? status.taxonomyUpdate : null);
  let updatePaused = $derived(status.taxonomyUpdate?.state === PAUSED ? status.taxonomyUpdate : null);
  // An open import's HEIC previews: those being made wait, the rest of its HEIC items that can be shown are done.
  let importPending = $derived(status.importPreviews?.making ?? 0);
  let importDone = $derived(status.importPreviews ? Math.max(0, status.importPreviews.count - status.importPreviews.unshowable - importPending) : 0);
  let previewsPending = $derived(status.previews.pending + importPending);
  let working = $derived(updating !== null || status.rescanning || status.processing !== null || status.import !== null || exporting !== null || backingUp !== null || previewsPending > 0
    || status.heic?.state === DECODER_CHECKING);
  let processing = $derived(status.processing);
  // The catch-up's steps each have their own words; one this page does not know (a newer server) reads as the update.
  // A step whose total is still unknown gets a bar without an end, under the `counting` name. After a place step
  // (Phase 5c) the places step re-checks every photo's place: "Checking photo places…".
  const stepPhrases = (step, rechecking) => (step === 'places' && rechecking
    ? { line: 'Checking photo places', phase: 'Checking photo places.', bar: 'Places checked', counting: 'Places checked' }
    : ({
      details: { line: 'Reading photo details', phase: 'Reading photo details.', bar: 'Photo details read', counting: 'Counting photo details' },
      places: { line: 'Finding places', phase: 'Finding places.', bar: 'Places found', counting: 'Places found' },
    })[step] ?? { line: 'Updating photo details', phase: 'Updating photo details.', bar: 'Photo details updated', counting: 'Counting photo details' });
  let stepWords = $derived(processing ? stepPhrases(processing.step, status.placeUpdate?.rechecking === true) : null);
  const num = (n) => n.toLocaleString();
  let rescan = $derived(status.rescan);
  let previewsDone = $derived(status.previews.done + (importPending ? importDone : 0));
  let previewTotal = $derived(previewsDone + previewsPending);
  let importing = $derived(status.import);
  // Looking through the folder, reading what it found, or filing: each phase has its own words; one this page
  // does not know (a newer server) gets neutral ones.
  const importPhrases = (phase, name) => ({
    walking: { line: `Looking through ${name}`, phase: `Looking through ${name}.`, bar: 'Looking for photos' },
    reading: { line: `Reading ${name}`, phase: `Reading ${name}.`, bar: 'Photos read' },
    filing: { line: 'Filing photos', phase: 'Filing photos.', bar: PHOTOS_FILED },
  })[phase] ?? { line: `Working on ${name}`, phase: `Working on ${name}.`, bar: 'Import progress' };
  let importWords = $derived(importing ? importPhrases(importing.phase, importing.rootName) : null);

  onMount(() => {
    refreshStatus();
    // Back to the front (another window may have run Check again, or the decoder appeared meanwhile): ask at once.
    const onFocus = () => refreshStatus();
    const onVisible = () => {
      if (document.visibilityState === 'visible') refreshStatus();
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisible);
    };
  });

  // The next request is scheduled only after the last one has finished, so requests never overlap.
  $effect(() => {
    if (!working) return undefined;
    let stopped = false;
    let timer;
    const again = async () => {
      await refreshStatus();
      if (!stopped) timer = setTimeout(again, POLL_MS);
    };
    timer = setTimeout(again, POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  });

  $effect(() => {
    if (working || !status.library) return undefined;
    let stopped = false;
    let timer;
    const again = async () => {
      await refreshStatus();
      if (!stopped) timer = setTimeout(again, LIBRARY_POLL_MS);
    };
    timer = setTimeout(again, LIBRARY_POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  });
</script>

<footer class="foot">
  <div class="wrap">
    <div class="jobs">
      <span class="sr-only" role="status">{updating ? 'Updating the taxonomy.' : status.rescanning ? 'Checking your photos.' : stepWords ? stepWords.phase : importWords ? importWords.phase : exporting ? 'Exporting photos.' : backingUp ? 'Backing up your library.' : previewsPending > 0 ? 'Preparing previews.' : ''}</span>
      {#if updating}
        <span class="job" aria-hidden="true"><span>{updateLine(updating)}</span></span>
        {#if updating.state === RUNNING && updating.total}
          <progress max={updating.total} value={updating.done} aria-label={PHOTOS_MOVED}></progress>
        {:else}
          <progress aria-label={PHOTOS_MOVED}></progress>
        {/if}
      {/if}
      {#if status.rescanning}
        <span class="job" aria-hidden="true">
          <span>Checking your photos…{rescan?.total ? ` ${num(rescan.checked)} of ${num(rescan.total)}` : ''}</span>
        </span>
        {#if rescan?.total}
          <progress max={rescan.total} value={rescan.checked} aria-label="Photos checked"></progress>
        {:else}
          <progress aria-label="Counting photos"></progress>
        {/if}
      {/if}
      {#if stepWords}
        <span class="job" aria-hidden="true">
          <span>{stepWords.line}…{processing.total ? ` ${num(processing.done)} of ${num(processing.total)}` : ''}</span>
        </span>
        {#if processing.total}
          <progress max={processing.total} value={processing.done} aria-label={stepWords.bar}></progress>
        {:else}
          <progress aria-label={stepWords.counting}></progress>
        {/if}
      {/if}
      {#if importWords}
        <span class="job" aria-hidden="true">
          <span>{importWords.line}…{importing.total ? ` ${num(importing.done)} of ${num(importing.total)}` : importing.phase === 'walking' && importing.done ? ` ${num(importing.done)} found` : ''}</span>
        </span>
        {#if importing.total}
          <progress max={importing.total} value={importing.done} aria-label={importWords.bar}></progress>
        {:else}
          <progress aria-label={importWords.bar}></progress>
        {/if}
      {/if}
      {#if exporting}
        <span class="job" aria-hidden="true"><span>{progressLine(exporting)}</span></span>
        <progress max={Math.max(exporting.total, 1)} value={exporting.done} aria-label={PHOTOS_EXPORTED}></progress>
      {/if}
      {#if backingUp}
        <span class="job" aria-hidden="true"><span>{backupLine(backingUp)}</span></span>
        {#if backingUp.total > 0}
          <progress max={backingUp.total} value={backingUp.done} aria-label={PHOTOS_BACKED_UP}></progress>
        {:else}
          <progress aria-label={PHOTOS_BACKED_UP}></progress>
        {/if}
      {/if}
      {#if previewsPending > 0}
        <span class="job" aria-hidden="true">
          <span>Preparing previews… {num(previewsDone)} of {num(previewTotal)}</span>
        </span>
        <progress max={previewTotal} value={previewsDone} aria-label="Previews ready"></progress>
      {/if}
    </div>
    {#if working && status.unreachable}<p class="foot-lost" role="alert">{updating ? NOT_ANSWERING : NOT_ANSWERING_PLAIN}</p>{/if}
    {#if session.lockNote}<p class="foot-pending">{session.lockNote}</p>{/if}
    {#if session.tabNote}<p class="foot-pending">{session.tabNote}</p>{/if}
    {#if status.placeUpdate?.pending > 0 && !status.placeUpdate.rechecking}<p class="foot-pending">{pendingLine(status.placeUpdate.pending)}</p>{/if}
    {#if status.stoppedImports.length}<StoppedImports stops={status.stoppedImports} quiet />{/if}
    {#if updatePaused}<PausedUpdate update={updatePaused} />{/if}
    {#if paused}<PausedExport run={paused} />{/if}
    {#if backupPaused}<PausedBackup run={backupPaused} />{/if}
    <!-- several windows A6, A22: a backup of Everything holding the photos (running, or paused part-way) says why, not muted, discard for one the app's closing stopped, cancel otherwise -->
    {#if status.backup?.holdsPhotos}<p class="foot-hold">{holdsPhotosLine(status.backup)}</p>{/if}
    {#if status.deletedCount > 0}<a href="#/deleted">Recently deleted ({status.deletedCount})</a>{/if}
  </div>
</footer>
