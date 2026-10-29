<script>
  import { onMount } from 'svelte';
  import { getChildren, getTaxon } from '../lib/api.js';
  import Breadcrumbs from '../lib/Breadcrumbs.svelte';
  import TaxonHeader from '../lib/TaxonHeader.svelte';
  import TaxonCard from '../lib/TaxonCard.svelte';
  import SpeciesCard from '../lib/SpeciesCard.svelte';
  import MapView from '../lib/MapView.svelte';
  import AdditionsCard from '../lib/AdditionsCard.svelte';
  import AddSpeciesDialog from '../lib/AddSpeciesDialog.svelte';
  import FilterResults from '../lib/FilterResults.svelte';
  import { parseFilter, printFilter } from '../lib/filter.js';
  import { exploreAddress, pageFilter, startsFiltered } from '../lib/exploreFilter.js';
  import { exploreHref, explorePhotoHref, exportHref, hrefFor } from '../lib/route.js';
  import { loadViewMode, saveViewMode } from '../lib/viewMode.js';
  import { LIBRARY_CHANGED } from '../lib/status.svelte.js';
  import LoadError from '../lib/LoadError.svelte';
  import { loadProblem } from '../lib/problems.js';

  /**
   * An Explore page (#/explore[/<id>]): the group's header and its child cards or the map, under the filter row
   * (filters-in-Explore design). The page's own part of the filter (the group as a Taxa chip; Custom additions as
   * Source: Custom; nothing on the top page) is in its path; `query` is the rest, `view` and `photoId` as on Export.
   * With no filter of its own the page is as it was, with the filter row and presets above the cards; with one, the
   * cards and the Cards/Map switch give way to the filter's Species · Photos · Places views (lib/FilterResults), where
   * photos can be edited, and Export these… opens Export with the same filter and view.
   * @type {{ id: string, query?: string | null, view?: string, photoId?: number | null, delay?: number }}
   */
  let { id, query = null, view = 'species', photoId = null, delay = 200 } = $props();

  let filtered = $derived(startsFiltered({ query, view, photoId }));
  // The full filter, printed: the page's part and the rest from the address.
  let full = $derived(printFilter(pageFilter(id, query ?? '')));
  let printed = $state('');
  // A filter's address: on this page while it still has the page's part, else on the top page.
  function resultsHref(text, which) {
    const at = exploreAddress(id, parseFilter(text));
    return exploreHref(at.id, at.query, which);
  }
  function photoHref(photo, text) {
    const at = exploreAddress(id, parseFilter(text));
    return explorePhotoHref(at.id, photo, at.query);
  }

  let taxon = $state(null);
  let children = $state([]);
  let error = $state('');
  let mode = $state(loadViewMode());
  let adding = $state(false);
  // What opened the Add dialog, for focus when it closes (a plain variable: it is read as the dialog goes away).
  let opener = null;

  // The Custom additions card always comes last, after the families.
  let cards = $derived([...children.filter((c) => c.rank !== 'additions'), ...children.filter((c) => c.rank === 'additions')]);

  function openAdd(from) {
    opener = from;
    adding = true;
  }

  // A species just added opens at once: its page is where its first photos go.
  function added(detail) {
    adding = false;
    window.location.hash = hrefFor(detail);
  }

  function show(next) {
    mode = next;
    saveViewMode(next);
  }

  $effect(() => {
    const wanted = id;
    let cancelled = false;
    error = '';
    Promise.all([getTaxon(wanted), getChildren(wanted)])
      .then(([t, c]) => {
        if (!cancelled) {
          taxon = t;
          children = c;
        }
      })
      .catch((e) => {
        if (!cancelled) {
          taxon = null;
          error = loadProblem(e);
        }
      });
    return () => {
      cancelled = true;
    };
  });

  // Photos the startup check found change the counts and covers: reload in place, keeping what is shown on failure.
  async function reload() {
    const wanted = id;
    try {
      const [t, c] = await Promise.all([getTaxon(wanted), getChildren(wanted)]);
      if (wanted !== id) return;
      taxon = t;
      children = c;
      error = '';
    } catch (e) {
      // A page on screen stays as it was; one that couldn't load says why again.
      if (wanted === id && error) error = loadProblem(e);
    }
  }

  onMount(() => {
    window.addEventListener(LIBRARY_CHANGED, reload);
    return () => window.removeEventListener(LIBRARY_CHANGED, reload);
  });
</script>

{#if error}
  <LoadError message={error} onRetry={reload} />
{:else if taxon}
  {#key taxon.id}
    <Breadcrumbs path={taxon.path} current={taxon} />
    <TaxonHeader {taxon}>
      {#snippet extra()}
        {#if taxon.rank === 'additions'}
          <button class="btn" type="button" onclick={(e) => openAdd(e.currentTarget)}>Add a species</button>
        {/if}
        {#if !filtered}
          <div class="seg" role="group" aria-label="View">
            <button type="button" class:on={mode === 'cards'} aria-pressed={mode === 'cards'} onclick={() => show('cards')}>Cards</button>
            <button type="button" class:on={mode === 'map'} aria-pressed={mode === 'map'} onclick={() => show('map')}>Map</button>
          </div>
        {/if}
      {/snippet}
    </TaxonHeader>
  {/key}
  <div class="xf">
    <FilterResults query={full} view={filtered ? view : 'species'} photoId={filtered ? photoId : null} {delay} results={filtered} editable
                   seed={taxon.rank === 'group' || taxon.rank === 'additions' ? null : { taxa: [taxon] }} {resultsHref} {photoHref} bind:printed>
      {#snippet actions()}
        <a class="btn ghost" href={exportHref(printed, view)}>Export these…</a>
      {/snippet}
    </FilterResults>
  </div>
  {#if filtered}
    <!-- the filter's views are above -->
  {:else if mode === 'map'}
    <MapView focusId={taxon.id} onFocus={(t) => { window.location.hash = hrefFor(t); }} />
    <p class="hint">Click a circle to zoom in, the background to zoom out. Filled circles are species you have photographed; click one to open it.</p>
  {:else}
    {#key taxon.id}
      <div class="grid stagger">
        {#each cards as child, i (child.id)}
          {#if child.rank === 'species'}<SpeciesCard taxon={child} {i} />
          {:else if child.rank === 'additions'}<AdditionsCard taxon={child} {i} onAdd={openAdd} />
          {:else}<TaxonCard taxon={child} {i} />{/if}
        {/each}
      </div>
      {#if taxon.rank === 'additions' && !children.length}
        <p class="hint">No custom species yet. Add one the list doesn't have, and file photos under it.</p>
      {/if}
    {/key}
  {/if}
  {#if adding}
    <AddSpeciesDialog onAdded={added} onClose={() => (adding = false)} returnFocus={() => opener} />
  {/if}
{:else}
  <p class="muted loading" role="status">Opening…</p>
{/if}

<style>
  .xf { margin: 18px 0 22px; }
</style>
