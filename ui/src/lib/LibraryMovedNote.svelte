<script>
  import { dismissMovedNote } from './api.js';
  import { refreshStatus, status } from './status.svelte.js';
  import { DISMISS, LIBRARY_MOVED, libraryMovedLine } from './messages.js';

  /**
   * Home's one-time note after a move of the library folder (change-library-folder design §6), from `/api/status`
   * `libraryMoved` (`{ to }`): "Your library was moved to …". It stays until Dismiss (POST
   * /api/library/moved-note/dismiss); a Dismiss that fails says why and keeps it.
   */
  const uid = $props.id();
  let dismissing = $state(false);
  let error = $state('');

  async function dismiss() {
    if (dismissing) return;
    dismissing = true;
    error = '';
    try {
      await dismissMovedNote();
      status.libraryMoved = null;
      refreshStatus();
    } catch (e) {
      error = e.message;
    } finally {
      dismissing = false;
    }
  }
</script>

{#if status.libraryMoved}
  <section class="stop-card" aria-labelledby="{uid}-h">
    <div class="stops">
      <h2 class="sr-only" id="{uid}-h">{LIBRARY_MOVED}</h2>
      <p><span>{libraryMovedLine(status.libraryMoved.to)}</span></p>
      {#if error}<p class="problem" role="alert">{error}</p>{/if}
      <p><button class="btn ghost" type="button" aria-disabled={dismissing} onclick={dismiss}>{DISMISS}</button></p>
    </div>
  </section>
{/if}
