<script>
  import { onMount, tick } from 'svelte';
  import FolderBrowser from '../lib/FolderBrowser.svelte';
  import Oakblue from '../lib/Oakblue.svelte';
  import { inspectFolder, retrySetup, setUpLibrary } from '../lib/api.js';
  import { USE_THIS_FOLDER } from '../lib/messages.js';
  import { meadow } from '../lib/meadow.js';
  import { setupHere } from '../lib/setupView.js';

  /**
   * `status.retry` (Phase 5c): the remembered library failed to open (a place-data step that failed, rolled back):
   * Try again under the problem opens it again; when it opens the app starts as usual (`onDone`), and when it fails
   * again its new problem replaces the old one. Under a failure, where the photos still are and that another folder starts
   * a new, empty library; no Try again when it can't help (`retry` false: a library from a newer version).
   * @type {{ status: { suggestedFolder: string, problem?: string | null, retry?: boolean }, onDone: () => void }}
   */
  let { status, onDone } = $props();

  // The welcome scene (Meadow design §4): the butterfly on its daisy above a meadow of daisies and lantana, soft light behind.
  const SCENE_MEADOW = meadow(480, 140, { seed: 3, density: 1.4 });

  // The Setup status the last Try again answered (null before one), or why it was refused.
  let retried = $state(null);
  let retryError = $state('');
  let retrying = $state(false);
  // A Try again answered with the library still not open: "Tried again at 14:02 — still couldn't open." (said in a
  // status, so it is heard even when the problem's words are the same).
  let retryNote = $state('');
  const clock = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  let problem = $derived(retryError || (retried ?? status).problem);
  // The remembered library that failed to open: its photos are where they were (contract §10). The server sends
  // `dataFolder` with `configured: false` only then (a fresh setup has none).
  let folder = $derived((retried ?? status).dataFolder ?? null);
  let canRetry = $derived(Boolean((retried ?? status).retry) || Boolean(retryError));

  // "Choose a different folder": the folder browser in setup mode (setup design §2) is open.
  let choosing = $state(false);
  let error = $state('');
  let busy = $state(false);
  let chooseButton = $state();
  // The suggested folder holds a library with unfinished work: what opening it clears (setup design §2), else ''.
  let suggestedNote = $state('');

  onMount(async () => {
    try {
      const check = await inspectFolder(status.suggestedFolder, true);
      if (check?.kind === 'existing' && check.unfinished && Object.values(check.unfinished).some(Boolean)) {
        suggestedNote = setupHere(check);
      }
    } catch {
      // the note is a courtesy: Use this folder still says why it can't be used, if it can't
    }
  });

  function choose() {
    error = '';
    choosing = true;
  }

  function back() {
    choosing = false;
    tick().then(() => chooseButton?.focus());
  }

  async function retry() {
    if (retrying) return;
    retrying = true;
    try {
      const next = await retrySetup();
      retryError = '';
      if (next?.configured) {
        retried = null;
        retryNote = '';
        onDone();
      } else {
        retried = next;
        // Emptied first, so the same words a minute later are still a change the status announces.
        retryNote = '';
        await tick();
        retryNote = `Tried again at ${clock(new Date())} — still couldn't open.`;
      }
    } catch (e) {
      // Nothing failed (any more): the library is open, and the app starts as usual, with nothing left from before.
      if (e?.code === 'not_failed') {
        retried = null;
        retryError = '';
        retryNote = '';
        onDone();
      } else {
        retryError = e?.message ?? String(e);
        retryNote = '';
      }
    } finally {
      retrying = false;
    }
  }

  async function use(folder) {
    busy = true;
    error = '';
    try {
      await setUpLibrary(folder, true);
      onDone();
    } catch (e) {
      if (e?.code === 'already_configured') onDone();
      else error = e.message;
    } finally {
      busy = false;
    }
  }

  /** The folder browser's choice: the library is kept in that folder; a refusal shows in the browser. */
  async function useChosen(listing) {
    try {
      await setUpLibrary(listing.path, false);
    } catch (e) {
      if (e?.code !== 'already_configured') throw e;
    }
    onDone();
  }
</script>

<main class="wrap setup">
  <div class="setup-scene" aria-hidden="true">
    <Oakblue mode="perch" flower />
    {@html SCENE_MEADOW}
  </div>
  <div class="text">
    <span class="eyebrow">Welcome to Butterfly Atlas</span>
    <h1>Where should Butterfly Atlas keep your photos?</h1>
    {#if problem}<p class="problem" role="alert">{problem}</p>{/if}
    {#if folder}<p class="kept-folder">Your photos are still in <span class="mono">{folder}</span>; choosing another folder starts a new, empty library.</p>{/if}
    {#if canRetry}
      <div class="actions">
        <button class="btn" type="button" aria-disabled={retrying} onclick={retry}>{retrying ? 'Trying again…' : 'Try again'}</button>
      </div>
      <p class="retried" role="status">{retryNote}</p>
    {/if}
    <p class="muted">Your library is an ordinary folder on this computer, with a folder for each species you photograph.</p>
    <div class="choice" aria-busy={busy}>
      <span class="path mono">{status.suggestedFolder}</span>
      {#if suggestedNote}<p class="hint">{suggestedNote}</p>{/if}
      <div class="actions">
        <button class="btn" type="button" disabled={busy} onclick={() => use(status.suggestedFolder)}>{busy ? 'Setting up…' : USE_THIS_FOLDER}</button>
        <button class="btn ghost" type="button" bind:this={chooseButton} disabled={busy} onclick={choose}>Choose a different folder</button>
      </div>
    </div>
    {#if error}<p class="problem" role="alert">{error}</p>{/if}
  </div>
</main>

{#if choosing}
  <FolderBrowser setup returnFocus={() => chooseButton} onChoose={useChosen} onClose={back} />
{/if}
