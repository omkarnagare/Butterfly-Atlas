import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import SpeciesCard from './SpeciesCard.svelte';

afterEach(() => vi.useRealTimers());

test('titles a species by its common name with the scientific name beneath', () => {
  const taxon = { id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true, species: { got: 0, total: 1 } };
  const { container } = render(SpeciesCard, { taxon });

  expect(screen.getByRole('link').getAttribute('href')).toBe('#/species/A5');
  expect(container.querySelector('.cnm').textContent).toBe('Plain Tiger');
  expect(container.querySelector('.meta i.bn').textContent).toBe('Danaus chrysippus');
  expect(screen.getByText('Not yet photographed')).toBeTruthy();
  expect(screen.getByText('On your list')).toBeTruthy();
});

test('uses the scientific name when there is no common name, and marks species off the list', () => {
  const taxon = { id: 'A13', rank: 'species', name: 'Papilio demoleus', common: null, family: 'Papilionidae', active: false, species: { got: 1, total: 1 } };
  const { container } = render(SpeciesCard, { taxon });

  expect(container.querySelector('.cnm')).toBeNull();
  expect(container.querySelector('.meta i.bn').textContent).toBe('Papilio demoleus');
  expect(screen.getByText('Photographed')).toBeTruthy();
  expect(screen.getByText('Not in the current India list')).toBeTruthy();
  expect(screen.queryByText('Not yet photographed')).toBeNull();
});

test('shows the cover photo once the species has one', () => {
  const taxon = { id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true,
    species: { got: 1, total: 1 }, cover: { id: 12, v: 1791700000000 } };
  const { container } = render(SpeciesCard, { taxon });

  const img = container.querySelector('.cover img');
  expect(img.getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700000000');
  expect(img.getAttribute('loading')).toBe('lazy');
  expect(container.querySelector('.cover svg')).toBeNull();
});

test('asks again for a cover thumbnail that was still being made', async () => {
  vi.useFakeTimers();
  const taxon = { id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true,
    species: { got: 1, total: 1 }, cover: { id: 12, v: 1791700000000 } };
  const { container } = render(SpeciesCard, { taxon });
  const img = container.querySelector('.cover img');

  Object.defineProperty(img, 'naturalWidth', { value: 1, configurable: true });
  await fireEvent.load(img);
  await vi.advanceTimersByTimeAsync(2000);

  expect(img.getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700000000&r=1');
});

test('a custom species carries the Custom badge in its family colour; an official one does not', () => {
  const taxon = { id: 'U3', rank: 'species', name: 'Neptis sankara', common: null, family: 'Nymphalidae', active: true, source: 'manual',
    species: { got: 0, total: 1 } };
  const { container, unmount } = render(SpeciesCard, { taxon });
  const badge = screen.getByText('Custom');
  expect(badge.classList.contains('custom')).toBe(true);
  expect(badge.closest('.spc').style.getPropertyValue('--fam').trim()).toBe('var(--f-nym)');
  unmount();

  render(SpeciesCard, { taxon: { ...taxon, id: 'A5', source: 'official' } });
  expect(screen.queryByText('Custom')).toBeNull();
  expect(container.querySelector('.badge.custom')).toBeNull();
});

test('a split species with photos to sort carries the Split badge', () => {
  const taxon = { id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: false, species: { got: 1, total: 1 }, toSort: 1 };
  const { unmount } = render(SpeciesCard, { taxon });
  expect(screen.getByText('Split — 1 photo to sort').classList.contains('badge')).toBe(true);
  unmount();
  render(SpeciesCard, { taxon: { ...taxon, toSort: 0 } });
  expect(screen.queryByText(/Split —/)).toBeNull();
});

test('a Next on your list tile shows the outline butterfly over a strip of grass; other cards have no grass', () => {
  const taxon = { id: 'A6', rank: 'species', name: 'Danaus genutia', common: 'Striped Tiger', family: 'Nymphalidae', active: true, species: { got: 0, total: 1 } };
  const { container, unmount } = render(SpeciesCard, { taxon, i: 1, strip: true });

  expect(container.querySelector('.cover svg.bf-out')).not.toBeNull();
  const grass = container.querySelector('.cover svg.meadow');
  expect(grass.getAttribute('aria-hidden')).toBe('true');
  expect(grass.querySelectorAll('circle')).toHaveLength(0);
  unmount();

  const grid = render(SpeciesCard, { taxon });
  expect(grid.container.querySelector('.cover svg.bf-out')).not.toBeNull();
  expect(grid.container.querySelector('.meadow')).toBeNull();
});
