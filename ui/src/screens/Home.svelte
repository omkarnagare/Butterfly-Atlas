<script>
  import { onMount } from 'svelte';
  import { getBackups, getSummary, getTags, photoThumb } from '../lib/api.js';
  import Ring from '../lib/Ring.svelte';
  import Meter from '../lib/Meter.svelte';
  import TaxonCard from '../lib/TaxonCard.svelte';
  import AdditionsCard from '../lib/AdditionsCard.svelte';
  import SpeciesCard from '../lib/SpeciesCard.svelte';
  import Butterfly from '../lib/Butterfly.svelte';
  import { RANK_PLURALS, names } from '../lib/names.js';
  import { famStyle } from '../lib/families.js';
  import { hrefFor, photoHref } from '../lib/route.js';
  import { rememberRect } from '../lib/flip.js';
  import { rememberMorph } from '../lib/morph.js';
  import { noteViewerOpen } from '../lib/viewer.js';
  import { labeller } from '../lib/tags.js';
  import { adultSvg } from '../lib/butterfly.js';
  import { meadow } from '../lib/meadow.js';
  import { ago } from '../lib/dates.js';
  import { BACKUP_CHANGED, LIBRARY_CHANGED, backupChanged, status } from '../lib/status.svelte.js';
  import { CANT_SHOW, typeBadge } from '../lib/raw.js';
  import { heicMissing, isHeic } from '../lib/heic.js';
  import LoadError from '../lib/LoadError.svelte';
  import { loadProblem } from '../lib/problems.js';
  import StoppedImports from '../lib/StoppedImports.svelte';
  import PausedExport from '../lib/PausedExport.svelte';
  import BackupCard from '../lib/BackupCard.svelte';
  import BackupSheet from '../lib/BackupSheet.svelte';
  import BackupAsk from '../lib/BackupAsk.svelte';
  import { askWasClosed, homeLine, noteAskClosed } from '../lib/backups.js';
  import { OVERLAYS_CHANGED, anyModalOpen } from '../lib/modal.js';
  import UpdateCards from '../lib/UpdateCards.svelte';
  import PlaceUpdateNote from '../lib/PlaceUpdateNote.svelte';
  import RescanLeftNote from '../lib/RescanLeftNote.svelte';
  import TagNamesNote from '../lib/TagNamesNote.svelte';
  import { thumbRetry } from '../lib/thumbRetry.js';
  import { PAUSED, PENDING, RUNNING, WAITING } from '../lib/states.js';
  import { IMPORTS_THAT_STOPPED, PAUSED_EXPORT } from '../lib/messages.js';

  /**
   * Home (prototype vHome): the tally with "Across N photos", the latest addition, family cards,
   * Next on your list, Recently added and Full life cycles. Imports cut short by the app closing, and a paused export,
   * show first, then the backup card (Phase 6: a running, paused or closed-by-the-app backup), then the taxonomy cards (an update ready or paused, splits to sort, custom-species matches), then the
   * place note (Phase 5c), then the files the last rescan did not add (Phase 7b audit D2). The heading takes focus when a
   * card's Dismiss leaves no card after it.
   * Photos shown here (the latest addition, Recently added) carry their RAW or HEIC badge and say "Can't show this photo"
   * when they can't be shown; with HEIC photos among the recent ones and no HEIC decoder, a line under them says so and
   * where to look (7b review fix U8).
   *
   * Backup (Phase 6 contract §3): while a backup is due (`due` of GET /api/backup) and none has its card, one quiet line
   * "Last backup: 12 days ago." / "Never backed up." with Back up now (the sheet). A library that was never asked
   * (`asked` false) gets the one-time preference dialog, once per page load (not each time Home is opened), when no other dialog is open and no
   * taxonomy update needs attention (it waits its turn); closing it without saving asks again next start. A backup list
   * that can't be read leaves Home as it is (Settings says why when it is opened).
   */
  const MILESTONES = [10, 25, 50, 100, 250, 500, 1000];
  // The meadow along the tally's foot (Meadow design §3), drawn once: it is the same every time.
  const TALLY_MEADOW = meadow(1200, 86, { seed: 5, density: 1.1 });

  let summary = $state(null);
  let tags = $state([]);
  let error = $state('');
  let got = $derived(summary?.group.species.got ?? 0);
  let next = $derived(MILESTONES.find((m) => m > got));
  let label = $derived(labeller(tags));
  let latest = $derived(summary?.latest ?? null);
  // The family ring (Meadow): the families, not the custom additions; its butterfly in the most photographed family's colour.
  let ringFamilies = $derived((summary?.families ?? []).filter((f) => f.rank !== 'additions'));
  let perch = $derived(ringFamilies.reduce((best, f) => (f.species.got > (best?.species.got ?? 0) ? f : best), null)?.family ?? null);

  let loads = 0;

  // Backup (Phase 6): the list is read on opening and when a backup changes; a failed read leaves the line out.
  let backups = $state(null);
  let backupLoads = 0;
  let sheetOpen = $state(false);
  let backUpButton = $state(null);
  let asking = $state(false);
  // Counts the overlays opening and closing, so "no other dialog is open" is looked at again.
  let overlays = $state(0);

  async function loadBackups() {
    const mine = ++backupLoads;
    try {
      const next = (await getBackups()) ?? null;
      if (mine === backupLoads && next) backups = next;
    } catch {
      // no line, no dialog; Settings says why when it is opened
    }
  }

  // A taxonomy update that is ready, running, waiting or paused has its own card or screen first.
  let updateNeedsYou = $derived([RUNNING, WAITING, PAUSED].includes(status.taxonomyUpdate?.state)
    || (status.taxonomyUpdate?.state === PENDING && !status.taxonomyUpdate.later));
  let showLine = $derived(backups?.due === true && !status.backup && backups.asked !== false);

  $effect(() => {
    overlays;
    if (backups?.asked === false && !asking && !askWasClosed() && !updateNeedsYou && !anyModalOpen()) asking = true;
  });

  async function askSaved(answer) {
    backups = answer;
    noteAskClosed();
    asking = false;
  }

  async function backupStarted(run) {
    backupChanged(run);
    sheetOpen = false;
  }

  async function load() {
    const mine = ++loads;
    try {
      // Tag labels only dress the captions, so Home still opens without them.
      const [s, g] = await Promise.all([getSummary(), getTags().catch(() => [])]);
      if (mine !== loads) return;
      summary = s;
      tags = g;
      error = '';
    } catch (e) {
      // A failed background reload keeps what is on screen.
      if (mine === loads && summary === null) error = loadProblem(e);
    }
  }

  // Photos the startup check found show up without leaving the page.
  onMount(() => {
    load();
    loadBackups();
    const noteOverlays = () => (overlays += 1);
    window.addEventListener(LIBRARY_CHANGED, load);
    window.addEventListener(BACKUP_CHANGED, loadBackups);
    window.addEventListener(OVERLAYS_CHANGED, noteOverlays);
    return () => {
      window.removeEventListener(LIBRARY_CHANGED, load);
      window.removeEventListener(BACKUP_CHANGED, loadBackups);
      window.removeEventListener(OVERLAYS_CHANGED, noteOverlays);
    };
  });

  const title = (taxon) => names(taxon).title;
  const photosText = (n) => (n === 1 ? '1 photo' : `${n} photos`);

  function openPhoto(event) {
    rememberRect(event.currentTarget.querySelector('img'));
    noteViewerOpen();
  }
</script>

{#if error}
  <LoadError message={error} onRetry={load} />
{:else if summary}
  {#if status.stoppedImports.length}
    <section class="stop-card" aria-label={IMPORTS_THAT_STOPPED}><StoppedImports stops={status.stoppedImports} label="" /></section>
  {/if}
  {#if status.export?.state === PAUSED}
    <section class="stop-card" aria-label={PAUSED_EXPORT}><PausedExport run={status.export} card label="" /></section>
  {/if}
  {#if status.backup}<BackupCard run={status.backup} />{/if}
  <UpdateCards />
  <PlaceUpdateNote />
  <RescanLeftNote />
  <TagNamesNote />
  {#if showLine}
    <p class="muted bk-line">{homeLine(backups)}
      <button class="bk-link" type="button" bind:this={backUpButton} onclick={() => (sheetOpen = true)}>Back up now</button></p>
  {/if}
  <div class="stagger">
    <div class="hh" style="--i:0">
      <section class="tally">
        <Ring {got} total={summary.group.species.total} families={ringFamilies} {perch} />
        <div class="tally-text">
          <div class="eyebrow">Your atlas · {summary.group.name}</div>
          <h1 tabindex="-1"><span class="key">{got} species</span> photographed</h1>
          <p>
            {#if next}<b>{next - got}</b> more to reach the {next}-species milestone.{:else}Every milestone reached.{/if}
            Across {photosText(summary.photoCount ?? 0)}.
          </p>
          <div class="ranks">
            {#each summary.group.ranks as r, i (r.rank)}
              <div class="rk"><span>{RANK_PLURALS[r.rank] ?? r.rank}</span><Meter got={r.got} total={r.total} {i} /><span class="mono">{r.got}/{r.total}</span></div>
            {/each}
          </div>
        </div>
        {@html TALLY_MEADOW}
      </section>
      {#if latest}
        <a class="latest photo" href={photoHref(latest.taxon.id, latest.photo.id)} style={famStyle(latest.taxon.family)} onclick={openPhoto}
           aria-label="Latest addition: {title(latest.taxon)}, {ago(latest.photo.addedAt)}">
          {#if latest.photo.unreadable}<span class="cant">{CANT_SHOW}</span>{:else}<img src={photoThumb(latest.photo)} alt="" decoding="async" use:thumbRetry />{/if}
          <span class="eyebrow">Latest addition</span>
          <span class="cap">
            <span class="bn">{latest.taxon.name}</span>
            <span class="muted">
              {latest.taxon.common ? `${latest.taxon.common} · ` : ''}{latest.photo.tags.length ? label(latest.photo.tags[0]) : 'Photo'} · {ago(latest.photo.addedAt)}
            </span>
          </span>
        </a>
      {:else}
        <div class="latest">
          <span class="eyebrow">Latest addition</span>
          <Butterfly hero />
          <span class="cap">
            <span class="muted">Your newest photo shows here. Add photos from any species page.</span>
            {#if !summary.photoCount}<a class="btn" href="#/import">Import a folder of photos</a>{/if}
          </span>
        </div>
      {/if}
    </div>
    <section class="sec" style="--i:1">
      <div class="sec-h"><h2>Families</h2><span class="muted">Open a family to explore it level by level</span><a class="btn ghost" href="#/explore">Explore all</a></div>
      <div class="grid fam-grid stagger">
        {#each summary.families as family, i (family.id)}
          {#if family.rank === 'additions'}<AdditionsCard taxon={family} {i} />{:else}<TaxonCard taxon={family} {i} />{/if}
        {/each}
      </div>
    </section>
    {#if summary.nextOnList?.length}
      <section class="sec" style="--i:2">
        <div class="sec-h"><h2>Next on your list</h2><span class="muted">Not yet photographed, in genera you have nearly covered</span></div>
        <div class="strip">
          {#each summary.nextOnList as taxon, i (taxon.id)}<SpeciesCard {taxon} {i} strip />{/each}
        </div>
      </section>
    {/if}
    {#if summary.recent?.length}
      <section class="sec" style="--i:3">
        <div class="sec-h"><h2>Recently added</h2></div>
        <div class="strip">
          {#each summary.recent as item (item.photo.id)}
            <a class="thumb" href={hrefFor(item.taxon)} onclick={(e) => rememberMorph(e.currentTarget.querySelector('[data-morph]'))}>
              <div class="cover" data-morph>
                {#if item.photo.unreadable}<span class="cant">{CANT_SHOW}</span>{:else}<img src={photoThumb(item.photo)} alt="" loading="lazy" decoding="async" use:thumbRetry />{/if}
                {#if typeBadge(item.photo)}<span class="tb"><span class={typeBadge(item.photo).toLowerCase()}>{typeBadge(item.photo)}</span></span>{/if}
              </div>
              <span class="cnm">{title(item.taxon)}</span>
              <span class="pc">{ago(item.photo.addedAt)}</span>
            </a>
          {/each}
        </div>
        {#if heicMissing(status.heic) && summary.recent.some((item) => isHeic(item.photo))}
          <p class="muted">{status.heic.system === 'other'
            ? 'Some photos are HEIC, and this computer has no HEIC decoder Butterfly Atlas can use. See Settings → About.'
            : "Some photos are HEIC, which this computer can't show yet. See Settings → About."}</p>
        {/if}
      </section>
    {/if}
    <section class="sec" style="--i:4">
      <div class="sec-h"><h2>Full life cycles</h2><span class="muted">Egg, larva, pupa and adult all photographed</span></div>
      <div class="chips">
        {#each summary.fullLifeCycles ?? [] as taxon (taxon.id)}
          <a class="full" href={hrefFor(taxon)}>{@html adultSvg}{title(taxon)}</a>
        {:else}
          <span class="muted">None yet.</span>
        {/each}
      </div>
    </section>
  </div>
{:else}
  <p class="muted loading" role="status">Opening your atlas…</p>
{/if}
{#if sheetOpen}
  <!-- a started backup replaces the link with its card: focus then goes to the page (the main region takes it) -->
  <BackupSheet returnFocus={() => (backUpButton?.isConnected ? backUpButton : document.querySelector('main'))} onStarted={backupStarted} onClose={() => (sheetOpen = false)} />
{/if}
{#if asking}
  <BackupAsk onSaved={askSaved} onClose={() => { noteAskClosed(); asking = false; }} />
{/if}

<style>
  .bk-line { margin: 0 0 18px; font-size: 14px; }
  .bk-link { margin-left: 6px; padding: 0; font: inherit; font-weight: 600; color: var(--accent); background: none; border: 0; cursor: pointer;
    text-decoration: underline; text-underline-offset: 2px; }
  .bk-link:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; border-radius: 4px; }
</style>
