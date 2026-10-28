<script>
  import { onMount, tick } from 'svelte';
  import { discardImport, getImports, startImport } from '../lib/api.js';
  import Dialog from '../lib/Dialog.svelte';
  import FolderBrowser from '../lib/FolderBrowser.svelte';
  import { photoCount, stoppedLine } from '../lib/importView.js';
  import { importHref } from '../lib/route.js';
  import { shortDate } from '../lib/dates.js';
  import { LIBRARY_CHANGED, refreshStatus } from '../lib/status.svelte.js';
  import LoadError from '../lib/LoadError.svelte';
  import { loadProblem } from '../lib/problems.js';
  import { showToast } from '../lib/toast.svelte.js';
  import { IMPORT_OPEN } from '../lib/states.js';
  import { REMOVE, WAIT_FOR_FILING } from '../lib/messages.js';

  /**
   * #/import (design §4.1, decision B4): unfinished imports as cards (Continue, Discard), finished ones with
   * their summary until removed, and "Import another folder". With no imports at all the folder browser opens
   * straight away. Choosing a folder starts its import, or continues the unfinished one of that folder.
   * Continue asks the server again (POST /api/imports), which looks through the folder anew and goes on reading;
   * for filing the app's closing cut short it opens the grid, which offers "Continue filing".
   */
  const POLL_MS = 2000;

  let imports = $state(null);
  let error = $state('');
  let browsing = $state(false);
  let busy = $state(false);
  let confirming = $state(null);
  let confirmError = $state('');
  let heading = $state(null);
  let anotherButton = $state(null);
  let cardError = $state(null); // { id, message }: why Continue failed, on that import's card

  let open = $derived((imports ?? []).filter((run) => run.state === IMPORT_OPEN));
  let finished = $derived((imports ?? []).filter((run) => run.state !== IMPORT_OPEN));
  // While an import looks through, reads or files its folder, the list is asked for again every 2 s.
  let anyWorking = $derived(open.some((run) => run.phase && run.phase !== 'idle'));
  let loads = 0;

  async function load() {
    const mine = ++loads;
    try {
      const next = await getImports();
      if (mine !== loads) return;
      imports = next;
      error = '';
    } catch (e) {
      // A failed reload keeps the list on screen.
      if (mine === loads && imports === null) error = loadProblem(e);
    }
  }

  $effect(() => {
    if (!anyWorking) return undefined;
    let stopped = false;
    let timer;
    const again = async () => {
      await load();
      if (!stopped) timer = setTimeout(again, POLL_MS);
    };
    timer = setTimeout(again, POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  });

  onMount(async () => {
    await load();
    if (imports && !imports.length) browsing = true;
  });

  // The list reads again on every LIBRARY_CHANGED: the library is back after a load failed, or photos changed (an
  // import filed in another window too, several-windows audit A9).
  onMount(() => {
    window.addEventListener(LIBRARY_CHANGED, load);
    return () => window.removeEventListener(LIBRARY_CHANGED, load);
  });

  const progress = (run) => `${run.counts.filed.toLocaleString()} of ${photoCount(run.counts.total)} filed`;
  const working = { walking: 'Looking through the folder…', reading: 'Reading photos…', filing: 'Filing photos…' };

  /** Starts or continues the import of `path`, then opens its grid. A failure is thrown to the caller. */
  async function go(path) {
    const { import: run, continued } = await startImport(path);
    if (continued) showToast(`Continuing your import from ${shortDate(run.createdAt)}: ${progress(run)}.`);
    refreshStatus();
    window.location.hash = importHref(run.id);
  }

  async function continueImport(run) {
    if (busy) return;
    cardError = null;
    // Filing the app's closing cut short: its grid offers "Continue filing" (asking the server to go on
    // reading here would forget where filing stopped).
    if (run.stopped?.phase === 'filing') {
      window.location.hash = importHref(run.id);
      return;
    }
    busy = true;
    try {
      await go(run.rootPath);
    } catch (e) {
      cardError = { id: run.id, message: e.message };
    } finally {
      busy = false;
    }
  }

  async function chosen(listing) {
    await go(listing.path);
    browsing = false;
  }

  async function discard() {
    if (busy) return;
    busy = true;
    confirmError = '';
    try {
      await discardImport(confirming.run.id);
      showToast(confirming.run.state === IMPORT_OPEN ? `The import of ${confirming.run.rootName} was discarded.` : `${confirming.run.rootName} was removed from this list.`);
      confirming = null;
      refreshStatus();
      await load();
      await tick();
      (anotherButton ?? heading)?.focus();
    } catch (e) {
      confirmError = e.message;
    } finally {
      busy = false;
    }
  }

  function ask(run) {
    if (busy) return;
    confirmError = '';
    confirming = { run };
  }
</script>

<div class="xh">
  <div>
    <div class="eyebrow">Your atlas</div>
    <h1 tabindex="-1" bind:this={heading}>Import folder</h1>
    <div class="cn">Bring a whole folder of photos into your atlas. The photos are copied; your folder is never changed.</div>
  </div>
  {#if imports}
    <button class="btn" type="button" bind:this={anotherButton} onclick={() => (browsing = true)}>
      {imports.length ? 'Import another folder' : 'Choose a folder'}
    </button>
  {/if}
</div>

{#if error}
  <LoadError message={error} onRetry={load} />
{:else if imports === null}
  <p class="muted loading" role="status">Opening…</p>
{:else if !imports.length}
  <div class="emptyp">
    <h2>No imports yet</h2>
    <p>Choose a folder of photos, for example a memory card or a trip. Butterfly Atlas groups the photos by folder and by bursts of shots, so you can name many at once.</p>
  </div>
{:else}
  {#if open.length}
    <section class="sec">
      <div class="sec-h"><h2>Unfinished</h2><span class="muted">Every choice is saved as you make it</span></div>
      <ul class="imp-cards">
        {#each open as run (run.id)}
          <li class="imp-card">
            <div class="imp-t">
              <b dir="auto">{run.rootName}</b>
              <span class="path" dir="auto">{run.rootPath}</span>
            </div>
            <span class="muted">{progress(run)}, last worked on {shortDate(run.updatedAt)}</span>
            {#if run.folderMissing}<span class="badge warn">Folder not found</span>{:else if working[run.phase]}<span class="badge">{working[run.phase]}</span>{:else if run.paused}<span class="badge">Paused</span>{/if}
            {#if run.stopped}<p class="imp-stop">{stoppedLine({ rootName: run.rootName, ...run.stopped })}</p>{/if}
            {#if run.notice}<p class="imp-notice">{run.notice}</p>{/if}
            {#if run.updateNote}<p class="imp-update"><b>Taxonomy update:</b> {run.updateNote}</p>{/if}
            {#if cardError?.id === run.id}<p class="problem" role="alert">{cardError.message}</p>{/if}
            <div class="imp-acts">
              <button class="btn" type="button" aria-disabled={busy} aria-label="Continue {run.rootName}" onclick={() => continueImport(run)}>Continue</button>
              <button class="btn ghost" type="button" aria-disabled={busy || run.phase === 'filing'} aria-label="Discard {run.rootName}"
                      onclick={() => { if (run.phase === 'filing') showToast(WAIT_FOR_FILING); else ask(run); }}>Discard</button>
            </div>
          </li>
        {/each}
      </ul>
    </section>
  {/if}
  {#if finished.length}
    <section class="sec">
      <div class="sec-h"><h2>Finished</h2></div>
      <ul class="imp-cards">
        {#each finished as run (run.id)}
          <li class="imp-card done">
            <div class="imp-t">
              <b dir="auto">{run.rootName}</b>
              <span class="path" dir="auto">{run.rootPath}</span>
            </div>
            <span class="muted">{photoCount(run.counts.filed)} filed, finished on {shortDate(run.updatedAt)}</span>
            <div class="imp-acts">
              <a class="btn ghost" href={importHref(run.id)} aria-label="Summary of {run.rootName}">Summary</a>
              <button class="btn ghost" type="button" aria-disabled={busy} aria-label="Remove {run.rootName} from this list" onclick={() => ask(run)}>{REMOVE}</button>
            </div>
          </li>
        {/each}
      </ul>
    </section>
  {/if}
{/if}

{#if browsing}
  <!-- Opened by itself there was no focus to go back to: Choose a folder takes it. -->
  <FolderBrowser onChoose={chosen} returnFocus={() => anotherButton} onClose={() => (browsing = false)} />
{/if}

{#if confirming}
  {@const run = confirming.run}
  <Dialog title={run.state === IMPORT_OPEN ? `Discard the import of ${run.rootName}?` : `Remove ${run.rootName} from this list?`} onClose={() => { if (!busy) confirming = null; }}>
    {#if run.state === IMPORT_OPEN}
      <p>The species, tags and skips you chose for photos not filed yet are forgotten. Photos already filed stay in your atlas, and the folder is not touched.</p>
    {:else}
      <p>Its summary goes. Photos it filed stay in your atlas, and the folder is not touched.</p>
    {/if}
    {#if confirmError}<p class="problem" role="alert">{confirmError}</p>{/if}
    {#snippet actions()}
      <button class="btn ghost" type="button" data-autofocus aria-disabled={busy} onclick={() => { if (!busy) confirming = null; }}>Cancel</button>
      <button class="btn" type="button" aria-disabled={busy} onclick={discard}>{run.state === IMPORT_OPEN ? 'Discard' : REMOVE}</button>
    {/snippet}
  </Dialog>
{/if}
