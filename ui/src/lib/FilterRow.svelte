<script>
  import { tick, untrack } from 'svelte';
  import Popover from './Popover.svelte';
  import { chips, without } from './filter.js';
  import { FILE_TYPE, PHOTO_STATUS } from './messages.js';

  /**
   * The Export screen's filter row (contract §4): one chip per active filter, worded from `names` (lib/filter.js
   * `chips`), each opening its popover (`onEdit(kind, chip)`) and with × to remove it (`onChange(filter)`); then
   * "+ Add filter", a menu of Taxa, Tags, Place, Photo status, Source (only while `manualSpecies`) and File type (Phase 7a), which
   * opens the chosen popover (`onEdit(kind, addButton)`). Missing life stages is set only by its preset, so its chip
   * can only be removed. Chips pop in as they appear (CSS, off under reduced motion).
   */
  let { filter, names, manualSpecies = false, onChange, onEdit } = $props();

  const uid = $props.id();
  let row = $state(null);
  let add = $state(null);
  let menu = $state(false);
  let list = $derived(chips(filter, names));
  let items = $derived([
    { kind: 'taxa', label: 'Taxa' },
    { kind: 'tags', label: 'Tags' },
    { kind: 'places', label: 'Place' },
    { kind: 'status', label: PHOTO_STATUS },
    ...(manualSpecies ? [{ kind: 'source', label: 'Source' }] : []),
    { kind: 'types', label: FILE_TYPE },
  ]);

  // Where focus goes once a removed chip has left the row (the page answers with a new filter).
  let refocus = -1;
  let removed = null;

  function remove(kind) {
    refocus = list.findIndex((chip) => chip.kind === kind);
    removed = kind;
    onChange(without(filter, kind));
  }

  // Focus goes to the chip now in the removed one's place, else to + Add filter. A new row that still has the removed
  // chip (the change made no difference; names arrived) forgets it, so a later row does not take focus.
  $effect(() => {
    const now = list;
    untrack(() => {
      if (refocus < 0) return;
      const at = refocus;
      const gone = !now.some((chip) => chip.kind === removed);
      refocus = -1;
      removed = null;
      if (!gone) return;
      tick().then(() => (row?.querySelectorAll('.fchip')[at]?.querySelector('button') ?? add)?.focus());
    });
  });

  async function choose(kind) {
    menu = false;
    await tick();
    onEdit(kind, add);
  }

  function onMenuKey(event) {
    const all = [...event.currentTarget.querySelectorAll('[role="menuitem"]')];
    const at = all.indexOf(document.activeElement);
    const to = { ArrowDown: (at + 1) % all.length, ArrowUp: (at - 1 + all.length) % all.length, Home: 0, End: all.length - 1 }[event.key];
    if (to === undefined) return;
    event.preventDefault();
    all[to]?.focus();
  }
</script>

<ul class="frow" bind:this={row} aria-label="Filters">
  {#each list as chip (chip.kind)}
    <li class="chip fchip">
      {#if chip.kind === 'stages'}
        <span>{chip.label}</span>
      {:else}
        <button type="button" class="fchip-l" aria-haspopup="dialog" onclick={(e) => onEdit(chip.kind, e.currentTarget)}>{chip.label}</button>
      {/if}
      <button type="button" class="fchip-x" aria-label="Remove {chip.label}" onclick={() => remove(chip.kind)}>×</button>
    </li>
  {/each}
  <li>
    <button type="button" class="chip fadd" bind:this={add} data-add-filter aria-haspopup="menu" aria-expanded={menu}
            aria-controls={menu ? `${uid}-menu` : undefined}
            onclick={() => (menu = true)}>+ Add filter</button>
  </li>
</ul>
{#if menu}
  <Popover title="Add filter" role="menu" anchor={add} onClose={() => (menu = false)}>
    <!-- svelte-ignore a11y_interactive_supports_focus -->
    <div class="fmenu" id="{uid}-menu" role="menu" aria-label="Add filter" tabindex="-1" onkeydown={onMenuKey}>
      {#each items as item (item.kind)}
        <button type="button" role="menuitem" onclick={() => choose(item.kind)}>{item.label}</button>
      {/each}
    </div>
  </Popover>
{/if}

<style>
  .frow { display: flex; flex-wrap: wrap; gap: 8px; margin: 0; padding: 0; list-style: none; align-items: center; }
  .fchip { padding: 0; gap: 0; background: var(--ink); color: var(--bg); border-color: var(--ink); animation: pop .3s cubic-bezier(.2,.8,.2,1.3) both; }
  .fchip > span, .fchip-l { padding: 6px 4px 6px 12px; font: inherit; }
  .fchip-x { padding: 6px 12px 6px 6px; opacity: .7; font-size: 15px; line-height: 1; }
  /* The chip is ink on bg: its buttons keep that colour in every state, in both themes (the shared ".chip button:hover"
     would give them the ink colour, invisible on the chip). */
  .fchip > span, .fchip button, .fchip button:hover, .fchip button:focus-visible, .fchip button:active { color: inherit; }
  .fchip-x:hover { opacity: 1; }
  .fadd { border-style: dashed; color: var(--muted); }
  .fadd:hover, .fadd[aria-expanded='true'] { color: var(--ink); }
  .fmenu { display: grid; gap: 2px; outline: none; }
  .fmenu button { text-align: left; padding: 9px 10px; border-radius: 10px; font-weight: 600; }
  .fmenu button:hover, .fmenu button:focus-visible { background: var(--surface-2); }
</style>
