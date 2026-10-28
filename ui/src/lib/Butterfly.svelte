<script>
  import { butterflySvg, heroSvg } from './butterfly.js';

  /** @type {{ hero?: boolean }} */
  let { hero = false } = $props();
</script>

{#if hero}
  <span class="hero-fly">{@html heroSvg}</span>
{:else}
  {@html butterflySvg}
{/if}
