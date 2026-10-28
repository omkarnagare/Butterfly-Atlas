<script>
  import Butterfly from './Butterfly.svelte';
  import Outline from './Outline.svelte';
  import { names } from './names.js';
  import { famStyle } from './families.js';
  import { hrefFor } from './route.js';
  import { rememberMorph } from './morph.js';
  import { stagger } from './motion.js';
  import { thumbUrl } from './api.js';
  import { thumbRetry } from './thumbRetry.js';
  import { splitBadge } from './updates.js';
  import { PHOTOGRAPHED } from './messages.js';
  import { meadow } from './meadow.js';

  /**
   * A species card: cover photo (or a drawing until one exists) and names (prototype spCard). `strip` = narrow card for
   * Home strips: there a species not yet photographed (Next on your list) has its outline over a strip of grass (Meadow §4).
   */
  let { taxon, i = 0, strip = false } = $props();
  let n = $derived(names(taxon));
  let got = $derived(taxon.species.got > 0);
  let grass = $derived(strip && !got && !taxon.cover ? meadow(220, 30, { seed: i + 2, flowers: false }) : '');
</script>

<a class="spc" class:empty={!got} class:target={strip} href={hrefFor(taxon)} style="{famStyle(taxon.family)};--i:{stagger(i)}"
   onclick={(e) => rememberMorph(e.currentTarget.querySelector('[data-morph]'))}>
  <div class="cover" data-morph>
    {#if taxon.cover}
      <img src={thumbUrl(taxon.cover.id, taxon.cover.v)} alt="" loading="lazy" decoding="async" use:thumbRetry />
    {:else if got}<Butterfly />{:else}<Outline /><span class="lab">Not yet photographed</span>{@html grass}{/if}
  </div>
  <div class="meta">
    {#if n.titleLatin}
      <i class="bn">{n.title}</i>
    {:else}
      <span class="cnm">{n.title}</span><i class="bn">{n.sub}</i>
    {/if}
    <span class="pc">{got ? PHOTOGRAPHED : 'On your list'}</span>
    {#if taxon.source === 'manual'}<span class="badge custom">Custom</span>{/if}
    {#if !taxon.active}<span class="badge">Not in the current India list</span>{/if}
    {#if taxon.toSort > 0}<span class="badge warn">{splitBadge(taxon.toSort)}</span>{/if}
  </div>
</a>
