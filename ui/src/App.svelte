<script>
  import { onMount, untrack } from 'svelte';
  import TopBar from './lib/TopBar.svelte';
  import SearchPalette from './lib/SearchPalette.svelte';
  import Toast from './lib/Toast.svelte';
  import Footer from './lib/Footer.svelte';
  import NoSession from './lib/NoSession.svelte';
  import { loadSessionWithRetry, session } from './lib/session.svelte.js';
  import { NOT_ANSWERING, refreshStatus, status } from './lib/status.svelte.js';
  import { anyModalOpen } from './lib/modal.js';
  import { getSetup } from './lib/api.js';
  import { UPDATE_HREF, parseRoute } from './lib/route.js';
  import { num } from './lib/exports.js';
  import Setup from './screens/Setup.svelte';
  import Home from './screens/Home.svelte';
  import Explore from './screens/Explore.svelte';
  import Species from './screens/Species.svelte';
  import Deleted from './screens/Deleted.svelte';
  import Imports from './screens/Imports.svelte';
  import ImportRun from './screens/ImportRun.svelte';
  import Export from './screens/Export.svelte';
  import Settings from './screens/Settings.svelte';
  import Update from './screens/Update.svelte';
  import Split from './screens/Split.svelte';
  import { PAUSED, PENDING, RUNNING } from './lib/states.js';
  import { PHOTOS_MOVED } from './lib/messages.js';

  let hash = $state(window.location.hash);
  let route = $derived(parseRoute(hash));
  let setup = $state(null);
  let error = $state('');
  let searching = $state(false);
  let opener = null;
  const CURRENT = { explore: 'explore', species: 'explore', split: 'explore', imports: 'import', import: 'import', export: 'export', settings: 'settings' };
  const POLL_MS = 2000;

  // Phase 5b: an update the last run left unfinished is continued at start; until it is done (or paused) the app shows
  // the launch line instead of the page, asking again every 2 s (the footer is not shown, so it does not ask too).
  let continuing = $derived(status.taxonomyUpdate?.state === RUNNING && status.taxonomyUpdate.continuing === true ? status.taxonomyUpdate : null);
  $effect(() => {
    if (!continuing) return undefined;
    let stopped = false;
    let timer;
    const again = async () => {
      await refreshStatus();
      if (!stopped) timer = setTimeout(again, POLL_MS);
    };
    timer = setTimeout(again, POLL_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  });

  // A continuation that pauses at launch opens the update screen, where it says why (contract §7).
  let wasContinuing = false;
  $effect(() => {
    const now = continuing !== null;
    if (wasContinuing && !now && status.taxonomyUpdate?.state === PAUSED) untrack(() => window.location.replace(UPDATE_HREF));
    wasContinuing = now;
  });

  // A pending update (not put off with Later) opens first, instead of Home: once per page load, decided by the first
  // status answer applied (a failed read, or one overtaken by a newer ask, leaves it to the next answer).
  let offered = false;
  function offerUpdate() {
    if (offered || !status.answers) return;
    offered = true;
    const pending = status.taxonomyUpdate?.state === PENDING && !status.taxonomyUpdate.later;
    // An update already paused when the page loaded (a continuation that paused before it) opens it too, to say why.
    const paused = status.taxonomyUpdate?.state === PAUSED;
    if ((pending || paused) && parseRoute(window.location.hash).screen === 'home') window.location.replace(UPDATE_HREF);
  }

  $effect(() => {
    if (setup?.configured && route.screen === 'setup') window.location.replace('#/');
  });

  function openSearch() {
    opener = document.activeElement;
    searching = true;
  }

  function closeSearch() {
    searching = false;
  }

  $effect(() => {
    if (setup?.configured && status.answers) untrack(offerUpdate);
  });

  async function loadSetup() {
    try {
      const next = await getSetup();
      // The status first (a failure leaves it as it was): it says whether an update is pending or still finishing.
      if (next.configured) {
        loadSessionWithRetry();
        await refreshStatus();
        offerUpdate();
      }
      setup = next;
      error = '';
    } catch (e) {
      error = e.message;
    }
  }

  async function setupDone() {
    await loadSetup();
    window.location.hash = '#/';
  }

  onMount(() => {
    const onHash = () => {
      const before = parseRoute(hash);
      hash = window.location.hash;
      const after = parseRoute(hash);
      // Opening, paging and closing a photo viewer keep the page under it where it was; so does a change of the Export
      // screen's filter or view.
      const samePage = before.screen === 'export' ? after.screen === 'export'
        : (before.screen === 'species' || before.screen === 'import' || before.screen === 'split') && after.screen === before.screen && before.id === after.id;
      if (!samePage) window.scrollTo?.(0, 0);
      if (setup?.configured) refreshStatus();
    };
    const onKey = (event) => {
      // Not while the launch line shows: the palette would open over the page much later.
      if (!setup?.configured || continuing || searching || anyModalOpen() || event.isComposing) return;
      const tag = event.target?.tagName ?? '';
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(tag) || event.target?.isContentEditable;
      if ((event.key === 'k' || event.key === 'K') && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        openSearch();
      } else if (event.key === '/' && !typing && !event.ctrlKey && !event.metaKey && !event.altKey) {
        event.preventDefault();
        openSearch();
      }
    };
    window.addEventListener('hashchange', onHash);
    window.addEventListener('keydown', onKey);
    loadSetup();
    return () => {
      window.removeEventListener('hashchange', onHash);
      window.removeEventListener('keydown', onKey);
    };
  });
</script>

<!-- The library's drive is full or gone (/api/status `library`): said at the top of every screen while it holds. -->
{#snippet libraryBanner()}
  {#if status.library}
    <section class="stop-card lib-banner" role="alert" aria-label="Your library">
      <div class="stops"><p><span>{status.library.message}</span></p></div>
    </section>
  {/if}
{/snippet}

<!-- Where there is no footer (starting up, a load error, the first-run setup, the launch line) the standing notes of the
     session are said here; the page's footer carries them otherwise. -->
{#snippet sessionNotes()}
  {#if session.lockNote}<p class="muted">{session.lockNote}</p>{/if}
  {#if session.tabNote}<p class="muted">{session.tabNote}</p>{/if}
{/snippet}

{#if session.lost}
  <NoSession />
{:else if error}
  <TopBar current={CURRENT[route.screen] ?? 'home'} />
  <main class="wrap">
    {@render sessionNotes()}
    <p class="problem" role="alert">{error}</p>
  </main>
{:else if setup === null}
  <TopBar current={CURRENT[route.screen] ?? 'home'} />
  <main class="wrap">
    {@render sessionNotes()}
    <p class="muted loading" role="status">Starting up…</p>
  </main>
{:else if !setup.configured}
  <div class="wrap">{@render sessionNotes()}</div>
  <Setup status={setup} onDone={setupDone} />
{:else if continuing}
  <TopBar current={CURRENT[route.screen] ?? 'home'} />
  <main class="wrap">
    {@render sessionNotes()}
    {@render libraryBanner()}
    <div class="progress launch">
      <!-- the status region keeps one sentence; the counts beside it change on every poll and are not announced -->
      <p>
        <span class="sr-only" role="status">Finishing the taxonomy update.</span>
        <span aria-hidden="true">Finishing the taxonomy update…{continuing.total ? ` ${num(continuing.done)} of ${num(continuing.total)}` : ''}</span>
      </p>
      {#if continuing.total}
        <progress max={continuing.total} value={continuing.done} aria-label={PHOTOS_MOVED}></progress>
      {:else}
        <progress aria-label={PHOTOS_MOVED}></progress>
      {/if}
      {#if status.unreachable}<p class="problem" role="alert">{NOT_ANSWERING}</p>{/if}
    </div>
  </main>
{:else}
  <div>
  <TopBar current={CURRENT[route.screen] ?? 'home'} onSearch={openSearch} />
  <!-- tabindex: focus can go to the page when an overlay goes away with what opened it -->
  <main class="wrap" tabindex="-1">
    {@render libraryBanner()}
    {#if route.screen === 'explore'}
      <Explore id={route.id} query={route.query ?? null} view={route.view ?? 'species'} photoId={route.photoId ?? null} />
    {:else if route.screen === 'species'}
      <Species id={route.id} photoId={route.photoId ?? null} tag={route.tag ?? null} place={route.place ?? null} type={route.type ?? null} />
    {:else if route.screen === 'deleted'}
      <Deleted />
    {:else if route.screen === 'imports'}
      <Imports />
    {:else if route.screen === 'import'}
      <ImportRun id={route.id} itemId={route.itemId ?? null} />
    {:else if route.screen === 'update'}
      <Update />
    {:else if route.screen === 'split'}
      <Split id={route.id} photoId={route.photoId ?? null} />
    {:else if route.screen === 'settings'}
      <Settings />
    {:else if route.screen === 'export'}
      <Export query={route.query} view={route.view} photoId={route.photoId ?? null} />
    {:else}
      <Home />
    {/if}
  </main>
  <Footer />
  </div>
  {#if searching}<SearchPalette onClose={closeSearch} returnFocus={() => opener} />{/if}
{/if}
<Toast />
