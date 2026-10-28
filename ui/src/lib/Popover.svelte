<script>
  import { onMount } from 'svelte';
  import { firstControl, isTopModal, modal } from './modal.js';

  /**
   * A small panel under the control that opened it (`anchor`), for the Export screen's filter popovers and the
   * Add filter menu (contract §4). It is a modal() overlay: the page behind is inert, focus starts on its first control
   * (or [data-autofocus]) and goes back to `returnFocus()` (the opener by default) when it closes. Escape (top overlay
   * only, not while an input method is composing) and a click outside close it. Kept inside the window: moved left and up
   * when there is no room right of or under its opener (never past the top), no taller than the room below its top
   * (`--room`); on a narrow window it spans the width.
   * `role` is the panel's role: 'dialog' (titled with `title`), or 'menu' for a menu of actions (the menu names itself).
   */
  let { title, anchor = null, onClose, returnFocus = () => anchor, role = 'dialog', children } = $props();

  const GUTTER = 16;
  const uid = $props.id();
  let root = $state(null);
  let box = $state(null);
  let place = $state('');

  function position() {
    if (!anchor || !box) return;
    const rect = anchor.getBoundingClientRect();
    const width = box.offsetWidth;
    const height = box.offsetHeight;
    const left = Math.max(GUTTER, Math.min(rect.left, window.innerWidth - width - GUTTER));
    const top = Math.max(GUTTER, Math.min(rect.bottom + 8, window.innerHeight - GUTTER - height));
    const room = Math.max(0, window.innerHeight - top - GUTTER);
    place = `top: ${Math.round(top)}px; left: ${Math.round(left)}px; --room: ${Math.round(room)}px`;
  }

  onMount(() => {
    position();
    if (box && !box.contains(document.activeElement)) firstControl(box)?.focus();
  });

  function onKey(event) {
    if (event.key === 'Escape' && !event.isComposing && isTopModal(root)) {
      event.preventDefault();
      onClose();
    }
  }
</script>

<svelte:window onkeydown={onKey} onresize={position} />

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_static_element_interactions -->
<div class="ov pop-bg" bind:this={root} use:modal={{ returnFocus }} onclick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
  {#if role === 'menu'}
    <div class="pop" bind:this={box} style={place}>{@render children?.()}</div>
  {:else}
    <div class="pop" bind:this={box} style={place} role="dialog" aria-modal="true" aria-labelledby="{uid}-t">
      <h2 class="pop-t" id="{uid}-t">{title}</h2>
      {@render children?.()}
    </div>
  {/if}
</div>

<style>
  .pop-bg { background: transparent; }
  .pop {
    position: fixed; top: 120px; left: 16px; width: min(380px, calc(100vw - 32px)); max-height: min(70vh, 520px, var(--room, 100vh)); overflow-y: auto;
    display: grid; gap: 12px; padding: 14px; border-radius: 16px; background: var(--surface); color: var(--ink);
    border: 1px solid var(--line); box-shadow: 0 30px 60px -24px rgba(0,0,0,.45);
    animation: pop .22s cubic-bezier(.2,.8,.2,1.2) both; transform-origin: top left;
  }
  .pop-t { margin: 0; font: 500 11px/1 var(--mono); letter-spacing: .09em; text-transform: uppercase; color: var(--muted); }
  @media (max-width: 520px) { .pop { left: 16px !important; right: 16px; width: auto; } }
</style>
