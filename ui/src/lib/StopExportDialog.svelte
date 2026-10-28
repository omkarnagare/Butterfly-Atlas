<script>
  import { cancelExport } from './api.js';
  import Dialog from './Dialog.svelte';
  import { stopLine } from './exports.js';
  import { exportChanged, exportGone, exportStopping } from './status.svelte.js';
  import { STOPPING } from './messages.js';

  /**
   * "Stop this export?" (Phase 4b contract E4): Cancel on the progress card and Discard on Home and in the footer are
   * the same. Keep the copied files (the default) or Remove the files this export wrote; Stop export asks the server
   * (POST /api/exports/{id}/cancel), the status follows (exportChanged), then `onStopped(answer)` closes it. While it
   * is stopping it can't be closed; a failure says why. `run` is the Export or the status's ExportStatus. Refused because
   * the export has already finished, it says nothing: the status and the screen read it again (exportGone), then
   * `onGone()` (by default `onClose`). `busy` (bindable) says a stop is on its way, until `onStopped` has finished.
   */
  let { run, onStopped, onClose, onGone = undefined, returnFocus = undefined, busy = $bindable(false) } = $props();

  const uid = $props.id();
  let remove = $state(false);
  let error = $state('');

  async function stop() {
    if (busy) return;
    busy = true;
    error = '';
    exportStopping(run.id);
    let answer;
    try {
      answer = await cancelExport(run.id, remove);
    } catch (e) {
      exportStopping(null);
      busy = false;
      if (e?.code === 'export_finished') {
        exportGone();
        await (onGone ?? onClose)();
      } else {
        error = e.message;
      }
      return;
    }
    exportChanged(answer);
    try {
      await onStopped(answer);
    } finally {
      busy = false;
    }
  }

  function close() {
    if (!busy) onClose();
  }
</script>

<Dialog title="Stop this export?" onClose={close} {returnFocus}>
  <p>{stopLine(run)}</p>
  <fieldset class="ropt-set">
    <legend class="sr-only">The copied files</legend>
    <label class="ropt"><input type="radio" name="{uid}-files" checked={!remove} data-autofocus onchange={() => (remove = false)} /><span>Keep the copied files</span></label>
    <label class="ropt"><input type="radio" name="{uid}-files" checked={remove} aria-describedby="{uid}-only" onchange={() => (remove = true)} /><span>Remove the files this export wrote</span></label>
    <span class="se-note muted" id="{uid}-only">Nothing else in that folder is touched.</span>
  </fieldset>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={close}>Go back</button>
    <button class="btn" type="button" aria-disabled={busy} onclick={stop}>{busy ? STOPPING : 'Stop export'}</button>
  {/snippet}
</Dialog>

<style>
  .se-note { padding: 0 10px 0 36px; font-size: 13px; }
</style>
