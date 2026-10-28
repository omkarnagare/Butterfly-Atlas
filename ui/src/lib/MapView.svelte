<script>
  import { onMount, untrack } from 'svelte';
  import { hierarchy, pack } from 'd3-hierarchy';
  import { interpolateZoom } from 'd3-interpolate';
  import { getTree } from './api.js';
  import { famColor } from './families.js';
  import { hrefFor } from './route.js';
  import { reducedMotion } from './motion.js';

  /**
   * Zoomable circle packing of the whole tree (docs/PLAN.md §8a, prototype drawMap). Filled circles
   * are photographed species, dashed ones are still to find. Only the focused level is labelled.
   */
  let { focusId, onFocus = () => {} } = $props();

  const SIZE = 640;
  let root = $state.raw(null);
  let focus = $state.raw(null);
  let view = $state.raw([SIZE / 2, SIZE / 2, SIZE]);
  let error = $state('');
  let frame = 0;

  let byId = new Map();
  const find = (id) => byId.get(id) ?? null;

  onMount(() => {
    getTree()
      .then((tree) => {
        const h = hierarchy(tree, (d) => (d.rank === 'species' ? null : d.children))
          .sum((d) => (d.rank === 'species' ? 1 : 0))
          .sort((a, b) => b.value - a.value);
        const laid = pack().size([SIZE, SIZE]).padding((d) => (d.depth === 0 ? 8 : 3))(h);
        byId = new Map(laid.descendants().map((d) => [d.data.id, d]));
        root = laid;
        const start = find(untrack(() => focusId)) ?? root;
        focus = start;
        view = [start.x, start.y, start.r * 2];
      })
      .catch((e) => {
        error = e.message;
      });
    return () => {
      if (frame) cancelAnimationFrame(frame);
    };
  });

  // Follow outside navigation (breadcrumbs, back button) without reacting to our own zooms.
  $effect(() => {
    const wanted = focusId;
    if (!root) return;
    untrack(() => {
      const node = find(wanted) ?? root;
      if (node !== focus) zoom(node, false);
    });
  });

  function zoom(node, report = true) {
    if (!node || node === focus) return;
    const from = view;
    const to = [node.x, node.y, node.r * 2];
    focus = node;
    if (report) onFocus(node.data);
    if (frame) cancelAnimationFrame(frame);
    if (reducedMotion() || typeof requestAnimationFrame !== 'function') {
      view = to;
      return;
    }
    const interpolate = interpolateZoom(from, to);
    const duration = Math.min(900, Math.max(350, interpolate.duration * 0.6));
    const start = performance.now();
    const step = (now) => {
      const t = Math.min(1, (now - start) / duration);
      view = interpolate(t);
      frame = t < 1 ? requestAnimationFrame(step) : 0;
    };
    frame = requestAnimationFrame(step);
  }

  function onCircle(event, node) {
    if (!focus) return;
    if (node === focus || !node.ancestors().includes(focus)) {
      // Let the click reach the svg handler, which zooms out one level.
      return;
    }
    event.stopPropagation();
    const next = node.ancestors().find((a) => a.depth === focus.depth + 1) ?? node;
    if (next.data.rank === 'species') window.location.hash = hrefFor(next.data);
    else zoom(next);
  }

  let nodes = $derived(root ? root.descendants() : []);
  let k = $derived(SIZE / view[2]);
  // Judge label size at the focus's own scale, so labels do not vanish while a zoom animates.
  let labels = $derived((focus?.children ?? []).filter((d) => d.r * (SIZE / (focus.r * 2)) >= 14));
  const label = (d) => (d.rank === 'species' ? d.common || d.name : d.name);
  const title = (d) => `${d.rank === 'species' && d.common ? `${d.common} (${d.name})` : d.name}`
    + (d.rank === 'species'
      ? ` · ${d.species.got > 0 ? 'photographed' : 'not yet photographed'}`
      : ` · ${d.species.got} of ${d.species.total} photographed`);
</script>

<div class="mapbox">
  {#if error}
    <p class="hint pad" role="alert">{error}</p>
  {:else if root && root.value === 0}
    <p class="hint pad">Nothing to map yet.</p>
  {:else if root}
    <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
    <svg class="map" viewBox="0 0 {SIZE} {SIZE}" role="img" aria-label="Map of the butterfly tree. Use the Cards view for a list you can browse with the keyboard."
         onclick={() => zoom(focus?.parent)}>
      <g transform="translate({SIZE / 2} {SIZE / 2}) scale({k}) translate({-view[0]} {-view[1]})">
        {#each nodes as d (d.data.id)}
          <!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
          <circle class="c r-{d.data.rank}" class:got={d.data.rank === 'species' && d.data.species.got > 0}
                  data-id={d.data.id} cx={d.x} cy={d.y} r={d.r}
                  style:--fam={d.data.family ? famColor(d.data.family) : null}
                  onclick={(e) => onCircle(e, d)}><title>{title(d.data)}</title></circle>
        {/each}
      </g>
      {#each labels as d (d.data.id)}
        <text x={(d.x - view[0]) * k + SIZE / 2} y={(d.y - view[1]) * k + SIZE / 2}
              style:font-size={d.data.rank === 'species' ? '11px' : '13px'}>{label(d.data)}</text>
      {/each}
    </svg>
  {:else}
    <p class="hint pad" role="status">Drawing the map…</p>
  {/if}
</div>
