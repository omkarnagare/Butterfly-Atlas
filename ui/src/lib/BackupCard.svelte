<script>
  import { untrack } from 'svelte';
  import { getBackup, getBackups, pauseBackup, resumeBackup } from './api.js';
  import { PROBLEM_KINDS, moreProblemsLine, problemsLine, sizeLine } from './exports.js';
  import { backupLine, backupPausedNotice, backupWhy, holdsPhotosLine, stopLine } from './backups.js';
  import { backupChanged, refreshStatus } from './status.svelte.js';
  import StopBackupDialog from './StopBackupDialog.svelte';
  import { CLOSED, RUNNING } from './states.js';
  import { HIDE_DETAILS, NOT_COPIED, PAUSE, PAUSING, PHOTOS_BACKED_UP, PHOTOS_NOT_COPIED, RESUME, SHOW_DETAILS } from './messages.js';

  /**
   * The backup card (Phase 6 contract §4–§5) on Home and in Settings, for the running or paused backup `run` (the
   * status's Backups.Status). Running: "Backing up your library — 3,210 of 12,400 photos", a bar, "1.6 of 4.2 GB" and the
   * path, with Pause and Cancel…; after Pause it reads "Pausing…" until the backup has stopped after its current file.
   * Paused: why (the notice of a full or missing drive, or "You paused a backup."), with Resume and Cancel…. A backup
   * the app's closing stopped (`pausedBy: 'closed'`) says "Backup stopped — the app was closed" with Continue and
   * Discard…; nothing restarts by itself. Cancel… and Discard… open the stop dialog (Keep or Remove). While running it
   * asks GET /api/backup every second for the live counts and sizes (a read that fails is asked again); stopped, it reads
   * once. Photos not copied are counted ("2 skipped, 1 failed") with their reasons behind Show details
   * (GET /api/backup/{id}). A refused action says why on the card and the status is read again. Buttons are
   * aria-disabled while a request is on its way. While the backup holds the photos (`holdsPhotos`, several-windows A6, A22)
   * the card says so, not muted.
   */
  let { run } = $props();

  const POLL_MS = 1000;
  const uid = $props.id();
  let busy = $state(false);
  let card = $state(null);
  let toggle = $state(null);
  let cancelButton = $state(null);
  let error = $state('');
  let pausedFor = $state(null);
  let live = $state(null);
  let stopping = $state(null);
  let open = $state(false);
  let details = $state(null);

  // The status is the truth about the state; the backup read brings the counts and sizes (and may be a second older).
  let shown = $derived(live?.id === run.id ? { ...live, state: run.state, pausedBy: run.pausedBy, notice: run.notice } : run);
  let running = $derived(run.state === RUNNING);
  let closed = $derived(!running && run.pausedBy === CLOSED);
  let pausing = $derived(running && pausedFor === run.id);
  let problems = $derived(problemsLine({ skipped: shown.skipped ?? 0, failed: shown.failed ?? 0 }));
  // What the open list was read for: the backup and how many photos were left out. Photos that fail later, or another
  // backup in this card, make it stale, and it is read again.
  let detailsKey = $derived(`${run.id}:${shown.skipped ?? 0}:${shown.failed ?? 0}`);

  // Once it has stopped, "Pausing…" is over: running again later (resumed from the footer) shows Pause.
  $effect(() => {
    if (!running) pausedFor = null;
  });

  // While running, asked every second, the next only after the last has answered; stopped, once (the counts of the
  // backup as it stopped). The id and the state are what it follows.
  $effect(() => {
    const id = run.id;
    const keepAsking = running;
    let stopped = false;
    let timer;
    const read = async () => {
      try {
        const answer = await getBackups();
        if (!stopped && answer?.current?.id === id) live = answer.current;
      } catch {
        // asked again a second later (the footer says when nothing answers)
      }
    };
    if (keepAsking) {
      const again = async () => {
        await read();
        if (!stopped) timer = setTimeout(again, POLL_MS);
      };
      timer = setTimeout(again, POLL_MS);
    } else {
      read();
    }
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  });

  async function act(request, after = () => {}) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      const answer = await request(run.id);
      after(answer);
      backupChanged(answer);
      const at = document.activeElement;
      if (!at || at === document.body || at === card || at === toggle) toggle?.focus();
    } catch (e) {
      error = e.message;
      refreshStatus();
    } finally {
      busy = false;
    }
  }

  // The answer may still say running: the backup stops after its current file.
  const pause = () => {
    if (!pausing) act(pauseBackup, (answer) => (pausedFor = answer.state === RUNNING ? answer.id : null));
  };
  const resume = () => act(resumeBackup, () => (pausedFor = null));
  const ask = () => {
    if (!busy) stopping = run;
  };

  let detailLoads = 0;

  // Another backup's list is not kept; the same backup's list stays on screen while it is read again (a longer one).
  async function loadDetails() {
    const mine = ++detailLoads;
    const id = run.id;
    const key = detailsKey;
    if (!details || details.id !== id) details = { id, key, loading: true, error: '', list: [], more: 0 };
    else details = { ...details, key, loading: !details.list.length && !details.more, error: '' };
    try {
      const answer = await getBackup(id);
      if (mine === detailLoads) details = { id, key, loading: false, error: '', list: answer.problems ?? [], more: answer.moreProblems ?? 0 };
    } catch (e) {
      if (mine === detailLoads) details = { id, key, loading: false, error: e.message, list: [], more: 0 };
    }
  }

  $effect(() => {
    const key = detailsKey;
    if (!open || !problems) return;
    untrack(() => {
      if (!details || details.error || details.key !== key) loadDetails();
    });
  });

  const toggleDetails = () => (open = !open);
</script>

<section class="xp bk" tabindex="-1" aria-labelledby="{uid}-line" bind:this={card}>
  <p class="xp-line" id="{uid}-line">{running ? backupLine(shown) : backupWhy(shown)}</p>
  {#if running || closed || shown.total > 0}
    {#if shown.total > 0}
      <progress class="xp-bar" max={Math.max(shown.total, 1)} value={shown.done} aria-label={PHOTOS_BACKED_UP}></progress>
    {:else}
      <progress class="xp-bar" aria-label={PHOTOS_BACKED_UP}></progress>
    {/if}
  {/if}
  <div class="xp-facts">
    {#if shown.bytesTotal > 0}<span>{sizeLine(shown)}</span>{/if}
    <span class="mono" dir="auto">{run.path}</span>
  </div>
  {#if closed}
    <p class="xp-notice">{backupPausedNotice(shown)} {stopLine(shown)}</p>
  {:else if !running}
    <p class="xp-notice">{backupPausedNotice(shown)}</p>
  {/if}
  {#if run.holdsPhotos}<p class="bk-hold">{holdsPhotosLine(run)}</p>{/if}
  {#if problems}
    <p class="xp-p">{problems}
      <button class="xp-more" type="button" aria-expanded={open} aria-controls={open ? `${uid}-d` : undefined} onclick={toggleDetails}>{open ? HIDE_DETAILS : SHOW_DETAILS}</button></p>
  {/if}
  {#if open && problems}
    <div class="xp-d" id="{uid}-d">
      {#if !details || details.loading}
        <p class="muted" role="status">Opening…</p>
      {:else if details.error}
        <div class="xp-err"><p class="problem" role="alert">{details.error}</p><button class="btn ghost" type="button" onclick={loadDetails}>Try again</button></div>
      {:else}
        <!-- tabindex: the list scrolls, and WebKit only scrolls what has focus from the keyboard -->
        <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
        <ul class="xp-probs" aria-label={PHOTOS_NOT_COPIED} tabindex="0">
          {#each details.list as problem}
            <li><span class="badge">{PROBLEM_KINDS[problem.kind] ?? problem.kind ?? NOT_COPIED}</span> <span class="mono">{problem.file ?? problem.rel}</span> — {problem.reason}</li>
          {/each}
        </ul>
        {#if details.more > 0}<p class="muted">{moreProblemsLine(details.more)}</p>{/if}
      {/if}
    </div>
  {/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  <div class="xp-acts">
    <button class={running ? 'btn ghost' : 'btn'} type="button" bind:this={toggle} aria-disabled={busy || pausing}
            onclick={running ? pause : resume}>{running ? (pausing ? PAUSING : PAUSE) : closed ? 'Continue' : RESUME}</button>
    <button class="btn ghost" type="button" bind:this={cancelButton} aria-disabled={busy} onclick={ask}>{closed ? 'Discard…' : 'Cancel…'}</button>
  </div>
</section>

{#if stopping}
  <StopBackupDialog run={stopping} returnFocus={() => (cancelButton?.isConnected ? cancelButton : document.querySelector('main'))}
                    onStopped={() => (stopping = null)} onGone={() => (stopping = null)} onClose={() => (stopping = null)} />
{/if}

<style>
  .xp { display: grid; gap: 10px; margin-bottom: 22px; padding: 16px; border-radius: 20px; background: var(--surface);
    border: 1px solid color-mix(in oklab, var(--accent) 45%, var(--line)); animation: rise .5s cubic-bezier(.2,.75,.2,1) both; }
  .xp:focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }
  .xp-line { margin: 0; font: 650 clamp(18px, 2.4vw, 22px)/1.25 var(--display); font-variant-numeric: tabular-nums; }
  .xp-bar { width: 100%; height: 8px; -webkit-appearance: none; appearance: none; border: 0; border-radius: 99px; overflow: hidden; background: var(--surface-2); }
  .xp-bar::-webkit-progress-bar { background: var(--surface-2); }
  .xp-bar::-webkit-progress-value { background: var(--accent); border-radius: 99px; transition: width .4s; }
  .xp-bar::-moz-progress-bar { background: var(--accent); }
  .xp-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; font-size: 13px; color: var(--muted); font-variant-numeric: tabular-nums; }
  .xp-facts .mono { min-width: 0; overflow-wrap: anywhere; }
  .bk-hold { margin: 0; font-size: 14px; color: var(--ink); }
  .xp-notice { margin: 0; padding: 10px 12px; border-radius: 12px; font-size: 14px; background: color-mix(in oklab, var(--glow) 16%, var(--surface));
    border: 1px solid color-mix(in oklab, var(--glow) 60%, var(--line)); }
  .xp-p { margin: 0; font-size: 14px; }
  .xp-more { margin-left: 6px; font-weight: 600; color: var(--accent); text-decoration: underline; text-underline-offset: 2px; }
  .xp-more:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 4px; }
  .xp-d { display: grid; gap: 4px; }
  .xp-err { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .xp-probs { margin: 0; padding: 8px 10px; list-style: none; display: grid; gap: 4px; max-height: 40vh; overflow-y: auto; font-size: 13px;
    border: 1px solid var(--line); border-radius: 12px; background: var(--bg); }
  .xp-probs .badge { padding: 2px 8px; font-size: 11px; background: var(--surface-2); }
  .xp-probs .mono { overflow-wrap: anywhere; }
  .xp-acts { display: flex; flex-wrap: wrap; gap: 8px; }
  /* inactive, but keeps its place and shows it is not available; pointer events stay so a click on "Pausing…" does not
     land on the card and take focus off the one Pause / Resume button */
  .xp-acts .btn[aria-disabled='true'] { pointer-events: auto; cursor: not-allowed; }
  .xp-acts .btn[aria-disabled='true']:hover { transform: none; box-shadow: none; }
</style>
