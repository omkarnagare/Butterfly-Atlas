<script>
  import { leaveViewerFor } from './viewer.js';

  /**
   * The photo viewers' title: the species' common name over its scientific name, or the scientific name alone. As a
   * link (`linked`) it shows the species page in place of the viewer.
   */
  let { taxonId, name, common = null, linked = false } = $props();
  let title = $derived(common || name);

  function open(event) {
    event.preventDefault();
    leaveViewerFor(taxonId);
  }
</script>

{#if linked}
  <a class="lb-name" href="#/species/{encodeURIComponent(taxonId)}" title="Open {title}" onclick={open}>
    <span>{title}</span>{#if common}<i class="bn">{name}</i>{/if}
  </a>
{:else}
  <div class="lb-name"><span>{title}</span>{#if common}<i class="bn">{name}</i>{/if}</div>
{/if}
