import { render, screen } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import Ring from './Ring.svelte';

test('says how many species are photographed', () => {
  const { container } = render(Ring, { got: 12, total: 1139 });
  expect(screen.getByRole('img', { name: '12 of 1139 species photographed' })).toBeTruthy();
  expect(container.querySelector('.num').textContent).toBe('12');
  expect(container.querySelector('.of').textContent).toBe('of 1139 species');
});

const fam = (family, got, total) => ({ family, species: { got, total } });
// A segment's [colour, length, start] (lengths in hundredths of the ring), and its photographed share.
const segments = (container) => [...container.querySelectorAll('.ring > svg g')].map((g) => ({
  fam: g.style.getPropertyValue('--fam'),
  len: Number(g.querySelector('.seg').getAttribute('stroke-dasharray').split(' ')[0]),
  at: Number(g.getAttribute('transform').match(/rotate\(([\d.]+)/)[1]),
  fill: g.querySelector('.fill') ? Number(g.querySelector('.fill').getAttribute('stroke-dasharray').split(' ')[0]) : 0,
}));

test('the family ring: a segment per family sized by its species, filling in as they are photographed', () => {
  const { container } = render(Ring, {
    got: 31, total: 400, families: [fam('Papilionidae', 1, 100), fam('Pieridae', 0, 60), fam('Nymphalidae', 30, 240)],
  });

  expect(screen.getByRole('img', { name: '31 of 400 species photographed' })).toBeTruthy();
  // 3° between segments: each is its share less 0.83 hundredths
  expect(segments(container)).toEqual([
    { fam: 'var(--f-pap)', len: 24.17, at: 0, fill: 2.6 },
    { fam: 'var(--f-pie)', len: 14.17, at: 90, fill: 0 },
    { fam: 'var(--f-nym)', len: 59.17, at: 144, fill: 7.4 },
  ]);
  expect(container.querySelector('.prog')).toBeNull();
});

test('a family with nothing in it takes no segment, and with no families the ring is one accent segment', () => {
  const { container, unmount } = render(Ring, { got: 0, total: 10, families: [fam('Papilionidae', 0, 10), fam('Riodinidae', 0, 0)] });
  expect(segments(container).map((s) => s.fam)).toEqual(['var(--f-pap)']);
  unmount();

  const plain = render(Ring, { got: 5, total: 10 });
  expect(plain.container.querySelector('.prog').getAttribute('style')).toContain('stroke-dashoffset: 50');
});

test('a butterfly perches on the ring: in the colour of the family given, else an outline', () => {
  const { container, unmount } = render(Ring, { got: 3, total: 10, families: [fam('Pieridae', 3, 10)], perch: 'Pieridae' });
  const perch = container.querySelector('.perch');
  expect(perch.getAttribute('aria-hidden')).toBe('true');
  expect(perch.style.getPropertyValue('--fam')).toBe('var(--f-pie)');
  expect(perch.querySelector('svg.bf')).not.toBeNull();
  unmount();

  const none = render(Ring, { got: 0, total: 10, families: [fam('Pieridae', 0, 10)] });
  expect(none.container.querySelector('.perch svg.bf-out')).not.toBeNull();
});
