<script>
  import { untrack } from 'svelte';
  import { fileButton, pairChoiceNeeded, pairsWaiting, stoppedLine } from './importView.js';
  import { PAUSE, PAUSING, PHOTOS_FILED } from './messages.js';

  /**
   * Filing an import (design §4.4): "File N assigned photos", then its progress and Pause while it runs.
   * A user's Pause shows only "Paused". Filing cut short by the app closing says it won't carry on by itself
   * and offers "Continue filing" (contract §3, stopped imports). The button can't be pressed (aria-disabled,
   * with the reason) while nothing is ready, while the folder is missing, while RAW + JPEG pairs wait for a choice
   * (Phase 7a) or while a change is being saved. The pairs' reason is not muted: it is a choice to make (review fix U6).
   * `count` is the shots ready to file, `files` the files they make (more with RAW + JPEG pairs filed as Both).
   * After Pause (`pausing`), Pause reads "Pausing…" and can't be pressed until filing has stopped.
   */
  let { summary, count, files = count, busy = false, pausing = false, onFile, onPause } = $props();

  const uid = $props.id();
  let filing = $derived(summary.phase === 'filing');
  let stopped = $derived(summary.stopped?.phase === 'filing' ? summary.stopped : null);
  let why = $derived(summary.folderMissing ? 'Connect the folder or choose where it moved first.'
    : pairsWaiting(summary.pairs) ? pairChoiceNeeded(summary.pairs) : count ? '' : 'Assign a species to some photos first.');
  let done = $derived(summary.progress?.done ?? 0);
  let total = $derived(summary.progress?.total ?? 0);

  // When filing starts or stops, the button focus was on goes: focus moves to the one that takes its place
  // (Pause, or File again), and the status region says what happened.
  let group = $state(null);
  let said = $state('');
  let had = false;
  let was = untrack(() => filing);
  $effect.pre(() => {
    filing;
    had = Boolean(group?.contains(document.activeElement));
  });
  $effect(() => {
    const now = filing;
    if (now === was) return;
    was = now;
    untrack(() => {
      said = now ? 'Filing started.' : summary.paused ? 'Paused.' : summary.notice ? 'Filing stopped.' : 'Filing finished.';
      if (had && !group.contains(document.activeElement)) group.querySelector('button')?.focus();
    });
  });
</script>

<span class="sr-only" role="status">{said}</span>
<div class="filing" role="group" aria-label="Filing" bind:this={group}>
  {#if filing}
    <span class="filing-n">Filing photos…{total ? ` ${done.toLocaleString()} of ${total.toLocaleString()}` : ''}</span>
    {#if total}
      <progress max={total} value={done} aria-label={PHOTOS_FILED}></progress>
    {:else}
      <progress aria-label={PHOTOS_FILED}></progress>
    {/if}
    {#if pausing}<span class="muted filing-why" id="{uid}-pausing">Filing stops after the photos it is filing now.</span>{/if}
    <button class="btn ghost" type="button" aria-disabled={busy || pausing} aria-describedby={pausing ? `${uid}-pausing` : undefined}
            onclick={() => { if (!busy && !pausing) onPause(); }}>{pausing ? PAUSING : PAUSE}</button>
  {:else}
    {#if stopped}
      <p class="filing-stop" id="{uid}-stop">{stoppedLine({ rootName: summary.rootName, ...stopped })}</p>
    {:else if summary.paused}
      <span class="badge">Paused</span>
    {/if}
    {#if why}<span class="filing-why" class:muted={!pairsWaiting(summary.pairs)} id="{uid}-why">{why}</span>{/if}
    <button class="btn" type="button" aria-disabled={busy || Boolean(why)} aria-describedby={why ? `${uid}-why` : stopped ? `${uid}-stop` : undefined}
            onclick={() => { if (!busy && !why) onFile(); }}>
      {stopped ? 'Continue filing' : fileButton(count, files, summary.pairs?.choice)}
    </button>
  {/if}
</div>
