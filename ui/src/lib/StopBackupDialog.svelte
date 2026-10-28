<script>
  import { cancelBackup } from './api.js';
  import Dialog from './Dialog.svelte';
  import { keptLine, stopLine } from './backups.js';
  import { backupChanged, backupGone, backupStopping } from './status.svelte.js';
  import { showToast } from './toast.svelte.js';
  import { STOPPING } from './messages.js';

  /**
   * "Stop this backup?" (Phase 6 contract §4, the stop dialog of an export): Cancel on the card and Discard on Home and in
   * the footer are the same. Keep the copied files (the default) or Remove the files this backup wrote (only those, and
   * only if unchanged); Stop backup asks the server (POST /api/backup/{id}/cancel), the status follows (backupChanged),
   * then `onStopped(answer)` closes it. When Remove left files (they changed since, or could not be reached) a toast
   * says how many ("N files were kept: …"). While it is stopping it can't be closed; a failure says why. `run` is the
   * Backup or the status's Backups.Status. A backup that has already finished says nothing: the status and the screen read
   * it again (backupGone), then `onGone()` (by default `onClose`).
   */
  let { run, onStopped, onClose, onGone = undefined, returnFocus = undefined, busy = $bindable(false) } = $props();

  const uid = $props.id();
  let remove = $state(false);
  let error = $state('');

  async function stop() {
    if (busy) return;
    busy = true;
    error = '';
    backupStopping(run.id);
    let answer;
    try {
      answer = await cancelBackup(run.id, remove);
    } catch (e) {
      backupStopping(null);
      busy = false;
      if (e?.code === 'backup_finished') {
        backupGone();
        await (onGone ?? onClose)();
      } else {
        error = e.message;
      }
      return;
    }
    backupChanged(answer);
    const kept = keptLine({ cancel: answer.cancel ?? (remove ? 'remove' : 'keep'), notRemoved: answer.notRemoved ?? 0 });
    if (kept) showToast(kept);
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

<Dialog title="Stop this backup?" onClose={close} {returnFocus}>
  <p>{stopLine(run)}</p>
  <fieldset class="ropt-set">
    <legend class="sr-only">The copied files</legend>
    <label class="ropt"><input type="radio" name="{uid}-files" checked={!remove} data-autofocus onchange={() => (remove = false)} /><span>Keep the copied files</span></label>
    <label class="ropt"><input type="radio" name="{uid}-files" checked={remove} aria-describedby="{uid}-only" onchange={() => (remove = true)} /><span>Remove the files this backup wrote</span></label>
    <span class="se-note muted" id="{uid}-only">Nothing else in that folder is touched. A file that has changed since the backup is kept.</span>
  </fieldset>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={close}>Go back</button>
    <button class="btn" type="button" aria-disabled={busy} onclick={stop}>{busy ? STOPPING : 'Stop backup'}</button>
  {/snippet}
</Dialog>

<style>
  .se-note { padding: 0 10px 0 36px; font-size: 13px; }
</style>
