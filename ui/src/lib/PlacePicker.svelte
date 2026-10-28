<script>
  import { onMount, tick } from 'svelte';
  import Dialog from './Dialog.svelte';
  import PlacesSheet from './PlacesSheet.svelte';
  import { addPlace, getRecentPlaces, searchPlaces } from './api.js';
  import { bareName, levelWord, placeWhere, sameName } from './places.js';
  import { PLACE_PROMPT } from './messages.js';

  /**
   * Choose the place for `count` photos (contract §3, design C8): a combobox like the species picker. It opens on
   * the recent places with nothing chosen; typing searches names and other spellings (after a short pause, from
   * 2 letters). Arrow keys move, Enter chooses, Escape closes. The last row, "Add “…” as a new place…", opens a
   * short form: the name and the state it is "Inside". "Your places…" opens the Your places sheet over it.
   * `onChoose(place)` may throw: the message shows and the picker stays. Former places that match (Phase 5c) are lines
   * under the list, not choices: "Chittoor is a former district (place names were updated). Choose Tirupati or
   * Annamayya.", each place it became a button that chooses it; a retired one "… and no longer listed.".
   */
  let { count = 1, onChoose, onClose, onPlacesChanged = () => {}, returnFocus } = $props();

  const DELAY = 150;
  const uid = $props.id();
  let input = $state(null);
  let nameInput = $state(null);
  let yourPlaces = $state(null);
  let query = $state('');
  let recent = $state([]);
  let results = $state([]);
  let formerMatches = $state([]);
  let searched = $state('');
  let selected = $state(-1);
  let error = $state('');
  let busy = $state(false);
  let managing = $state(false);
  // The new place form: its name, the state it is in, and the state search.
  let adding = $state(false);
  let name = $state('');
  let insideQuery = $state('');
  let parent = $state(null);
  let states = $state([]);
  let stateAt = $state(0);
  let statesFor = $state('');
  // Counts each opening and closing of the form, so an add answered after Back is not chosen.
  let form = 0;

  let title = $derived(count === 1 ? 'Place for this photo' : `Place for ${count.toLocaleString()} photos`);
  let wanted = $derived(query.trim());
  let ready = $derived(wanted.length >= 2 && searched === wanted);
  let addable = $derived(ready && !results.some((place) => sameName(place.name, wanted)));
  let showRecent = $derived(!wanted && recent.length > 0);
  let options = $derived(
    !wanted ? recent.map((place) => ({ place })) : ready ? [...results.map((place) => ({ place })), ...(addable ? [{ add: true }] : [])] : [],
  );

  /** A search that waits for typing to pause and drops answers to older text; `level` limits it to that level. */
  function searcher(answer, level) {
    let timer;
    let controller;
    const stop = () => {
      clearTimeout(timer);
      controller?.abort();
    };
    return {
      stop,
      run(text) {
        stop();
        if (text.length < 2) return;
        timer = setTimeout(async () => {
          controller = new AbortController();
          try {
            answer(text, await searchPlaces(text, { level, signal: controller.signal }));
          } catch (e) {
            if (e?.name !== 'AbortError') error = e.message;
          }
        }, DELAY);
      },
    };
  }

  const places = searcher((text, found) => {
    if (text !== query.trim()) return;
    results = found;
    formerMatches = found.formerMatches ?? [];
    searched = text;
    selected = 0;
  });
  // The server finds states only; the filter stays as a guard.
  const inside = searcher((text, found) => {
    if (text !== insideQuery.trim() || parent) return;
    states = found.filter((place) => place.level === 'state');
    statesFor = text;
    stateAt = 0;
  }, 'state');

  async function loadRecent() {
    try {
      recent = await getRecentPlaces();
    } catch {
      recent = [];
    }
  }

  onMount(() => {
    loadRecent();
    return () => {
      places.stop();
      inside.stop();
    };
  });

  function onInput(event) {
    query = event.currentTarget.value;
    results = [];
    formerMatches = [];
    searched = '';
    selected = -1;
    error = '';
    places.run(query.trim());
  }

  $effect(() => {
    const at = selected;
    if (at >= 0 && options.length) document.getElementById(`${uid}-${at}`)?.scrollIntoView?.({ block: 'nearest' });
  });

  /** Chooses `place`; false when onChoose refused it (the message shows). */
  async function choose(place) {
    if (busy) return false;
    busy = true;
    error = '';
    try {
      await onChoose(place);
      return true;
    } catch (e) {
      error = e.message;
      return false;
    } finally {
      busy = false;
    }
  }

  async function startAdding() {
    form++;
    adding = true;
    name = wanted;
    insideQuery = '';
    parent = null;
    states = [];
    statesFor = '';
    error = '';
    await tick();
    nameInput?.focus();
  }

  /** Back to the search, showing `message` if given. */
  async function stopAdding(message = '') {
    form++;
    inside.stop();
    adding = false;
    error = message;
    await tick();
    input?.focus();
  }

  function pick(option) {
    if (option.add) startAdding();
    else choose(option.place);
  }

  function onKeydown(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      selected = Math.min(options.length - 1, selected + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      if (selected > 0) selected--;
    } else if (event.key === 'Enter' && options[selected]) {
      event.preventDefault();
      pick(options[selected]);
    }
  }

  function onInsideInput(event) {
    insideQuery = event.currentTarget.value;
    parent = null;
    states = [];
    statesFor = '';
    error = '';
    inside.run(insideQuery.trim());
  }

  function chooseState(place) {
    parent = place;
    insideQuery = place.path.join(', ');
    states = [];
    statesFor = '';
  }

  function onInsideKey(event) {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      stateAt = Math.min(states.length - 1, stateAt + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      stateAt = Math.max(0, stateAt - 1);
    } else if (event.key === 'Enter' && states[stateAt]) {
      event.preventDefault();
      chooseState(states[stateAt]);
    }
  }

  // Escape in the form goes back to the search; it does not close the picker.
  function onFormKey(event) {
    if (event.key !== 'Escape') return;
    event.preventDefault();
    event.stopPropagation();
    stopAdding();
  }

  async function add(event) {
    event.preventDefault();
    if (busy) return;
    const trimmed = name.trim();
    if (!trimmed) {
      error = 'Give the place a name.';
      return;
    }
    if (!parent) {
      error = 'Choose the state it is in.';
      return;
    }
    busy = true;
    error = '';
    const run = form;
    let place;
    try {
      place = await addPlace(trimmed, parent.id);
    } catch (e) {
      if (run === form) error = e.message;
      busy = false;
      return;
    }
    busy = false;
    // Gone back meanwhile: the place exists now, so the search shows it instead of offering to add it.
    if (run !== form) {
      if (wanted.length >= 2) places.run(wanted);
      return;
    }
    if (await choose(place)) return;
    await stopAdding(error);
    if (wanted.length >= 2) places.run(wanted);
  }

  async function closeSheet() {
    managing = false;
    // A place renamed or deleted there shows as it is now.
    loadRecent();
    if (wanted.length >= 2) places.run(wanted);
  }
</script>

{#snippet row(place)}
  <span class="tx"><span class="pl-line"><b>{place.name}</b>{#if place.path.length > 1}<span class="muted">{` · ${placeWhere(place)}`}</span>{/if}</span></span>
{/snippet}

{#snippet pickerActions()}
  <button class="btn ghost pl-mine" type="button" bind:this={yourPlaces} onclick={() => (managing = true)}>Your places…</button>
  <button class="btn ghost" type="button" onclick={onClose}>Cancel</button>
{/snippet}

<Dialog {title} {onClose} {returnFocus} actions={adding ? undefined : pickerActions}>
  {#if !adding}
    <input class="field" bind:this={input} data-autofocus value={query} oninput={onInput} onkeydown={onKeydown} autocomplete="off" spellcheck="false"
           placeholder={PLACE_PROMPT} aria-label="Place" role="combobox" aria-autocomplete="list" aria-expanded={options.length > 0}
           aria-controls="{uid}-list" aria-activedescendant={selected >= 0 && options[selected] ? `${uid}-${selected}` : undefined} />
    <!-- in the page from the start, so a screen reader hears the former places when they come; above the list, so the
         line comes before "Add … as a new place…" -->
    <div class="pl-formers" aria-live="polite">
    {#if ready}
      {#each formerMatches as match (match.place.id)}
        <p class="pl-former">{bareName(match.place.name, match.place.level)} is a former {levelWord(match.place.level, match.place.path)} (place names were updated){#if match.now.length}{'. Choose '}{#each match.now as now, i (now.id)}{#if i > 0}{i === match.now.length - 1 ? ' or ' : ', '}{/if}<button class="linkish" type="button" aria-disabled={busy} aria-label={now.path.join(', ')} title={now.path.join(', ')} onclick={() => choose(now)}>{now.name}</button>{/each}.{:else}{' and no longer listed.'}{/if}</p>
      {/each}
    {/if}
    </div>
    {#if showRecent}<h3 class="lbl2 pl-h" id="{uid}-recent">Recent</h3>{/if}
    <div class="pick-list" id="{uid}-list" role="listbox" aria-label={showRecent ? undefined : 'Places'}
         aria-labelledby={showRecent ? `${uid}-recent` : undefined}>
      {#each options as option, k (option.add ? '+' : option.place.id)}
        <!-- svelte-ignore a11y_click_events_have_key_events -->
        <div class="res" id="{uid}-{k}" tabindex="-1" role="option" aria-selected={k === selected} aria-disabled={busy} class:sel={k === selected}
             onclick={() => pick(option)} onmousemove={() => (selected = k)}>
          {#if option.add}
            <span class="tx"><span class="pl-line">Add “{wanted}” as a new place…</span></span>
          {:else}
            {@render row(option.place)}
            <span class="rk2">{option.place.level}</span>
            {#if option.place.mine}<span class="pl-you">added by you</span>{/if}
          {/if}
        </div>
      {/each}
    </div>
    <p class="muted" role="status">{#if ready}{results.length ? (results.length === 1 ? '1 place' : `${results.length} places`) : formerMatches.length ? `No current place matches “${searched}”.` : `No place matches “${searched}”.`}{/if}</p>
  {:else}
    <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
    <form class="pl-add" onsubmit={add} onkeydown={onFormKey}>
      <label for="{uid}-name">Name</label>
      <input class="field" id="{uid}-name" bind:this={nameInput} bind:value={name} maxlength="80" autocomplete="off" oninput={() => (error = '')} />
      <label for="{uid}-inside">Inside</label>
      <input class="field" id="{uid}-inside" value={insideQuery} oninput={onInsideInput} onkeydown={onInsideKey} autocomplete="off" spellcheck="false"
             placeholder="Type a state" role="combobox" aria-autocomplete="list" aria-expanded={states.length > 0} aria-controls="{uid}-states"
             aria-activedescendant={states.length ? `${uid}-s${stateAt}` : undefined} />
      <div class="pick-list" id="{uid}-states" role="listbox" aria-label="States">
        {#each states as place, k (place.id)}
          <!-- svelte-ignore a11y_click_events_have_key_events -->
          <div class="res" id="{uid}-s{k}" tabindex="-1" role="option" aria-selected={k === stateAt} class:sel={k === stateAt}
               onclick={() => chooseState(place)} onmousemove={() => (stateAt = k)}>{@render row(place)}</div>
        {/each}
      </div>
      <p class="muted" role="status">{#if statesFor && !states.length}No state matches “{statesFor}”.{/if}</p>
      <p class="muted">A place you add is a district. You can rename or delete it in Your places.</p>
      <div class="dlg-f">
        <button class="btn ghost" type="button" onclick={() => stopAdding()}>Back</button>
        <button class="btn" type="submit" aria-disabled={busy}>Add place</button>
      </div>
    </form>
  {/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
</Dialog>
{#if managing}
  <PlacesSheet onChanged={onPlacesChanged} onClose={closeSheet} returnFocus={() => yourPlaces} />
{/if}
