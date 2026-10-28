<script>
  import { tick } from 'svelte';
  import { pairChoiceLabel, pairChoices, pairCount, pairName } from './importView.js';
  import { NO_DECODER } from './heic.js';
  import { status as appStatus } from './status.svelte.js';

  /**
   * How the import's RAW + JPEG pairs are filed (Phase 7a contract §3, audit 6), above the grid. While pairs wait to be
   * filed and nothing is chosen: "This folder has 340 RAW + JPEG pairs (same name, same folder). File: ◯ Both ◯ JPEG
   * only ◯ RAW only", nothing pre-selected, with the help "Both: two photos of each shot."; a choice is saved at once
   * (`onChoose`). After a choice: one line, "RAW + JPEG pairs: Both · Change"; Change shows the choices again. Once a
   * pair has been filed the choice can't change: "RAW + JPEG pairs filed: Both.". `pairs` is the summary's
   * `{ count, toFile, choice, locked }` or null. While a change is being saved (`busy`) a choice does nothing.
   * Review fix U6: the choices opened by Change close again with "Keep Both" (the current choice), and a choice that
   * could not be saved (the page says why; the summary keeps its choice) puts the radios back as they were.
   * Phase 7b: every word follows the partner types the folder's pairs hold (`pairs.kinds`): "RAW + HEIC pairs", "HEIC
   * only", and with both kinds "RAW + JPEG or RAW + HEIC pairs", "JPEG or HEIC only" (contract §1, audit 14). With HEIC
   * pairs while the computer has no HEIC decoder (`heicMissing`), the bar adds that HEIC only would file photos that
   * can't be seen here yet (audit 15).
   */
  let { pairs, busy = false, heicMissing = false, onChoose } = $props();

  const uid = $props.id();
  let changing = $state(false);
  let line = $state(null);
  let set = $state(null);
  let kinds = $derived(pairs?.kinds);
  let named = $derived(pairName(kinds));
  // Review fix U6: the choice as the bar labels it ("HEIC only", "JPEG or HEIC only").
  let hiddenLine = $derived.by(() => {
    const label = pairChoiceLabel('jpeg', kinds);
    const what = kinds?.includes('jpeg') ? 'HEIC photos' : 'photos';
    // A system with no decoder Butterfly Atlas can use has nothing to wait for (review fix U11).
    if (appStatus.heic?.system === 'other') return `${NO_DECODER} ${label} would file ${what} you can't see here.`;
    return `This computer can't show HEIC photos yet. ${label} would file ${what} you can't see here until it can.`;
  });
  let open = $derived(Boolean(pairs && !pairs.locked && (pairs.choice ? changing : pairs.toFile > 0)));

  async function choose(choice) {
    if (busy) return;
    await onChoose(choice);
    await tick();
    if (pairs?.choice !== choice) {
      // Not saved: the radios say the choice the import still has.
      for (const input of set?.querySelectorAll('input') ?? []) input.checked = input.value === pairs?.choice;
      return;
    }
    changing = false;
    await tick();
    // The choices go once one is saved: focus, lost with them, goes to the line's Change.
    if (!document.activeElement || document.activeElement === document.body) line?.querySelector('button')?.focus();
  }

  async function keep() {
    changing = false;
    await tick();
    line?.querySelector('button')?.focus();
  }

  async function change() {
    changing = true;
    await tick();
    document.getElementById(`${uid}-${pairs.choice}`)?.focus();
  }
</script>

{#if pairs?.count && pairs.locked && pairs.choice}
  <p class="imp-pairs-line">{named} pairs filed: {pairChoiceLabel(pairs.choice, kinds)}.</p>
{:else if open}
  <div class="imp-pairs" role="group" aria-labelledby="{uid}-what" aria-describedby="{uid}-help">
    <p id="{uid}-what">This folder has {pairCount(pairs.count, kinds)} (same name, same folder).</p>
    <fieldset class="ropt-set imp-pairs-set" bind:this={set}>
      <legend>File:</legend>
      {#each pairChoices(kinds) as option (option.value)}
        <label class="ropt">
          <input type="radio" name="{uid}-choice" id="{uid}-{option.value}" value={option.value} checked={pairs.choice === option.value}
                 aria-disabled={busy} onclick={(event) => { if (busy) event.preventDefault(); }} onchange={() => choose(option.value)} />
          <span>{option.label}</span>
        </label>
      {/each}
    </fieldset>
    <p class="muted" id="{uid}-help">Both: two photos of each shot.</p>
    {#if heicMissing && kinds?.includes('heic')}<p>{hiddenLine}</p>{/if}
    {#if pairs.choice}
      <p><button class="linkish" type="button" onclick={keep}>Keep {pairChoiceLabel(pairs.choice, kinds)}</button></p>
    {/if}
  </div>
{:else if pairs?.count && pairs.choice}
  <p class="imp-pairs-line" bind:this={line}>
    {named} pairs: {pairChoiceLabel(pairs.choice, kinds)} · <button class="linkish" type="button" aria-disabled={busy} onclick={() => { if (!busy) change(); }}>Change</button>
  </p>
{/if}
