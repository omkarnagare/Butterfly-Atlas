<script>
  import { tick } from 'svelte';
  import { clearBackups, deleteBackup, getBackup } from './api.js';
  import Dialog from './Dialog.svelte';
  import { exportDate, moreProblemsLine, num, PROBLEM_KINDS, problemsLine } from './exports.js';
  import { historyLine, keptLine } from './backups.js';
  import { stagger } from './motion.js';
  import { NOT_FOUND } from './codes.js';
  import { DONE } from './states.js';
  import { CLEAR_HISTORY, HIDE_DETAILS, NOT_COPIED, PHOTOS_NOT_COPIED, REMOVE, SHOW_DETAILS, SHOW_FEWER } from './messages.js';

  /**
   * Past backups in Settings (Phase 6; Export's list of past exports, for backups), newest first, hidden when there are
   * none: each with the date it ended (else began) and what it was ("✓ 13 Oct 2026, 21:40 · Everything, 12,400 photos",
   * "Cancelled at 120 of 312 (files kept)"), its skipped and failed photos behind Show details (GET /api/backup/{id}: file
   * and reason, "and N more"), the files Remove could not delete, the path, and Remove from the list. Removing one or
   * clearing the history is confirmed first and removes only the record (the backed up files stay); `onRemoved(id)` and
   * `onCleared()` update the list. Focus then goes to the next row's Remove from the list (else the one before), or to
   * `fallbackFocus()` once the list is gone. The 3 newest show, then "Show all N past backups" / "Show fewer".
   */
  let { past, onRemoved, onCleared, fallbackFocus = () => null } = $props();

  const uid = $props.id();
  const SHOWN = 3;
  let list = $state(null);
  let all = $state(false);
  let rowsShown = $derived(all ? past : past.slice(0, SHOWN));
  // No Show fewer to press once there are no more than the 3: the next time it grows, it shows the 3 newest.
  $effect(() => {
    if (past.length <= SHOWN) all = false;
  });
  let open = $state({});
  let details = $state({});
  let confirming = $state(null);
  let busy = $state(false);
  let error = $state('');
  // Where focus goes when the dialog closes: back to the button that opened it, or after a removal the next row.
  let focusTo = () => null;

  async function load(backupId) {
    details[backupId] = { loading: true, error: '', problems: [], more: 0 };
    try {
      const run = await getBackup(backupId);
      details[backupId] = { loading: false, error: '', problems: run.problems ?? [], more: run.moreProblems ?? 0 };
    } catch (e) {
      details[backupId] = { loading: false, error: e.message, problems: [], more: 0 };
    }
  }

  function toggle(backupId) {
    open[backupId] = !open[backupId];
    if (open[backupId] && (!details[backupId] || details[backupId].error)) load(backupId);
  }

  function ask(kind, run, button) {
    error = '';
    focusTo = () => button;
    confirming = { kind, run };
  }

  function close() {
    if (!busy) confirming = null;
  }

  const removeButton = (backupId) => list?.querySelector(`[data-backup-id="${backupId}"] .pe-rm`) ?? null;

  async function confirm() {
    if (busy) return;
    busy = true;
    error = '';
    const { kind, run } = confirming;
    try {
      if (kind === 'all') {
        await clearBackups();
        await onCleared();
        focusTo = fallbackFocus;
      } else {
        try {
          await deleteBackup(run.id);
        } catch (e) {
          // already gone: it leaves the list all the same
          if (e?.code !== NOT_FOUND) throw e;
        }
        const at = past.findIndex((r) => r.id === run.id);
        const neighbour = past[at + 1] ?? past[at - 1] ?? null;
        await onRemoved(run.id);
        focusTo = () => (neighbour ? removeButton(neighbour.id) : null) ?? fallbackFocus();
      }
      await tick();
      busy = false;
      confirming = null;
    } catch (e) {
      error = e.message;
      busy = false;
    }
  }
</script>

{#if past.length}
  <section class="pe" aria-labelledby="{uid}-h">
    <h2 id="{uid}-h">Past backups</h2>
    <ul class="pe-list" role="list" aria-labelledby="{uid}-h" bind:this={list}>
      {#each rowsShown as run, i (run.id)}
        {@const problems = problemsLine(run)}
        {@const notRemoved = keptLine(run)}
        {@const shown = details[run.id]}
        <li class="pe-row" data-backup-id={run.id} tabindex="-1" style="--i: {stagger(i)}">
          <div class="pe-c">
            <p class="pe-t" id="{uid}-t{run.id}">{#if run.state === DONE}<span class="pe-ok" aria-hidden="true">{'✓ '}</span>{/if}{exportDate(run.finishedAt ?? run.startedAt)} · {historyLine(run)}</p>
            {#if problems}
              <p class="pe-p">{problems}
                <button class="pe-more" type="button" aria-expanded={Boolean(open[run.id])} aria-controls={open[run.id] ? `${uid}-d${run.id}` : undefined}
                        onclick={() => toggle(run.id)}>{open[run.id] ? HIDE_DETAILS : SHOW_DETAILS}</button></p>
            {/if}
            {#if open[run.id]}
              <div class="pe-d" id="{uid}-d{run.id}">
                {#if !shown || shown.loading}
                  <p class="muted" role="status">Opening…</p>
                {:else if shown.error}
                  <div class="pe-err"><p class="problem" role="alert">{shown.error}</p><button class="btn ghost" type="button" onclick={() => load(run.id)}>Try again</button></div>
                {:else}
                  <!-- tabindex: the list scrolls, and WebKit only scrolls what has focus from the keyboard -->
                  <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
                  <ul class="pe-probs" aria-label={PHOTOS_NOT_COPIED} tabindex="0">
                    {#each shown.problems as problem}
                      <li><span class="badge">{PROBLEM_KINDS[problem.kind] ?? problem.kind ?? NOT_COPIED}</span> <span class="mono">{problem.file ?? problem.rel}</span> — {problem.reason}</li>
                    {/each}
                  </ul>
                  {#if shown.more > 0}<p class="muted pe-p">{moreProblemsLine(shown.more)}</p>{/if}
                {/if}
              </div>
            {/if}
            {#if notRemoved}<p class="pe-p">{notRemoved}</p>{/if}
            <p class="pe-path mono" dir="auto">{run.path}</p>
          </div>
          <button class="btn ghost pe-rm" type="button" aria-describedby="{uid}-t{run.id}" onclick={(event) => ask('one', run, event.currentTarget)}>Remove from the list</button>
        </li>
      {/each}
    </ul>
    {#if past.length > SHOWN}
      <button class="pe-all" type="button" aria-expanded={all} onclick={() => (all = !all)}>{all ? SHOW_FEWER : `Show all ${num(past.length)} past backups`}</button>
    {/if}
    <button class="btn ghost pe-clear" type="button" onclick={(event) => ask('all', null, event.currentTarget)}>{CLEAR_HISTORY}</button>
  </section>
{/if}

{#if confirming}
  <Dialog title={confirming.kind === 'all' ? 'Remove all past backups from the list?' : 'Remove this backup from the list?'} onClose={close}
          returnFocus={() => focusTo()}>
    <p>The backed up files stay where they are.</p>
    {#if error}<p class="problem" role="alert">{error}</p>{/if}
    {#snippet actions()}
      <button class="btn ghost" type="button" data-autofocus aria-disabled={busy} onclick={close}>Cancel</button>
      <button class="btn" type="button" aria-disabled={busy} onclick={confirm}>{confirming.kind === 'all' ? CLEAR_HISTORY : REMOVE}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .pe { display: grid; gap: 10px; margin-bottom: 22px; }
  .pe h2 { font-size: 20px; }
  .pe-list { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
  .pe-row { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 8px 12px; padding: 12px 14px; border-radius: 16px; background: var(--surface);
    border: 1px solid var(--line); animation: rise .45s cubic-bezier(.2,.75,.2,1) both; animation-delay: calc(var(--i, 0) * 45ms); }
  .pe-row:focus-visible { outline: 3px solid var(--accent); outline-offset: 2px; }
  .pe-c { flex: 1 1 320px; min-width: 0; display: grid; gap: 4px; }
  .pe-t { margin: 0; font-weight: 650; }
  .pe-ok { color: var(--accent); }
  .pe-p { margin: 0; font-size: 14px; }
  .pe-more { margin-left: 6px; font-weight: 600; color: var(--accent); text-decoration: underline; text-underline-offset: 2px; }
  .pe-more:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 4px; }
  .pe-d { display: grid; gap: 4px; margin: 4px 0; }
  .pe-err { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .pe-probs { margin: 0; padding: 8px 10px; list-style: none; display: grid; gap: 4px; max-height: 40vh; overflow-y: auto; font-size: 13px;
    border: 1px solid var(--line); border-radius: 12px; background: var(--bg); }
  .pe-probs .badge { padding: 2px 8px; font-size: 11px; background: var(--surface-2); }
  .pe-probs .mono { overflow-wrap: anywhere; }
  .pe-path { margin: 0; font-size: 12px; color: var(--muted); overflow-wrap: anywhere; }
  .pe-rm { padding: 6px 10px; font-size: 13px; }
  .pe-all { justify-self: start; font-weight: 600; color: var(--accent); text-decoration: underline; text-underline-offset: 2px; }
  .pe-clear { justify-self: start; padding: 7px 12px; font-size: 13px; }
</style>
