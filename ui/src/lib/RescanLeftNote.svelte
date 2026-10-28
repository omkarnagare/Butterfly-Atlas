<script>
  import { dismissRescanLeft } from './api.js';
  import { refreshStatus, status } from './status.svelte.js';

  /**
   * Home's note of the files in the library folders the last rescan did not add (Phase 7b audit D2), from `/api/status`
   * `rescanLeft` (`[{ reason, next, count, examples }]`, review fix U12), per reason: "3 files in your library folders
   * weren't added: HEIC image sequences (several frames in one file) aren't supported.", the examples the server gives
   * (at most 5 paths: "e.g. …") and what to do (`next`). It stays until Dismiss (POST /api/rescan-left/dismiss),
   * which hides it until the next rescan; a Dismiss that fails says why and keeps it.
   */
  const uid = $props.id();
  let dismissing = $state(false);
  let error = $state('');

  const line = ({ reason, count }) => (count === 1
    ? `1 file in your library folders wasn't added: ${reason}.`
    : `${count.toLocaleString()} files in your library folders weren't added: ${reason}.`);

  async function dismiss() {
    if (dismissing) return;
    dismissing = true;
    error = '';
    try {
      await dismissRescanLeft();
      status.rescanLeft = [];
      refreshStatus();
    } catch (e) {
      error = e.message;
    } finally {
      dismissing = false;
    }
  }
</script>

{#if status.rescanLeft.length}
  <section class="stop-card" aria-labelledby="{uid}-h">
    <div class="stops">
      <h2 class="sr-only" id="{uid}-h">Files not added</h2>
      {#each status.rescanLeft as left (left.reason)}
        <p><span>{line(left)}</span></p>
        {#if left.examples?.length}<p class="muted mono" dir="auto">e.g. {left.examples.join(', ')}</p>{/if}
        {#if left.next}<p><span>{left.next}</span></p>{/if}
      {/each}
      {#if error}<p class="problem" role="alert">{error}</p>{/if}
      <p><button class="btn ghost" type="button" aria-disabled={dismissing} onclick={dismiss}>Dismiss</button></p>
    </div>
  </section>
{/if}
