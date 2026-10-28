<script>
  import { tick, untrack } from 'svelte';
  import { addSearchWord, removeSearchWord } from './api.js';

  /** The user's own search words for one taxon (design D3). Pages remount it per taxon. */
  let { taxonId, words = [] } = $props();

  let list = $state(untrack(() => [...words]));
  let draft = $state('');
  let error = $state('');
  let open = $state(false);
  let input = $state(null);
  let toggleButton = $state(null);
  let panel = $state(null);
  let seq = 0;

  async function toggle() {
    open = !open;
    if (open) {
      await tick();
      input?.focus();
    }
  }

  function close() {
    open = false;
    toggleButton?.focus();
  }

  function onWindowKey(event) {
    if (open && event.key === 'Escape' && panel?.contains(document.activeElement)) {
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
      list = updated;
      draft = '';
      error = '';
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
      const buttons = panel?.querySelectorAll('ul button') ?? [];
      const target = buttons[Math.min(at, buttons.length - 1)];
      (target ?? input)?.focus();
    } catch (e) {
      if (mine === seq) error = e.message;
    }
  }
</script>

<svelte:window onkeydown={onWindowKey} />

<div class="words">
  <button class="chip" type="button" bind:this={toggleButton} aria-expanded={open} aria-controls="words-{taxonId}"
          aria-label={list.length ? `Search words, ${list.length} saved` : 'Search words'} onclick={toggle}>
    Search words{#if list.length} <span class="n">{list.length}</span>{/if}
  </button>
  {#if open}
    <div class="words-panel" id="words-{taxonId}" bind:this={panel} role="group" aria-label="Search words">
      <p class="muted">Add your own words to find this in search, such as a local name.</p>
      {#if list.length}
        <ul class="chips" aria-label="Your search words">
          {#each list as word (word)}
            <li class="chip">{word}<button type="button" aria-label="Remove {word}" onclick={() => remove(word)}>×</button></li>
          {/each}
        </ul>
      {/if}
      <form onsubmit={add}>
        <input class="field" bind:this={input} bind:value={draft} maxlength="80" aria-label="New search word"
               placeholder="A local or easy-to-remember name" oninput={() => (error = '')} />
        <button class="btn" type="submit" disabled={!draft.trim()}>Add</button>
      </form>
      {#if error}<p class="problem" role="alert">{error}</p>{/if}
    </div>
  {/if}
</div>
