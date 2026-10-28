<script>
  import { hideToast, toast } from './toast.svelte.js';
  import { OVERLAYS_CHANGED, anyModalOpen } from './modal.js';

  /**
   * Shows the latest showToast() message for its time (2.6 s; 8 s with a link), with its link when it has one; following
   * the link hides it. The status region stays in the page so it is announced. While an overlay is open the link is out
   * of the Tab order (the toast stays live beside the overlay, and Tab must not leave the overlay for it).
   */
  let visible = $state(false);
  let overlay = $state(false);

  $effect(() => {
    const note = () => {
      overlay = anyModalOpen();
    };
    window.addEventListener(OVERLAYS_CHANGED, note);
    return () => window.removeEventListener(OVERLAYS_CHANGED, note);
  });

  $effect(() => {
    if (!toast.seq) return undefined;
    if (!toast.message) {
      visible = false;
      return undefined;
    }
    visible = true;
    overlay = anyModalOpen();
    const timer = setTimeout(() => {
      visible = false;
    }, toast.ms);
    return () => clearTimeout(timer);
  });
</script>

<div role="status" data-live>
  {#if visible}
    {#key toast.seq}
      <div class="toast">{toast.message}{#if toast.link}{' '}<a href={toast.link.href} tabindex={overlay ? -1 : undefined} onclick={hideToast}>{toast.link.label}</a>{/if}</div>
    {/key}
  {/if}
</div>
