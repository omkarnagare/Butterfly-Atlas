<script>
  import { onMount, untrack } from 'svelte';
  import Popover from './Popover.svelte';
  import TagPicker from './TagPicker.svelte';
  import { getTags } from './api.js';

  /**
   * The Tags popover (contract §4): a TagPicker in filter mode (behaviour tags §4.5) — the built-in tags and the chosen
   * ones as toggles, the user's other tags found with "Find a tag", no Create; "Match any" / "Match all" (per photo).
   * Each change goes straight to `onChange(filter, { tags })`, with every tag's label so the chip can name them.
   * `tags` are the tags already read (the Export screen holds them). Only when none are given does it read them itself:
   * "Loading tags…" until they come; closed before then, the request is aborted and its answer ignored.
   */
  let { filter, tags: given = [], anchor = null, returnFocus, onChange, onClose } = $props();

  const builtinFirst = (all) => [...all.filter((tag) => tag.source === 'builtin'), ...all.filter((tag) => tag.source !== 'builtin')];

  const known = builtinFirst(untrack(() => given));
  let tags = $state(known);
  let loading = $state(known.length === 0);
  let error = $state('');

  onMount(() => {
    if (known.length) return undefined;
    const controller = new AbortController();
    getTags({ signal: controller.signal })
      .then((all) => {
        if (controller.signal.aborted) return;
        tags = builtinFirst(all);
      })
      .catch((e) => {
        if (!controller.signal.aborted) error = e.message;
      })
      .finally(() => {
        if (!controller.signal.aborted) loading = false;
      });
    return () => controller.abort();
  });

  const labels = () => tags.map(({ key, label }) => ({ key, label }));

  function toggle(key) {
    const next = filter.tags.includes(key) ? filter.tags.filter((each) => each !== key) : [...filter.tags, key];
    onChange({ ...filter, tags: next, tagMode: next.length ? filter.tagMode : 'any' }, { tags: labels() });
  }

  const mode = (tagMode) => onChange({ ...filter, tagMode }, {});
</script>

<Popover title="Tags" {anchor} {returnFocus} {onClose}>
  <div class="seg" role="group" aria-label="Photos match">
    <button type="button" class:on={filter.tagMode === 'any'} aria-pressed={filter.tagMode === 'any'} onclick={() => mode('any')}>Match any</button>
    <button type="button" class:on={filter.tagMode === 'all'} aria-pressed={filter.tagMode === 'all'} onclick={() => mode('all')}>Match all</button>
  </div>
  <TagPicker {tags} mode="filter" stateOf={(key) => (filter.tags.includes(key) ? 'on' : 'off')} onToggle={toggle} />
  {#if loading}<p class="muted" role="status">Loading tags…</p>{/if}
  {#if error}<p class="problem" role="alert">{error}</p>{/if}
</Popover>
