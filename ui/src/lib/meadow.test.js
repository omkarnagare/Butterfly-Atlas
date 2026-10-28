import { expect, test } from 'vitest';
import { daisy, daisySvg, lantana, leaf, meadow } from './meadow.js';

const parse = (markup) => {
  const box = document.createElement('div');
  box.innerHTML = markup;
  return box.firstElementChild;
};
// Every colour is a CSS variable, so the Light / Dark switch recolours the drawing.
const colours = (markup) => [...markup.matchAll(/(?:fill|stroke)="([^"]+)"/g)].map(([, c]) => c).filter((c) => c !== 'none');

test('a meadow is a decorative SVG strip of the asked size, drawn the same for the same seed', () => {
  const strip = meadow(600, 80, { seed: 5 });
  const svg = parse(strip);

  expect(svg.namespaceURI).toBe('http://www.w3.org/2000/svg');
  expect(svg.getAttribute('class')).toBe('meadow');
  expect(svg.getAttribute('aria-hidden')).toBe('true');
  expect(svg.getAttribute('viewBox')).toBe('0 0 600 80');
  expect(meadow(600, 80, { seed: 5 })).toBe(strip);
  expect(meadow(600, 80, { seed: 6 })).not.toBe(strip);
});

test('a meadow has flowers among its grass, or only grass when asked', () => {
  const svg = parse(meadow(600, 80, { seed: 5 }));
  expect(svg.querySelectorAll('circle').length).toBeGreaterThan(0);
  expect(svg.querySelectorAll('path').length).toBeGreaterThan(100);

  const grass = parse(meadow(220, 30, { seed: 2, flowers: false }));
  expect(grass.querySelectorAll('circle')).toHaveLength(0);
  expect(grass.querySelectorAll('path').length).toBeGreaterThan(0);
});

test('the meadow, its daisies, lantana and leaves take their colours from the theme', () => {
  for (const markup of [meadow(600, 80, { seed: 5 }), daisy(10, 10, 8), lantana(10, 10, 8, ['var(--lan-1)', 'var(--lan-2)', 'var(--lan-3)']), leaf(0, 0, 20, 30)]) {
    const used = colours(markup);
    expect(used.length).toBeGreaterThan(0);
    for (const c of used) expect(c).toMatch(/^var\(--[\w-]+\)$/);
  }
});

test('a daisy has twelve petals round its heart; a lantana is a dome of four-petal florets', () => {
  const petals = (markup) => (markup.match(/<ellipse/g) ?? []).length;
  expect(petals(daisy(10, 10, 8))).toBe(12);
  expect((daisy(10, 10, 8).match(/<circle/g) ?? []).length).toBe(1);
  expect((lantana(10, 10, 8, ['var(--lan-1)', 'var(--lan-2)', 'var(--lan-3)']).match(/<circle/g) ?? []).length % 4).toBe(0);
});

test('a daisy mark is a small decorative SVG of one daisy', () => {
  const box = document.createElement('div');
  box.innerHTML = daisySvg;
  const svg = box.firstElementChild;
  expect(svg.getAttribute('class')).toBe('daisy');
  expect(svg.getAttribute('aria-hidden')).toBe('true');
  expect(svg.querySelectorAll('ellipse')).toHaveLength(12);
});
