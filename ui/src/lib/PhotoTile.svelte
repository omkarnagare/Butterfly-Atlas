<script>
  import { photoThumb } from './api.js';
  import { photoHref } from './route.js';
  import { rememberRect } from './flip.js';
  import { noteViewerOpen } from './viewer.js';
  import { thumbRetry } from './thumbRetry.js';
  import { CANT_SHOW, typeBadge } from './raw.js';

  /**
   * One gallery photo (prototype .tile): its thumbnail at its own proportions, tag labels over it and a
   * "New" badge just after it was added. A link to the viewer, or a toggle while selecting.
   * Until its real thumbnail arrives the tile shimmers; one still being made arrives as a 1×1 placeholder
   * (no-store), so the tile asks again a few times (thumbRetry). `tag`, `place` and `type` are the gallery filters, passed on
   * to the viewer. A RAW photo carries a "RAW" badge (a HEIC photo "HEIC", Phase 7b); a photo whose thumbnail can't be made (`unreadable`, any format)
   * says "Can't show this photo" instead of a grey tile (Phase 7a).
   */
  let {
    photo, label = (key) => key, tag = 'all', place = null, type = null, fresh = false, i = 0, selecting = false, selected = false, onToggle = () => {},
  } = $props();

  let names = $derived(photo.tags.map(label));
  let ratio = $derived(photo.width && photo.height ? `${photo.width} / ${photo.height}` : '4 / 3');
  let described = $derived([photo.fileName, ...names, photo.unreadable ? CANT_SHOW.toLowerCase() : ''].filter(Boolean).join(', '));
  let ready = $state(false);
  let badge = $derived(typeBadge(photo));

  function open(event) {
    rememberRect(event.currentTarget.querySelector('img'));
    noteViewerOpen();
  }
</script>

{#snippet body()}
  {#if photo.unreadable}
    <span class="cant" aria-hidden="true">{CANT_SHOW}</span>
  {:else}
    <img src={photoThumb(photo)} alt="" loading="lazy" decoding="async" width={photo.width} height={photo.height} use:thumbRetry={() => (ready = true)} />
  {/if}
  {#if names.length}<span class="tt" aria-hidden="true">{#each names as name (name)}<span>{name}</span>{/each}</span>{/if}
  {#if fresh || badge}
    <span class="tb">{#if fresh}<span class="new">New</span>{/if}{#if badge}<span class={badge.toLowerCase()}>{badge}</span>{/if}</span>
  {/if}
{/snippet}

{#if selecting}
  <button type="button" class="tile picking" class:sel={selected} class:waiting={!ready && !photo.unreadable} data-key={photo.id} data-photo-id={photo.id}
          style="aspect-ratio:{ratio}" aria-pressed={selected} aria-label="Select {described}" onclick={() => onToggle(photo)}>
    {@render body()}<span class="check" aria-hidden="true"></span>
  </button>
{:else}
  <a class="tile" class:fresh class:waiting={!ready && !photo.unreadable} href={photoHref(photo.taxonId, photo.id, tag, place, type)} data-key={photo.id} data-photo-id={photo.id}
     style="aspect-ratio:{ratio};--i:{i}" aria-label="Open {described}" onclick={open}>
    {@render body()}
  </a>
{/if}
