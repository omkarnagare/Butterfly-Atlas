<script>
  import { onDestroy, tick, untrack } from 'svelte';
  import { checkBackup, startBackup } from './api.js';
  import { isTopModal, modal } from './modal.js';
  import FolderBrowser from './FolderBrowser.svelte';
  import { spaceLine } from './exports.js';
  import { CHECKING_THE_FOLDER, DATABASE_ONLY, STARTING } from './messages.js';

  /**
   * The Backup sheet (Phase 6 contract §4), the Export sheet's pattern: What to back up (Database and settings only, or
   * Everything: the database and every photo), Where (the folder browser opens over the sheet; the library's own folder
   * can't be chosen). Every change of the folder or the choice asks GET /api/backup/check again (an older answer is
   * dropped): its space line, or the problem it finds in words, shows beside Back up, which is aria-disabled until a
   * folder is chosen, while a check is on its way and while a problem stands. A destination on the library's own drive
   * adds one quiet line (`sameDrive`); Everything says that Recently deleted photos are not included. Back up starts it
   * (POST /api/backup) and hands the answer to `onStarted(run)`, which closes the sheet; a refusal (a backup running, a
   * taxonomy update) shows until any choice changes. Nothing is remembered: it opens the same every time. While it is
   * starting nothing changes and it cannot be closed.
   */
  let { returnFocus = undefined, onStarted, onClose } = $props();

  const WHAT = [
    { value: 'database', label: DATABASE_ONLY, hint: 'Small and quick: your species list, tags, places and settings, without the photos.' },
    { value: 'everything', label: 'Everything', hint: 'The database and all your photos, in the same folders.' },
  ];

  const uid = $props.id();
  let root = $state(null);
  let chooseButton = $state(null);
  let changeButton = $state(null);
  let what = $state('everything');
  let path = $state(null);
  let browsing = $state(false);
  // Counts the folders chosen, so the same one chosen again is checked again.
  let chosenTimes = $state(0);
  let check = $state(null);
  let checking = $state(false);
  let checkError = $state('');
  let starting = $state(false);
  let startError = $state('');

  let problem = $derived(startError || checkError || (checking ? '' : check?.problem?.message) || '');
  let settled = $derived(check && !checking && !check.problem);
  let space = $derived(settled ? spaceLine(check) : '');
  let ready = $derived(path !== null && !checking && check !== null && !check.problem && !checkError && !starting);

  let seq = 0;
  let controller = null;

  async function runCheck(asked, folder) {
    const mine = ++seq;
    controller?.abort();
    controller = new AbortController();
    const { signal } = controller;
    checking = true;
    checkError = '';
    try {
      const answer = await checkBackup(asked, folder, { signal });
      if (mine === seq) check = answer;
    } catch (e) {
      if (mine === seq && e?.name !== 'AbortError') {
        check = null;
        checkError = e.message;
      }
    } finally {
      if (mine === seq) checking = false;
    }
  }

  $effect(() => {
    const asked = what;
    const folder = path;
    chosenTimes;
    if (folder !== null) untrack(() => runCheck(asked, folder));
  });

  // Any change of a choice makes a refused start's message out of date.
  $effect(() => {
    what;
    path;
    chosenTimes;
    untrack(() => (startError = ''));
  });

  onDestroy(() => {
    seq += 1;
    controller?.abort();
  });

  // The folder is shown before the browser closes, so focus can go back to Change….
  async function chosen(listing) {
    path = listing.path;
    chosenTimes += 1;
    await tick();
    browsing = false;
  }

  async function start() {
    if (!ready) return;
    starting = true;
    startError = '';
    try {
      const run = await startBackup({ what, path });
      await onStarted(run);
    } catch (e) {
      startError = e.message;
    } finally {
      starting = false;
    }
  }

  function close() {
    if (!starting) onClose();
  }

  // While starting, a choice does not move: the group's radios are put back as they were (mouse and arrow keys alike).
  function pick(event, value) {
    if (!starting) {
      what = value;
      return;
    }
    for (const input of event.currentTarget.closest('fieldset').querySelectorAll('input')) input.checked = input.value === what;
  }

  function browse() {
    if (!starting) browsing = true;
  }

  function onKey(event) {
    if (event.key === 'Escape' && isTopModal(root)) {
      event.preventDefault();
      close();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov sheet-bg" bind:this={root} use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) close(); }}>
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="{uid}-title">
    <div class="sh-h">
      <h2 id="{uid}-title">Back up your library</h2>
    </div>
    <div class="sh-b">
      <fieldset class="ropt-set xs-set xs-in">
        <legend class="lbl2">What to back up</legend>
        {#each WHAT as option (option.value)}
          <label class="ropt">
            <input type="radio" name="{uid}-what" value={option.value} checked={what === option.value}
                   aria-describedby="{uid}-what-{option.value}" aria-disabled={starting} onchange={(event) => pick(event, option.value)} />
            <span>{option.label}</span>
          </label>
          <span class="xs-eg" id="{uid}-what-{option.value}">{option.hint}</span>
        {/each}
        {#if what === 'everything'}<p class="muted xs-name">Photos in Recently deleted are not included.</p>{/if}
      </fieldset>

      <section class="ropt-set xs-set" aria-labelledby="{uid}-where">
        <h3 class="lbl2" id="{uid}-where">Where</h3>
        {#if path === null}
          <button class="btn ghost xs-choose" type="button" bind:this={chooseButton} aria-disabled={starting} onclick={browse}>Choose where to back up…</button>
        {:else}
          <div class="xs-path">
            <span class="mono" dir="auto">{path}</span>
            <button class="btn ghost" type="button" bind:this={changeButton} aria-disabled={starting} onclick={browse}>Change…</button>
          </div>
          {#if check?.name}<p class="muted xs-name">Creates {check.name}</p>{/if}
          {#if settled && check.sameDrive}
            <p class="muted xs-name">This backup is on the same drive as your library; it will not protect against that drive failing.</p>
          {/if}
        {/if}
      </section>
    </div>
    <div class="sh-f xs-f">
      {#if problem}
        <p class="problem xs-line" id="{uid}-line" role="alert">{problem}</p>
      {:else}
        <p class="muted xs-line" id="{uid}-line" aria-live="polite">
          {path === null ? 'Choose where to back up.' : checking ? CHECKING_THE_FOLDER : space}
        </p>
      {/if}
      <button class="btn ghost" type="button" aria-disabled={starting} onclick={close}>Cancel</button>
      <button class="btn" type="button" aria-disabled={!ready} aria-describedby="{uid}-line" onclick={start}>{starting ? STARTING : 'Back up'}</button>
    </div>
  </div>
</div>

{#if browsing}
  <FolderBrowser backup startPath={path} returnFocus={() => changeButton ?? chooseButton} onChoose={chosen} onClose={() => (browsing = false)} />
{/if}

<style>
  .sh-h h2 { font-size: 26px; }
  .xs-set legend { padding: 0; }
  .xs-set .lbl2 { margin-bottom: 6px; }
  .xs-in { animation: rise .35s cubic-bezier(.2,.8,.2,1) both; }
  .xs-eg { display: block; padding: 0 10px 6px 36px; font-size: 13px; color: var(--muted); overflow-wrap: anywhere; }
  .xs-choose { justify-self: start; }
  .xs-path { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
  .xs-path .mono { flex: 1 1 200px; min-width: 0; font-size: 13px; overflow-wrap: anywhere; }
  .xs-name { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
  .xs-f { flex-wrap: wrap; align-items: center; }
  .xs-line { margin: 0 auto 0 0; flex: 1 1 200px; font-size: 13px; }
  /* on a phone the line has its own row, the buttons stay together under it */
  @media (max-width: 520px) { .xs-line { flex-basis: 100%; } }
  p.problem.xs-line { padding: 8px 10px; }
</style>
