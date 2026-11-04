<script>
  /**
   * A sentence that names another device (lib/messages.js `{ lead, name, rest, bold }`): the name bold where the
   * one-device design has it, plain "another device" when its name couldn't be read.
   */
  let { words } = $props();
</script>

{words.lead}{#if words.bold}<strong>{words.name}</strong>{:else}{words.name}{/if}{words.rest}
