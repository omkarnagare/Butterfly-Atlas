<script>
  import Popover from './Popover.svelte';

  /**
   * A popover with one choice among a few (contract §4: Photo status — All / Photographed / Not photographed; Source —
   * All / Official / Custom), as a radio group: focus starts on the current choice; arrow keys choose the next or
   * previous one (`onPick(value)`), Enter, Space or a click chooses and closes.
   */
  let { title, options, value, anchor = null, returnFocus, onPick, onClose } = $props();

  let group = $state(null);

  function pickAt(at, close) {
    const option = options[(at + options.length) % options.length];
    onPick(option.value);
    if (close) onClose();
    else group?.querySelectorAll('[role="radio"]')[(at + options.length) % options.length]?.focus();
  }

  function onKey(event) {
    // From the radio with focus: the page may not have answered the last choice yet.
    const radios = [...(group?.querySelectorAll('[role="radio"]') ?? [])];
    const focused = radios.indexOf(document.activeElement);
    const at = focused >= 0 ? focused : options.findIndex((option) => option.value === value);
    if (event.key === 'ArrowDown' || event.key === 'ArrowRight') {
      event.preventDefault();
      pickAt(at + 1, false);
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowLeft') {
      event.preventDefault();
      pickAt(at - 1, false);
    }
  }
</script>

<Popover {title} {anchor} {returnFocus} {onClose}>
  <div class="choices" role="radiogroup" aria-label={title} tabindex="-1" bind:this={group} onkeydown={onKey}>
    {#each options as option, k (option.value)}
      <button type="button" role="radio" aria-checked={option.value === value} tabindex={option.value === value ? 0 : -1}
              data-autofocus={option.value === value ? '' : undefined} onclick={() => pickAt(k, true)}>
        <span class="dot" aria-hidden="true"></span>{option.label}
      </button>
    {/each}
  </div>
</Popover>

<style>
  .choices { display: grid; gap: 2px; outline: none; }
  .choices button { display: flex; align-items: center; gap: 10px; text-align: left; padding: 9px 10px; border-radius: 10px; font-weight: 600; }
  .choices button:hover, .choices button:focus-visible { background: var(--surface-2); }
  .dot { width: 16px; height: 16px; border-radius: 50%; border: 1.5px solid var(--line); flex: 0 0 auto; transition: border-width .15s, border-color .15s; }
  [aria-checked='true'] .dot { border: 5px solid var(--accent); }
</style>
