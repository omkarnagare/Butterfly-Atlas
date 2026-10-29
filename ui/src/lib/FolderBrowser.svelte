<script>
  import { onMount, tick } from 'svelte';
  import { getFsRoots, inspectFolder, listFolder } from './api.js';
  import { isTopModal, modal } from './modal.js';
  import { photoCount } from './importView.js';
  import { CLOSE, USE_THIS_FOLDER } from './messages.js';
  import { setupCanChoose, setupHere, setupLabel } from './setupView.js';

  /**
   * The in-app folder browser (design §4.1, decision B1): shortcuts and drives, breadcrumbs, the folders
   * inside with their own folder and photo counts, and a field for pasting a path. The server lists the
   * folders; nothing is uploaded. `onChoose(listing)` gets the folder as GET /api/fs/list answered it and may
   * throw (its message, e.g. the server's `library_folder` refusal, shows beside the button). The library's own folder can't be chosen. A folder with an unfinished
   * import offers to continue it. `relocating` is for an import whose folder moved: the button reads
   * "Use this folder". `exporting` is for choosing where an export goes (Phase 4b contract §5): titled "Choose where to
   * export", the button reads "Export here", and nothing is said of imports. `backup` is the same for a backup (Phase 6):
   * "Choose where to back up", "Back up here", "The backup goes inside …". `setup` is for choosing where the library is
   * kept (setup design §2): titled "Choose where to keep your library", it starts at Documents, shows no photo counts,
   * and says what happens to the folder shown from the server's check (its library opens, or one is made inside it).
   * Starts at `startPath`, else Pictures (Documents for setup), else the first shortcut.
   *
   * Keyboard: the folder list is one Tab stop (Up/Down/Home/End move in it, Enter opens); Backspace or Alt+Up
   * goes up one level (outside the path field). While a choice is being made it can't be closed. Focus goes back
   * to `returnFocus()` when it closes, else to where it was.
   */
  let { title = undefined, relocating = false, exporting = false, backup = false, setup = false, startPath = null, returnFocus = undefined, onChoose, onClose } = $props();

  const uid = $props.id();
  let root = $state(null);
  let list = $state(null);
  let chooseButton = $state(null);
  let roots = $state([]);
  let listing = $state(null);
  // Setup: the server's check of the folder shown (null while it is on its way, or when it failed).
  let check = $state(null);
  let pasted = $state('');
  let loading = $state(true);
  let busy = $state(false);
  let error = $state('');
  let active = $state(0); // the folder in the list that is in the Tab order
  let seq = 0;
  let controller = null;

  // The shortcut the folder shown is in: the longest one its path starts with (as a whole folder name).
  const inside = (path, base) => path === base || (path.startsWith(base) && (/[\\/]$/.test(base) || /^[\\/]/.test(path.slice(base.length))));
  let place = $derived(listing ? roots.filter((r) => inside(listing.path, r.path)).sort((a, b) => b.path.length - a.path.length)[0] ?? null : null);
  let cantChoose = $derived(!listing || (setup ? !setupCanChoose(check) : listing.isLibrary));
  // Some folder inside might hold photos: it has some, has folders of its own, or wasn't counted.
  let more = $derived(Boolean(listing?.folders.some((f) => f.photos || f.folders || f.photos == null)));
  // The path field's example, in this computer's own form.
  let example = $derived.by(() => {
    const windows = roots.length ? roots.some((r) => /^[A-Za-z]:\\/.test(r.path)) : /Windows/.test(globalThis.navigator?.userAgent ?? '');
    const mac = roots.length ? roots.some((r) => r.path.startsWith('/Users/') || r.path.startsWith('/Volumes/')) : /Mac/.test(globalThis.navigator?.userAgent ?? '');
    return windows ? 'For example, C:\\Users\\you\\Pictures\\Butterflies' : mac ? 'For example, /Users/you/Pictures/Butterflies' : 'For example, /home/you/Pictures/Butterflies';
  });
  // Where something is written (an export, a backup): a folder outside the library, and nothing said of imports.
  let writing = $derived(exporting || backup);
  let heading = $derived(title ?? (setup ? 'Choose where to keep your library' : backup ? 'Choose where to back up' : exporting ? 'Choose where to export' : 'Choose a folder to import'));
  let label = $derived(setup ? setupLabel(check) : backup ? 'Back up here' : exporting ? 'Export here' : relocating ? USE_THIS_FOLDER : listing?.openImport ? 'Continue your import' : 'Import this folder');
  let here = $derived(
    !listing ? ''
      : setup ? setupHere(check)
      : listing.isLibrary ? (writing ? "That's your library folder. Choose a folder outside the library." : "That's your library folder. Choose the folder your photos are in.")
        : writing ? `The ${backup ? 'backup' : 'export'} goes inside ${listing.name}.`
          : listing.openImport ? `You've filed ${listing.openImport.filed.toLocaleString()} of ${photoCount(listing.openImport.total)} from this folder.`
            : `${photoCount(listing.photos)} in this folder${more ? ', more in the folders inside' : ''}.`,
  );

  onMount(async () => {
    root?.querySelector('[role="dialog"]')?.focus();
    try {
      roots = await getFsRoots();
    } catch (e) {
      error = e.message;
      loading = false;
      return;
    }
    const start = startPath ?? (roots.find((r) => r.kind === (setup ? 'documents' : 'pictures')) ?? roots[0])?.path;
    if (start) await open(start, false);
    else loading = false;
  });

  /**
   * Lists `path`; a failure keeps the folder on screen and says why. `focusList` moves focus to its first folder.
   * A newer listing aborts an older one still on its way, and only the newest answer is shown. A typed path is
   * cleared only once it is the one opened (`typed`).
   */
  async function open(path, focusList = true, typed = false) {
    const mine = ++seq;
    controller?.abort();
    const mineController = typeof AbortController === 'function' ? new AbortController() : null;
    controller = mineController;
    loading = true;
    error = '';
    try {
      const next = await listFolder(path, { signal: mineController?.signal });
      if (mine !== seq) return false;
      check = null;
      listing = next;
      if (setup) checkFolder(next.path, mine);
      active = 0;
      if (typed) pasted = '';
      if (focusList) {
        await tick();
        (list?.querySelector('button') ?? chooseButton)?.focus();
      }
      return true;
    } catch (e) {
      if (mine === seq && e?.name !== 'AbortError') error = e.message;
      return false;
    } finally {
      if (mine === seq) loading = false;
    }
  }

  /** Setup: asks the server what happens to the folder shown; a failure says why, and the folder can't be chosen. */
  async function checkFolder(path, mine) {
    try {
      const answer = await inspectFolder(path, false);
      if (mine === seq) check = answer;
    } catch (e) {
      if (mine === seq) error = e.message;
    }
  }

  function onPaste(event) {
    event.preventDefault();
    if (pasted.trim()) open(pasted.trim(), true, true);
  }

  async function choose() {
    if (busy || loading || cantChoose) return;
    busy = true;
    error = '';
    try {
      await onChoose(listing);
    } catch (e) {
      error = e.message;
    } finally {
      busy = false;
    }
  }

  function counts(folder) {
    const parts = [];
    if (folder.folders) parts.push(folder.folders === 1 ? '1 folder' : `${folder.folders.toLocaleString()} folders`);
    if (setup) return parts.join(' · ');
    if (folder.photos) parts.push(photoCount(folder.photos));
    if (folder.folders === 0 && folder.photos === 0) parts.push('Empty');
    return parts.join(' · ');
  }

  /** Closing waits while a choice is being made (the import may be starting). */
  function close() {
    if (!busy) onClose();
  }

  function onKey(event) {
    if (!isTopModal(root) || event.isComposing) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return;
    }
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(event.target?.tagName ?? '');
    const backspace = event.key === 'Backspace' && !typing && !event.altKey && !event.metaKey && !event.ctrlKey;
    if ((backspace || (event.key === 'ArrowUp' && event.altKey)) && listing?.parent) {
      event.preventDefault();
      open(listing.parent);
    }
  }

  /** Up/Down/Home/End within the folder list. */
  function onListKey(event) {
    if (event.altKey || event.isComposing) return;
    const n = listing.folders.length;
    const next = { ArrowDown: active + 1, ArrowUp: active - 1, Home: 0, End: n - 1 }[event.key];
    if (next === undefined) return;
    event.preventDefault();
    active = Math.max(0, Math.min(n - 1, next));
    list?.querySelectorAll('button')[active]?.focus();
  }
</script>

<svelte:window onkeydown={onKey} />

{#snippet icon(kind)}
  <svg class="fb-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    {#if kind === 'home'}<path d="M4 11 12 4l8 7v8a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z" />
    {:else if kind === 'drive'}<rect x="3" y="13" width="18" height="7" rx="2" /><path d="M5 13 7.5 5h9L19 13M17 16.5h.01" />
    {:else}<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />{/if}
  </svg>
{/snippet}

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov dlg-bg" bind:this={root} use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) close(); }}>
  <div class="fb" role="dialog" aria-modal="true" aria-labelledby="{uid}-title" tabindex="-1">
    <div class="fb-h">
      <h2 id="{uid}-title">{heading}</h2>
      <button class="fb-x" type="button" aria-label={CLOSE} onclick={close}>×</button>
    </div>
    <div class="fb-body">
      <nav class="fb-roots" aria-label="Places">
        {#each roots as shortcut (shortcut.path)}
          <button type="button" class="fb-root" aria-current={place === shortcut ? 'true' : undefined}
                  onclick={() => open(shortcut.path)}>
            {@render icon(shortcut.kind)}{shortcut.name}
          </button>
        {/each}
      </nav>
      <div class="fb-main" aria-busy={loading}>
        {#if listing}
          <nav class="crumbs fb-crumbs" aria-label="Folder path">
            {#each listing.crumbs.slice(0, -1) as crumb (crumb.path)}
              <button type="button" dir="auto" title={crumb.path} onclick={() => open(crumb.path)}>{crumb.name}</button><span class="sep" aria-hidden="true">›</span>
            {/each}
            <span aria-current="page" dir="auto" title={listing.path}>{listing.name}</span>
          </nav>
          {#if listing.folders.length}
            <!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
            <ul class="fb-list" bind:this={list} aria-label="Folders in {listing.name}" onkeydown={onListKey}>
              {#each listing.folders as folder, k (folder.path)}
                <li>
                  <button type="button" tabindex={k === active ? 0 : -1} onfocus={() => (active = k)} onclick={() => open(folder.path)}>
                    {@render icon('folder')}
                    <span class="nm" dir="auto" title={folder.name}>{folder.name}</span>
                    <span class="ct">{counts(folder)}</span>
                  </button>
                </li>
              {/each}
            </ul>
          {:else}
            <p class="muted fb-none">No folders inside this one.</p>
          {/if}
        {:else if loading}
          <p class="muted fb-none">Opening…</p>
        {:else}
          <p class="muted fb-none">Paste the path of a folder below to open it.</p>
        {/if}
      </div>
    </div>
    <form class="fb-path" onsubmit={onPaste}>
      <label for="{uid}-path">Or paste the path of a folder</label>
      <div class="fb-row">
        <input id="{uid}-path" class="field mono" bind:value={pasted} autocomplete="off" spellcheck="false"
               onkeydown={(event) => { if (event.key === 'Enter' && !event.isComposing) onPaste(event); }}
               placeholder={listing?.path ?? example} />
        <button class="btn ghost" type="submit" aria-disabled={!pasted.trim()}>Go</button>
      </div>
    </form>
    <div class="dlg-f fb-f">
      {#if error}<p class="problem" id="{uid}-here" role="alert">{error}</p>{:else}<p class="muted" id="{uid}-here">{here}</p>{/if}
      <button class="btn ghost" type="button" onclick={close}>Cancel</button>
      <button class="btn" type="button" bind:this={chooseButton} aria-disabled={busy || loading || cantChoose}
              aria-describedby="{uid}-here" onclick={choose}>{busy && setup ? 'Setting up…' : busy && !writing ? 'Opening…' : label}</button>
    </div>
  </div>
</div>
