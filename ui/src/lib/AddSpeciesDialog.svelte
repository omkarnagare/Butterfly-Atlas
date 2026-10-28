<script>
  import { onMount, tick, untrack } from 'svelte';
  import Dialog from './Dialog.svelte';
  import { addCustomSpecies, checkCustomName, getChildren, renameCustomSpecies } from './api.js';
  import { famStyle } from './families.js';
  import { hrefFor } from './route.js';

  /**
   * Add a species the list doesn't have (Phase 5a contract §4): Family (the group's families, each in its colour),
   * Scientific name, Common name. The name is checked as it is typed (250 ms after the last key; an answer to older
   * text is dropped); a blocked name shows the server's message and, when it names a species, **Open it**. Add is
   * aria-disabled until a family is chosen and the name is ok. `name` pre-fills the scientific name (from the species
   * picker); focus then starts on the families. On success `onAdded(detail)` gets the new TaxonDetail. Add (or Enter) before
   * the name has an answer, or after a check failed, checks it at once and then goes on or shows the answer. While a save
   * is on its way the fields are read-only and a second submit does nothing.
   * With `taxon` (a custom species' TaxonDetail) it renames it instead: its family is shown, not editable, and
   * `onAdded(detail)` gets the species as renamed. While a save is on its way the dialog can't be closed.
   */
  let { name: initial = '', taxon = null, onAdded, onClose, returnFocus = undefined } = $props();

  const DELAY = 250;
  const GROUP = 'G:butterflies';
  const uid = $props.id();
  // What the dialog opened with: renaming or adding, and the text it starts from.
  const renaming = untrack(() => taxon !== null);

  let families = $state([]);
  let familiesError = $state('');
  let retrying = $state(false);
  let familyId = $state(null);
  let name = $state(untrack(() => (renaming ? taxon.name : initial)));
  let common = $state(untrack(() => (renaming ? taxon.common ?? '' : '')));
  // The check's answer and the text it was for (`for`); null while there is none (empty, waiting, asking or failed).
  let check = $state(null);
  // The text whose check failed (offline…): Add stays usable to ask again.
  let failedFor = $state(null);
  let error = $state('');
  let blocked = $state(null);
  let busy = $state(false);
  let nameInput = $state(null);
  let list = $state(null);
  let touched = false;
  let timer;
  let controller;

  let nameOk = $derived(check?.ok === true && check.for === name.trim());
  // Renaming: something must differ from the species as it is.
  let changed = $derived(!renaming || (nameOk && (check.name !== taxon.name || common.trim() !== (taxon.common ?? ''))));
  let retry = $derived(failedFor !== null && failedFor === name.trim());
  let ready = $derived(((nameOk && changed) || retry) && (renaming || familyId !== null) && !busy);

  function stopChecking() {
    clearTimeout(timer);
    controller?.abort();
  }

  /** Asks about the text now in the field; an answer for older text is dropped. */
  async function ask(text) {
    controller = new AbortController();
    failedFor = null;
    try {
      const answer = await checkCustomName(text, { id: renaming ? taxon.id : undefined, signal: controller.signal });
      if (text !== name.trim()) return;
      check = { ...answer, for: text };
      blocked = answer.ok ? null : answer;
    } catch (e) {
      if (e?.name === 'AbortError' || text !== name.trim()) return;
      check = null;
      failedFor = text;
      error = e.message;
    }
  }

  function schedule(now = false) {
    stopChecking();
    check = null;
    failedFor = null;
    blocked = null;
    const text = name.trim();
    if (!text) return;
    if (now) ask(text);
    else timer = setTimeout(() => ask(text), DELAY);
  }

  function onNameInput(event) {
    touched = true;
    name = event.currentTarget.value;
    error = '';
    schedule();
  }

  /**
   * Reads the families. `again` is Retry: it stays in place ("Retrying…", aria-disabled) until the answer, so a second
   * failure keeps focus on it; once the families are there, focus goes to the first.
   */
  async function loadFamilies(again = false) {
    if (retrying) return;
    if (again) retrying = true;
    else familiesError = '';
    try {
      const children = await getChildren(GROUP);
      families = children.filter((child) => child.rank === 'family');
      familiesError = '';
    } catch (e) {
      familiesError = e.message;
      return;
    } finally {
      retrying = false;
    }
    // Pre-filled from the picker: the family is what is left to choose, so focus goes there (unless the user has moved on).
    if (again || (initial && !touched && document.activeElement === nameInput)) {
      await tick();
      list?.querySelector('input')?.focus();
    }
  }

  onMount(() => {
    if (!renaming) loadFamilies();
    if (name.trim()) schedule(true);
    return stopChecking;
  });

  async function save(event) {
    event.preventDefault();
    if (busy) return;
    if (!renaming && familyId === null) {
      error = "Choose the species' family.";
      return;
    }
    const typed = name.trim();
    if (!typed) return;
    busy = true;
    error = '';
    // No answer yet for this text (typed just now, or the check failed): ask now, then go on or show the answer.
    if (check?.for !== typed) {
      stopChecking();
      await ask(typed);
    }
    if (!nameOk || !changed) {
      busy = false;
      return;
    }
    const text = check.name;
    const commonName = common.trim() || null;
    let detail;
    try {
      detail = renaming ? await renameCustomSpecies(taxon.id, text, commonName) : await addCustomSpecies(familyId, text, commonName);
    } catch (e) {
      busy = false;
      if (e?.code === 'in_list' || e?.code === 'name_exists') {
        check = { ok: false, code: e.code, message: e.message, taxon: e.taxon, for: name.trim() };
        blocked = check;
      } else {
        error = e.message;
      }
      return;
    }
    try {
      await onAdded(detail);
    } finally {
      busy = false;
    }
  }

  function close() {
    if (!busy) onClose();
  }
</script>

<Dialog title={renaming ? 'Rename species' : 'Add a species'} onClose={close} {returnFocus}>
  <form class="as-form" onsubmit={save} novalidate>
    {#if renaming}
      <p class="as-fam" style={famStyle(taxon.family)}><span class="lbl2">Family</span> <span class="as-dot" aria-hidden="true"></span>{taxon.family}</p>
    {:else}
      <fieldset class="ropt-set as-fams" bind:this={list}>
        <legend class="lbl2">Family</legend>
        {#each families as f (f.id)}
          <label class="ropt" style={famStyle(f.name)}>
            <input type="radio" name="{uid}-family" value={f.id} checked={familyId === f.id} aria-disabled={busy || undefined}
                   onclick={(e) => { if (busy) e.preventDefault(); }} onchange={() => { familyId = f.id; error = ''; }} />
            <span>{f.name}</span>{#if f.common}<span class="muted as-cn">{f.common}</span>{/if}
          </label>
        {:else}
          {#if familiesError}
            <p class="problem" role="alert">{familiesError}</p>
            <button class="btn ghost as-retry" type="button" aria-disabled={retrying} onclick={() => loadFamilies(true)}>{retrying ? 'Retrying…' : 'Retry'}</button>
          {:else}<p class="muted" role="status">Loading the families…</p>{/if}
        {/each}
      </fieldset>
    {/if}
    <label class="lbl2" for="{uid}-name">Scientific name</label>
    <input class="field bn" id="{uid}-name" bind:this={nameInput} value={name} oninput={onNameInput} data-autofocus maxlength="200" readonly={busy}
           autocomplete="off" spellcheck="false" placeholder="Genus and species, e.g. Neptis sankara" aria-describedby="{uid}-check" />
    <div id="{uid}-check" role="status" class="as-check">
      {#if blocked}
        <p class="problem">{blocked.message}{#if blocked.taxon}{' '}<a href={hrefFor({ ...blocked.taxon, rank: 'species' })} onclick={onClose}>Open it</a>{/if}</p>
      {:else if nameOk && check.name !== name.trim()}
        <p class="muted">It will be saved as <i class="bn">{check.name}</i>.</p>
      {/if}
    </div>
    <label class="lbl2" for="{uid}-common">Common name <span class="muted">(optional)</span></label>
    <input class="field" id="{uid}-common" bind:value={common} readonly={busy} maxlength="100" autocomplete="off" oninput={() => (error = '')} />
    {#if error}<p class="problem" role="alert">{error}</p>{/if}
    <div class="dlg-f">
      <button class="btn ghost" type="button" aria-disabled={busy} onclick={close}>Cancel</button>
      <button class="btn" type="submit" aria-disabled={!ready}>
        {#if busy}{renaming ? 'Renaming…' : 'Adding…'}{:else}{renaming ? 'Rename' : 'Add'}{/if}
      </button>
    </div>
  </form>
</Dialog>

<style>
  .as-form { display: grid; gap: 8px; }
  .as-form .lbl2 { margin: 6px 0 0; }
  .as-fams legend { padding: 0; }
  .as-fams .ropt input:checked { border-color: var(--fam); }
  .as-fams .ropt::before, .as-dot { content: ''; width: 10px; height: 10px; border-radius: 50%; background: var(--fam); flex: 0 0 auto; order: 1; }
  .as-fams .ropt span { order: 2; }
  .as-retry { justify-self: start; }
  .as-cn { font-weight: 400; font-size: 13px; }
  .as-fam { display: flex; align-items: center; gap: 8px; }
  .as-fam .lbl2 { margin: 0 4px 0 0; }
  /* always rendered (a live region hidden with display: none may not be announced when text appears); empty, it takes no room */
  .as-check:empty { margin-top: -8px; }
  .as-check .muted { font-size: 13px; }
  .field.bn { font-family: var(--latin); font-style: italic; }
  .field.bn::placeholder { font-family: var(--body); font-style: normal; }
</style>
