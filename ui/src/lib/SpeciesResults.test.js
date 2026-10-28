import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import SpeciesResults from './SpeciesResults.svelte';
import { EMPTY } from './filter.js';

const row = (n, over = {}) => ({
  id: `A${n}`, name: `Danaus species${n}`, common: n % 2 ? `Tiger ${n}` : null, family: 'Nymphalidae', subfamily: 'Danainae', tribe: 'Danaini',
  genus: 'Danaus', photos: n % 3, missing: [], source: 'official', active: true, ...over,
});
const many = Array.from({ length: 1000 }, (_, i) => row(i + 1));
const items = (container) => [...container.querySelectorAll('[role="listitem"]')];

test('only the rows near the view are in the page; the list says how many there are', () => {
  const { container } = render(SpeciesResults, { rows: many, filter: EMPTY });
  const list = screen.getByRole('list', { name: 'Species' });
  expect(items(container).length).toBeGreaterThan(5);
  expect(items(container).length).toBeLessThan(40);
  expect(list.style.height).toBe(`${1000 * 64}px`);
  expect(items(container)[0].getAttribute('aria-setsize')).toBe('1000');
  expect(items(container)[2].getAttribute('aria-posinset')).toBe('3');
});

test('a row: scientific name, common name, family colour, photo count; it links to the species with the chips pre-set', () => {
  const rows = [row(1, { photos: 24 }), row(2, { photos: 1 }), row(3, { photos: 0, common: 'Blue Tiger' })];
  const { container } = render(SpeciesResults, { rows, filter: { ...EMPTY, tags: ['larva'], places: ['P20'] } });
  const links = screen.getAllByRole('link');
  expect(links.map((a) => a.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Danaus species1 Tiger 1 24 photos', 'Danaus species2 1 photo', 'Danaus species3 Blue Tiger Not photographed',
  ]);
  expect(links[0].getAttribute('href')).toBe('#/species/A1?tag=larva&place=P20');
  expect(container.querySelector('.frow i').classList.contains('bn')).toBe(true);
  expect(links[0].getAttribute('style')).toContain('--fam: var(--f-nym)');
});

test('with Missing life stages each row names the stages it lacks', () => {
  render(SpeciesResults, { rows: [row(1, { photos: 5, missing: ['egg', 'pupa'] })], filter: { ...EMPTY, stages: 'missing' } });
  expect(screen.getByRole('link').textContent).toContain('Missing: egg, pupa');
});

test('arrow keys, Home and End move between rows, even to rows not yet in the page', async () => {
  const { container } = render(SpeciesResults, { rows: many, filter: EMPTY });
  const first = screen.getAllByRole('link')[0];
  first.focus();
  await fireEvent.keyDown(first, { key: 'ArrowDown' });
  expect(document.activeElement.getAttribute('href')).toBe('#/species/A2');
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowUp' });
  expect(document.activeElement.getAttribute('href')).toBe('#/species/A1');
  await fireEvent.keyDown(document.activeElement, { key: 'End' });
  expect(document.activeElement.getAttribute('href')).toBe('#/species/A1000');
  expect(items(container).at(-1).getAttribute('aria-posinset')).toBe('1000');
  await fireEvent.keyDown(document.activeElement, { key: 'Home' });
  expect(document.activeElement.getAttribute('href')).toBe('#/species/A1');
});

test('only the focused row is in the tab order', async () => {
  render(SpeciesResults, { rows: many.slice(0, 5), filter: EMPTY });
  expect(screen.getAllByRole('link').map((a) => a.tabIndex)).toEqual([0, -1, -1, -1, -1]);
  const links = screen.getAllByRole('link');
  links[0].focus();
  await fireEvent.keyDown(links[0], { key: 'ArrowDown' });
  expect(screen.getAllByRole('link').map((a) => a.tabIndex)).toEqual([-1, 0, -1, -1, -1]);
});

test('the same result fetched again keeps the row in the tab order and does not rise again; a new filter starts at the first row', async () => {
  const rows = many.slice(0, 20);
  const { container, rerender } = render(SpeciesResults, { rows, filter: EMPTY, query: '' });
  const first = screen.getAllByRole('link')[0];
  first.focus();
  await fireEvent.keyDown(first, { key: 'ArrowDown' });
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
  await new Promise((resolve) => setTimeout(resolve, 950));
  expect(container.querySelector('.fitem.in')).toBeNull();
  expect(container.querySelector('[data-index="2"]').tabIndex).toBe(0);

  await rerender({ rows: rows.map((each) => ({ ...each })), query: '' });
  expect(container.querySelector('[data-index="2"]').tabIndex).toBe(0);
  expect(container.querySelector('.fitem.in')).toBeNull();

  await rerender({ rows: rows.slice(0, 10), query: 'status=not' });
  expect(container.querySelector('[data-index="0"]').tabIndex).toBe(0);
  expect(container.querySelector('.fitem.in')).not.toBeNull();
});

test('a custom species’ row carries the Custom badge in its family colour', () => {
  render(SpeciesResults, { rows: [row(1, { id: 'U3', name: 'Neptis sankara', source: 'manual' }), row(2)], filter: EMPTY });
  const [custom, official] = screen.getAllByRole('link');
  const badge = custom.querySelector('.badge.custom');
  expect(badge.textContent).toBe('Custom');
  expect(badge.closest('.frow').style.getPropertyValue('--fam').trim()).toBe('var(--f-nym)');
  expect(official.querySelector('.badge')).toBeNull();
});
