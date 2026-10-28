<script>
  import { setBackupPreference } from './api.js';
  import Dialog from './Dialog.svelte';
  import { PREFERENCES } from './backups.js';

  /**
   * The one-time question (Phase 6 contract §3), asked on Home for a library that was never asked: "Do you want reminders
   * to back up your library?" with the three choices (Remind me chosen), Save and "You can change this later in
   * Settings". Save stores the choice (PUT /api/backup/preference) and hands the Backups answer to `onSaved`; a failure
   * says why and keeps the dialog open. Closing without saving (Not now, Escape, the backdrop) saves nothing, so it is
   * asked again the next time the app starts.
   */
  let { onSaved, onClose, returnFocus = undefined } = $props();

  const uid = $props.id();
  let choice = $state('remind');
  let busy = $state(false);
  let error = $state('');

  async function save() {
    if (busy) return;
    busy = true;
    error = '';
    try {
      await onSaved(await setBackupPreference(choice));
    } catch (e) {
      error = e.message;
    } finally {
      busy = false;
    }
  }

  function close() {
    if (!busy) onClose();
  }
</script>

<Dialog title="Do you want reminders to back up your library?" onClose={close} {returnFocus}>
  <fieldset class="ropt-set">
    <legend class="sr-only">Reminders to back up</legend>
    {#each PREFERENCES as option (option.value)}
      <label class="ropt">
        <input type="radio" name="{uid}-pref" value={option.value} checked={choice === option.value} data-autofocus={option.value === 'remind' ? '' : undefined}
               aria-describedby="{uid}-pref-{option.value}" onchange={() => (choice = option.value)} />
        <span>{option.label}</span>
      </label>
      <span class="ba-hint muted" id="{uid}-pref-{option.value}">{option.hint}</span>
    {/each}
  </fieldset>
  <p class="muted">You can change this later in Settings</p>
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}
    <button class="btn ghost" type="button" aria-disabled={busy} onclick={close}>Not now</button>
    <button class="btn" type="button" aria-disabled={busy} onclick={save}>{busy ? 'Saving…' : 'Save'}</button>
  {/snippet}
</Dialog>

<style>
  .ba-hint { display: block; padding: 0 10px 6px 36px; font-size: 13px; }
</style>
