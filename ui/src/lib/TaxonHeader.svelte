<script>
  import Meter from './Meter.svelte';
  import SearchWords from './SearchWords.svelte';
  import { names, RANK_LABELS } from './names.js';
  import { famStyle } from './families.js';
  import { countUp } from './countUp.js';
  import { morphInto } from './morph.js';

  /**
   * Explore page header: rank, title, progress; `extra` renders under the progress (view switch). Custom additions has
   * its group's name as eyebrow (its rank label is its own name) and no search words; a progress line shows only while
   * there are species to count.
   */
  let { taxon, extra } = $props();
  let n = $derived(names(taxon));
  let additions = $derived(taxon.rank === 'additions');
  let eyebrow = $derived(taxon.rank === 'group' ? 'All families'
    : additions ? taxon.path?.find((c) => c.rank === 'group')?.name ?? '' : RANK_LABELS[taxon.rank] ?? taxon.rank);
</script>

<div class="xh" style={famStyle(taxon.family)}>
  <div>
    <div class="eyebrow">{eyebrow}</div>
    <h1 class:bn={n.titleLatin} use:morphInto>{n.title}</h1>
    {#if n.sub}<div class="cn">{n.sub}</div>{/if}
  </div>
  <div class="xstat">
    {#if taxon.species.total}
      <span class="ct"><b use:countUp={taxon.species.got}>{taxon.species.got}</b> of {taxon.species.total} species photographed</span>
      <Meter got={taxon.species.got} total={taxon.species.total} />
    {/if}
    {#if !additions}<SearchWords taxonId={taxon.id} words={taxon.searchWords} />{/if}
    {@render extra?.()}
  </div>
</div>
