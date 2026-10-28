<script>
  import { tick } from 'svelte';
  import PhotoTile from './PhotoTile.svelte';
  import { measure, play } from './flip.js';
  import { labeller, tagCounts } from './tags.js';
  import { atPlace, levelWord, placeChips, widerChip } from './places.js';
  import { FILE_TYPES } from './filter.js';
  import HeicBar from './HeicBar.svelte';
  import { isHeic } from './heic.js';
  import { heicBarOn } from './status.svelte.js';
  import { FILE_TYPE } from './messages.js';
  import { famStyle } from './families.js';

  /**
   * The species gallery (prototype .tools + .gal): tag chips with counts filter a masonry of photos,
   * which glide to their new places (FLIP). Built-in tags always show (dimmed when unused); a user tag
   * shows once a photo of this species carries it. `actions` renders the buttons at the row's end.
   * Above them, "Where photographed" (contract §3): a chip per place with its count and "N unknown"; a place chip
   * filters too, together with the tag (pressing it again clears it); when no photo has both it says so.
   * `filter` (a tag key or 'all') and `place` (a place id, 'unknown' or null) are bindable so the page can act on what
   * is shown; tiles pass them on to the viewer. A `place` above the photos' own places (pre-set from the Export results)
   * adds a first chip "in Maharashtra · 12" for it, which stays (unpressed) once cleared. With photos of more than one
   * file type (Phase 7a), a "File type" line has a chip per type with its count, filtering with the others; `type` (a
   * file type or null, pre-set from the Export results) is bindable too. Phase 7b (audit D1): with HEIC photos among
   * them while the computer has no HEIC decoder, a bar says so above the photos, with How and Check again.
   * Meadow (design §4): the selected tag chip takes the colour of the species' `family`; the other chips stay quiet.
   */
  let {
    photos, tags = [], family = null, fresh = new Set(), selecting = false, selected = new Set(), onToggle = () => {}, actions,
    filter = $bindable('all'), place = $bindable(null), type = $bindable(null),
  } = $props();

  const uid = $props.id();
  let gal = $state(null);
  let label = $derived(labeller(tags));
  let counts = $derived(tagCounts(photos));
  let chips = $derived(tags.filter((tag) => tag.source === 'builtin' || counts.get(tag.key)));
  let where = $derived(placeChips(photos));
  let widerKey = $state(null);
  let wider = $derived(widerKey ? widerChip(photos, widerKey) : null);
  // The chosen place chip is a former place (Phase 5c): its photos need a current one.
  let formerChosen = $derived(where.chips.find((chip) => chip.key === place && chip.former) ?? null);
  let typeCounts = $derived(FILE_TYPES.map((each) => ({ ...each, n: photos.filter((photo) => photo.fileType === each.value).length })).filter((each) => each.n));
  let shown = $derived(photos.filter((photo) => (filter === 'all' || photo.tags.includes(filter)) && (place === null || atPlace(photo, place))
    && (type === null || photo.fileType === type)));
  let active = $derived([filter !== 'all', place !== null, type !== null].filter(Boolean).length);
  let freshOrder = $derived(shown.filter((photo) => fresh.has(photo.id)).map((photo) => photo.id));

  // A filter whose last photo was retagged, moved or deleted falls back to All.
  $effect(() => {
    if (filter !== 'all' && !counts.get(filter)) filter = 'all';
  });

  // A place above the photos' own places gets its own chip.
  $effect(() => {
    if (place !== null && widerChip(photos, place)) widerKey = place;
  });

  // A file type no photo has (any more) falls back to every type.
  $effect(() => {
    if (type !== null && !photos.some((photo) => photo.fileType === type)) type = null;
  });

  // A place filter whose last photo was moved, deleted or given another place falls back to every place.
  $effect(() => {
    if (place !== null && !photos.some((photo) => atPlace(photo, place))) place = null;
  });

  async function glide(change) {
    const before = measure(gal);
    change();
    await tick();
    play(gal, before);
  }

  function choose(key) {
    if (key !== filter) glide(() => (filter = key));
  }

  const choosePlace = (key) => glide(() => (place = place === key ? null : key));
  const chooseType = (key) => glide(() => (type = type === key ? null : key));
  const photoCount = (n) => (n === 1 ? '1 photo' : `${n} photos`);
</script>

<div class="where" role="group" aria-labelledby="{uid}-where">
  <span class="eyebrow" id="{uid}-where">Where photographed</span>
  {#if wider}
    <button type="button" class="chip" class:on={place === wider.key} title={wider.title} aria-label="in {wider.name}, {photoCount(wider.n)}"
            aria-pressed={place === wider.key} onclick={() => choosePlace(wider.key)}>
      {`in ${wider.name} · `}<span class="n">{wider.n}</span>
    </button>
  {/if}
  {#each where.chips as chip (chip.key)}
    <button type="button" class="chip" class:on={place === chip.key} title={chip.title} aria-label="{chip.name}, {photoCount(chip.n)}"
            aria-pressed={place === chip.key} onclick={() => choosePlace(chip.key)}>
      {chip.name} <span class="n">{chip.n}</span>
    </button>
  {/each}
  {#if where.unknown}
    <button type="button" class="chip" class:on={place === 'unknown'} aria-label="Unknown place, {photoCount(where.unknown)}"
            aria-pressed={place === 'unknown'} onclick={() => choosePlace('unknown')}>
      <span class="n">{where.unknown}</span> unknown
    </button>
  {/if}
</div>
{#if typeCounts.length > 1 || type !== null}
  <div class="where" role="group" aria-labelledby="{uid}-types">
    <span class="eyebrow" id="{uid}-types">{FILE_TYPE}</span>
    {#each typeCounts as each (each.value)}
      <button type="button" class="chip" class:on={type === each.value} aria-label="{each.label}, {photoCount(each.n)}"
              aria-pressed={type === each.value} onclick={() => chooseType(each.value)}>
        {each.label} <span class="n">{each.n}</span>
      </button>
    {/each}
  </div>
{/if}
{#if formerChosen}
  <p class="place-hint">These photos are in a former {levelWord(formerChosen.level, formerChosen.path)}. Select them and choose Place… to give them a current one.</p>
{/if}
<div class="tools gal-tools" style={famStyle(family)}>
  <div class="chips" role="group" aria-label="Show photos tagged">
    <button type="button" class="chip" class:on={filter === 'all'} aria-pressed={filter === 'all'} onclick={() => choose('all')}>
      All <span class="n">{photos.length}</span>
    </button>
    {#each chips as tag (tag.key)}
      {@const n = counts.get(tag.key) ?? 0}
      <button type="button" class="chip" class:on={filter === tag.key} class:dim={!n} aria-disabled={!n ? 'true' : undefined}
              aria-label="{tag.label}, {n} {n === 1 ? 'photo' : 'photos'}"
              aria-pressed={filter === tag.key} onclick={() => n && choose(tag.key)}>
        {tag.label} <span class="n">{n}</span>
      </button>
    {/each}
  </div>
  {#if actions}<div class="acts">{@render actions()}</div>{/if}
</div>
{#if heicBarOn() && photos.some(isHeic)}<HeicBar />{/if}
<div class="gal" bind:this={gal}>
  {#each shown as photo (photo.id)}
    <PhotoTile {photo} {label} tag={filter} {place} {type} fresh={fresh.has(photo.id)} i={Math.max(0, freshOrder.indexOf(photo.id))}
               {selecting} selected={selected.has(photo.id)} {onToggle} />
  {/each}
</div>
{#if !shown.length && active > 1}<p class="muted">{active === 2 ? 'No photos match both filters.' : 'No photos match these filters.'}</p>{/if}
