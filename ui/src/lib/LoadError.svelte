<script>
  /**
   * A page whose data couldn't be loaded: the problem (lib/problems.js `loadProblem`: the server's words, or "Couldn't
   * load this page." when the library's drive is the reason, which the banner above already says) and Try again.
   */
  let { message, onRetry } = $props();
</script>

<div class="ex-problem"><p class="problem" role="alert">{message}</p><button class="btn ghost" type="button" onclick={onRetry}>Try again</button></div>
