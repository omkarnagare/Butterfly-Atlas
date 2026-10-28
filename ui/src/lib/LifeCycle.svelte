<script>
  import { adultSvg } from './butterfly.js';

  /** Egg → larva → pupa → adult, lit as each stage is photographed (prototype .lc), and the gold badge. */
  let { stages } = $props();

  const STAGES = [
    { key: 'egg', label: 'Egg', icon: '<svg viewBox="0 0 24 24" aria-hidden="true"><ellipse cx="12" cy="13" rx="6" ry="8"/></svg>' },
    { key: 'larva', label: 'Larva', icon: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="4" cy="14" r="3"/><circle cx="9" cy="12" r="3"/><circle cx="14" cy="12" r="3"/><circle cx="19" cy="14" r="3"/></svg>' },
    { key: 'pupa', label: 'Pupa', icon: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2c4 4 5 12 0 20-5-8-4-16 0-20z"/></svg>' },
    { key: 'adult', label: 'Adult', icon: adultSvg },
  ];

  let got = $derived(STAGES.filter((s) => stages?.[s.key]));
  let missing = $derived(STAGES.filter((s) => !stages?.[s.key]));
  let description = $derived(
    (got.length ? `Life stages photographed: ${got.map((s) => s.label.toLowerCase()).join(', ')}.` : 'No life stages photographed yet.')
    + (got.length && missing.length ? ` Not yet: ${missing.map((s) => s.label.toLowerCase()).join(', ')}.` : ''),
  );
</script>

<div class="lc" role="img" aria-label={description}>
  {#each STAGES as stage, k (stage.key)}
    {#if k}<span class="lcl" class:on={stages?.[stage.key] && stages?.[STAGES[k - 1].key]}></span>{/if}
    <span class="stage" class:on={stages?.[stage.key]} style="--i:{k}"><span class="dot">{@html stage.icon}</span>{stage.label}</span>
  {/each}
</div>
{#if !missing.length}<span class="full">Full life cycle photographed</span>{/if}
