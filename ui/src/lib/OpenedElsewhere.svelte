<script>
  import DeviceWords from './DeviceWords.svelte';
  import { quitOpenedElsewhere } from './api.js';
  import { NOT_OPENED_ELSEWHERE } from './codes.js';
  import { dayAt, dayClock } from './dates.js';
  import { CLOSED_HERE, OPENED_ELSEWHERE_TITLE, QUIT, QUITTING, openedElsewhereWords, unsentLine } from './messages.js';
  import { NOT_ANSWERING_PLAIN, refreshStatus, status } from './status.svelte.js';

  /**
   * The full page every window shows in place of its screen once the library was opened on another device while this one
   * ran (one-device design §6; contract §2, §5), from the status's `openedElsewhere` (`{ name, at, unsentSince }`): what
   * happened, that this device has stopped saving, how to settle it, changes not yet sent when there are any, and Quit
   * (POST quit, 202). Once Quit is taken the app is quitting: the page says it has closed here, and the window asks
   * nothing more (`status.closedHere`). A refused or failed Quit says why beside it.
   */
  const uid = $props.id();
  let quitting = $state(false);
  let error = $state('');
  // Once Quit was taken the status is no longer read, so the words stay as they were.
  let elsewhere = $derived(status.openedElsewhere);

  async function quit() {
    if (quitting) return;
    quitting = true;
    error = '';
    try {
      await quitOpenedElsewhere();
      status.closedHere = true;
    } catch (e) {
      error = e?.message ?? String(e);
      if (e?.code === NOT_OPENED_ELSEWHERE) refreshStatus();
    } finally {
      quitting = false;
    }
  }
</script>

<main class="wrap elsewhere-page" aria-labelledby="{uid}-h">
  <h1 id="{uid}-h">{OPENED_ELSEWHERE_TITLE}</h1>
  {#if elsewhere}
    <p><DeviceWords words={openedElsewhereWords({ name: elsewhere.name, at: dayAt(elsewhere.at) })} /></p>
    {#if elsewhere.unsentSince}<p>{unsentLine(dayClock(elsewhere.unsentSince), elsewhere.name)}</p>{/if}
  {/if}
  {#if status.closedHere}
    <p role="status">{CLOSED_HERE}</p>
  {:else}
    <div class="ep-actions">
      <button class="btn" type="button" aria-disabled={quitting} onclick={quit}>{quitting ? QUITTING : QUIT}</button>
      {#if error}<p class="problem" role="alert">{error}</p>{/if}
    </div>
    {#if status.unreachable}<p class="problem" role="alert">{NOT_ANSWERING_PLAIN}</p>{/if}
  {/if}
</main>

<style>
  .elsewhere-page { min-height: 100vh; display: grid; grid-template-columns: minmax(0, 1fr); align-content: center; gap: 14px; max-width: 720px; }
  .elsewhere-page h1 { font-size: clamp(24px, 4vw, 34px); margin: 0; }
  .elsewhere-page p { margin: 0; min-width: 0; overflow-wrap: anywhere; }
  .ep-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
</style>
