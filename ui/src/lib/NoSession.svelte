<script>
  /**
   * Phase 6 contract §2: an API answered `401 no_session`, so this page was not opened from the app (no session cookie,
   * e.g. a stale bookmark or another browser). It replaces the whole page; nothing is asked of the server any more
   * (api.js stops sending). The words are the server's `no_session` message (several-windows contract §1).
   */
  import { NO_SESSION_WORDS } from './messages.js';
</script>

<main class="wrap nosession" role="alert">
  <h1>{NO_SESSION_WORDS}</h1>
  <p class="muted">If you started it in a browser tab, reload from the address the app printed.</p>
</main>

<style>
  .nosession { min-height: 100vh; display: grid; align-content: center; justify-items: center; gap: 12px; text-align: center; }
  .nosession h1 { font-size: clamp(24px, 4vw, 34px); }
  .nosession p { margin: 0; }
</style>
