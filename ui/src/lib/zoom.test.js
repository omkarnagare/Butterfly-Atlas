import { expect, test } from 'vitest';
import { DOUBLE, FIT, clamp, fitSize, maxScale, pan, pinch, toggleAt, wheelFactor, zoomAt } from './zoom.js';

const stage = { w: 1000, h: 800 };
const natural = { w: 4000, h: 3000 };
const fit = fitSize(natural, stage); // 1000 × 750
const max = maxScale(natural, fit); // 4

test('a photo fits inside the stage and a small one is not enlarged', () => {
  expect(fit).toEqual({ w: 1000, h: 750 });
  expect(fitSize({ w: 3000, h: 4000 }, stage)).toEqual({ w: 600, h: 800 });
  expect(fitSize({ w: 400, h: 300 }, stage)).toEqual({ w: 400, h: 300 });
  expect(fitSize({ w: 0, h: 0 }, stage)).toEqual({ w: 0, h: 0 });
});

test('zooming keeps the spot under the pointer still', () => {
  const point = { x: 200, y: -100 };
  const under = (view) => ({ x: (point.x - view.x) / view.scale, y: (point.y - view.y) / view.scale });

  const once = zoomAt(FIT, 2, point, fit, stage, max);
  const twice = zoomAt(once, 1.5, point, fit, stage, max);

  expect(once).toEqual({ scale: 2, x: -200, y: 100 });
  expect(under(once)).toEqual(under(FIT));
  expect(under(twice).x).toBeCloseTo(under(FIT).x);
  expect(under(twice).y).toBeCloseTo(under(FIT).y);
});

test('zoom stays between fit and the deepest zoom', () => {
  expect(zoomAt(FIT, 0.5, { x: 0, y: 0 }, fit, stage, max)).toEqual(FIT);
  expect(zoomAt(FIT, 100, { x: 0, y: 0 }, fit, stage, max).scale).toBe(4);
  expect(maxScale({ w: 12000, h: 8000 }, { w: 1000, h: 667 })).toBe(12);
  expect(maxScale(natural, { w: 0, h: 0 })).toBe(4);
});

test('panning stops at the edges, and a photo smaller than the stage stays centred', () => {
  const zoomed = { scale: 2, x: 0, y: 0 }; // 2000 × 1500: 500 px spare each side across, 350 down
  expect(pan(zoomed, 300, -200, fit, stage, max)).toEqual({ scale: 2, x: 300, y: -200 });
  expect(pan(zoomed, 900, 900, fit, stage, max)).toEqual({ scale: 2, x: 500, y: 350 });
  expect(pan(FIT, 50, 50, fit, stage, max)).toEqual(FIT);
  expect(clamp({ scale: 1.05, x: 80, y: 80 }, fit, stage, max)).toEqual({ scale: 1.05, x: 25, y: 0 });
});

test('the wheel zooms in when scrolled up, and counts lines and pages too', () => {
  expect(wheelFactor(-100)).toBeGreaterThan(1);
  expect(wheelFactor(100)).toBeLessThan(1);
  expect(wheelFactor(100) * wheelFactor(-100)).toBeCloseTo(1);
  expect(wheelFactor(3, 1)).toBeCloseTo(wheelFactor(48));
  expect(wheelFactor(10000)).toBeCloseTo(wheelFactor(200));
});

test('double-click zooms into that spot, and again goes back to fit', () => {
  const zoomed = toggleAt(FIT, { x: 100, y: 0 }, fit, stage, max);
  expect(zoomed).toEqual({ scale: DOUBLE, x: -150, y: 0 });
  expect(toggleAt(zoomed, { x: 0, y: 0 }, fit, stage, max)).toEqual(FIT);
});

test('zooming in and out by the same steps lands exactly on fit', () => {
  let view = FIT;
  for (let i = 0; i < 3; i++) view = zoomAt(view, 1.25, { x: 0, y: 0 }, fit, stage, max);
  for (let i = 0; i < 3; i++) view = zoomAt(view, 1 / 1.25, { x: 0, y: 0 }, fit, stage, max);
  expect(view.scale).toBe(1);
});

test('pinching zooms around the fingers and follows them when they move together', () => {
  const spread = pinch(FIT, [{ x: -50, y: 0 }, { x: 50, y: 0 }], [{ x: -100, y: 0 }, { x: 100, y: 0 }], fit, stage, max);
  expect(spread).toEqual({ scale: 2, x: 0, y: 0 });

  const moved = pinch(spread, [{ x: -100, y: 0 }, { x: 100, y: 0 }], [{ x: -60, y: 30 }, { x: 140, y: 30 }], fit, stage, max);
  expect(moved).toEqual({ scale: 2, x: 40, y: 30 });
});
