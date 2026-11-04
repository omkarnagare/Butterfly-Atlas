<script>
  import { publishAgain } from './api.js';
  import { NOT_STOPPED } from './codes.js';
  import { PUBLISH_BAR_LABEL, TRY_AGAIN, TRYING_AGAIN, publishLine } from './messages.js';
  import { refreshStatus, status } from './status.svelte.js';

  /**
   * The bar under the header on every screen while changes can't be sent to the library folder (one-device design §5,
   * §10; contract §2, §5), from the status's `publish`: during the 5 automatic tries why and that it tries again in a
   * minute; after the 5th, Try again (POST /api/library/publish: one attempt, its answer's `publish` taken at once). It
   * can't be dismissed; it goes when a publish works. A Try again that fails or is refused says why beside it.
   */
  const uid = $props.id();
  let trying = $state(false);
  let error = $state('');

  let publish = $derived(status.publish);
  let stopped = $derived(publish?.state === 'stopped');

  async function tryAgain() {
    if (trying) return;
    trying = true;
    error = '';
    try {
      const answer = await publishAgain();
      status.publish = answer?.publish ?? null;
      refreshStatus();
    } catch (e) {
      error = e?.message ?? String(e);
      if (e?.code === NOT_STOPPED) refreshStatus();
    } finally {
      trying = false;
    }
  }

  // A Try again's refusal belongs to the stopped bar it came from.
  $effect(() => {
    if (!stopped) error = '';
  });
</script>

{#if publish}
  <section class="stop-card lib-banner publish-bar" aria-labelledby="{uid}-h">
    <div class="stops">
      <h2 class="sr-only" id="{uid}-h">{PUBLISH_BAR_LABEL}</h2>
      <p><span>{publishLine(publish)}</span></p>
      {#if stopped}
        <p><button class="btn" type="button" aria-disabled={trying} onclick={tryAgain}>{trying ? TRYING_AGAIN : TRY_AGAIN}</button></p>
        {#if error}<p class="problem" role="alert">{error}</p>{/if}
      {/if}
    </div>
  </section>
{/if}
