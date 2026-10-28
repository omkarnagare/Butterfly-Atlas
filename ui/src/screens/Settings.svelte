<script>
  import { onMount } from 'svelte';
  import { getBackups, setBackupPreference } from '../lib/api.js';
  import BackupCard from '../lib/BackupCard.svelte';
  import BackupSheet from '../lib/BackupSheet.svelte';
  import PastBackups from '../lib/PastBackups.svelte';
  import LoadError from '../lib/LoadError.svelte';
  import { PREFERENCES, lastLine, unreadLine } from '../lib/backups.js';
  import { loadProblem } from '../lib/problems.js';
  import { loadSession, session } from '../lib/session.svelte.js';
  import { BACKUP_CHANGED, backupChanged, status } from '../lib/status.svelte.js';
  import HeicCheck from '../lib/HeicCheck.svelte';
  import { aboutLine } from '../lib/heic.js';
  import { chooseTheme, theme } from '../lib/theme.svelte.js';
  import { APPEARANCE, APPEARANCE_NOTE, DARK, LIGHT } from '../lib/messages.js';

  /**
   * Settings (#/settings, Phase 6 contract §3–§4): the Backup section, Appearance and About.
   * Backup: the three reminder choices (Remind me / Remind me less often / No backup needed), saved at once
   * (PUT /api/backup/preference); one that can't be saved goes back to the saved choice and says why. Under them the
   * last backup in words ("Last backup: 12 days ago — Everything", "4 photos could not be copied" when the last one left
   * photos out, "Never backed up"), Back up now (the sheet; inactive with its reason while a backup is running or
   * paused), the card of the running, paused or closed-by-the-app backup, and the past backups. Nothing about a
   * destination is remembered. The list is read again whenever a backup changes (BACKUP_CHANGED).
   * About: the version and the data, config and log folders (GET /api/session), and the standing note of a drive that
   * can't take the library's lock; details that can't be read say so, with Try again.
   * Phase 7b: how HEIC photos are shown, from `/api/status` `heic` (followed as it changes, audit 11): "HEIC photos: shown
   * with macOS's sips.", "HEIC photos: checking for a decoder…", or why they can't be and what to do, with Check again.
   * Appearance (Meadow design §6): Light or Dark, saved at once (PUT /api/appearance) and shown in every window; none is
   * chosen until one is saved, and the note says the computer's setting is followed meanwhile. A choice that can't be
   * saved goes back to the saved one and the server's reason shows under the choices, as for the reminders.
   */
  const uid = $props.id();

  let backups = $state(null);
  let error = $state('');
  let saving = $state(false);
  let prefError = $state('');
  let sheetOpen = $state(false);
  let backUpButton = $state(null);
  let aboutTried = $state(false);
  let aboutBusy = $state(false);
  let loads = 0;

  let busyBackup = $derived(status.backup !== null);

  const THEMES = [{ value: 'light', label: LIGHT }, { value: 'dark', label: DARK }];
  let themeSaving = $state(false);
  let themeError = $state('');

  async function load() {
    const mine = ++loads;
    try {
      const next = await getBackups();
      if (mine !== loads) return;
      backups = next;
      error = '';
    } catch (e) {
      // A failed reload keeps what is on screen.
      if (mine === loads && backups === null) error = loadProblem(e);
    }
  }

  async function about() {
    aboutBusy = true;
    try {
      await loadSession();
    } finally {
      aboutTried = true;
      aboutBusy = false;
    }
  }

  onMount(() => {
    load();
    if (!session.info) about();
    window.addEventListener(BACKUP_CHANGED, load);
    return () => window.removeEventListener(BACKUP_CHANGED, load);
  });

  // The radios show the saved choice: after a save that failed (or while one is on its way) they are put back as they were.
  function restore(fieldset) {
    for (const input of fieldset.querySelectorAll('input')) input.checked = input.value === backups.preference;
  }

  async function choose(event, value) {
    const fieldset = event.currentTarget.closest('fieldset');
    if (saving || value === backups.preference) {
      restore(fieldset);
      return;
    }
    saving = true;
    prefError = '';
    try {
      backups = await setBackupPreference(value);
    } catch (e) {
      prefError = e.message;
      restore(fieldset);
    } finally {
      saving = false;
    }
  }

  // As for the reminders: the radios show the saved appearance after a save that failed (or while one is on its way).
  function restoreTheme(fieldset) {
    for (const input of fieldset.querySelectorAll('input')) input.checked = input.value === theme.saved;
  }

  async function chooseAppearance(event, value) {
    const fieldset = event.currentTarget.closest('fieldset');
    if (themeSaving || value === theme.saved) {
      restoreTheme(fieldset);
      return;
    }
    themeSaving = true;
    themeError = '';
    try {
      await chooseTheme(value);
    } catch (e) {
      themeError = e.message;
      restoreTheme(fieldset);
    } finally {
      themeSaving = false;
    }
  }

  function openSheet() {
    if (!busyBackup) sheetOpen = true;
  }

  // The sheet closes with the start: the status follows the answer, and the card takes the sheet's place.
  async function started(run) {
    backupChanged(run);
    sheetOpen = false;
  }

  let unread = $derived(backups ? unreadLine(backups.lastUnread ?? 0) : '');
</script>

<div class="xh">
  <div>
    <div class="eyebrow">Your atlas</div>
    <h1 tabindex="-1">Settings</h1>
  </div>
</div>

<section class="sec set" aria-labelledby="{uid}-backup">
  <div class="sec-h"><h2 id="{uid}-backup">Backup</h2><span class="muted">Nothing is copied by itself, and where to copy is asked every time</span></div>
  {#if error}
    <LoadError message={error} onRetry={load} />
  {:else if backups === null}
    <p class="muted loading" role="status">Opening…</p>
  {:else}
    {#if status.backup}<BackupCard run={status.backup} />{/if}
    <fieldset class="ropt-set set-prefs">
      <legend class="lbl2">Reminders to back up</legend>
      {#each PREFERENCES as option (option.value)}
        <label class="ropt">
          <input type="radio" name="{uid}-pref" value={option.value} checked={backups.preference === option.value}
                 aria-describedby="{uid}-pref-{option.value}" aria-disabled={saving} onchange={(event) => choose(event, option.value)} />
          <span>{option.label}</span>
        </label>
        <span class="set-hint muted" id="{uid}-pref-{option.value}">{option.hint}</span>
      {/each}
    </fieldset>
    {#if prefError}<p class="problem" role="alert">{prefError}</p>{/if}
    <div class="set-last">
      <p class="set-when">{lastLine(backups)}</p>
      {#if unread}<p class="set-unread">{unread}</p>{/if}
      <p>
        <button class="btn" type="button" bind:this={backUpButton} aria-disabled={busyBackup} aria-describedby={busyBackup ? `${uid}-busy` : undefined}
                onclick={openSheet}>Back up now</button>
        {#if busyBackup}<span class="muted" id="{uid}-busy">Finish or cancel the backup in progress first.</span>{/if}
      </p>
    </div>
    <PastBackups past={backups.past ?? []} onRemoved={load} onCleared={load} fallbackFocus={() => backUpButton} />
  {/if}
</section>

<section class="sec set" aria-labelledby="{uid}-appearance">
  <div class="sec-h"><h2 id="{uid}-appearance">{APPEARANCE}</h2></div>
  <fieldset class="ropt-set set-prefs" aria-labelledby="{uid}-appearance" aria-describedby={theme.saved ? undefined : `${uid}-theme-note`}>
    {#each THEMES as option (option.value)}
      <label class="ropt">
        <input type="radio" name="{uid}-theme" value={option.value} checked={theme.saved === option.value}
               aria-disabled={themeSaving} onchange={(event) => chooseAppearance(event, option.value)} />
        <span>{option.label}</span>
      </label>
    {/each}
  </fieldset>
  {#if !theme.saved}<p class="set-note muted" id="{uid}-theme-note">{APPEARANCE_NOTE}</p>{/if}
  {#if themeError}<p class="problem" role="alert">{themeError}</p>{/if}
</section>

<section class="sec set" aria-labelledby="{uid}-about">
  <div class="sec-h"><h2 id="{uid}-about">About</h2></div>
  {#if session.info}
    <dl class="set-about">
      <dt>Version</dt><dd>{session.info.version}</dd>
      {#if session.info.dataFolder}<dt>Data folder</dt><dd class="mono" dir="auto">{session.info.dataFolder}</dd>{/if}
      <dt>Config folder</dt><dd class="mono" dir="auto">{session.info.configFolder}</dd>
      <dt>Log folder</dt><dd class="mono" dir="auto">{session.info.logFolder}</dd>
    </dl>
    {#if session.lockNote}<p class="set-unread">{session.lockNote}</p>{/if}
  {:else if aboutTried}
    <div class="ex-problem"><p class="problem" role="alert">The version and folders couldn't be read. Butterfly Atlas isn't answering; close it and open it again if this stays.</p>
      <button class="btn ghost" type="button" aria-disabled={aboutBusy} onclick={about}>Try again</button></div>
  {:else}
    <p class="muted loading" role="status">Opening…</p>
  {/if}
  {#if status.heic}
    <div class="set-heic">
      <p>{aboutLine(status.heic)}</p>
      <HeicCheck besides={aboutLine(status.heic)} />
    </div>
  {/if}
</section>

{#if sheetOpen}
  <BackupSheet returnFocus={() => backUpButton} onStarted={started} onClose={() => (sheetOpen = false)} />
{/if}

<style>
  .set-prefs legend { padding: 0; margin-bottom: 6px; }
  .set-hint { display: block; padding: 0 10px 6px 36px; font-size: 13px; }
  .set-note { margin: 0; font-size: 13px; }
  .set-last { display: grid; gap: 8px; }
  .set-last p { margin: 0; }
  .set-when { font-weight: 650; }
  .set-unread { margin: 0; padding: 10px 12px; border-radius: 12px; font-size: 14px; background: color-mix(in oklab, var(--glow) 16%, var(--surface));
    border: 1px solid color-mix(in oklab, var(--glow) 60%, var(--line)); }
  .set-about { display: grid; grid-template-columns: max-content minmax(0, 1fr); gap: 6px 18px; margin: 0; }
  .set-about dt { color: var(--muted); }
  .set-heic { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; margin-top: 12px; }
  .set-heic p { margin: 0; flex-basis: 100%; }
  .set-about dd { margin: 0; overflow-wrap: anywhere; }
  .set-about dd.mono { font-size: 13px; }
</style>
