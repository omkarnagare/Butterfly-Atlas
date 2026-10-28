<script>
  import Oakblue from './Oakblue.svelte';
  import { openWindow } from './api.js';
  import { LINK_MS, showToast } from './toast.svelte.js';

  /** @type {{ current?: 'home' | 'explore' | 'export' | 'import' | 'settings', onSearch?: () => void }} */
  let { current = 'home', onSearch = () => {} } = $props();

  // New window (several-windows contract §8, A13): "Opening…" (aria-disabled) until the app answers, which can take a
  // few seconds while the browser starts; then a toast either way, a refusal in the server's words, long enough to read.
  let opening = $state(false);
  async function newWindow() {
    if (opening) return;
    opening = true;
    try {
      await openWindow();
      showToast('A new window is opening.');
    } catch (e) {
      showToast(e?.message ?? String(e), { ms: LINK_MS });
    } finally {
      opening = false;
    }
  }

  // The bar is sticky: its real height (it wraps on narrow windows) goes to --bar-h, which scroll-padding uses so
  // keyboard scrolling stops below it.
  let bar = $state(null);
  $effect(() => {
    if (!bar) return undefined;
    const note = () => document.documentElement.style.setProperty('--bar-h', `${bar.offsetHeight}px`);
    note();
    const observer = typeof ResizeObserver === 'function' ? new ResizeObserver(note) : null;
    observer?.observe(bar);
    return () => observer?.disconnect();
  });
</script>

<header class="bar" bind:this={bar}>
  <div class="wrap inner">
    <a class="logo" href="#/" aria-label="Butterfly Atlas home">
      <Oakblue mode="perch" />
      <b>Butterfly Atlas</b>
    </a>
    <nav aria-label="Main">
      <a class="navb" aria-current={current === 'home' ? 'page' : undefined} href="#/">Home</a>
      <a class="navb" aria-current={current === 'explore' ? 'page' : undefined} href="#/explore">Explore</a>
      <a class="navb" aria-current={current === 'export' ? 'page' : undefined} href="#/export">Export</a>
      <a class="navb" aria-current={current === 'settings' ? 'page' : undefined} href="#/settings">Settings</a>
      <button class="navb" type="button" aria-disabled={opening || undefined} onclick={newWindow}>{opening ? 'Opening…' : 'New window'}</button>
    </nav>
    <a class="imp" href="#/import" aria-label="Import folder" aria-current={current === 'import' ? 'page' : undefined}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /><path d="M12 10v6m-3-3h6" />
      </svg>
      <span>Import folder</span>
    </a>
    <button class="searchbtn" type="button" aria-label="Search species, genera, families" onclick={onSearch}>
      <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" aria-hidden="true">
        <circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" />
      </svg>
      <span>Search species, genera, families</span>
    </button>
  </div>
</header>

<style>
  .bar {
    position: sticky;
    top: 0;
    z-index: 20;
    background: color-mix(in oklab, var(--bg) 95%, transparent);
    border-bottom: 1px solid var(--line);
  }
  .inner { display: flex; align-items: center; gap: 10px; min-height: 62px; flex-wrap: wrap; }
  .logo { display: flex; align-items: center; gap: 8px; font: 700 19px/1 var(--display); margin-right: 8px; text-decoration: none; }
  .logo :global(.oak) { width: 50px; }
  nav { display: flex; gap: 4px; }
  .navb { padding: 8px 14px; border-radius: 999px; font-weight: 600; color: var(--muted); text-decoration: none; transition: background .2s, color .2s; }
  .navb:hover { color: var(--ink); }
  button.navb { border: 0; background: none; font: inherit; font-weight: 600; cursor: pointer; }
  button.navb[aria-disabled='true'] { cursor: default; }
  .navb[aria-current='page'] { background: var(--surface-2); color: var(--ink); }
  .imp {
    margin-left: auto; display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 12px; font-weight: 600;
    color: var(--muted); text-decoration: none; border: 1px solid transparent; transition: background .2s, color .2s;
  }
  .imp:hover { color: var(--ink); background: var(--surface-2); }
  .imp[aria-current='page'] { background: var(--surface-2); color: var(--ink); }
  .searchbtn {
    display: flex; align-items: center; gap: 10px; flex: 0 1 260px; min-width: 0;
    border: 1px solid var(--line); background: var(--surface); border-radius: 12px;
    padding: 8px 10px 8px 12px; color: var(--muted); transition: border-color .2s, box-shadow .2s;
  }
  .searchbtn:hover { border-color: var(--accent); box-shadow: 0 0 0 4px color-mix(in oklab, var(--accent) 14%, transparent); }
  .searchbtn span { flex: 1; text-align: left; }
  @media (max-width: 640px) {
    .searchbtn span, .imp span, .logo b { display: none; }
  }
</style>
