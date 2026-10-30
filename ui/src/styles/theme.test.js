import { readFileSync } from 'node:fs';
import { describe, expect, test } from 'vitest';

/**
 * WCAG AA for the Meadow tokens (design §2), read from theme.css itself so a changed colour is checked at once: text
 * ≥ 4.5:1 on every background it sits on, family bands included; bars ≥ 3:1 against their track and the card (1.4.11).
 * (Read from disk: Vitest empties imported CSS, `?raw` included. Tests run from ui/. Windows line endings, which a
 * Windows checkout gives, become \n so the selectors below match.)
 */
const read = (file) => readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
const css = read('src/styles/theme.css');
// Some pairs depend on which token a rule in atlas.css uses: the colour it names (`var(--x)`, family ones by family).
const atlas = read('src/styles/atlas.css');
function uses(selector, property) {
  const at = atlas.indexOf(`\n${selector} {`);
  if (at < 0) throw new Error(`no ${selector} rule in atlas.css`);
  const body = atlas.slice(atlas.indexOf('{', at) + 1, atlas.indexOf('}', at));
  const value = body.match(new RegExp(`(?:^|[\\s;])${property}:([^;]+)`))?.[1] ?? '';
  return value.match(/var\(--([\w-]+)/)?.[1] ?? null;
}

// The declarations of the first rule whose selector is `selector` (the braces after it, to the matching close).
function tokens(selector) {
  const at = css.indexOf(`${selector} {`);
  if (at < 0) throw new Error(`no ${selector} rule in theme.css`);
  const body = css.slice(css.indexOf('{', at) + 1, css.indexOf('}', at));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*([^;]+);/g)].map(([, name, value]) => [name, value.trim()]));
}

const LIGHT = tokens(':root');
// The dusk block overrides the light one; what it leaves out stays as in light.
const DUSK = { ...LIGHT, ...tokens(':root[data-theme=dark]') };

// The theme store (lib/theme.svelte.js) sets data-theme; the device's setting only colours the page before it runs.
test('the dusk tokens are keyed on data-theme, not on the device setting', () => {
  const early = tokens('@media (prefers-color-scheme: dark) {\n  :root:not([data-theme])');
  expect(Object.keys(early).sort()).toEqual(['bg', 'ink']);
  expect([early.bg, early.ink]).toEqual([DUSK.bg, DUSK.ink]);
});

const rgb = (hex) => {
  const h = hex.replace('#', '');
  const full = h.length === 3 ? [...h].map((c) => c + c).join('') : h;
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16) / 255);
};
const lin = (c) => (c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
const unlin = (c) => (c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055);
const luminance = (c) => {
  const [r, g, b] = c.map(lin);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
};
const ratio = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

// color-mix(in oklab, a p%, b), as the bands are drawn.
function toOklab(c) {
  const [r, g, b] = c.map(lin);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s, 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s];
}
function fromOklab([L, a, b]) {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s].map((c) => Math.min(1, Math.max(0, unlin(c))));
}
const mix = (a, p, b) => {
  const [x, y] = [toOklab(a), toOklab(b)];
  return fromOklab(x.map((v, i) => v * p + y[i] * (1 - p)));
};

const FAMILIES = ['pap', 'pie', 'nym', 'lyc', 'hes', 'rio'];

// The Celebration opens over anything, photo grids included: its words sit on a solid card, the surface, whose ink,
// muted and accent pairs are checked below in both themes.
test('the celebration card is solid surface, not see-through', () => {
  expect(uses('.cele-card', 'background')).toBe('surface');
  expect(uses('.cele-card p', 'color')).toBe('muted');
});

// The species header's band: as the card bands in light, a lighter hint of the family colour at dusk, where 13% of the
// bright orange over the dark surface reads as a brown panel.
test('the species header band is the card band in light and a lighter tint at dusk', () => {
  expect(LIGHT['head-tint']).toBe(LIGHT['band-tint']);
  expect(parseFloat(DUSK['head-tint'])).toBeLessThanOrEqual(8);
});

describe.each([['light', LIGHT], ['dusk', DUSK]])('%s', (_, t) => {
  const c = (name) => rgb(t[name]);
  const band = (f) => mix(c(`f-${f}`), parseFloat(t['band-tint']) / 100, c('surface'));

  test.each(['bg', 'surface', 'surface-2'])('body and muted text on %s', (bg) => {
    expect(ratio(c('ink'), c(bg))).toBeGreaterThanOrEqual(4.5);
    expect(ratio(c('muted'), c(bg))).toBeGreaterThanOrEqual(4.5);
  });

  test.each(FAMILIES)('body and muted text on the %s band', (f) => {
    expect(ratio(c('ink'), band(f))).toBeGreaterThanOrEqual(4.5);
    expect(ratio(c('muted'), band(f))).toBeGreaterThanOrEqual(4.5);
  });

  test.each(FAMILIES)('body and muted text on the %s species header band', (f) => {
    const head = mix(c(`f-${f}`), parseFloat(t['head-tint']) / 100, c('surface'));
    expect(ratio(c('ink'), head)).toBeGreaterThanOrEqual(4.5);
    expect(ratio(c('muted'), head)).toBeGreaterThanOrEqual(4.5);
  });

  // Life stages carry meaning (1.4.11): an unlit stage's ring and mark, and a lit stage's disc and its mark on it, ≥ 3:1
  // against the card and the header band; a lit stage takes its family's shade.
  test.each(FAMILIES)('life stages against the surface and the %s header band', (f) => {
    const head = mix(c(`f-${f}`), parseFloat(t['head-tint']) / 100, c('surface'));
    const family = (name) => c(name.replace(/^fam/, `f-${f}`));
    const unlitRing = c(uses('.stage .dot', 'border'));
    const unlitMark = c(uses('.stage .dot svg *', 'fill'));
    const litDisc = family(uses('.stage.on .dot', 'background'));
    const litMark = c(uses('.stage.on .dot svg *', 'fill'));
    for (const bg of [c('surface'), head]) {
      expect(ratio(unlitRing, bg)).toBeGreaterThanOrEqual(3);
      expect(ratio(unlitMark, bg)).toBeGreaterThanOrEqual(3);
      expect(ratio(litDisc, bg)).toBeGreaterThanOrEqual(3);
    }
    expect(ratio(litMark, litDisc)).toBeGreaterThanOrEqual(3);
  });

  test.each(['bg', 'surface', 'surface-2'])('accent text on %s', (bg) => {
    expect(ratio(c('accent'), c(bg))).toBeGreaterThanOrEqual(4.5);
  });

  test('button text on the accent', () => {
    expect(ratio(c('accent-ink'), c('accent'))).toBeGreaterThanOrEqual(4.5);
  });

  test.each([...FAMILIES.map((f) => `f-${f}-deep`), 'accent'])('a %s bar against its track and the card', (bar) => {
    expect(ratio(c(bar), c('track'))).toBeGreaterThanOrEqual(3);
    expect(ratio(c(bar), c('surface'))).toBeGreaterThanOrEqual(3);
  });
});
