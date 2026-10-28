// The Meadow design's drawings (§3): grass, daisies, lantana and ixora with their leaves, from the chosen Home mockup.
// Markup strings, like butterfly.js, with every colour a CSS variable from theme.css, so the Light / Dark switch
// recolours them. Seeded, so a drawing is the same each time it is drawn. Static: nothing here moves.

// Coordinates to one decimal keep the markup short.
const r1 = (n) => Math.round(n * 10) / 10;

// A small seeded random (Park–Miller), so every load draws the same meadow.
function rng(seed) {
  let s = seed;
  return () => (s = (s * 16807) % 2147483647) / 2147483647;
}

export function daisy(x, y, r, petal = 'var(--petal)', heart = 'var(--heart)') {
  let out = '';
  for (let i = 0; i < 12; i++) {
    out += `<ellipse cx="${r1(x)}" cy="${r1(y - r * 0.62)}" rx="${r1(r * 0.2)}" ry="${r1(r * 0.46)}" fill="${petal}" transform="rotate(${i * 30} ${r1(x)} ${r1(y)})"/>`;
  }
  return `${out}<circle cx="${r1(x)}" cy="${r1(y)}" r="${r1(r * 0.3)}" fill="${heart}"/>`;
}

/** Lantana (or ixora, in its own colours): a dome of tiny four-petal florets, `colours` from the middle outwards. */
export function lantana(x, y, r, colours, seed = 3) {
  const rand = rng(seed);
  let out = '';
  for (let i = 0; i < 26; i++) {
    const a = rand() * Math.PI * 2;
    const d = Math.sqrt(rand()) * r;
    const fx = x + Math.cos(a) * d;
    const fy = y + Math.sin(a) * d * 0.7;
    const c = colours[d < r * 0.35 ? 0 : d < r * 0.7 ? 1 : 2];
    const s = r * 0.16;
    for (let k = 0; k < 4; k++) {
      out += `<circle cx="${r1(fx + Math.cos(k * Math.PI / 2) * s * 0.7)}" cy="${r1(fy + Math.sin(k * Math.PI / 2) * s * 0.7)}" r="${r1(s * 0.62)}" fill="${c}"/>`;
    }
  }
  return out;
}

export function leaf(x, y, len, angle, fill = 'var(--leaf)') {
  const at = `transform="translate(${r1(x)} ${r1(y)}) rotate(${r1(angle)})"`;
  return `<path d="M0,0 C${r1(len * 0.3)},${r1(-len * 0.28)} ${r1(len * 0.75)},${r1(-len * 0.22)} ${r1(len)},0 C${r1(len * 0.75)},${r1(len * 0.22)} ${r1(len * 0.3)},${r1(len * 0.28)} 0,0 Z" fill="${fill}" ${at}/>`
    + `<path d="M0,0 L${r1(len * 0.92)},0" stroke="var(--leaf-vein)" stroke-width="1" ${at}/>`;
}

function blade(x, base, h, lean, fill) {
  return `<path d="M${r1(x - 3)},${r1(base)} Q${r1(x + lean * 0.4)},${r1(base - h * 0.6)} ${r1(x + lean)},${r1(base - h)} Q${r1(x + lean * 0.3)},${r1(base - h * 0.55)} ${r1(x + 3)},${r1(base)} Z" fill="${fill}"/>`;
}

/** A flower on its stem with two leaves, from the ground at `base` up to `top`: a daisy, lantana or ixora by `kind`. */
export function flower(x, base, top, kind, rand, seed = 1) {
  let out = `<path d="M${r1(x)},${r1(base)} Q${r1(x + (rand() - 0.5) * 20)},${r1((base + top) / 2)} ${r1(x)},${r1(top)}" stroke="var(--stem)" stroke-width="2.4" fill="none"/>`;
  out += leaf(x, base - (base - top) * 0.45, 22, -30 - rand() * 20, 'var(--leaf)') + leaf(x, base - (base - top) * 0.3, 18, 200 + rand() * 20, 'var(--leaf-2)');
  if (kind === 0) out += daisy(x, top, 15);
  else if (kind === 1) out += lantana(x, top, 15, ['var(--lan-1)', 'var(--lan-2)', 'var(--lan-3)'], seed);
  else out += lantana(x, top, 13, ['var(--ix-1)', 'var(--ix-2)', 'var(--ix-2)'], seed * 5);
  return out;
}

/**
 * A meadow strip, `w` × `h` in SVG units: back grass, flowers on stems (one every ~150 units; none with
 * `flowers: false`), front grass. It fills its box's width and keeps its foot on the box's bottom edge.
 */
export function meadow(w, h, { seed = 7, density = 1, flowers = true } = {}) {
  const rand = rng(seed);
  let back = '';
  let mid = '';
  let front = '';
  for (let x = 0; x < w; x += 7 / density) back += blade(x + rand() * 6, h, h * (0.35 + rand() * 0.4), (rand() - 0.5) * 30, 'var(--grass-back)');
  if (flowers) {
    const spots = Math.max(1, Math.round((w / 150) * density));
    for (let i = 0; i < spots; i++) {
      const fx = (i + 0.3 + rand() * 0.4) * (w / spots);
      mid += flower(fx, h, h * (0.18 + rand() * 0.3), i % 3, rand, i + seed);
    }
  }
  for (let x = 0; x < w; x += 5 / density) {
    front += blade(x + rand() * 5, h, h * (0.15 + rand() * 0.25), (rand() - 0.5) * 24, rand() > 0.5 ? 'var(--grass)' : 'var(--grass-2)');
  }
  return `<svg class="meadow" viewBox="0 0 ${w} ${h}" preserveAspectRatio="xMidYMax slice" aria-hidden="true">${back}${mid}${front}</svg>`;
}

/** One daisy as a small mark (the Celebration's milestone badge sits on it). */
export const daisySvg = `<svg class="daisy" viewBox="0 0 40 40" aria-hidden="true">${daisy(20, 20, 19)}</svg>`;
