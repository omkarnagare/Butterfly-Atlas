<script>
  import { onDestroy, tick } from 'svelte';
  import { createTag } from './api.js';
  import { alsoChanging, checkTagName, matchTags, tagNamed } from './tags.js';
  import {
    FIND_A_TAG, FIND_OR_ADD_A_TAG, MATCHING_TAGS, RECENT_TAGS, alreadyChosen, createTag as createWords, moreTags, noTagCalled,
  } from './messages.js';

  const SHOWN_MATCHES = 12;

  /**
   * The tag picker (behaviour tags design §4, contract §2.2): every built-in tag in tag order, then the user's tags that
   * are chosen, then a box "Find or add a tag" whose matches (in tag order, chosen ones left out, at most 12) can be
   * chosen; the user's other tags are found only by typing. A user tag unchosen here stays (unpressed) until `scope`
   * changes (a photo viewer passes the photo's id), so a slip is undone with one click. Chips never move when pressed.
   *
   * Under them, a "Recent tags" row: the user's tags that carry `recent` (getTags, used last first), those not shown
   * above, so the tags in use are a click away without typing (2026-11-13 design, addendum §6).
   *
   * `stateOf(key)` is 'on', 'some' or 'off'; `onToggle(key)` is what pressing a chip or choosing a match does. In 'tag'
   * mode a press of mating is `onToggle('mating', also)`, `also` the tags that change with it (male and female, lib/tags.js
   * alsoChanging): the screen sets each of them as it sets mating, in the same change. With
   * `tristate` (Tag photos) the chips are checkboxes (all / some / none); otherwise they are toggle buttons.
   * In `mode` 'tag' a name no tag has can be created: `createTag`, then `await onCreated(tag)` (the screen reads its
   * tags again), then `onToggle(tag.key)`; a refusal shows under the box and `onCreated(null)` still re-reads (another
   * window may have made the same tag a moment ago). In 'filter' mode (the Tags filter) there is no Create.
   * While `disabled` (the screen is busy) chips, matches and Create are aria-disabled and do nothing. `creating` (bindable)
   * is true while a Create runs, so the screen can hold its own Add or Apply until the new tag is known.
   *
   * Keys in the box: Enter chooses the tag named exactly what is typed, else the first match, else Create; Esc with text
   * clears it and goes no further. Esc in an empty box is left to the dialog or popover behind, except with
   * `blurOnEmptyEscape` (the photo viewers, which ignore keys typed in a text box): focus leaves the box, so the next
   * Esc closes the viewer.
   */
  let {
    tags = [], stateOf, onToggle, mode = 'tag', onCreated = async () => {}, disabled = false, scope = null, label = 'Tags',
    tristate = false, blurOnEmptyEscape = false, creating = $bindable(false),
  } = $props();

  const uid = $props.id();
  let box = $state(null);
  let query = $state('');
  let refusal = $state('');
  let gone = false;
  onDestroy(() => {
    gone = true;
  });

  // The user tags shown in this scope: chosen now, or chosen earlier in it. Kept outside Svelte's state on purpose: it
  // only remembers what the chips already showed, and a new scope starts afresh.
  let keptScope;
  let kept = new Set();
  let builtins = $derived(tags.filter((tag) => tag.source === 'builtin'));
  let mine = $derived.by(() => {
    if (keptScope !== scope) {
      keptScope = scope;
      kept = new Set();
    }
    const shown = [];
    for (const tag of tags) {
      if (tag.source === 'builtin') continue;
      if (stateOf(tag.key) !== 'off') kept.add(tag.key);
      if (kept.has(tag.key)) shown.push(tag);
    }
    return shown;
  });

  let recent = $derived(tags.filter((tag) => tag.source !== 'builtin' && tag.recent != null && !mine.includes(tag))
    .sort((a, b) => a.recent - b.recent));

  let typed = $derived(query.trim());
  let found = $derived(matchTags(tags, query));
  let open = $derived(found.filter((tag) => stateOf(tag.key) === 'off'));
  let exact = $derived(tagNamed(tags, query));
  // What Create would make, or why it can't (the server's words); nothing to say for a name of only marks.
  let check = $derived(mode === 'tag' && typed && !exact ? checkTagName(query, tags) : null);
  let creatable = $derived(check && check.name && !check.problem ? check.name : '');
  let problem = $derived(check?.name ? check.problem : '');
  let boxWords = $derived(mode === 'filter' ? FIND_A_TAG : FIND_OR_ADD_A_TAG);

  const checked = (state) => (state === 'on' ? 'true' : state === 'some' ? 'mixed' : 'false');

  function press(key) {
    if (disabled) return;
    const also = mode === 'tag' ? alsoChanging(key, stateOf) : [];
    if (also.length) onToggle(key, also);
    else onToggle(key);
  }

  function clear() {
    query = '';
    refusal = '';
  }

  /** A match chosen (from the list or with Enter): the box clears, and keyboard focus stays in it. */
  function choose(tag) {
    if (disabled) return;
    if (stateOf(tag.key) !== 'on') press(tag.key);
    clear();
    box?.focus();
  }

  async function create() {
    if (disabled || creating || !creatable) return;
    const name = creatable;
    const at = scope;
    creating = true;
    refusal = '';
    let tag;
    try {
      tag = await createTag(name);
    } catch (e) {
      if (!gone) refusal = e.message;
      try {
        await onCreated(null);
      } finally {
        creating = false;
      }
      return;
    }
    try {
      await onCreated(tag);
    } finally {
      creating = false;
    }
    // Closed, or moved on to another photo, meanwhile: the tag is made, but chosen for nothing.
    if (gone || at !== scope) return;
    onToggle(tag.key);
    clear();
    await tick();
    box?.focus();
  }

  function onKey(event) {
    if (event.isComposing) return;
    if (event.key === 'Escape') {
      if (query) {
        event.preventDefault();
        event.stopPropagation();
        clear();
      } else if (blurOnEmptyEscape) {
        event.preventDefault();
        box?.blur();
      }
    } else if (event.key === 'Enter') {
      event.preventDefault();
      if (disabled || creating || !typed) return;
      const tag = exact ?? open[0];
      if (tag) choose(tag);
      else if (creatable) create();
    }
  }
</script>

{#snippet chip(tag, state, onclick)}
  {#if tristate}
    <button class="chip" type="button" role="checkbox" aria-checked={checked(state)} class:on={state === 'on'} class:some={state === 'some'}
            aria-disabled={disabled} {onclick}>{tag.label}</button>
  {:else}
    <button class="chip" type="button" class:on={state === 'on'} aria-pressed={state === 'on'} aria-disabled={disabled} {onclick}>{tag.label}</button>
  {/if}
{/snippet}

<div class="tag-picker">
  <div class="chips" role="group" aria-label={label}>
    {#each [...builtins, ...mine] as tag (tag.key)}
      {@render chip(tag, stateOf(tag.key), () => press(tag.key))}
    {/each}
  </div>
  {#if recent.length}
    <div class="chips tp-recent" role="group" aria-label={RECENT_TAGS}>
      <span class="muted tp-label" aria-hidden="true">Recent</span>
      {#each recent as tag (tag.key)}
        {@render chip(tag, stateOf(tag.key), () => press(tag.key))}
      {/each}
    </div>
  {/if}
  <input class="field tp-find" type="search" bind:this={box} bind:value={query} maxlength="60" placeholder={boxWords} aria-label={boxWords}
         aria-describedby={refusal ? `${uid}-refusal` : problem ? `${uid}-problem` : undefined}
         oninput={() => (refusal = '')} onkeydown={onKey} />
  {#if typed}
    {#if open.length || creatable}
      <div class="chips" role="group" aria-label={MATCHING_TAGS}>
        {#each open.slice(0, SHOWN_MATCHES) as tag (tag.key)}
          {@render chip(tag, 'off', () => choose(tag))}
        {/each}
        {#if creatable}
          <button class="chip tp-create" type="button" aria-disabled={disabled || creating} onclick={create}>{createWords(creatable)}</button>
        {/if}
      </div>
    {/if}
    {#if open.length > SHOWN_MATCHES}<p class="muted" aria-live="polite">{moreTags(open.length - SHOWN_MATCHES)}</p>{/if}
    {#if !found.length}<p class="muted" aria-live="polite">{noTagCalled(typed)}</p>{/if}
    <!-- every tag the name matches is already chosen (shown above): said, so the box is never left with nothing under it -->
    {#if found.length && !open.length}<p class="muted" aria-live="polite">{alreadyChosen(found.map((tag) => tag.label))}</p>{/if}
    {#if problem}<p class="problem" id="{uid}-problem" aria-live="polite">{problem}</p>{/if}
  {/if}
  {#if refusal}<p class="problem" id="{uid}-refusal" role="alert">{refusal}</p>{/if}
</div>

<style>
  .tag-picker { display: grid; gap: 10px; }
  .tp-find { padding: 6px 10px; font-size: 14px; }
  .tag-picker p { margin: 0; }
  .tp-recent { align-items: center; }
  .tp-label { font-size: 12px; margin-right: 2px; }
  :global(.lb) .tp-find { background: rgba(255,255,255,.08); border-color: rgba(255,255,255,.16); color: #eaf1ed; }
  :global(.lb) .tp-find::placeholder { color: #9fb1a9; }
</style>
