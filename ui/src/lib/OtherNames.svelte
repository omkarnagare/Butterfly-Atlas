<script>
  import { tick, untrack } from 'svelte';
  import { addSearchWord, removeSearchWord } from './api.js';

  /**
   * A species' other names: the user's own names that search finds it by (design D3's search words; the server keeps
   * calling them words). Saved names show as chips; "+ Add a name" opens the field in place. Pages remount it per taxon.
   */
  let { taxonId, words = [] } = $props();

  let list = $state(untrack(() => [...words]));
  let draft = $state('');
  let error = $state('');
  let note = $state('');
  let open = $state(false);
  let input = $state(null);
  let addButton = $state(null);
  let chips = $state(null);
  let seq = 0;

  async function openField() {
    open = true;
    await tick();
    input?.focus();
  }

  async function close() {
    open = false;
    draft = '';
    error = '';
    note = '';
    await tick();
    addButton?.focus();
  }

  function onKey(event) {
    if (event.key === 'Escape') {
      event.stopPropagation();
      close();
    }
  }

  async function add(event) {
    event.preventDefault();
    const word = draft.trim();
    if (!word) return;
    const mine = ++seq;
    try {
      const updated = await addSearchWord(taxonId, word);
      if (mine !== seq) return;
      error = '';
      // The server saves nothing for a name the species already has: say so rather than empty the field.
      if (updated.length === list.length) {
        note = `“${word}” is already a name of this species.`;
        return;
      }
      list = updated;
      draft = '';
      note = '';
      await tick();
      input?.focus();
    } catch (e) {
      if (mine === seq) error = e.message;
    }
  }

  async function remove(word) {
    const at = list.indexOf(word);
    const mine = ++seq;
    try {
      const updated = await removeSearchWord(taxonId, word);
      if (mine !== seq) return;
      list = updated;
      error = '';
      await tick();
      const buttons = chips?.querySelectorAll('button') ?? [];
      const target = buttons[Math.min(at, buttons.length - 1)];
      (target ?? addButton ?? input)?.focus();
    } catch (e) {
      if (mine === seq) error = e.message;
    }
  }
</script>

<div class="other-names">
  <div class="on-row">
    {#if list.length}
      <span class="muted">Other names:</span>
      <ul class="chips" aria-label="Other names" bind:this={chips}>
        {#each list as word (word)}
          <li class="chip">{word}<button type="button" aria-label="Remove {word}" onclick={() => remove(word)}>×</button></li>
        {/each}
      </ul>
    {/if}
    {#if !open}
      <button class="btn ghost sm" type="button" bind:this={addButton} onclick={openField}>+ Add a name</button>
    {/if}
  </div>
  {#if open}
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <form onsubmit={add} onkeydown={onKey}>
      <input class="field" bind:this={input} bind:value={draft} maxlength="80" aria-label="New other name"
             placeholder="A local name or a nickname" oninput={() => { error = ''; note = ''; }} />
      <button class="btn" type="submit" disabled={!draft.trim()}>Add</button>
      <button class="btn ghost" type="button" onclick={close}>Cancel</button>
    </form>
    <p class="muted hint">Search finds this species by these names too, such as a local name or a nickname.</p>
    <p class="muted" role="status">{note}</p>
  {/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
</div>
