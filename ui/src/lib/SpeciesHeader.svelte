<script>
  import Butterfly from './Butterfly.svelte';
  import OtherNames from './OtherNames.svelte';
  import LifeCycle from './LifeCycle.svelte';
  import { famStyle } from './families.js';
  import { morphInto } from './morph.js';
  import { countUp } from './countUp.js';
  import { displayUrl, thumbUrl } from './api.js';
  import { photoHref, splitHref } from './route.js';
  import { splitBadge } from './updates.js';
  import { rememberRect } from './flip.js';
  import { noteViewerOpen } from './viewer.js';
  import { thumbRetry } from './thumbRetry.js';

  /**
   * Top of a species page (prototype vSpecies): names, Atlas id, counts, life-cycle strip, folder. The hero
   * is the species' cover photo once it has one (docs/PLAN.md §8a: the user's own best photo), opening the
   * viewer; until then the flapping drawn butterfly. The large display image fades in over the thumbnail.
   * `extra` renders under the Atlas ID (Edit… on a custom species). A split species with photos still to sort
   * (`toSort`, Phase 5b) says how many, with a link to sort them.
   */
  let { taxon, photoCount, extra } = $props();
  let loaded = $state(false);
  // A different cover (or a new version of it) starts hidden again until its large image has loaded.
  $effect(() => {
    void taxon.cover?.id;
    void taxon.cover?.v;
    loaded = false;
  });

  function openCover(event) {
    rememberRect(event.currentTarget.querySelector('img'));
    noteViewerOpen();
  }
  let stageCount = $derived(Object.values(taxon.stages ?? {}).filter(Boolean).length);
</script>

<div class="sph" style={famStyle(taxon.family)}>
  <div class="sp-art" use:morphInto>
    {#if taxon.cover}
      {#key taxon.cover.id}
        <a class="sp-cover" href={photoHref(taxon.id, taxon.cover.id)} onclick={openCover}>
          <img src={thumbUrl(taxon.cover.id, taxon.cover.v)} alt="" decoding="async" use:thumbRetry />
          <img class="sharp" class:on={loaded} src={displayUrl(taxon.cover.id, taxon.cover.v)} alt="{taxon.common || taxon.name}, your cover photo"
               decoding="async" onload={() => (loaded = true)} onerror={() => (loaded = false)} />
        </a>
      {/key}
    {:else}
      <Butterfly hero />
    {/if}
  </div>
  <div class="sp-info stagger">
    <div class="path" style="--i:0">
      {#each taxon.path.slice(1) as c, k (c.id)}{k ? ' › ' : ''}<span class:bn={c.rank === 'genus'}>{c.name}</span>{/each}
    </div>
    <div style="--i:1">
      <h1>{taxon.name}</h1>
      {#if taxon.common}<div class="cn">{taxon.common}</div>{/if}
    </div>
    <div class="sp-tags" style="--i:2">
      <span class="idchip">Atlas ID {taxon.id}</span>
      {#if taxon.source === 'manual'}<span class="badge custom">Custom</span>{/if}
      {#if !taxon.active}<span class="badge">Not in the current India list</span>{/if}
      {#if taxon.toSort > 0}<span class="badge warn">{splitBadge(taxon.toSort)}</span><a class="sp-sort" href={splitHref(taxon.id)}>Sort photos</a>{/if}
      {@render extra?.()}
    </div>
    {#if taxon.synonyms.length}
      <div class="muted syn" style="--i:3">
        Also known as {#each taxon.synonyms as synonym, k (synonym)}{k ? ', ' : ''}<i class="bn">{synonym}</i>{/each}
      </div>
    {/if}
    <div style="--i:3"><OtherNames taxonId={taxon.id} words={taxon.searchWords} /></div>
    <div class="facts" style="--i:3">
      <span><b use:countUp={photoCount}>{photoCount}</b>{photoCount === 1 ? 'photo' : 'photos'}</span>
      <span><b>{stageCount}/4</b>life stages</span>
    </div>
    <div style="--i:4"><LifeCycle stages={taxon.stages} /></div>
    {#if taxon.folder}
      <div class="path" style="--i:5">{photoCount ? 'Stored in' : 'Folder created with the first photo:'} {taxon.folder}</div>
    {/if}
  </div>
</div>
