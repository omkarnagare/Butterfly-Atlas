/*
 * Zoom and pan for the photo viewer, as pure functions so the maths is tested without a browser.
 * A view is { scale, x, y }: the photo, at its fitted size scaled by `scale` about its centre, sits in the
 * middle of the stage moved by (x, y) pixels. Points are measured from the centre of the stage.
 * It is drawn laid out at that zoomed size (`placement`), not scaled with a CSS transform: a transform
 * scales the raster of the fitted size, which looks pixelated, while a layout at the zoomed size is drawn
 * from the full-size image. Both put every pixel in the same place.
 */
export const FIT = Object.freeze({ scale: 1, x: 0, y: 0 });
/** One press of + or -. */
export const STEP = 1.25;
/** How far a double-click zooms in. */
export const DOUBLE = 2.5;

/** The photo's size when it fits the stage; small photos are never enlarged. */
export function fitSize(natural, stage) {
  if (!natural.w || !natural.h || !stage.w || !stage.h) return { w: 0, h: 0 };
  const k = Math.min(stage.w / natural.w, stage.h / natural.h, 1);
  return { w: natural.w * k, h: natural.h * k };
}

/** The deepest zoom: 4×, or further if that is needed to see the photo's own pixels. */
export function maxScale(natural, fit) {
  return fit.w ? Math.max(4, natural.w / fit.w) : 4;
}

/** Where the photo is laid out for a view: its zoomed size, centred on the stage's centre moved by (x, y). */
export function placement(view, fit) {
  return { w: fit.w * view.scale, h: fit.h * view.scale, x: view.x, y: view.y };
}

/** Keeps the scale between fit and `max` and the photo's edges from leaving gaps; smaller than the stage = centred. */
export function clamp(view, fit, stage, max) {
  let scale = Math.min(max, Math.max(1, view.scale));
  if (Math.abs(scale - 1) < 1e-6) scale = 1; // + + + − − − must land exactly on fit
  const limit = (size, room, value) => {
    const spare = (size * scale - room) / 2;
    return spare > 0 ? Math.min(spare, Math.max(-spare, value)) : 0;
  };
  return { scale, x: limit(fit.w, stage.w, view.x), y: limit(fit.h, stage.h, view.y) };
}

/** Zooms by `factor` keeping the photo point under `point` where it is. */
export function zoomAt(view, factor, point, fit, stage, max) {
  const scale = Math.min(max, Math.max(1, view.scale * factor));
  const k = scale / view.scale;
  return clamp({ scale, x: point.x - (point.x - view.x) * k, y: point.y - (point.y - view.y) * k }, fit, stage, max);
}

/** Drags the photo by (dx, dy), stopping at its edges. */
export function pan(view, dx, dy, fit, stage, max) {
  return clamp({ scale: view.scale, x: view.x + dx, y: view.y + dy }, fit, stage, max);
}

/** Zoom factor for one wheel event: up zooms in. Lines and pages are turned into pixels first. */
export function wheelFactor(deltaY, deltaMode = 0) {
  const pixels = deltaMode === 1 ? deltaY * 16 : deltaMode === 2 ? deltaY * 400 : deltaY;
  return Math.exp(-Math.max(-200, Math.min(200, pixels)) * 0.0025);
}

/** Double-click: zoom into that spot, or back to fit when already zoomed. */
export function toggleAt(view, point, fit, stage, max) {
  return view.scale > 1 ? { ...FIT } : zoomAt(view, Math.min(DOUBLE, max), point, fit, stage, max);
}

/**
 * Two fingers moved from the pair `from` to the pair `to`: the photo follows their midpoint and
 * zooms by the change in their distance, around that midpoint.
 */
export function pinch(view, from, to, fit, stage, max) {
  const distance = ([p, q]) => Math.hypot(p.x - q.x, p.y - q.y);
  const middle = ([p, q]) => ({ x: (p.x + q.x) / 2, y: (p.y + q.y) / 2 });
  const before = distance(from);
  const m0 = middle(from);
  const m1 = middle(to);
  const moved = { scale: view.scale, x: view.x + m1.x - m0.x, y: view.y + m1.y - m0.y };
  return zoomAt(moved, before ? distance(to) / before : 1, m1, fit, stage, max);
}
