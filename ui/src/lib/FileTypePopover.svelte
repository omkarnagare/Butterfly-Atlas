<script>
  import Popover from './Popover.svelte';
  import { FILE_TYPES } from './filter.js';
  import { FILE_TYPE } from './messages.js';

  /**
   * The File type popover (Phase 7a contract §4): JPEG, PNG, TIFF, WebP, RAW and HEIC (Phase 7b) as toggles, any number
   * at once; none means every type. Each change goes straight to `onChange(filter)`. This is how RAW and HEIC photos are
   * left out of an export.
   */
  let { filter, anchor = null, returnFocus, onChange, onClose } = $props();

  function toggle(value) {
    const types = filter.types ?? [];
    onChange({ ...filter, types: types.includes(value) ? types.filter((each) => each !== value) : [...types, value] });
  }
</script>

<Popover title={FILE_TYPE} {anchor} {returnFocus} {onClose}>
  <div class="chips" role="group" aria-label={FILE_TYPE}>
    {#each FILE_TYPES as type (type.value)}
      {@const on = (filter.types ?? []).includes(type.value)}
      <button type="button" class="chip" class:on aria-pressed={on} onclick={() => toggle(type.value)}>{type.label}</button>
    {/each}
  </div>
  <p class="muted">None chosen: every file type.</p>
</Popover>
