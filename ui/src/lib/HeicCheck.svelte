<script>
  import { checkHeicAgain, status } from './status.svelte.js';
  import { HEIC_CHECKING } from './heic.js';
  import { DECODER_AVAILABLE } from './states.js';

  /**
   * Check again (Phase 7b contract §3): looks for the computer's HEIC decoder afresh (POST /api/heic/check, which waits
   * up to 30 s). The button shows while the decoder isn't available; while the check runs it does nothing more and
   * "Checking for a HEIC decoder…" is said; then what it found: "HEIC photos can be shown now. Making their
   * thumbnails…", "Still not available. " and what to do, or that it is still checking (the footer follows it, audit
   * 11). A check that can't be asked says why. `besides` is a line already on screen: a result saying the same is left
   * out.
   */
  let { besides = '' } = $props();

  let error = $state('');
  // The check and its result live in the status (review fix U3): a poll that says `checking` meanwhile, or another
  // place's Check again, keeps them; they go when the state moves on.
  let checking = $derived(status.heicCheck.running);
  let result = $derived(status.heicCheck.result);

  async function check() {
    if (checking) return;
    error = '';
    try {
      await checkHeicAgain();
    } catch (e) {
      error = e.message;
    }
  }
</script>

{#if status.heic && status.heic.state !== DECODER_AVAILABLE}
  <button class="btn ghost" type="button" aria-disabled={checking} onclick={check}>Check again</button>
{/if}
<span class="heic-said" role="status">{checking ? HEIC_CHECKING : result === besides ? '' : result}</span>
{#if error}<span class="problem" role="alert">{error}</span>{/if}
