<script>
  import Dialog from './Dialog.svelte';
  import ImportResult from './ImportResult.svelte';
  import { photoCount } from './importView.js';

  /**
   * An import's result as a dialog (design §4.5): what it has filed so far and, while photos still wait,
   * "Leave the rest for later" or "Finish and leave the rest in the folder" (else "Finish"). `ready` photos
   * have a species but aren't filed yet: the dialog says so, as finishing would leave them in the folder.
   * `error` is why finishing failed, as the server words it. Finish can't be pressed while `busy`. `kinds` names the
   * import's pairs (Phase 7b).
   */
  let { result, kinds = undefined, ready = 0, busy = false, error = '', returnFocus, onFinish, onClose } = $props();
</script>

<Dialog title={result.left ? 'Filed so far' : 'Everything is filed'} {returnFocus} {onClose}>
  <ImportResult {result} {kinds} />
  {#if ready}
    <p class="problem">{ready === 1 ? "1 photo has a species but isn't filed yet. File it first, or finishing leaves it in the folder."
      : `${ready.toLocaleString()} photos have a species but aren't filed yet. File them first, or finishing leaves them in the folder.`}</p>
  {/if}
  {#if result.left}
    <p class="muted">Finishing leaves the {photoCount(result.left)} still waiting in the folder; {result.left === 1 ? 'it' : 'they'} can't be filed from this import later.</p>
  {/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
  {#snippet actions()}
    {#if result.left}
      <button class="btn ghost" type="button" data-autofocus onclick={onClose}>Leave the rest for later</button>
      <button class="btn" type="button" aria-disabled={busy} onclick={() => { if (!busy) onFinish(); }}>Finish and leave the rest in the folder</button>
    {:else}
      <button class="btn ghost" type="button" onclick={onClose}>Not yet</button>
      <button class="btn" type="button" data-autofocus aria-disabled={busy} onclick={() => { if (!busy) onFinish(); }}>Finish</button>
    {/if}
  {/snippet}
</Dialog>
