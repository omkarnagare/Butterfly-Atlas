import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import SpeciesHeader from './SpeciesHeader.svelte';

const taxon = (over) => ({
  id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true,
  path: [{ id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null }], species: { got: 0, total: 1 },
  synonyms: [], searchWords: [], folder: null, photoCount: 0, stages: { egg: false, larva: false, pupa: false, adult: false },
  cover: null, ...over,
});

test('keeps the drawn butterfly until the species has a cover', () => {
  const { container } = render(SpeciesHeader, { taxon: taxon(), photoCount: 0 });

  expect(container.querySelector('.sp-art .hero-fly')).not.toBeNull();
  expect(container.querySelector('.sp-art a')).toBeNull();
});

test('shows the cover photo as the hero, sharp once loaded, and opens it in the viewer', async () => {
  const cover = { id: 12, taxonId: 'A5', v: 1791700000000, isCover: true };
  const { container } = render(SpeciesHeader, { taxon: taxon({ cover, photoCount: 1, species: { got: 1, total: 1 } }), photoCount: 1 });

  const link = screen.getByRole('link', { name: 'Plain Tiger, your cover photo' });
  expect(link.getAttribute('href')).toBe('#/species/A5/photo/12');
  const [thumb, full] = link.querySelectorAll('img');
  expect(thumb.getAttribute('src')).toBe('./api/photos/12/thumb?v=1791700000000');
  expect(full.getAttribute('src')).toBe('./api/photos/12/display?v=1791700000000');
  expect(full.classList.contains('on')).toBe(false);
  await fireEvent.load(full);
  expect(full.classList.contains('on')).toBe(true);
  expect(container.querySelector('.sp-art .hero-fly')).toBeNull();
});

test('the large cover image is not the gold badge and stays hidden when it fails to load', async () => {
  const cover = { id: 12, taxonId: 'A5', v: 1, isCover: true };
  const { container } = render(SpeciesHeader, { taxon: taxon({ cover, photoCount: 1, species: { got: 1, total: 1 } }), photoCount: 1 });

  const sharp = container.querySelector('.sp-cover .sharp');
  expect(sharp.classList.contains('full')).toBe(false);
  await fireEvent.error(sharp);
  expect(sharp.classList.contains('on')).toBe(false);
  expect(container.querySelectorAll('.sp-cover img')).toHaveLength(2);
});

test('a custom species carries the Custom badge in its family colour', () => {
  const { container, unmount } = render(SpeciesHeader, { taxon: taxon({ id: 'U3', name: 'Neptis sankara', common: null, source: 'manual' }), photoCount: 0 });
  const badge = screen.getByText('Custom');
  expect(badge.classList.contains('custom')).toBe(true);
  expect(badge.closest('.sph').style.getPropertyValue('--fam').trim()).toBe('var(--f-nym)');
  expect(container.querySelector('.idchip').textContent).toBe('Atlas ID U3');
  unmount();

  render(SpeciesHeader, { taxon: taxon({ source: 'official' }), photoCount: 0 });
  expect(screen.queryByText('Custom')).toBeNull();
});

test('the Atlas ID, the badges and the extra controls share one row (wrapping when narrow)', async () => {
  const { createRawSnippet } = await import('svelte');
  const extra = createRawSnippet(() => ({ render: () => '<button type="button">Edit…</button>' }));
  const { container } = render(SpeciesHeader, { taxon: taxon({ source: 'manual', active: false }), photoCount: 0, extra });
  const row = container.querySelector('.idchip').parentElement;
  expect(row.classList.contains('sp-tags')).toBe(true);
  expect(row.contains(screen.getByText('Custom'))).toBe(true);
  expect(row.contains(screen.getByText('Not in the current India list'))).toBe(true);
  expect(row.contains(screen.getByRole('button', { name: 'Edit…' }))).toBe(true);
});

test('a split species with photos to sort says how many, with a link to sort them; none to sort, no badge', () => {
  const { unmount } = render(SpeciesHeader, { taxon: taxon({ active: false, toSort: 28 }), photoCount: 40 });
  const badge = screen.getByText('Split — 28 photos to sort');
  expect(badge.classList.contains('badge')).toBe(true);
  expect(badge.parentElement.classList.contains('sp-tags')).toBe(true);
  expect(screen.getByRole('link', { name: 'Sort photos' }).getAttribute('href')).toBe('#/split/A5');
  unmount();

  render(SpeciesHeader, { taxon: taxon({ toSort: 0 }), photoCount: 0 });
  expect(screen.queryByText(/Split —/)).toBeNull();
  expect(screen.queryByRole('link', { name: 'Sort photos' })).toBeNull();
});

test('the other names sit right under the older names', () => {
  const { container } = render(SpeciesHeader, { taxon: taxon({ synonyms: ['Danais chrysippus'], searchWords: ['Kaner'] }), photoCount: 0 });

  const older = container.querySelector('.syn');
  const other = container.querySelector('.other-names');
  expect(older.nextElementSibling.contains(other)).toBe(true);
  expect(other.textContent).toContain('Other names:');
  expect(screen.getByRole('button', { name: 'Remove Kaner' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: /Search words/ })).toBeNull();
});
