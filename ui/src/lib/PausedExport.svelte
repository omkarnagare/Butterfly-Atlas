<script>
  import { resumeExport } from './api.js';
  import { copiedLine, pausedLine, pausedNotice } from './exports.js';
  import { exportChanged, exportGone } from './status.svelte.js';
  import { showProblem } from './toast.svelte.js';
  import StopExportDialog from './StopExportDialog.svelte';
  import { PAUSED } from './states.js';
  import { PAUSED_EXPORT } from './messages.js';

  /**
   * A paused export (/api/status `export`, Phase 4b contract §5) in the footer's quiet line: "Export paused at 3,200 of
   * 50,000." with Continue export (POST resume; a refusal is said in a toast, except one because the export has already
   * finished: the status is read again, exportGone) and Discard… (the stop dialog, E4). `card` is Home's version, like
   * a stopped import: the notice (or "You paused an export.") and "3,200 of 50,000 photos copied to <path>.". Both
   * buttons are aria-disabled while Continue export is asked. Once continued or discarded the line goes, and focus goes
   * to the page's main (from the line or the page; focus moved elsewhere meanwhile stays); so it does when the line
   * goes with its stop dialog open (resumed or finished elsewhere). `label` names the group; a page that names the
   * region around it passes ''.
   */
  let { run, card = false, label = PAUSED_EXPORT } = $props();

  let busy = $state(false);
  let stopping = $state(null);
  let line = $state(null);
  let discardButton = $state(null);

  const main = () => document.querySelector('main');

  async function resume() {
    if (busy) return;
    busy = true;
    try {
      const answer = await resumeExport(run.id);
      exportChanged(answer);
      // Running again, this line goes: focus goes to the page, unless it has moved elsewhere meanwhile.
      const at = document.activeElement;
      if (answer.state !== PAUSED && (!at || at === document.body || line?.contains(at))) main()?.focus();
    } catch (e) {
      if (e?.code === 'export_finished') exportGone();
      else showProblem(e);
    } finally {
      busy = false;
    }
  }

  function discard() {
    if (!busy) stopping = run;
  }

  // Stopped, or gone with the dialog open (Discard… has left the page), this line goes with the export: focus goes to
  // the page. (A plain flag: a teardown reads state as it was.)
  let stopped = false;
  const back = () => (stopped || !discardButton?.isConnected ? main() : discardButton);
</script>

<div class={card ? 'stops' : 'stops quiet'} role={label ? 'group' : undefined} aria-label={label || undefined} bind:this={line}>
  {#if card}
    <p class="xpaused-why">{pausedNotice(run)}</p>
    <p class="muted">{copiedLine(run)}</p>
  {/if}
  <p>
    {#if !card}<span>{pausedLine(run)}</span>{/if}
    <button class={card ? 'btn' : ''} type="button" aria-disabled={busy} onclick={resume}>Continue export</button>
    <button class={card ? 'btn ghost' : ''} type="button" bind:this={discardButton} aria-disabled={busy} onclick={discard}>Discard…</button>
  </p>
</div>

{#if stopping}
  <StopExportDialog run={stopping} returnFocus={back} onStopped={() => { stopped = true; stopping = null; }}
                    onGone={() => { stopped = true; stopping = null; }} onClose={() => (stopping = null)} />
{/if}

<style>
  /* the layout is the stopped imports' (atlas.css .stops); the footer's buttons read as its links */
  .xpaused-why { font-weight: 650; }
  .quiet button { color: var(--muted); text-decoration: underline; text-underline-offset: 2px; font-size: inherit; }
  .quiet button:hover { color: var(--ink); }
  .quiet button[aria-disabled='true'] { opacity: .45; cursor: default; }
</style>
