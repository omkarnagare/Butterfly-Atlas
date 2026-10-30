<script>
  import { onMount, tick } from 'svelte';
  import { deleteOldCopy, getLibraryChange, moveLibrary, switchLibrary } from './api.js';
  import Dialog from './Dialog.svelte';
  import FolderBrowser from './FolderBrowser.svelte';
  import LoadError from './LoadError.svelte';
  import { WHAT_LABELS } from './backups.js';
  import { longDate } from './dates.js';
  import { formatBytes } from './filter.js';
  import { loadProblem } from './problems.js';
  import { changeStarted } from './status.svelte.js';
  import {
    CANCEL, DELETE_OLD_COPY, DELETE_OLD_COPY_TITLE, DELETING, FIXED_FOLDER_NOTE, FOLDER, FREE_ON_DRIVE, GO_TO_BACKUP, LIBRARY_FOLDER,
    LIBRARY_FOLDER_HINT, MOVE_LIBRARY, NO_BACKUP_YET, OLD_COPY_DELETED, SIZE, USE_DIFFERENT_LIBRARY, andMoreLine, deleteOldCopyAsk,
    lastBackupLine, oldCopyLeftLine, oldCopyLine, otherFilesLeftLine,
  } from './messages.js';

  /**
   * Settings → Library folder (change-library-folder design §2, §3, §9; contract §3 GET /api/library/change): the folder,
   * the library's size and its drive's free space, and Move library… and Use a different library…, each opening the
   * folder browser in its mode; the folder chosen there starts the change (a refusal shows beside the browser's button),
   * and every window then shows the change's screen (`changeStarted`). Under -Datlas.dataDir (`fixed`) both buttons are
   * disabled with the note why.
   * After a move, the old copy: "The old copy of your library is still at … (12.4 GB)." with Delete old copy, whose
   * confirmation names the library in use and the last backup ("Your last backup (Everything) was on 27 October.", or
   * "You have no backup yet." with Go to Backup, `onGoToBackup`). What the delete did is said in plain words: deleted, or
   * how many files couldn't be and where (the first ten, "and N more"), when Delete can be chosen again; files that
   * aren't part of a library are named as left. A refused delete says why in the confirmation.
   */
  let { onGoToBackup } = $props();

  const uid = $props.id();
  let library = $state(null);
  let error = $state('');
  let loads = 0;
  // 'move' or 'switch' while the folder browser is open.
  let choosing = $state(null);
  let moveButton = $state(null);
  let switchButton = $state(null);
  let deleteButton = $state(null);
  let confirming = $state(false);
  let deleting = $state(false);
  let deleteError = $state('');
  // What the last delete did, with the folder it was in: `{ folder, deleted, left, leftCount }`.
  let result = $state(null);

  async function load() {
    const mine = ++loads;
    try {
      const next = await getLibraryChange();
      if (mine !== loads) return;
      library = next;
      error = '';
    } catch (e) {
      if (mine === loads && library === null) error = loadProblem(e);
    }
  }

  onMount(load);

  function choose(kind) {
    if (library?.fixed) return;
    choosing = kind;
  }

  /** The browser's choice starts the change; a refusal is thrown back for the browser to show beside its button. */
  async function start(listing) {
    const answer = await (choosing === 'move' ? moveLibrary : switchLibrary)(listing.path);
    choosing = null;
    changeStarted(answer);
  }

  function openConfirm() {
    deleteError = '';
    confirming = true;
  }

  function closeConfirm() {
    if (!deleting) confirming = false;
  }

  async function goToBackup() {
    confirming = false;
    await tick();
    onGoToBackup?.();
  }

  async function remove() {
    if (deleting) return;
    const folder = library.oldCopy.folder;
    deleting = true;
    deleteError = '';
    try {
      const answer = await deleteOldCopy();
      result = { folder, deleted: answer?.deleted === true, left: answer?.left ?? [], leftCount: answer?.leftCount ?? 0 };
      confirming = false;
      await load();
    } catch (e) {
      deleteError = e.message;
    } finally {
      deleting = false;
    }
  }

  let lastBackup = $derived(library?.oldCopy?.lastBackup ?? null);
  let more = $derived(result ? result.leftCount - result.left.length : 0);
  let returnTo = $derived(choosing === 'move' ? moveButton : switchButton);
</script>

<section class="sec set" aria-labelledby="{uid}-h">
  <div class="sec-h"><h2 id="{uid}-h">{LIBRARY_FOLDER}</h2><span class="muted">{LIBRARY_FOLDER_HINT}</span></div>
  {#if error}
    <LoadError message={error} onRetry={load} />
  {:else if library === null}
    <p class="muted loading" role="status">Opening…</p>
  {:else}
    <dl class="set-about">
      <dt>{FOLDER}</dt><dd class="mono" dir="auto">{library.folder}</dd>
      <dt>{SIZE}</dt><dd>{formatBytes(library.size ?? 0)}</dd>
      <dt>{FREE_ON_DRIVE}</dt><dd>{formatBytes(library.free ?? 0)}</dd>
    </dl>
    <p class="lf-actions">
      <button class="btn ghost" type="button" bind:this={moveButton} aria-disabled={library.fixed}
              aria-describedby={library.fixed ? `${uid}-fixed` : undefined} onclick={() => choose('move')}>{MOVE_LIBRARY}</button>
      <button class="btn ghost" type="button" bind:this={switchButton} aria-disabled={library.fixed}
              aria-describedby={library.fixed ? `${uid}-fixed` : undefined} onclick={() => choose('switch')}>{USE_DIFFERENT_LIBRARY}</button>
    </p>
    {#if library.fixed}<p class="set-note muted" id="{uid}-fixed">{FIXED_FOLDER_NOTE}</p>{/if}
    {#if library.oldCopy}
      <div class="set-unread lf-old">
        <p>{oldCopyLine(library.oldCopy.folder, formatBytes(library.oldCopy.size ?? 0))}</p>
        <button class="btn ghost" type="button" bind:this={deleteButton} onclick={openConfirm}>{DELETE_OLD_COPY}</button>
      </div>
    {/if}
    <div role="status">
      {#if result}
        <div class="lf-result">
          {#if result.deleted}
            <p>{OLD_COPY_DELETED}</p>
            {#if result.leftCount > 0}<p>{otherFilesLeftLine(result.folder, result.leftCount)}</p>{/if}
          {:else}
            <p>{oldCopyLeftLine(result.folder, result.leftCount)}</p>
          {/if}
          {#if result.left.length}
            <ul class="lf-left">
              {#each result.left as path (path)}<li class="mono" dir="auto">{path}</li>{/each}
              {#if more > 0}<li>{andMoreLine(more)}</li>{/if}
            </ul>
          {/if}
        </div>
      {/if}
    </div>
  {/if}
</section>

{#if choosing}
  <FolderBrowser change={choosing} librarySize={library.size ?? 0} returnFocus={() => returnTo} onChoose={start} onClose={() => (choosing = null)} />
{/if}

{#if confirming && library?.oldCopy}
  <Dialog title={DELETE_OLD_COPY_TITLE} onClose={closeConfirm} returnFocus={() => deleteButton}>
    <p>{deleteOldCopyAsk(library.oldCopy.folder, library.folder)}</p>
    {#if lastBackup}
      <p>{lastBackupLine(WHAT_LABELS[lastBackup.what] ?? lastBackup.what, longDate(lastBackup.finishedAt))}</p>
    {:else}
      <p>{NO_BACKUP_YET} <button class="linkish" type="button" onclick={goToBackup}>{GO_TO_BACKUP}</button></p>
    {/if}
    {#if deleteError}<p class="problem" role="alert">{deleteError}</p>{/if}
    {#snippet actions()}
      <button class="btn ghost" type="button" aria-disabled={deleting} data-autofocus onclick={closeConfirm}>{CANCEL}</button>
      <button class="btn" type="button" aria-disabled={deleting} onclick={remove}>{deleting ? DELETING : DELETE_OLD_COPY}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  /* as Settings' About and its notes */
  .set-about { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 6px 18px; margin: 0; }
  .set-about dt { color: var(--muted); }
  .set-about dd { margin: 0; overflow-wrap: anywhere; }
  .set-about dd.mono { font-size: 13px; }
  .set-note { margin: 8px 0 0; font-size: 13px; }
  .set-unread { padding: 10px 12px; border-radius: 12px; font-size: 14px; background: color-mix(in oklab, var(--glow) 16%, var(--surface));
    border: 1px solid color-mix(in oklab, var(--glow) 60%, var(--line)); }
  .lf-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0 0; }
  .lf-old { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-top: 12px; }
  .lf-old p { margin: 0; flex: 1 1 260px; }
  .lf-result { margin-top: 12px; }
  .lf-result p { margin: 0 0 6px; }
  .lf-left { margin: 0; padding-left: 20px; font-size: 13px; }
  .lf-left li { overflow-wrap: anywhere; }
  .linkish { padding: 0; font: inherit; color: var(--accent); text-decoration: underline; text-underline-offset: 2px; background: none; border: 0; cursor: pointer; }
</style>
