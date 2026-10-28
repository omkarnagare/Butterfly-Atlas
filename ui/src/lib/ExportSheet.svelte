<script>
  import { onDestroy, tick, untrack } from 'svelte';
  import { checkExport, startExport } from './api.js';
  import { isTopModal, modal } from './modal.js';
  import CountLine from './CountLine.svelte';
  import FolderBrowser from './FolderBrowser.svelte';
  import { spaceLine } from './exports.js';
  import { exactBox, exactWarning } from './raw.js';
  import { CHECKING_THE_FOLDER, SPECIES_LIST_CSV, STARTING } from './messages.js';

  /**
   * The Export sheet (Phase 4b contract E1 and §5): one scrolling side sheet with the count line of the filter (`query`,
   * as printed; `count` its count answer), What to export, Where (the folder browser opens over the sheet), Folders and
   * Remove location (photos and both), Places in the list (species list and both); choices that do not apply are
   * hidden. Every change of the folder or of what to export asks GET /api/exports/check again (an older answer is
   * dropped): its space line, or the refusal starting would get, shows beside Export, which is aria-disabled until a
   * folder is chosen, while a check is on its way and while there is a problem. Export starts it (POST /api/exports)
   * and hands the answer to `onStarted(run)`, which closes the sheet; a refusal shows until any choice changes (the
   * check has no layout, so only the start knows a path is too long). The same folder chosen again is checked again;
   * the name it creates stays shown until the new answer; a check that fails takes it away. It opens with the defaults
   * every time (D16). While the export is starting nothing changes: the choices, Cancel and Choose… / Change… are
   * aria-disabled and do nothing, and Escape and the backdrop do not close it (a refusal is about the choices it sent).
   *
   * RAW photos (Phase 7a contract §5): when the check says the photos copied include RAW ones (`check.raw.photos`), the
   * sheet warns under Remove location that they go out exactly as they are, with any location, and asks with a box
   * that is never pre-ticked: "Export the RAW photos with their location". It unticks whenever the sheet opens and
   * whenever the number of RAW photos changes; the check and the start carry it (`rawWithLocation`), and until it is
   * ticked the check's `raw_location` problem keeps Export aria-disabled with its words beside it. A start refused with
   * `raw_location` (RAW photos the last check did not count) checks again, so the warning and the box appear.
   * Phase 7b: HEIC photos (`check.heic`) go out exactly too, after the same box: the warning and the box name what is
   * there ("30 HEIC photos …", "12 RAW and 30 HEIC photos …"; "Export the RAW and HEIC photos with their location"), the
   * box unticks when either number changes, and the start says which kinds the box was shown for (`acceptedKinds`,
   * audit 17), so photos of a kind never shown are refused rather than sent with their location.
   */
  let { query, count = null, returnFocus = undefined, onStarted, onClose } = $props();

  const WHAT = [{ value: 'photos', label: 'Photos' }, { value: 'csv', label: SPECIES_LIST_CSV }, { value: 'both', label: 'Both' }];
  const LAYOUTS = [
    { value: 'species', label: 'Keep species folders', hint: 'Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus/Danaus_chrysippus_03.jpg' },
    { value: 'flat', label: 'All photos in one folder' },
  ];
  const DEPTHS = [
    { value: 'none', label: 'None' }, { value: 'country', label: 'Country' }, { value: 'state', label: 'Country + State' },
    { value: 'district', label: 'Country + State + District' },
  ];

  const uid = $props.id();
  let root = $state(null);
  let chooseButton = $state(null);
  let changeButton = $state(null);
  let what = $state('photos');
  let path = $state(null);
  let layout = $state('species');
  let strip = $state(true);
  let rawOk = $state(false);
  let depth = $state('none');
  let browsing = $state(false);
  // Counts the folders chosen, so the same one chosen again is checked again.
  let chosenTimes = $state(0);
  let check = $state(null);
  let checking = $state(false);
  let checkError = $state('');
  let starting = $state(false);
  let startError = $state('');

  let photos = $derived(what !== 'csv');
  let raw = $derived(check?.raw ?? null);
  let heic = $derived(check?.heic ?? null);
  let rawPhotos = $derived(raw?.photos ?? 0);
  let heicPhotos = $derived(heic?.photos ?? 0);
  // The kinds the warning and the box were shown for.
  let shownKinds = $derived([rawPhotos > 0 ? 'raw' : null, heicPhotos > 0 ? 'heic' : null].filter(Boolean));
  let list = $derived(what !== 'photos');
  let problem = $derived(startError || checkError || (checking ? '' : check?.problem?.message) || '');
  let space = $derived(check && !checking && !check.problem ? spaceLine(check) : '');
  // A refused start can be tried again; a refusal the check found can not.
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
      const answer = await checkExport(query, asked, folder, { signal, rawWithLocation: rawOk });
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
    rawOk;
    if (folder !== null) untrack(() => runCheck(asked, folder));
  });

  // A tick is for the RAW and HEIC photos the sheet said: another number of either (another answer) unticks it (audit 9).
  $effect(() => {
    rawPhotos;
    heicPhotos;
    untrack(() => (rawOk = false));
  });

  // Any change of a choice makes a refused start's message out of date.
  $effect(() => {
    what;
    path;
    layout;
    strip;
    depth;
    chosenTimes;
    rawOk;
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
      const run = await startExport({ filter: query, what, path, layout, stripLocation: strip, placeDepth: depth, rawWithLocation: rawOk,
        acceptedKinds: rawOk ? shownKinds : [] });
      await onStarted(run);
    } catch (e) {
      startError = e.message;
      // RAW photos the check didn't see (added since): checking again brings the warning and the box (review fix U1).
      if (e?.code === 'raw_location') runCheck(what, path);
    } finally {
      starting = false;
    }
  }

  function close() {
    if (!starting) onClose();
  }

  // While starting, a choice does not move: the group's radios are put back as they were (mouse and arrow keys alike).
  function pick(event, set, value, chosen) {
    if (!starting) {
      set(value);
      return;
    }
    for (const input of event.currentTarget.closest('fieldset').querySelectorAll('input')) input.checked = input.value === chosen;
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

{#snippet choices(name, options, chosen, set, legend)}
  <fieldset class="ropt-set xs-set xs-in">
    <legend class="lbl2">{legend}</legend>
    {#each options as option (option.value)}
      <label class="ropt">
        <input type="radio" name="{uid}-{name}" value={option.value} checked={chosen === option.value}
               aria-describedby={option.hint ? `${uid}-${name}-${option.value}` : undefined} aria-disabled={starting}
               onchange={(event) => pick(event, set, option.value, chosen)} />
        <span>{option.label}</span>
      </label>
      {#if option.hint}<span class="xs-eg mono" id="{uid}-{name}-{option.value}">{option.hint}</span>{/if}
    {/each}
  </fieldset>
{/snippet}

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov sheet-bg" bind:this={root} use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) close(); }}>
  <div class="sheet" role="dialog" aria-modal="true" aria-labelledby="{uid}-title">
    <div class="sh-h">
      <h2 id="{uid}-title">Export</h2>
      <CountLine {count} />
    </div>
    <div class="sh-b">
      {@render choices('what', WHAT, what, (value) => (what = value), 'What to export')}

      <section class="ropt-set xs-set" aria-labelledby="{uid}-where">
        <h3 class="lbl2" id="{uid}-where">Where</h3>
        {#if path === null}
          <button class="btn ghost xs-choose" type="button" bind:this={chooseButton} aria-disabled={starting} onclick={browse}>Choose where to export…</button>
        {:else}
          <div class="xs-path">
            <span class="mono" dir="auto">{path}</span>
            <button class="btn ghost" type="button" bind:this={changeButton} aria-disabled={starting} onclick={browse}>Change…</button>
          </div>
          {#if check?.name}<p class="muted xs-name">Creates {check.name}</p>{/if}
        {/if}
      </section>

      {#if photos}
        {@render choices('layout', LAYOUTS, layout, (value) => (layout = value), 'Folders')}
        <div class="ropt-set xs-set xs-in">
          <button class="xs-sw" type="button" role="switch" aria-checked={strip} aria-disabled={starting}
                  aria-describedby={strip ? undefined : `${uid}-gps`}
                  onclick={() => { if (!starting) strip = !strip; }}><span class="xs-track" aria-hidden="true"></span>Remove location from photos</button>
          {#if !strip}
            <p class="xs-warn" id="{uid}-gps">Photos will keep their GPS position. Anyone you share them with can see where they were taken.</p>
          {/if}
        </div>
        {#if rawPhotos + heicPhotos > 0}
          <div class="ropt-set xs-set xs-in">
            <p class="xs-warn" id="{uid}-raw">{exactWarning(raw, heic)}</p>
            <label class="xs-check">
              <input type="checkbox" checked={rawOk} aria-describedby="{uid}-raw" aria-disabled={starting}
                     onchange={(event) => { if (starting) event.currentTarget.checked = rawOk; else rawOk = event.currentTarget.checked; }} />
              <span>{exactBox(raw, heic)}</span>
            </label>
          </div>
        {/if}
      {/if}

      {#if list}
        {@render choices('depth', DEPTHS, depth, (value) => (depth = value), 'Places in the list')}
      {/if}
    </div>
    <div class="sh-f xs-f">
      {#if problem}
        <p class="problem xs-line" id="{uid}-line" role="alert">{problem}</p>
      {:else}
        <p class="muted xs-line" id="{uid}-line" aria-live="polite">
          {path === null ? 'Choose where to export.' : checking ? CHECKING_THE_FOLDER : photos ? space : ''}
        </p>
      {/if}
      <button class="btn ghost" type="button" aria-disabled={starting} onclick={close}>Cancel</button>
      <button class="btn" type="button" aria-disabled={!ready} aria-describedby="{uid}-line" onclick={start}>{starting ? STARTING : 'Export'}</button>
    </div>
  </div>
</div>

{#if browsing}
  <FolderBrowser exporting startPath={path} returnFocus={() => changeButton ?? chooseButton} onChoose={chosen} onClose={() => (browsing = false)} />
{/if}

<style>
  .sh-h h2 { font-size: 26px; }
  .xs-set legend { padding: 0; }
  .xs-set .lbl2 { margin-bottom: 6px; }
  .xs-in { animation: rise .35s cubic-bezier(.2,.8,.2,1) both; }
  .xs-eg { display: block; padding: 0 10px 6px 36px; font-size: 11px; color: var(--muted); overflow-wrap: anywhere; }
  .xs-choose { justify-self: start; }
  .xs-path { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
  .xs-path .mono { flex: 1 1 200px; min-width: 0; font-size: 13px; overflow-wrap: anywhere; }
  .xs-name { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
  .xs-sw { display: flex; align-items: center; gap: 10px; text-align: left; padding: 7px 10px; border-radius: 10px; font-weight: 600; }
  .xs-sw:hover { background: var(--surface-2); }
  .xs-sw:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
  .xs-track { position: relative; width: 34px; height: 20px; border-radius: 99px; background: var(--line); flex: 0 0 auto; transition: background .2s; }
  .xs-track::after { content: ''; position: absolute; top: 2px; left: 2px; width: 16px; height: 16px; border-radius: 50%; background: var(--surface);
    box-shadow: 0 1px 3px rgba(0,0,0,.25); transition: transform .2s cubic-bezier(.2,.8,.2,1); }
  [aria-checked='true'] .xs-track { background: var(--accent); }
  [aria-checked='true'] .xs-track::after { transform: translateX(14px); }
  .xs-warn { margin: 4px 0 0; padding: 10px 12px; border-radius: 12px; font-size: 13px; background: color-mix(in oklab, var(--glow) 16%, var(--surface));
    border: 1px solid color-mix(in oklab, var(--glow) 60%, var(--line)); animation: pop .25s cubic-bezier(.2,.8,.2,1.2) both; }
  .xs-check { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border-radius: 10px; font-weight: 600; cursor: pointer; }
  .xs-check:hover { background: var(--surface-2); }
  .xs-check input { width: 16px; height: 16px; margin: 0; accent-color: var(--accent); flex: 0 0 auto; }
  .xs-f { flex-wrap: wrap; align-items: center; }
  .xs-line { margin: 0 auto 0 0; flex: 1 1 200px; font-size: 13px; }
  /* on a phone the line has its own row, the buttons stay together under it */
  @media (max-width: 520px) { .xs-line { flex-basis: 100%; } }
  p.problem.xs-line { padding: 8px 10px; }
</style>
