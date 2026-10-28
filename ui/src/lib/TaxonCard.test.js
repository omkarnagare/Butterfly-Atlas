import { render, screen } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import TaxonCard from './TaxonCard.svelte';

test('shows the rank, name, a flock for photographed species and the count', () => {
  const taxon = { id: 'A3', rank: 'tribe', name: 'Danaini', common: null, family: 'Nymphalidae', active: true, species: { got: 2, total: 5 } };
  const { container } = render(TaxonCard, { taxon, i: 0 });

  const link = screen.getByRole('link');
  expect(link.getAttribute('href')).toBe('#/explore/A3');
  expect(link.getAttribute('style')).toContain('--fam: var(--f-nym)');
  expect(screen.getByText('Tribe')).toBeTruthy();
  expect(screen.getByText('Danaini')).toBeTruthy();
  expect(container.querySelectorAll('.mini')).toHaveLength(2);
  expect(container.querySelector('.ct').textContent).toBe('2 of 5 species');
});

test('sets a genus name in italics and says when nothing is photographed', () => {
  const taxon = { id: 'A11', rank: 'genus', name: 'Papilio', common: 'Swallowtails', family: 'Papilionidae', active: true, species: { got: 0, total: 3 } };
  const { container } = render(TaxonCard, { taxon });

  expect(container.querySelector('.nm.bn').textContent).toBe('Papilio');
  expect(screen.getByText('Swallowtails')).toBeTruthy();
  expect(screen.getByText('Nothing photographed yet')).toBeTruthy();
});

test('a band in the family colour carries the flock, or the outline butterfly before anything is photographed (Meadow §4)', () => {
  const taxon = { id: 'A1', rank: 'family', name: 'Nymphalidae', common: 'Brush-footed Butterflies', family: 'Nymphalidae', active: true, species: { got: 3, total: 433 } };
  const { container, unmount } = render(TaxonCard, { taxon });

  const link = screen.getByRole('link');
  expect(link.classList.contains('banded')).toBe(true);
  // the band is drawn first but read last: the card is read as rank, name, count
  expect(link.textContent.trim().startsWith('Family')).toBe(true);
  expect(container.querySelectorAll('.band .mini')).toHaveLength(3);
  expect(container.querySelector('.band .bf-out')).toBeNull();
  unmount();

  render(TaxonCard, { taxon: { ...taxon, species: { got: 0, total: 433 } } });
  const band = document.querySelector('.band');
  expect(band.querySelector('svg.bf-out')).not.toBeNull();
  expect(band.querySelector('.nf').textContent).toBe('Nothing photographed yet');
});
