<script>
  import { tick } from 'svelte';
  import Dialog from './Dialog.svelte';
  import DeviceWords from './DeviceWords.svelte';
  import { continueDiscarded, openHereAnyway, retrySetup } from './api.js';
  import { NOT_ON_OTHER_DEVICE, NOTHING_DISCARDED, TOO_SOON } from './codes.js';
  import { clockOf, dayAt, dayClock, minutesSince } from './dates.js';
  import {
    CANCEL, CONTINUE, CONTINUING, DISCARDED_PHOTOS_LEAD, DISCARDED_TITLE, LIBRARY_FOLDER, NOT_SYNCED_TITLE, NOT_SYNCED_WORDS,
    ON_OTHER_DEVICE_TITLE, OPEN_HERE_ANYWAY, OPEN_HERE_ANYWAY_ASK, OPEN_HERE_TITLE, OPENING_HERE, TRY_AGAIN, TRYING_AGAIN, agoWords,
    andMoreLine, discardedWords, openAnywayWarning, otherDeviceWords, triedAgainLine,
  } from './messages.js';

  /**
   * The setup screen's page in place of the folder chooser when the library can't be opened on this device yet (one-device
   * contract §1, §5; design §4, §5, §7), from the Setup status's `otherDevice`, `notSynced` or `discarded`:
   * - `otherDevice`: open on another device. Before `openAnywayFrom` (by this device's clock) Try again and when Open here
   *   anyway will be offered; from then on, Open here anyway… too, which asks first (Dialog). The clock is read every
   *   10 s, so the button and "… minutes ago" move without a reload.
   * - `notSynced`: the database hasn't arrived yet; Try again.
   * - `discarded`: unsent changes were discarded, with the photos left out of the atlas; Continue.
   * While another device or the sync app is waited for, the checks run again every minute (POST /api/setup/retry, contract
   * §1), without the "Tried again" line, which is for the user's own Try again; a refusal of Open here anyway or Continue
   * runs them again at once.
   * Every answer goes to `onStatus` (the setup screen opens the app when it is `configured`); every failure is said.
   * @type {{ status: object, onStatus: (next: object) => void }}
   */
  let { status, onStatus } = $props();

  const uid = $props.id();
  const TICK_MS = 10_000;
  const POLL_MS = 60_000;

  let now = $state(new Date());
  let busy = $state(false);
  let error = $state('');
  let pollError = $state('');
  let triedNote = $state('');
  let asking = $state(false);
  let askButton = $state();

  let other = $derived(status.otherDevice ?? null);
  let notSynced = $derived(status.notSynced ?? null);
  let discarded = $derived(other || notSynced ? null : status.discarded ?? null);
  let waiting = $derived(Boolean(other || notSynced));
  let late = $derived(Boolean(other) && Date.parse(other.openAnywayFrom) <= now.getTime());

  $effect(() => {
    if (!other) return undefined;
    const timer = setInterval(() => { now = new Date(); }, TICK_MS);
    return () => clearInterval(timer);
  });

  $effect(() => {
    if (!waiting) return undefined;
    const timer = setInterval(reread, POLL_MS);
    return () => clearInterval(timer);
  });

  // A newer "last seen" took the button away: the question it asked goes too.
  $effect(() => {
    if (!late && asking) asking = false;
  });

  async function reread() {
    try {
      const next = await retrySetup();
      pollError = '';
      now = new Date();
      onStatus(next);
    } catch (e) {
      // Nothing is refused any more (the library was opened meanwhile): the app starts as usual.
      if (e?.code === 'not_failed') onStatus({ configured: true });
      else pollError = e?.message ?? String(e);
    }
  }

  async function act(call, { refusals = [] } = {}) {
    if (busy) return null;
    busy = true;
    error = '';
    try {
      const next = await call();
      now = new Date();
      return next;
    } catch (e) {
      // Try again when nothing is refused any more (the library was opened meanwhile): the app starts as usual.
      if (e?.code === 'not_failed') return { configured: true };
      error = e?.message ?? String(e);
      triedNote = '';
      // The server saw it otherwise: read what it says now, so the page follows.
      if (refusals.includes(e?.code)) reread();
      return null;
    } finally {
      busy = false;
    }
  }

  async function tryAgain() {
    triedNote = '';
    const next = await act(retrySetup);
    if (!next) return;
    onStatus(next);
    if (!next.configured) {
      await tick();
      triedNote = triedAgainLine(clockOf(new Date().toISOString()));
    }
  }

  async function openAnyway() {
    asking = false;
    const next = await act(openHereAnyway, { refusals: [TOO_SOON, NOT_ON_OTHER_DEVICE] });
    if (next) onStatus(next);
  }

  async function carryOn() {
    const next = await act(continueDiscarded, { refusals: [NOTHING_DISCARDED] });
    if (next) onStatus(next);
  }
</script>

<main class="wrap device-page" aria-labelledby="{uid}-h">
  {#if other}
    <h1 id="{uid}-h">{ON_OTHER_DEVICE_TITLE}</h1>
    <p><DeviceWords words={otherDeviceWords({
      name: other.name, seen: dayAt(other.seen), ago: agoWords(minutesSince(other.seen, now)), from: clockOf(other.openAnywayFrom), late,
    })} /></p>
    <div class="dp-actions">
      <button class="btn" type="button" aria-disabled={busy} onclick={tryAgain}>{busy && !late ? TRYING_AGAIN : TRY_AGAIN}</button>
      {#if late}
        <button class="btn ghost" type="button" bind:this={askButton} aria-disabled={busy} onclick={() => { if (!busy) asking = true; }}>
          {busy ? OPENING_HERE : OPEN_HERE_ANYWAY_ASK}
        </button>
      {/if}
    </div>
  {:else if notSynced}
    <h1 id="{uid}-h">{NOT_SYNCED_TITLE}</h1>
    <p>{NOT_SYNCED_WORDS}</p>
    <p class="dp-folder">{LIBRARY_FOLDER}: <span class="mono" dir="auto">{notSynced.folder}</span></p>
    <div class="dp-actions">
      <button class="btn" type="button" aria-disabled={busy} onclick={tryAgain}>{busy ? TRYING_AGAIN : TRY_AGAIN}</button>
    </div>
  {:else if discarded}
    <h1 id="{uid}-h">{DISCARDED_TITLE}</h1>
    <p><DeviceWords words={discardedWords({ name: discarded.by, at: dayAt(discarded.at), since: dayClock(discarded.since) })} /></p>
    {#if discarded.photos?.length}
      <p>{DISCARDED_PHOTOS_LEAD}</p>
      <ul class="dp-photos mono">
        {#each discarded.photos as photo (photo)}<li dir="auto">{photo}</li>{/each}
        {#if discarded.photosMore > 0}<li>{andMoreLine(discarded.photosMore)}</li>{/if}
      </ul>
    {/if}
    <div class="dp-actions">
      <button class="btn" type="button" aria-disabled={busy} onclick={carryOn}>{busy ? CONTINUING : CONTINUE}</button>
    </div>
  {/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#if pollError}<p class="problem" role="alert">{pollError}</p>{/if}
  {#if waiting}<p class="dp-tried" role="status">{triedNote}</p>{/if}
</main>

{#if asking && other}
  <Dialog title={OPEN_HERE_TITLE} onClose={() => (asking = false)} returnFocus={() => askButton}>
    <p>{openAnywayWarning(other.name)}</p>
    {#snippet actions()}
      <button class="btn ghost" type="button" onclick={() => (asking = false)}>{CANCEL}</button>
      <button class="btn" type="button" data-autofocus onclick={openAnyway}>{OPEN_HERE_ANYWAY}</button>
    {/snippet}
  </Dialog>
{/if}

<style>
  .device-page { min-height: 100vh; display: grid; grid-template-columns: minmax(0, 1fr); align-content: center; gap: 14px; max-width: 720px; }
  .device-page h1 { font-size: clamp(24px, 4vw, 34px); margin: 0; }
  .device-page p { margin: 0; min-width: 0; overflow-wrap: anywhere; }
  .dp-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
  .dp-photos { margin: 0; padding-left: 20px; font-size: 13px; overflow-wrap: anywhere; }
  .dp-tried:empty { display: none; }
</style>
