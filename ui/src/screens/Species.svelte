<script>
  import { PHOTO_ACCEPT, getPhotos, getTags, getTaxon, isPhotoSequence } from '../lib/api.js';
  import Breadcrumbs from '../lib/Breadcrumbs.svelte';
  import SpeciesHeader from '../lib/SpeciesHeader.svelte';
  import Outline from '../lib/Outline.svelte';
  import Gallery from '../lib/Gallery.svelte';
  import AddPhotosSheet from '../lib/AddPhotosSheet.svelte';
  import Celebration from '../lib/Celebration.svelte';
  import PhotoViewer from '../lib/PhotoViewer.svelte';
  import SelectionBar from '../lib/SelectionBar.svelte';
  import BulkTagDialog from '../lib/BulkTagDialog.svelte';
  import SpeciesPicker from '../lib/SpeciesPicker.svelte';
  import TagsSheet from '../lib/TagsSheet.svelte';
  import PlacesSheet from '../lib/PlacesSheet.svelte';
  import PlacePicker from '../lib/PlacePicker.svelte';
  import CustomSpeciesEdit from '../lib/CustomSpeciesEdit.svelte';
  import { atPlace } from '../lib/places.js';
  import { closeViewer } from '../lib/viewer.js';
  import { acceptedBy, allAdded, unknownPlaceLine } from '../lib/upload.js';
  import { photoCount, speciesSelection } from '../lib/speciesSelection.svelte.js';
  import { showProblem, showToast } from '../lib/toast.svelte.js';
  import { untrack } from 'svelte';
  import { anyModalOpen } from '../lib/modal.js';
  import { reducedMotion } from '../lib/motion.js';
  import { LIBRARY_CHANGED, refreshStatus } from '../lib/status.svelte.js';
  import LoadError from '../lib/LoadError.svelte';
  import { loadProblem } from '../lib/problems.js';
  import { famStyle } from '../lib/families.js';
  import { hrefFor } from '../lib/route.js';
  import { meadow } from '../lib/meadow.js';

  /**
   * A species page (prototype vSpecies): names, life-cycle strip, the photo gallery and adding photos.
   * With `photoId` (#/species/<id>/photo/<photoId>[?tag=][&place=][&type=]) the photo viewer opens over it. `tag`,
   * `place` and `type` (a file type, Phase 7a) also set the gallery's chips when the page opens (#/species/<id>?tag=&place=&type=,
   * from the Export results); one that matches no photo falls back to all.
   * Selecting photos and the bulk actions on them are in lib/speciesSelection.svelte.js.
   * Meadow (design §4): the gallery takes the family colour for its selected tag chip, and a species not photographed
   * yet shows its outline over a small meadow.
   */
  const EMPTY_MEADOW = meadow(640, 56, { seed: 11, density: 0.9 });
  let { id, photoId = null, tag = null, place = null, type = null } = $props();

  let taxon = $state(null);
  let photos = $state([]);
  let tags = $state([]);
  let error = $state('');
  let adding = $state(false);
  let dropped = $state([]);
  let fresh = $state(new Set());
  let celebrating = $state(null);
  let lastAdd = null;
  let filter = $state('all');
  let placeFilter = $state(null);
  let typeFilter = $state(null);
  let selecting = $state(false);
  let selected = $state(new Set());
  let choosing = $state(null);
  let busy = $state(false);
  let managingTags = $state(false);
  let managingPlaces = $state(false);

  const load = (wanted) => Promise.all([getTaxon(wanted), getPhotos(wanted), getTags().catch(() => [])]);

  $effect(() => {
    const wanted = id;
    let cancelled = false;
    error = '';
    fresh = new Set();
    adding = false;
    dropped = [];
    celebrating = null;
    lastAdd = null;
    filter = 'all';
    placeFilter = null;
    typeFilter = null;
    selecting = false;
    selected = new Set();
    choosing = null;
    managingTags = false;
    managingPlaces = false;
    load(wanted)
      .then(([t, p, g]) => {
        if (cancelled) return;
        taxon = t;
        photos = p;
        tags = g;
        filter = tag ?? 'all';
        placeFilter = place;
        typeFilter = type;
        prune();
      })
      .catch((e) => {
        if (cancelled) return;
        taxon = null;
        error = loadProblem(e);
      });
    return () => {
      cancelled = true;
    };
  });

  /** Reloads the page's data after a change, and the footer's Recently deleted count. */
  async function refresh() {
    const wanted = id;
    refreshStatus();
    try {
      const [t, p, g] = await load(wanted);
      if (wanted !== id) return;
      taxon = t;
      photos = p;
      tags = g;
      error = '';
      prune();
    } catch (e) {
      // A page that couldn't load says why again (Try again, or the library back); one on screen says it in a toast.
      if (error) error = loadProblem(e);
      else showProblem(e);
    }
  }

  // Photos the startup check found (or found gone) show up without leaving the page.
  $effect(() => {
    const onChange = () => refresh();
    window.addEventListener(LIBRARY_CHANGED, onChange);
    return () => window.removeEventListener(LIBRARY_CHANGED, onChange);
  });

  let title = $derived(taxon ? taxon.common || taxon.name : '');

  // The page's state as the selection and its bulk actions see it.
  const page = {
    get photos() { return photos; },
    get shown() { return shown; },
    get chosen() { return chosen; },
    get selecting() { return selecting; }, set selecting(v) { selecting = v; },
    get selected() { return selected; }, set selected(v) { selected = v; },
    get choosing() { return choosing; }, set choosing(v) { choosing = v; },
    get busy() { return busy; }, set busy(v) { busy = v; },
    refresh,
  };
  const {
    prune, toggle, selectAll, stopSelecting, tagSelected, moveSelected, placeSelected, unplaceSelected, deleteSelected, onPageKey,
  } = speciesSelection(page);

  // A custom species renamed: its photos have moved, so the page is read again.
  async function renamed(detail) {
    const before = taxon.name;
    await refresh();
    showToast(detail.name === before ? 'Changes saved.' : `Renamed to ${detail.name}.`);
  }

  // A custom species deleted: on to its own Custom additions.
  function deleted() {
    showToast(`${title} deleted.`);
    window.location.hash = hrefFor(taxon.path.find((crumb) => crumb.rank === 'additions') ?? { id: 'U:butterflies', rank: 'additions' });
  }

  function openSheet(files = []) {
    dropped = files;
    lastAdd = null;
    adding = true;
  }

  function added(summary) {
    lastAdd = summary;
    fresh = new Set(summary.addedIds);
  }

  async function sheetClosed() {
    adding = false;
    const summary = lastAdd;
    lastAdd = null;
    if (!summary?.added) return;
    // Not earlier, and before the celebration: new content must not appear behind an open overlay.
    await refresh();
    const unknown = unknownPlaceLine(summary);
    const more = unknown ? ` ${unknown}` : '';
    if (!summary.firstForSpecies) {
      if (allAdded(summary)) showToast(`${summary.added === 1 ? '1 photo' : `${summary.added} photos`} added to ${title}.${more}`);
      return;
    }
    const count = summary.speciesCount;
    const milestone = summary.milestone ? ` Milestone reached: ${summary.milestone} species.` : '';
    if (count === null) showToast(`${title} is now in your atlas.${milestone}${more}`);
    else if (reducedMotion()) showToast(`${title} is species number ${count} in your atlas.${milestone}${more}`);
    else celebrating = { count, milestone: summary.milestone };
  }

  // Photos dragged onto the page open the sheet with them.
  // What the gallery shows under its tag, place and file type filters: "Select all" selects these.
  let shown = $derived(photos.filter((p) => (filter === 'all' || p.tags.includes(filter)) && (placeFilter === null || atPlace(p, placeFilter))
    && (typeFilter === null || p.fileType === typeFilter)));

  // Only what the gallery shows can be acted on; a change of filter starts a new selection.
  let chosen = $derived(shown.filter((p) => selected.has(p.id)));
  $effect(() => {
    filter;
    placeFilter;
    typeFilter;
    untrack(() => {
      if (selected.size) selected = new Set();
    });
  });

  const hasFiles = (event) => event.dataTransfer?.types?.includes('Files');

  function onPageDragOver(event) {
    if (hasFiles(event)) event.preventDefault();
  }

  function onPageDrop(event) {
    if (!hasFiles(event)) return;
    event.preventDefault(); // always: the web view must never navigate to a dropped file
    if (!taxon || anyModalOpen()) return;
    const all = [...(event.dataTransfer.files ?? [])];
    // A HEIC image sequence goes to the sheet too, which says why it can't be added (7b review fix U4).
    const ok = all.filter((file) => acceptedBy(PHOTO_ACCEPT, file) || isPhotoSequence(file));
    const left = all.filter((file) => !ok.includes(file));
    if (left.length) {
      showToast(`${left.length === 1 ? left[0].name : `${left.length} files`} not added: only JPEG, PNG, TIFF, WebP, RAW and HEIC photos can be added.`);
    }
    if (ok.length) openSheet(ok);
  }

  let loaded = $derived(taxon !== null && taxon.id === id);
  let viewing = $derived(loaded && photoId !== null && photos.some((p) => p.id === photoId));

  // An address naming a photo this species does not have (deleted, moved) shows the species page, its gallery chips
  // (?tag=&place=) kept.
  $effect(() => {
    if (!loaded || photoId === null || viewing) return;
    const params = [];
    if (tag && tag !== 'all') params.push(`tag=${encodeURIComponent(tag)}`);
    if (place) params.push(`place=${encodeURIComponent(place)}`);
    if (type) params.push(`type=${encodeURIComponent(type)}`);
    window.location.replace(`#/species/${encodeURIComponent(id)}${params.length ? `?${params.join('&')}` : ''}`);
  });
</script>

<svelte:window ondragover={onPageDragOver} ondrop={onPageDrop} onkeydown={onPageKey} />

{#if error}
  <LoadError message={error} onRetry={refresh} />
{:else if taxon}
  {#key taxon.id}
    <Breadcrumbs path={taxon.path} current={taxon} />
    {#snippet edit()}<CustomSpeciesEdit {taxon} photoCount={photos.length} onRenamed={renamed} onDeleted={deleted} />{/snippet}
    <SpeciesHeader {taxon} photoCount={photos.length} extra={taxon.source === 'manual' ? edit : undefined} />
    {#if photos.length}
      <Gallery {photos} {tags} family={taxon.family} {fresh} {selecting} {selected} onToggle={toggle} bind:filter bind:place={placeFilter} bind:type={typeFilter}>
        {#snippet actions()}
          <button class="btn ghost" type="button" aria-pressed={selecting} data-select onclick={() => (selecting ? stopSelecting() : (selecting = true))}>Select</button>
          <button class="btn ghost" type="button" data-tags onclick={() => (managingTags = true)}>Tags</button>
          <button class="btn ghost" type="button" data-places onclick={() => (managingPlaces = true)}>Places</button>
          <button class="btn" type="button" data-add-photos onclick={() => openSheet()}>+ Add photos</button>
        {/snippet}
      </Gallery>
      {#if selecting}
        <SelectionBar count={chosen.length} total={shown.length} {busy} onAll={selectAll}
                      onTag={() => (choosing = 'tags')} onMove={() => (choosing = 'species')} onPlace={() => (choosing = 'place')}
                      onRemovePlace={unplaceSelected} onDelete={deleteSelected} onCancel={stopSelecting} />
      {/if}
      {#if choosing === 'tags'}
        <BulkTagDialog photos={chosen} {tags} onApply={tagSelected} onClose={() => (choosing = null)} />
      {:else if choosing === 'species'}
        <SpeciesPicker title="Move {photoCount(chosen.length)} to another species" exclude={taxon.id}
                       onPick={moveSelected} onClose={() => (choosing = null)} />
      {:else if choosing === 'place'}
        <PlacePicker count={chosen.length} onChoose={placeSelected} onPlacesChanged={refresh} onClose={() => (choosing = null)} />
      {/if}
    {:else}
      <div class="emptyp" style={famStyle(taxon.family)}>
        <Outline />
        <h2>Not photographed yet</h2>
        <p>This species is still on your list. Add your first photo and the atlas will create its folder for you.</p>
        <button class="btn" type="button" data-add-photos onclick={() => openSheet()}>+ Add first photos</button>
        {@html EMPTY_MEADOW}
      </div>
    {/if}
    {#if adding}
      <AddPhotosSheet {taxon} {tags} files={dropped} onAdded={added} onClose={sheetClosed} />
    {/if}
    {#if managingTags}
      <TagsSheet onChanged={refresh} onClose={() => (managingTags = false)} />
    {/if}
    {#if managingPlaces}
      <PlacesSheet onChanged={refresh} onClose={() => (managingPlaces = false)} />
    {/if}
    {#if viewing}
      <PhotoViewer {taxon} {photos} {photoId} {tag} {place} {type} {tags} onChanged={refresh} onClose={() => closeViewer(taxon.id)} />
    {/if}
    {#if celebrating}
      <Celebration {taxon} count={celebrating.count} milestone={celebrating.milestone} onClose={() => (celebrating = null)} />
    {/if}
  {/key}
{:else}
  <p class="muted loading" role="status">Opening…</p>
{/if}
