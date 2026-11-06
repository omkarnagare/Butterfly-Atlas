<script>
  import { dismissSyncNote } from './api.js';
  import { refreshStatus, status } from './status.svelte.js';
  import { SYNC_BEFORE_OTHER_DEVICE, SYNC_SENT_LAST_SESSION } from './states.js';
  import { DISMISS, SYNC_BEFORE_OTHER_LINE, SYNC_BEFORE_OTHER_TITLE, SYNC_SENT_LINE, SYNC_SENT_TITLE } from './messages.js';

  /**
   * Home's one-time sync note (one-device design §5 case 2, §8; contract §2), from `/api/status` `syncNote`: that the
   * last session's changes were sent to the library (`sent_last_session`), or, the first time this device claims a
   * library, to quit here before opening it on another device (`before_other_device`). It stays until Dismiss (POST
   * /api/library/sync-note/dismiss); the status then brings the next one, if any. A Dismiss that fails says why and keeps it.
   */
  const uid = $props.id();
  let dismissing = $state(false);
  let error = $state('');

  let sent = $derived(status.syncNote === SYNC_SENT_LAST_SESSION);
  let shown = $derived(sent || status.syncNote === SYNC_BEFORE_OTHER_DEVICE);

  async function dismiss() {
    if (dismissing) return;
    dismissing = true;
    error = '';
    try {
      await dismissSyncNote();
      status.syncNote = null;
      await refreshStatus();
    } catch (e) {
      error = e?.message ?? String(e);
    } finally {
      dismissing = false;
    }
  }
</script>

{#if shown}
  <section class="stop-card" aria-labelledby="{uid}-h">
    <div class="stops">
      <h2 class="sr-only" id="{uid}-h">{sent ? SYNC_SENT_TITLE : SYNC_BEFORE_OTHER_TITLE}</h2>
      <p><span>{sent ? SYNC_SENT_LINE : SYNC_BEFORE_OTHER_LINE}</span></p>
      {#if error}<p class="problem" role="alert">{error}</p>{/if}
      <p><button class="btn ghost" type="button" aria-disabled={dismissing} onclick={dismiss}>{DISMISS}</button></p>
    </div>
  </section>
{/if}
