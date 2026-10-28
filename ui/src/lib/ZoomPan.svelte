<script>
  import { untrack } from 'svelte';
  import { FIT, clamp, fitSize, maxScale, pan, pinch, toggleAt, wheelFactor, zoomAt } from './zoom.js';
  import { orientTransform, turned } from './raw.js';
  import { BUSY } from './codes.js';

  /**
   * The viewer's photo: the thumbnail at once, the full-size display image over it once loaded. Wheel or
   * pinch zooms around the pointer, double-click zooms into a spot (again: back to fit), dragging pans
   * up to the edges. The viewer calls zoomBy()/reset() for the + - 0 keys. A horizontal drag on an
   * unzoomed photo is a swipe: onSwipe(1) for next, onSwipe(-1) for previous. One status region says
   * `loadingNote` (when given) until the full-size image arrives, and `failedNote` when it can't be opened; the caller
   * words it (Phase 7a: a RAW photo's own note). `broken` says at once that the full size can't be shown: the note
   * shows and nothing is loaded. `orientation` (EXIF 1–8) is the turn the full-size image needs to stand upright (a
   * RAW preview's); `width` and `height` are the size as shown, so zoom and pan work in the turned frame and the
   * image is laid out unturned inside it. The placeholder is already upright.
   * `explain` (Phase 7b audit 7, a HEIC photo): an <img> can't read why the server refused the full size, so on its
   * first error the address is fetched once and the server's own words are said; a refusal because Butterfly Atlas is
   * busy (`busy`, still checking for a decoder, or a decode taking too long) gets Try again, which loads it afresh; so
   * does no answer at all, or one without the server's words ("Butterfly Atlas isn't answering. Try again.", review fix U7).
   */
  let {
    src, placeholder, width, height, alt = '', onSwipe = () => {}, loadingNote = '',
    failedNote = 'This photo could not be opened at full size; showing a preview.', broken = false, orientation = 1, explain = false,
  } = $props();

  let stageEl = $state(null);
  let stageW = $state(0);
  let stageH = $state(0);
  let view = $state({ ...FIT });
  let glide = $state(false);
  let loaded = $state(false);
  let failed = $state(false);
  let attempt = $state(0);
  // What the server said when `explain` asked why (`{ message, busy }`), else null.
  let why = $state(null);
  let seen = $state({ w: 0, h: 0 });
  const pointers = new Map();
  const starts = new Map();
  let moved = new Map();
  let presses = [];
  let swipeFrom = null;
  let retryTimer = 0;
  let lastScale = 1;

  // Without a size from the library, the loaded image's own size is used (turned, when it is shown turned).
  let quarter = $derived(turned(orientation));
  let natural = $derived(width && height ? { w: width, h: height } : seen.w ? (quarter ? { w: seen.h, h: seen.w } : seen) : { w: 1600, h: 1200 });
  let shownSrc = $derived(attempt ? `${src}${src.includes('?') ? '&' : '?'}retry=${attempt}` : src);
  let stage = $derived({ w: stageW, h: stageH });
  let fit = $derived(fitSize(natural, stage));
  let max = $derived(maxScale(natural, fit));
  let gone = $derived(broken || failed);
  // A quarter turn lays the image out at the frame's height by its width, centred, then turns it.
  let origStyle = $derived.by(() => {
    const turn = orientTransform(orientation);
    if (!turn) return '';
    if (!quarter) return `transform:${turn}`;
    return `inset:auto;left:50%;top:50%;width:${fit.h}px;height:${fit.w}px;transform:translate(-50%, -50%) ${turn}`;
  });

  // A new photo starts fitted, with its full-size image still to load.
  $effect(() => {
    src;
    untrack(() => {
      view = { ...FIT };
      loaded = false;
      failed = false;
      why = null;
      attempt = 0;
      seen = { w: 0, h: 0 };
      clearTimeout(retryTimer);
    });
    return () => clearTimeout(retryTimer);
  });

  // Keep the view inside the edges when the window changes size.
  $effect(() => {
    const [f, s, m] = [fit, stage, max];
    untrack(() => {
      view = clamp(view, f, s, m);
    });
  });

  // The stage's size, followed as the window changes (jsdom has no ResizeObserver; the window's resize event stands in).
  $effect(() => {
    if (!stageEl) return undefined;
    const measure = () => {
      stageW = stageEl.clientWidth;
      stageH = stageEl.clientHeight;
    };
    measure();
    if (typeof ResizeObserver !== 'function') {
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }
    const observer = new ResizeObserver(measure);
    observer.observe(stageEl);
    return () => observer.disconnect();
  });

  // Wheel listeners must not be passive, or preventDefault would not stop the page scrolling.
  $effect(() => {
    if (!stageEl) return undefined;
    const onWheel = (event) => {
      event.preventDefault();
      glide = false;
      if (!event.ctrlKey && view.scale > 1 && event.deltaMode === 0) {
        // A zoomed photo: a trackpad two-finger scroll (or a wheel) moves it.
        view = pan(view, -event.deltaX, -event.deltaY, fit, stage, max);
        return;
      }
      // Chromium and WebView2 send a trackpad pinch as ctrl+wheel with small deltas.
      const delta = event.ctrlKey ? event.deltaY * 4 : event.deltaY;
      view = zoomAt(view, wheelFactor(delta, event.deltaMode), at(event), fit, stage, max);
    };
    // WebKit (macOS Safari and the macOS web view) sends a pinch as gesture events with a running scale.
    const onGestureStart = (event) => {
      event.preventDefault();
      lastScale = 1;
    };
    const onGestureChange = (event) => {
      event.preventDefault();
      glide = false;
      const factor = event.scale / lastScale;
      lastScale = event.scale;
      if (factor > 0) view = zoomAt(view, factor, at(event), fit, stage, max);
    };
    const onGestureEnd = (event) => event.preventDefault();
    stageEl.addEventListener('wheel', onWheel, { passive: false });
    stageEl.addEventListener('gesturestart', onGestureStart, { passive: false });
    stageEl.addEventListener('gesturechange', onGestureChange, { passive: false });
    stageEl.addEventListener('gestureend', onGestureEnd, { passive: false });
    return () => {
      stageEl.removeEventListener('wheel', onWheel);
      stageEl.removeEventListener('gesturestart', onGestureStart);
      stageEl.removeEventListener('gesturechange', onGestureChange);
      stageEl.removeEventListener('gestureend', onGestureEnd);
    };
  });

  export function zoomBy(factor) {
    glide = true;
    view = zoomAt(view, factor, { x: 0, y: 0 }, fit, stage, max);
  }

  export function reset() {
    glide = true;
    view = { ...FIT };
  }

  function at(event) {
    const r = stageEl.getBoundingClientRect();
    return { x: event.clientX - r.left - r.width / 2, y: event.clientY - r.top - r.height / 2 };
  }

  function onDown(event) {
    if (event.button > 0) return;
    stageEl.setPointerCapture?.(event.pointerId);
    pointers.set(event.pointerId, at(event));
    starts.set(event.pointerId, at(event));
    moved.set(event.pointerId, false);
    glide = false;
    swipeFrom = pointers.size === 1 ? at(event) : null;
  }

  function onMove(event) {
    if (!pointers.has(event.pointerId)) return;
    const before = [...pointers.values()];
    pointers.set(event.pointerId, at(event));
    const start = starts.get(event.pointerId);
    const here = at(event);
    if (start && Math.hypot(here.x - start.x, here.y - start.y) > 5) moved.set(event.pointerId, true);
    const now = [...pointers.values()];
    if (now.length === 2) view = pinch(view, before, now, fit, stage, max);
    else if (now.length === 1 && view.scale > 1) view = pan(view, now[0].x - before[0].x, now[0].y - before[0].y, fit, stage, max);
  }

  function onUp(event) {
    if (!pointers.has(event.pointerId)) return;
    const end = at(event);
    pointers.delete(event.pointerId);
    presses = [...presses, moved.get(event.pointerId) === true].slice(-2);
    starts.delete(event.pointerId);
    moved.delete(event.pointerId);
    if (swipeFrom && pointers.size === 0 && view.scale === 1) {
      const dx = end.x - swipeFrom.x;
      if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(end.y - swipeFrom.y)) onSwipe(dx < 0 ? 1 : -1);
    }
    if (pointers.size === 0) swipeFrom = null;
  }

  async function onLoad(event) {
    const img = event.currentTarget;
    const wanted = shownSrc;
    try {
      await img.decode?.();
    } catch {
      // Showing it undecoded is fine.
    }
    if (wanted !== shownSrc) return;
    if (img.naturalWidth) seen = { w: img.naturalWidth, h: img.naturalHeight };
    loaded = true;
  }

  // A failed download is tried again twice (after 2 s and 4 s) before the preview is all there is.
  function onError() {
    if (explain) {
      askWhy();
      return;
    }
    if (attempt >= 2) {
      failed = true;
      return;
    }
    clearTimeout(retryTimer);
    retryTimer = setTimeout(() => (attempt += 1), 2000 * (attempt + 1));
  }

  // No answer, or one that can't be read: never the "may be damaged" note, and Try again (review fix U7).
  const NOT_ANSWERING = { message: "Butterfly Atlas isn't answering. Try again.", busy: true };

  // The server's words for the refusal (contract errors: `{ error: { code, message } }`); the caller's note without them.
  async function askWhy() {
    const wanted = shownSrc;
    let said = null;
    try {
      const response = await fetch(wanted, { headers: { Accept: 'application/json' } });
      if (!response.ok) {
        const error = (await response.json().catch(() => null))?.error ?? null;
        // An answer without the server's words is not the photo's fault (review fix U7).
        said = error?.message ? { message: error.message, busy: error.code === BUSY || response.status === 503 } : NOT_ANSWERING;
      }
    } catch {
      said = NOT_ANSWERING;
    }
    if (wanted !== shownSrc) return;
    // It answers now: loaded again (twice at most), then offered again with Try again.
    if (!said && attempt < 2) {
      attempt += 1;
      return;
    }
    why = said ?? { message: '', busy: true };
    failed = true;
  }

  function tryAgain() {
    why = null;
    failed = false;
    attempt += 1;
  }

  function onDouble(event) {
    // Two quick drags are not a double-click.
    if (presses.some(Boolean)) return;
    glide = true;
    view = toggleAt(view, at(event), fit, stage, max);
  }
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="zp" class:zoomed={view.scale > 1} bind:this={stageEl}
     onpointerdown={onDown} onpointermove={onMove} onpointerup={onUp} onpointercancel={onUp} ondblclick={onDouble}>
  <div class="zp-photo" class:glide style="width:{fit.w}px;height:{fit.h}px;transform:translate({view.x}px, {view.y}px) scale({view.scale})">
    <img class="ph" src={placeholder} alt={loaded ? '' : alt} draggable="false" />
    {#if !gone}
      <img class="orig" class:on={loaded} src={shownSrc} alt={loaded ? alt : ''} draggable="false" decoding="async" style={origStyle}
           onload={onLoad} onerror={onError} />
    {/if}
  </div>
  <p class={gone || (loadingNote && !loaded) ? 'zp-note' : 'sr-only'} role="status">{gone ? why?.message || failedNote : !loaded ? loadingNote : ''}</p>
  {#if gone && why?.busy}<button class="btn ghost zp-again" type="button" onclick={tryAgain}>Try again</button>{/if}
</div>
