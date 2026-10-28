<script>
  import Butterfly from './Butterfly.svelte';
  import Outline from './Outline.svelte';
  import Meter from './Meter.svelte';
  import { names, RANK_LABELS } from './names.js';
  import { famStyle } from './families.js';
  import { hrefFor } from './route.js';
  import { rememberMorph } from './morph.js';
  import { stagger } from './motion.js';

  /**
   * A group card: rank, name, progress (prototype nodeCard), on Home and in Explore. Its band (Meadow design §4), a tint
   * of the family colour, carries a flock for photographed species, or the outline butterfly before any; drawn first,
   * it comes last in the markup so the card is read as rank, name and count.
   */
  let { taxon, i = 0 } = $props();
  let n = $derived(names(taxon));
  let flock = $derived(Math.min(taxon.species.got, 5));
</script>

<a class="node banded" href={hrefFor(taxon)} style="{famStyle(taxon.family)};--i:{stagger(i)}"
   onclick={(e) => rememberMorph(e.currentTarget.querySelector('[data-morph]'))}>
  <span class="eyebrow">{RANK_LABELS[taxon.rank] ?? taxon.rank}</span>
  <span class="nm" class:bn={n.titleLatin} data-morph>{n.title}</span>
  <span class="cn">{n.sub ?? ''}</span>
  <Meter got={taxon.species.got} total={taxon.species.total} {i} />
  <span class="ct"><b>{taxon.species.got}</b> of {taxon.species.total} species</span>
  <span class="band">
    {#if flock}
      <span class="flock" class:one={flock === 1}>
        {#each Array.from({ length: flock }) as _, j (j)}<span class="mini" style="--j:{j}"><Butterfly /></span>{/each}
      </span>
    {:else}
      <span class="nf">Nothing photographed yet</span><Outline />
    {/if}
  </span>
</a>
