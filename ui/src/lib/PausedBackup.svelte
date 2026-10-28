<script>
  import { resumeBackup } from './api.js';
  import { backupWhy } from './backups.js';
  import { backupChanged, backupGone } from './status.svelte.js';
  import { showProblem } from './toast.svelte.js';
  import StopBackupDialog from './StopBackupDialog.svelte';
  import { CLOSED, PAUSED } from './states.js';

  /**
   * A paused or stopped backup (/api/status `backup`, Phase 6 contract §5) in the footer's quiet line: "Backup stopped —
   * the app was closed" (`pausedBy: 'closed'`; nothing restarts by itself) or "Backup paused at 3,210 of 12,400." followed
   * by its notice (a full or missing drive says so here too), with Continue backup (POST resume; a refusal is said in a
   * toast, except one because the backup has already finished: the status is read again) and Discard… (the stop dialog).
   * Both buttons are aria-disabled while Continue backup is asked. Once continued or discarded the line goes, and focus
   * goes to the page's main (from the line or the page; focus moved elsewhere meanwhile stays). The footer's version of
   * Home's BackupCard, like PausedExport is for an export.
   */
  let { run, label = 'Paused backup' } = $props();

  let busy = $state(false);
  let stopping = $state(null);
  let line = $state(null);
  let discardButton = $state(null);

  const main = () => document.querySelector('main');

  async function resume() {
    if (busy) return;
    busy = true;
    try {
      const answer = await resumeBackup(run.id);
      backupChanged(answer);
      const at = document.activeElement;
      if (answer.state !== PAUSED && (!at || at === document.body || line?.contains(at))) main()?.focus();
    } catch (e) {
      if (e?.code === 'backup_finished') backupGone();
      else showProblem(e);
    } finally {
      busy = false;
    }
  }

  function discard() {
    if (!busy) stopping = run;
  }

  // Stopped, or gone with the dialog open, this line goes with the backup: focus goes to the page.
  let stopped = false;
  const back = () => (stopped || !discardButton?.isConnected ? main() : discardButton);
</script>

<div class="stops quiet" role="group" aria-label={label} bind:this={line}>
  <p>
    <span>{backupWhy(run)}{run.pausedBy !== CLOSED && run.notice ? ` ${run.notice}` : ''}</span>
    <button type="button" aria-disabled={busy} onclick={resume}>Continue backup</button>
    <button type="button" bind:this={discardButton} aria-disabled={busy} onclick={discard}>Discard…</button>
  </p>
</div>

{#if stopping}
  <StopBackupDialog run={stopping} returnFocus={back} onStopped={() => { stopped = true; stopping = null; }}
                    onGone={() => { stopped = true; stopping = null; }} onClose={() => (stopping = null)} />
{/if}

<style>
  /* the layout is the stopped imports' (atlas.css .stops); the footer's buttons read as its links */
  .quiet button { color: var(--muted); text-decoration: underline; text-underline-offset: 2px; font-size: inherit; }
  .quiet button:hover { color: var(--ink); }
  .quiet button[aria-disabled='true'] { opacity: .45; cursor: default; }
</style>
