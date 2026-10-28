<script>
  /**
   * The top of an import's page: "Import folder" back to the imports (with "· Finished" for a finished one), the
   * folder's name as the page's heading (focusable, so focus can be moved to it) and its path, each in its own
   * text direction, and `children` beside them (filing and Finish…, or Remove).
   */
  let { summary, finished = false, heading = $bindable(null), children } = $props();
</script>

<div class="xh imp-h">
  <div>
    <div class="eyebrow"><a href="#/import">Import folder</a>{finished ? ' · Finished' : ''}</div>
    <h1 tabindex="-1" bind:this={heading} dir="auto">{summary.rootName}</h1>
    <div class="path" dir="auto">{summary.rootPath}</div>
  </div>
  {@render children?.()}
</div>
