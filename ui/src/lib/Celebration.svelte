<script>
  import { onMount } from 'svelte';
  import { countUp } from './countUp.js';
  import { butterflySvg } from './butterfly.js';
  import { daisySvg } from './meadow.js';
  import { famColor } from './families.js';
  import { isTopModal, modal } from './modal.js';
  import { reducedMotion } from './motion.js';

  /**
   * First photo of a species (docs/PLAN.md §8a): the species counter ticks up, a burst of butterflies
   * flies out, and a milestone badge shows at 10, 25, 50… Pages show a toast instead under reduced motion.
   * An import celebrates all its new species at once: `added` new species (no `taxon`), the counter ticking
   * up from `count - added`. Focus goes back to `returnFocus()` (default: the Add photos button).
   * Meadow (design §5): the butterflies keep their family colours, the count takes the accent, and the milestone badge
   * sits on a small daisy.
   */
  let {
    taxon = null, count, added = 1, milestone = null, returnFocus = () => document.querySelector('[data-add-photos]'), onClose,
  } = $props();

  const FAMILIES = ['Papilionidae', 'Pieridae', 'Nymphalidae', 'Lycaenidae', 'Hesperiidae', 'Riodinidae'];
  let root = $state(null);
  let button = $state(null);
  let title = $derived(taxon ? taxon.common || taxon.name : '');

  onMount(() => {
    button?.focus();
    if (reducedMotion() || typeof document.body.animate !== 'function') return undefined;
    const flyers = [];
    const reach = Math.max(window.innerWidth, window.innerHeight) * 0.45;
    for (let i = 0; i < 18; i++) {
      const el = document.createElement('div');
      el.className = 'flyer';
      el.setAttribute('aria-hidden', 'true');
      el.style.setProperty('--fam', famColor(i < 6 && taxon ? taxon.family : FAMILIES[i % FAMILIES.length]));
      el.innerHTML = butterflySvg;
      document.body.append(el);
      flyers.push(el);
      const angle = Math.random() * Math.PI * 2;
      const r = 220 + Math.random() * reach;
      const x = Math.cos(angle) * r;
      const y = Math.sin(angle) * r - 80;
      const s = 0.6 + Math.random() * 0.9;
      const turn = () => (Math.random() - 0.5) * 60;
      const flight = el.animate([
        { transform: 'translate(0, 0) scale(.2)', opacity: 0 },
        { opacity: 1, offset: 0.15 },
        { transform: `translate(${x * 0.5}px, ${y * 0.5 - 60}px) rotate(${turn()}deg) scale(${s})`, opacity: 1, offset: 0.6 },
        { transform: `translate(${x}px, ${y - 120}px) rotate(${turn()}deg) scale(${s})`, opacity: 0 },
      ], { duration: 2200 + Math.random() * 1200, delay: i * 45, easing: 'cubic-bezier(.2,.7,.3,1)', fill: 'both' });
      flight.onfinish = () => el.remove();
    }
    return () => {
      for (const el of flyers) el.remove();
    };
  });

  function onKey(event) {
    if (event.key === 'Escape' && isTopModal(root)) {
      event.preventDefault();
      onClose();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov cele" bind:this={root} use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <div class="cele-card" role="dialog" aria-modal="true" aria-labelledby="cele-title" aria-describedby="cele-text">
    <span class="eyebrow" id="cele-title">New species in your atlas</span>
    <span class="big" aria-hidden="true" use:countUp={{ from: count - (taxon ? 1 : added), to: count }}>{count}</span>
    {#if taxon}
      <i class="bn">{taxon.name}</i>
      <p id="cele-text">{title} is species number {count}.</p>
    {:else}
      <p id="cele-text">{added === 1 ? '1 new species photographed!' : `${added.toLocaleString()} new species photographed!`} Your atlas now has {count.toLocaleString()} species.</p>
    {/if}
    {#if milestone}<span class="full">{@html daisySvg}Milestone reached: {milestone.toLocaleString()} species</span>{/if}
    <button class="btn" type="button" bind:this={button} onclick={onClose}>Keep going</button>
  </div>
</div>
