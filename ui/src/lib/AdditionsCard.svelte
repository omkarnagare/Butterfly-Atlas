<script>
  import { hrefFor } from './route.js';
  import { rememberMorph } from './morph.js';
  import { stagger } from './motion.js';

  /**
   * The Custom additions card (Phase 5a contract §4), last at group level: dashed, neutral, no flock. Empty, it invites
   * adding a species and `onAdd(button)` opens the Add dialog; with species it counts them and opens their page.
   * Without `onAdd` (Home, which shows it only once it has a species) it is always the link.
   */
  let { taxon, i = 0, onAdd = null } = $props();
  let total = $derived(taxon.species.total);
</script>

{#if total || !onAdd}
  <a class="node add-card" href={hrefFor(taxon)} style="--i:{stagger(i)}"
     onclick={(e) => rememberMorph(e.currentTarget.querySelector('[data-morph]'))}>
    <span class="nm" data-morph>{taxon.name}</span>
    <span class="ct">{total} species · {taxon.species.got} photographed</span>
  </a>
{:else}
  <button type="button" class="node add-card" style="--i:{stagger(i)}" onclick={(e) => onAdd(e.currentTarget)}>
    <span class="plus" aria-hidden="true">+</span>
    <span class="nm">{taxon.name}</span>
    <span class="cn">Add a species the list doesn't have</span>
  </button>
{/if}
