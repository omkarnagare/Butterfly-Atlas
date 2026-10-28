<script>
  import { onMount } from 'svelte';
  import { firstControl, isTopModal, modal } from './modal.js';

  /**
   * A small centred dialog for choices and confirmations. Focus starts on the element marked
   * data-autofocus (else the first control); Escape, the backdrop and onClose dismiss it. Focus goes back to
   * `returnFocus()` when given, else to whatever had it before. `heading` (a snippet) shows the title with markup, such
   * as a scientific name in italics; `title` stays its words and names the dialog.
   */
  let { title, heading, onClose, children, actions, returnFocus } = $props();

  const uid = $props.id();
  let root = $state(null);
  let box = $state(null);

  onMount(() => {
    if (box) firstControl(box)?.focus();
  });

  function onKey(event) {
    if (event.key === 'Escape' && isTopModal(root)) {
      event.preventDefault();
      onClose();
    }
  }
</script>

<svelte:window onkeydown={onKey} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov dlg-bg" bind:this={root} use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  <!-- with markup in the heading, the dialog is named by `title` itself (an inline element can split the words) -->
  <div class="dlg" bind:this={box} role="dialog" aria-modal="true" aria-labelledby={heading ? undefined : `${uid}-title`}
       aria-label={heading ? title : undefined}>
    <h2 id="{uid}-title">{#if heading}{@render heading()}{:else}{title}{/if}</h2>
    {@render children?.()}
    {#if actions}<div class="dlg-f">{@render actions()}</div>{/if}
  </div>
</div>
