<script>
  import { retryUpdate } from './api.js';
  import { pausedNoticeOf } from './updates.js';
  import { UPDATE_HREF } from './route.js';
  import { refreshStatus, retryRefused, retrySent, status, updateChanged } from './status.svelte.js';
  import { showProblem } from './toast.svelte.js';
  import { PAUSED } from './states.js';
  import { PAUSED_TAXONOMY_UPDATE } from './messages.js';

  /**
   * A paused taxonomy update (/api/status `taxonomyUpdate`, Phase 5b contract §4, §6, §7): the footer's quiet line
   * "Taxonomy update paused." with the notice, a link to the update screen and Retry; `card` is the version for Home and the update screen: "Taxonomy update paused",
   * the notice (what to do) and Retry. Retry is aria-disabled while it is asked; the answer is the status at once
   * (updateChanged). A refusal because it is no longer paused reads the status again; any other is said in a toast.
   * Once it runs again the line goes, and focus goes to the page's main (from the line or the page). `label` names the
   * group; a page that names the region around it passes ''.
   */
  let { update, card = false, label = PAUSED_TAXONOMY_UPDATE } = $props();

  let busy = $state(false);
  const retryLine = (note) => `Tried again at ${note.at} — still paused.${note.message ? ` ${note.message}` : ''}`;
  let line = $state(null);

  async function retry() {
    if (busy) return;
    busy = true;
    try {
      const code = update.notice?.code ?? null;
      const answer = await retryUpdate();
      retrySent(code);
      const at = document.activeElement;
      const from = !at || at === document.body || line?.contains(at);
      updateChanged(answer);
      if (answer.state !== PAUSED && from) document.querySelector('main')?.focus();
    } catch (e) {
      if (e?.code === 'not_paused') refreshStatus();
      else if (e?.status === 409) retryRefused(e.message, update.notice?.code ?? null);
      else showProblem(e);
    } finally {
      busy = false;
    }
  }
</script>

<div class={card ? 'stops' : 'stops quiet'} role={label ? 'group' : undefined} aria-label={label || undefined} bind:this={line}>
  {#if card}
    <p class="up-why">Taxonomy update paused</p>
    <p>{pausedNoticeOf(update)}</p>
    {#if status.retryNote}<p class="up-retry" role="status">{retryLine(status.retryNote)}</p>{/if}
  {/if}
  <p>
    {#if !card}
      <span>Taxonomy update paused. {pausedNoticeOf(update)}{#if status.retryNote}{' '}{retryLine(status.retryNote)}{/if}</span>
      <a href={UPDATE_HREF}>Taxonomy update</a>
    {/if}
    <button class={card ? 'btn' : ''} type="button" aria-disabled={busy} onclick={retry}>{busy ? 'Retrying…' : 'Retry'}</button>
  </p>
</div>

<style>
  .up-why { font-weight: 650; }
  .up-retry { color: var(--muted); }
  .quiet button { color: var(--muted); text-decoration: underline; text-underline-offset: 2px; font-size: inherit; }
  .quiet button:hover { color: var(--ink); }
  .quiet button[aria-disabled='true'] { opacity: .45; cursor: default; }
</style>
