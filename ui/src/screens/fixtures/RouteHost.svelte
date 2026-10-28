<script>
  import ImportRun from '../ImportRun.svelte';

  /** Test host: holds the route as App does (a new object on every address change) and passes it on. */
  let { initial } = $props();
  let route = $state(initial);
  export function go(next) {
    route = next;
  }
</script>

<ImportRun id={route.id} itemId={route.itemId ?? null} />
