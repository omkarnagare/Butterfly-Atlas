<script>
  import { onDestroy, tick } from 'svelte';
  import Oakblue from '../lib/Oakblue.svelte';
  import { inspectFolder, retrySetup, setUpLibrary } from '../lib/api.js';
  import { USE_THIS_FOLDER } from '../lib/messages.js';
  import { meadow } from '../lib/meadow.js';

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

  let choosing = $state(false);
  let path = $state('');
  let check = $state(null);
  let error = $state('');
  let busy = $state(false);
  let timer;
  let input = $state();
  let chooseButton = $state();

  onDestroy(() => clearTimeout(timer));

  function choose() {
    choosing = true;
    tick().then(() => input?.focus());
  }

  function back() {
    clearTimeout(timer);
    check = null;
    choosing = false;
    error = '';
    tick().then(() => chooseButton?.focus());
  }

  function onInput(event) {
    const value = event.currentTarget.value;
    clearTimeout(timer);
    check = null;
    error = '';
    if (!value.trim()) return;
    timer = setTimeout(async () => {
      try {
        const result = await inspectFolder(value);
        if (value === path) check = result;
      } catch {
        check = null;
      }
    }, 250);
  }

  function hint(c) {
    if (!c) return '';
    if (!c.absolute) return 'Enter the full path of a folder.';
    if (c.hasLibrary) return 'This folder already has a Butterfly Atlas library. It will open as it is.';
    if (c.exists && !c.folder) return 'That is a file, not a folder.';
    if (c.exists) return c.writable ? 'This folder will hold your library.' : 'Butterfly Atlas cannot save files in this folder.';
    return c.writable ? 'Butterfly Atlas will create this folder.' : 'Butterfly Atlas cannot create a folder here.';
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
      await setUpLibrary(folder);
      onDone();
    } catch (e) {
      if (e?.code === 'already_configured') onDone();
      else error = e.message;
    } finally {
      busy = false;
    }
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
    {#if !choosing}
      <div class="choice" aria-busy={busy}>
        <span class="path mono">{status.suggestedFolder}</span>
        <div class="actions">
          <button class="btn" type="button" disabled={busy} onclick={() => use(status.suggestedFolder)}>{busy ? 'Setting up…' : USE_THIS_FOLDER}</button>
          <button class="btn ghost" type="button" bind:this={chooseButton} disabled={busy} onclick={choose}>Choose a different folder</button>
        </div>
      </div>
    {:else}
      <form class="choice" aria-busy={busy} onsubmit={(e) => { e.preventDefault(); use(path); }}>
        <label for="folder">Folder path</label>
        <input id="folder" class="field mono" bind:this={input} bind:value={path} oninput={onInput} aria-describedby="folder-hint"
               placeholder="For example, /Volumes/Photos/Butterflies"
               autocomplete="off" spellcheck="false" />
        <p class="hint" id="folder-hint" aria-live="polite">{hint(check)}</p>
        <div class="actions">
          <button class="btn" type="submit" disabled={busy || !path.trim()}>
            {busy ? 'Setting up…' : check?.hasLibrary ? 'Open this existing library' : USE_THIS_FOLDER}
          </button>
          <button class="btn ghost" type="button" disabled={busy} onclick={back}>Back</button>
        </div>
      </form>
    {/if}
    {#if error}<p class="problem" role="alert">{error}</p>{/if}
  </div>
</main>
