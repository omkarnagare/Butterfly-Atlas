<script>
  import { photoCount } from './importView.js';
  import { declineCustomMatch, mergeCustomMatch } from './api.js';
  import Dialog from './Dialog.svelte';
  import { updateChanged, refreshStatus } from './status.svelte.js';
  import { showProblem, showToast } from './toast.svelte.js';

  /**
   * An open custom-species match (Phase 5b contract §5, design §4.5): "Your custom *Neptis sankara* (4 photos) now
   * matches the official *Neptis sankara* — Merge · Not now". Merge asks first in a Dialog, then starts the merge job
   * (the footer shows it; the status follows the answer at once); a refusal shows in the dialog. Not now declines it
   * (offered again after the next update), with a toast; a refusal is said in a toast. Both are aria-disabled while
   * either is asked. `onDone(match)` once it is decided, so the page drops it.
   */
  let { match, onDone = () => {} } = $props();

  let busy = $state(false);
  let confirming = $state(false);
  let error = $state('');
  let mergeButton = $state(null);


  async function merge() {
    if (busy) return;
    busy = true;
    error = '';
    try {
      const answer = await mergeCustomMatch(match.custom.id, match.official.id);
      updateChanged(answer);
      confirming = false;
      onDone(match);
    } catch (e) {
      if (withdrawn(e)) return;
      error = e.message;
    } finally {
      busy = false;
    }
  }

  // The official species left the list (404 match_withdrawn, contract §7): the prompt goes, the message as a toast.
  function withdrawn(e) {
    if (e?.code !== 'match_withdrawn') return false;
    confirming = false;
    showToast(e.message);
    refreshStatus();
    onDone(match);
    return true;
  }

  async function decline() {
    if (busy) return;
    busy = true;
    try {
      await declineCustomMatch(match.custom.id, match.official.id);
      showToast(`Not merged. ${match.custom.name} will be offered again after the next taxonomy update.`);
      refreshStatus();
      onDone(match);
    } catch (e) {
      if (!withdrawn(e)) showProblem(e);
    } finally {
      busy = false;
    }
  }

  function close() {
    if (busy) return;
    confirming = false;
    error = '';
  }
</script>

<div class="stops match" role="group" aria-label="Custom species {match.custom.name} matches an official one">
  <p>
    <span>
      Your custom <i class="bn">{match.custom.name}</i> ({photoCount(match.custom.photos ?? 0)}) now matches the official
      <i class="bn">{match.official.name}</i>{match.official.common ? ` (${match.official.common})` : ''}.
    </span>
    <button class="btn" type="button" bind:this={mergeButton} aria-disabled={busy} onclick={() => { if (!busy) confirming = true; }}>Merge</button>
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={decline}>Not now</button>
  </p>
</div>

{#snippet heading()}Merge <i class="bn">{match.custom.name}</i>?{/snippet}

{#if confirming}
  <Dialog title="Merge {match.custom.name}?" {heading} onClose={close}
          returnFocus={() => (mergeButton?.isConnected ? mergeButton : document.querySelector('main'))}>
    <p>
      {#if (match.custom.photos ?? 0) === 1}
        Its photo moves to the official <i class="bn">{match.official.name}</i>, with its tags and places,
      {:else if match.custom.photos}
        Its {photoCount(match.custom.photos)} move to the official <i class="bn">{match.official.name}</i>, with their tags and places,
      {:else}
        It has no photos to move to the official <i class="bn">{match.official.name}</i>,
      {/if}
      and the custom species is removed. Its names are kept as search words.
    </p>
    {#if error}<p class="problem" role="alert">{error}</p>{/if}
    {#snippet actions()}
      <button class="btn ghost" type="button" data-autofocus aria-disabled={busy} onclick={close}>Cancel</button>
      <button class="btn" type="button" aria-disabled={busy} onclick={merge}>{busy ? 'Merging…' : 'Merge'}</button>
    {/snippet}
  </Dialog>
{/if}
