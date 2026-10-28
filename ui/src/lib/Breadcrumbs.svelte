<script>
  import { hrefFor } from './route.js';

  /** @type {{ path: Array<{id: string, rank: string, name: string}>, current: {rank: string, name: string} }} */
  let { path, current } = $props();
  const latin = (rank) => rank === 'genus' || rank === 'species';
</script>

<nav class="crumbs" aria-label="Location">
  {#each path as crumb (crumb.id)}
    <a href={hrefFor(crumb)} class:bn={latin(crumb.rank)}>{crumb.name}</a><span class="sep" aria-hidden="true">›</span>
  {/each}
  <span aria-current="page" class:bn={latin(current.rank)}>{current.name}</span>
</nav>
