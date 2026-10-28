<script>
  import { oakblueMarkup } from './oakblue.js';

  /** @type {{ mode?: 'perch' | 'fly' | 'rest', flower?: boolean }} */
  let { mode = 'perch', flower = false } = $props();
</script>

<span class="oak {mode}" aria-hidden="true">{@html oakblueMarkup(flower)}</span>
