<script>
  import { onMount, tick } from 'svelte';
  import { search } from './api.js';
  import { names, RANK_LABELS } from './names.js';
  import { famStyle } from './families.js';
  import { hrefFor } from './route.js';
  import { modal } from './modal.js';
  import { splitBadge } from './updates.js';
  import { ALSO_KNOWN_AS } from './messages.js';

  /** ⌘K / Ctrl+K / "/" search (docs/PLAN.md §8a): results as you type, arrow keys and Enter. */
  let { onClose, returnFocus = undefined } = $props();

  let query = $state('');
  let results = $state([]);
  let selected = $state(0);
  let status = $state('idle');
  let searched = $state('');
  let announce = $state('');
  let error = $state('');
  let input;
  let timer;
  let controller;

  onMount(() => {
    input?.focus();
    return () => {
      clearTimeout(timer);
      controller?.abort();
    };
  });

  function onInput(event) {
    query = event.currentTarget.value;
    selected = 0;
    announce = '';
    clearTimeout(timer);
    controller?.abort();
    const wanted = query.trim();
    if (!wanted) {
      results = [];
      status = 'idle';
      return;
    }
    timer = setTimeout(() => run(wanted), 120);
  }

  async function run(wanted) {
    controller = new AbortController();
    status = 'loading';
    error = '';
    try {
      const found = await search(wanted, { signal: controller.signal });
      if (wanted === query.trim()) {
        results = found;
        selected = 0;
        searched = wanted;
        status = 'done';
        announce = found.length === 1 ? '1 result' : found.length ? `${found.length} results` : 'No results';
      }
    } catch (e) {
      if (e?.name !== 'AbortError') {
        results = [];
        selected = 0;
        error = e.message;
        status = 'error';
      }
    }
  }

  function open(hit) {
    onClose();
    window.location.hash = hrefFor(hit);
  }

  function onKeydown(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      selected = Math.max(0, Math.min(results.length - 1, selected + 1));
      reveal();
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      selected = Math.max(0, selected - 1);
      reveal();
    } else if (event.key === 'Enter' && results[selected]) {
      event.preventDefault();
      open(results[selected]);
    }
  }

  function onWindowKeydown(event) {
    if (event.key === 'Escape' && !event.isComposing) {
      event.preventDefault();
      onClose();
    }
  }

  async function reveal() {
    await tick();
    document.getElementById(`hit-${selected}`)?.scrollIntoView?.({ block: 'nearest' });
  }

  const aka = (hit) => (hit.matchKind === 'synonym' || hit.matchKind === 'word' ? hit.matched : null);
  const latinAka = (hit) => hit.matchKind === 'synonym';
  const tally = (hit) => (hit.rank === 'species'
    ? (hit.species.got ? 'photographed' : 'not yet')
    : `${RANK_LABELS[hit.rank] ?? hit.rank} · ${hit.species.got}/${hit.species.total}`);
</script>

<svelte:window onkeydown={onWindowKeydown} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov pal-bg" use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <div class="pal" role="dialog" aria-modal="true" aria-label="Search">
    <div class="pal-in">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
        <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
      </svg>
      <input bind:this={input} value={query} oninput={onInput} onkeydown={onKeydown}
             placeholder="Try “tiger”, “Papilio” or “blues”" autocomplete="off" spellcheck="false"
             aria-label="Search" role="combobox" aria-expanded={results.length > 0} aria-controls="search-results"
             aria-activedescendant={results.length ? `hit-${selected}` : undefined} />
      <kbd>Esc</kbd>
    </div>
    <div class="res-list" id="search-results" role="listbox" aria-label="Results">
      {#each results as hit, k (hit.id)}
        {@const n = names(hit)}
        <a id="hit-{k}" class="res" tabindex="-1" class:sel={k === selected} role="option" aria-selected={k === selected}
           href={hrefFor(hit)} style={famStyle(hit.family)}
           onclick={(e) => { e.preventDefault(); open(hit); }} onmousemove={() => (selected = k)}>
          <span class="dotf"></span>
          <span class="tx">
            <b class:bn={n.titleLatin}>{n.title}</b>
            {#if n.sub}<span class:bn={n.subLatin} class:muted={!n.subLatin}>{n.sub}</span>{/if}
            {#if aka(hit)}<span class="muted">{ALSO_KNOWN_AS} <i class:bn={latinAka(hit)}>{aka(hit)}</i></span>{/if}
            {#if hit.path}<span class="muted">{hit.path}</span>{/if}
            {#if hit.source === 'manual'}<span class="badge custom">Custom</span>{/if}
            {#if hit.active === false}<span class="badge">Not in the current India list</span>{/if}
            {#if hit.toSort > 0}<span class="badge warn">{splitBadge(hit.toSort)}</span>{/if}
          </span>
          <span class="rk2">{tally(hit)}</span>
        </a>
      {/each}
    </div>
    {#if status === 'done' && !results.length}
      <div class="pal-empty">No species or group matches “{searched}”. Try the common or the scientific name.</div>
    {/if}
    {#if status === 'error'}<div class="pal-empty" role="alert">{error}</div>{/if}
    <div class="sr-only" role="status">{announce}</div>
    <div class="pal-foot"><span>↑ ↓ move</span><span>Enter open</span><span>Esc close</span></div>
  </div>
</div>
