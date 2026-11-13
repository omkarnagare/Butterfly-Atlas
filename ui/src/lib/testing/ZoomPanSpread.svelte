<script>
  import ZoomPan from '../ZoomPan.svelte';

  // As the viewers pass it: the photo's own props spread in, so a fresh photo object (the page's photos read again)
  // gives ZoomPan new props with the same values.
  let { photo } = $props();
  const extra = (p) => ({ alt: p.alt });
</script>

<ZoomPan src={`./api/photos/${photo.id}/display`} placeholder={`./api/photos/${photo.id}/thumb?v=1`} width={photo.width} height={photo.height} {...extra(photo)} />
