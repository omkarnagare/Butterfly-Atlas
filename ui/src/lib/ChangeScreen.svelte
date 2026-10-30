<script>
  import { cancelChange, dismissChange } from './api.js';
  import { sizeLine } from './exports.js';
  import { NOT_ANSWERING_PLAIN, refreshStatus, status } from './status.svelte.js';
  import {
    BACK_TO_SETTINGS, CANCEL, CANCELLING, CHANGE_BLOCKS, COULD_NOT_REOPEN, FILES_COPIED, GOING_BACK, LIBRARY_UNCHANGED, MOVE_CANCELLED,
    MOVE_FAILED, MOVING_LIBRARY, STEP_CANCELLING, STEP_CHECKING_COPY, STEP_CHECKING_LIBRARY, STEP_COPYING, STEP_COPYING_DATABASE,
    STEP_REOPENING, STEP_SWITCHING, SWITCH_CANCELLED, SWITCH_FAILED, SWITCHING_LIBRARY, changeToLine, copyLeftLine, filesLine,
    CLOSED_TITLE, LIBRARY_NOW_AT, OPEN_AGAIN, OPENS_IN_NEW_WINDOW, REOPENING_TITLE,
  } from './messages.js';

  /**
   * The full-page screen every window shows in place of its page while the library folder is moved or changed
   * (change-library-folder design §5–§7), from the status's `changing` (contract §2), read again on every status event.
   * - Running: what it is doing ("Copying your photos and files…"), where to, and for a move's copy the files and bytes
   *   with a bar; Cancel while `canCancel` (a refusal says why beside it).
   * - `failed`: why (`problem`; none after a cancel that left files), that the library is as it was, what of the copy
   *   couldn't be removed (`left`), and Back to Settings, which dismisses it (POST dismiss) and opens Settings.
   * - `reopening`: the last page, kept when the app has gone: "Reopening Butterfly Atlas" (restart: it opens in a new
   *   window, this one can be closed) or "Butterfly Atlas has closed" (quit: open it again; first "Butterfly Atlas
   *   couldn't reopen itself." and why when it tried), with the library's folder on its own line, wrapping anywhere.
   */
  let { changing } = $props();

  const uid = $props.id();
  let cancelling = $state(false);
  let cancelError = $state('');
  let leaving = $state(false);
  let leaveError = $state('');

  let move = $derived(changing.kind === 'move');
  let step = $derived(changing.step);
  let failed = $derived(step === 'failed');
  let reopen = $derived(step === 'reopening' ? changing.reopen : null);
  let words = $derived({
    copying: STEP_COPYING,
    copying_database: STEP_COPYING_DATABASE,
    checking: move ? STEP_CHECKING_COPY : STEP_CHECKING_LIBRARY,
    switching: STEP_SWITCHING,
    cancelling: STEP_CANCELLING,
    reopening: STEP_REOPENING,
  }[step] ?? STEP_SWITCHING);
  let files = $derived(changing.files ?? { done: 0, total: 0 });
  let bytes = $derived(changing.bytes ?? { done: 0, total: 0 });
  let counted = $derived(step === 'copying' && files.total > 0);

  async function cancel() {
    if (cancelling) return;
    cancelling = true;
    cancelError = '';
    try {
      await cancelChange();
      refreshStatus();
    } catch (e) {
      cancelError = e.message;
    } finally {
      cancelling = false;
    }
  }

  async function back() {
    if (leaving) return;
    leaving = true;
    leaveError = '';
    try {
      await dismissChange();
      window.location.hash = '#/settings';
      status.changing = null;
      refreshStatus();
    } catch (e) {
      leaveError = e.message;
    } finally {
      leaving = false;
    }
  }
</script>

<main class="wrap change-screen" aria-labelledby="{uid}-h">
  {#if reopen}
    {#if reopen.how === 'quit' && reopen.why}
      <p class="problem">{COULD_NOT_REOPEN}</p>
      <p class="muted">{reopen.why}</p>
    {/if}
    <h1 id="{uid}-h">{reopen.how === 'restart' ? REOPENING_TITLE : CLOSED_TITLE}</h1>
    <p>{LIBRARY_NOW_AT} <span class="mono cs-to cs-folder" dir="auto">{reopen.folder}</span></p>
    <p>{reopen.how === 'restart' ? OPENS_IN_NEW_WINDOW : OPEN_AGAIN}</p>
  {:else if failed}
    <h1 id="{uid}-h">{changing.problem ? (move ? MOVE_FAILED : SWITCH_FAILED) : (move ? MOVE_CANCELLED : SWITCH_CANCELLED)}</h1>
    {#if changing.problem}<p class="problem" role="alert">{changing.problem}</p>{/if}
    <p>{LIBRARY_UNCHANGED}</p>
    {#if changing.left}<p class="cs-left">{copyLeftLine(changing.left.folder, changing.left.files)}</p>{/if}
    <div class="cs-actions">
      <button class="btn" type="button" aria-disabled={leaving} onclick={back}>{leaving ? GOING_BACK : BACK_TO_SETTINGS}</button>
      {#if leaveError}<p class="problem" role="alert">{leaveError}</p>{/if}
    </div>
  {:else}
    <h1 id="{uid}-h">{move ? MOVING_LIBRARY : SWITCHING_LIBRARY}</h1>
    <p class="mono cs-to" dir="auto">{changeToLine(changing.to)}</p>
    <div class="progress cs-progress">
      <!-- the status region keeps the step's words; the counts beside it change every second and are not announced -->
      <p>
        <span class="sr-only" role="status">{words}</span>
        <span aria-hidden="true">{words}</span>
      </p>
      {#if counted}
        <p class="cs-counts">{filesLine(files.done, files.total)}{bytes.total > 0 ? ` · ${sizeLine({ bytesDone: bytes.done, bytesTotal: bytes.total })}` : ''}</p>
        <progress max={files.total} value={files.done} aria-label={FILES_COPIED}></progress>
      {:else}
        <progress aria-label={words}></progress>
      {/if}
    </div>
    {#if changing.canCancel}
      <div class="cs-actions">
        <button class="btn ghost" type="button" aria-disabled={cancelling} onclick={cancel}>{cancelling ? CANCELLING : CANCEL}</button>
        {#if cancelError}<p class="problem" role="alert">{cancelError}</p>{/if}
      </div>
    {/if}
    {#if status.unreachable}<p class="problem" role="alert">{NOT_ANSWERING_PLAIN}</p>{/if}
    <p class="muted cs-note">{CHANGE_BLOCKS}</p>
  {/if}
</main>

<style>
  .change-screen { min-height: 100vh; display: grid; grid-template-columns: minmax(0, 1fr); align-content: center; gap: 14px; max-width: 720px; }
  .change-screen h1 { font-size: clamp(24px, 4vw, 34px); margin: 0; }
  .change-screen p { margin: 0; }
  .cs-to { overflow-wrap: anywhere; font-size: 14px; }
  .cs-folder { display: block; margin-top: 4px; }
  .change-screen h1, .change-screen p { min-width: 0; overflow-wrap: anywhere; }
  .cs-progress { display: grid; gap: 8px; }
  .cs-progress progress { width: 100%; }
  .cs-counts { font-variant-numeric: tabular-nums; }
  .cs-left { overflow-wrap: anywhere; }
  .cs-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
  .cs-note { font-size: 13px; }
</style>
