/*
 * Overlays (sheets, viewer, dialogs) use this Svelte action on their root. While one is open the rest
 * of the page is inert — not focusable, not clickable, hidden from screen readers — and does not
 * scroll; when it closes, focus goes back to `returnFocus()` or to whatever had it before. Elements
 * marked data-live (the toast) stay live so their messages are still announced. Overlays can nest
 * (a dialog over the viewer); only the top one should answer Escape.
 *
 * What is inert is worked out from the stack every time it changes, never piecemeal: everything outside
 * the top overlay is inert and the top overlay never is, whatever order or update two overlays opened in.
 * When the top one closes, the one below is live again and focus goes back into it (to where it was, or
 * its first control), unless the closing overlay names another place with `returnFocus` that is usable:
 * inside the overlay now on top, or anywhere once none is open. Focus starts on [data-autofocus] (not
 * disabled), else the first control. Content the page adds behind an open overlay is made inert as it comes.
 */
const stack = [];
const marked = new Set();
let overflow = null;
let observer = null;

export const anyModalOpen = () => stack.length > 0;
/** Sent on window whenever an overlay opens or closes (the toast takes its link out of the Tab order meanwhile). */
export const OVERLAYS_CHANGED = 'atlas:overlays-changed';
export const isTopModal = (node) => stack.length > 0 && stack[stack.length - 1].node === node;

const AUTOFOCUS = '[data-autofocus]:not(:disabled)';
const FOCUSABLE = 'input:not(:disabled), select:not(:disabled), textarea:not(:disabled), button:not(:disabled), [href], [tabindex]:not([tabindex="-1"])';

/** The control an overlay starts on: the one marked data-autofocus (not disabled), else its first control. */
export const firstControl = (node) => node.querySelector(AUTOFOCUS) ?? node.querySelector(FOCUSABLE);

/** Whether `el` should be made inert while `top` is the top overlay (it is beside it, not part of its path). */
const shuts = (el, top) => el.nodeType === 1 && !el.contains(top) && !el.hasAttribute('inert') && !el.hasAttribute('data-live') && el.tagName !== 'SCRIPT';

// New children of the top overlay's ancestors (a toast, a selection bar) arrive live: make just those inert too
// (a change of the stack still works everything out again in apply()).
function watch() {
  if (observer || typeof MutationObserver !== 'function') return;
  observer = new MutationObserver((records) => {
    const top = stack[stack.length - 1]?.node;
    if (!top) return;
    for (const record of records) {
      if (record.type !== 'childList' || record.target === top || !record.target.contains(top)) continue;
      for (const added of record.addedNodes) {
        if (added.parentElement !== record.target || !shuts(added, top)) continue;
        added.setAttribute('inert', '');
        marked.add(added);
      }
    }
  });
  observer.observe(document.body, { childList: true, subtree: true });
}

function unwatch() {
  observer?.disconnect();
  observer = null;
}

/** Makes everything outside the top overlay inert, and nothing else (inert set by others is left alone). */
function apply() {
  window.dispatchEvent(new CustomEvent(OVERLAYS_CHANGED));
  for (const el of marked) el.removeAttribute('inert');
  marked.clear();
  const root = document.documentElement;
  if (!stack.length) {
    if (overflow !== null) root.style.overflow = overflow;
    overflow = null;
    unwatch();
    return;
  }
  watch();
  if (overflow === null) overflow = root.style.overflow;
  root.style.overflow = 'hidden';
  const top = stack[stack.length - 1].node;
  for (let el = top; el && el.parentElement && el !== document.body; el = el.parentElement) {
    for (const sibling of el.parentElement.children) {
      if (sibling === el || !shuts(sibling, top)) continue;
      sibling.setAttribute('inert', '');
      marked.add(sibling);
    }
  }
}

/** Focus into the top overlay: where it last was, else its first control, else the overlay itself. */
function focusTop() {
  const entry = stack[stack.length - 1];
  if (!entry || entry.node.contains(document.activeElement)) return;
  const target = (entry.last?.isConnected && entry.node.contains(entry.last) ? entry.last : null)
    ?? firstControl(entry.node) ?? entry.node;
  target.focus?.();
}

export function modal(node, options = {}) {
  const entry = { node, options: options ?? {}, before: document.activeElement, last: null };
  const remember = (event) => {
    entry.last = event.target;
  };
  node.addEventListener('focusin', remember);
  stack.push(entry);
  apply();
  // An overlay focuses its own first control as it mounts; if nothing inside the top one has focus by then, give it.
  queueMicrotask(focusTop);

  return {
    update(next) {
      entry.options = next ?? {};
    },
    destroy() {
      node.removeEventListener('focusin', remember);
      const at = stack.indexOf(entry);
      if (at === -1) return;
      const wasTop = at === stack.length - 1;
      stack.splice(at, 1);
      apply();
      if (!wasTop) return;
      const chosen = entry.options.returnFocus?.() ?? null;
      queueMicrotask(() => {
        const below = stack[stack.length - 1];
        if (chosen?.isConnected && (!below || below.node.contains(chosen))) chosen.focus?.();
        else if (!below && entry.before?.isConnected) entry.before.focus?.();
        else if (below && entry.before?.isConnected && below.node.contains(entry.before)) entry.before.focus?.();
        else focusTop();
      });
    },
  };
}
