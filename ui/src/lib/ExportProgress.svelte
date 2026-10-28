<script>
  import { getExports, pauseExport, resumeExport } from './api.js';
  import { pausedLine, progressLine, sizeLine } from './exports.js';
  import { exportChanged, exportGone } from './status.svelte.js';
  import { RUNNING } from './states.js';
  import { PAUSE, PAUSING, PHOTOS_EXPORTED, RESUME } from './messages.js';

  /**
   * The progress card at the top of the Export screen (Phase 4b contract E2 and §5) for the running or paused export
   * `run`. Running: "Exporting photos… 3,200 of 50,000", a bar, "1.6 of 4.2 GB" and the path, with Pause and Cancel…;
   * after Pause it reads "Pausing…" until the export has stopped after its current file; paused: "Export paused at
   * 3,200 of 50,000." with the notice, Resume and Cancel…. Pause and Resume answer through `onRun(export)` (and the
   * status follows, exportChanged); Cancel… is `onCancel()` (the screen's stop dialog). While running it asks
   * GET /api/exports every second (through `read()`, which may answer null for an answer to drop) and hands the answer
   * to `onList({ current, past })`. Buttons are aria-disabled while a request is on its way; one refused because the
   * export has already finished says nothing (the status and the screen read it again, exportGone). The card takes
   * focus after a start (tabindex -1); `cardElement()` and `cancelElement()` are where the screen puts focus. Pause,
   * "Pausing…" and Resume are one button, so focus stays on it as it turns; after an answer focus is put there (WebKit
   * does not focus a clicked button) when it is on the card, on the button or has fallen to the page, not when it has
   * moved to Cancel… or outside the card meanwhile.
   */
  let { run, onRun, onList, onCancel, read = getExports } = $props();

  const POLL_MS = 1000;
  const uid = $props.id();
  let busy = $state(false);
  let card = $state(null);
  let toggle = $state(null);
  let cancelButton = $state(null);
  let error = $state('');
  let pausedFor = $state(null);

  let running = $derived(run.state === RUNNING);
  let pausing = $derived(running && pausedFor === run.id);

  // Once it has stopped, "Pausing…" is over: running again later (resumed from the footer or Home) shows Pause.
  $effect(() => {
    if (!running) pausedFor = null;
  });

  // The next request is asked for only after the last one has answered, so they never overlap.
  $effect(() => {
    if (!running) return undefined;
    let stopped = false;
    let timer;
    const again = async () => {
      try {
        const list = await read();
        if (!stopped && list) onList(list);
      } catch {
        // asked again a second later
      }
      if (!stopped) timer = setTimeout(again, POLL_MS);
    };
    timer = setTimeout(again, POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  });

  export const cardElement = () => card;
  export const cancelElement = () => cancelButton;

  async function act(request, after = () => {}) {
    if (busy) return;
    busy = true;
    error = '';
    try {
      const answer = await request(run.id);
      after(answer);
      exportChanged(answer);
      onRun(answer);
      const at = document.activeElement;
      if (!at || at === document.body || at === card || at === toggle) toggle?.focus();
    } catch (e) {
      if (e?.code === 'export_finished') exportGone();
      else error = e.message;
    } finally {
      busy = false;
    }
  }

  // The answer may still say running: the export stops after its current file.
  const pause = () => {
    if (!pausing) act(pauseExport, (answer) => (pausedFor = answer.state === RUNNING ? answer.id : null));
  };
  const resume = () => act(resumeExport, () => (pausedFor = null));
  const cancel = () => {
    if (!busy) onCancel();
  };
</script>

<section class="xp" tabindex="-1" aria-labelledby="{uid}-line" bind:this={card}>
  <p class="xp-line" id="{uid}-line">{running ? progressLine(run) : pausedLine(run)}</p>
  <progress class="xp-bar" max={Math.max(run.total, 1)} value={run.done} aria-label={PHOTOS_EXPORTED}></progress>
  <div class="xp-facts">
    {#if run.bytesTotal > 0}<span>{sizeLine(run)}</span>{/if}
    <span class="mono" dir="auto">{run.path}</span>
  </div>
  {#if !running && run.notice}<p class="xp-notice">{run.notice}</p>{/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  <div class="xp-acts">
    <button class={running ? 'btn ghost' : 'btn'} type="button" bind:this={toggle} aria-disabled={busy || pausing}
            onclick={running ? pause : resume}>{running ? (pausing ? PAUSING : PAUSE) : RESUME}</button>
    <button class="btn ghost" type="button" bind:this={cancelButton} aria-disabled={busy} onclick={cancel}>Cancel…</button>
  </div>
</section>

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
  .xp-notice { margin: 0; padding: 10px 12px; border-radius: 12px; font-size: 14px; background: color-mix(in oklab, var(--glow) 16%, var(--surface));
    border: 1px solid color-mix(in oklab, var(--glow) 60%, var(--line)); }
  .xp-acts { display: flex; flex-wrap: wrap; gap: 8px; }
  /* inactive, but keeps its place and shows it is not available. Pointer events stay (atlas.css drops them): a click on
     "Pausing…" would otherwise land on the card (tabindex -1) and take focus off the one Pause / Resume button. */
  .xp-acts .btn[aria-disabled='true'] { pointer-events: auto; cursor: not-allowed; }
  .xp-acts .btn[aria-disabled='true']:hover { transform: none; box-shadow: none; }
</style>
