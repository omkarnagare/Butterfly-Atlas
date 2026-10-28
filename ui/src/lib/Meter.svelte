<script>
  import { stagger } from './motion.js';
  /** @type {{ got: number, total: number, i?: number }} */
  let { got, total, i = 0 } = $props();
  let pct = $derived(total > 0 ? Math.min(100, (got / total) * 100) : 0);
</script>

<!-- `some`: anything at all counted keeps a sliver of the bar, so a first photo shows on a long list (Meadow §4) -->
<span class="meter" aria-hidden="true"><i class:some={got > 0} style="width:{pct}%;--i:{stagger(i)}"></i></span>
