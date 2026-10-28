import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import MapView from './MapView.svelte';
import { getTree } from './api.js';

vi.mock('./api.js', () => ({ getTree: vi.fn() }));

const species = (id, name, common, got) => ({
  id, rank: 'species', name, common, family: 'Nymphalidae', species: { got, total: 1 }, children: [],
});
const tree = {
  id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null, family: null, species: { got: 1, total: 2 },
  children: [{
    id: 'A1', rank: 'family', name: 'Nymphalidae', common: 'Brush-footed Butterflies', family: 'Nymphalidae',
    species: { got: 1, total: 2 },
    children: [species('A5', 'Danaus chrysippus', 'Plain Tiger', 1), species('A6', 'Danaus genutia', 'Striped Tiger', 0)],
  }],
};

beforeEach(() => {
  vi.resetAllMocks();
  getTree.mockResolvedValue(tree);
});

test('draws a circle for every taxon and labels the level in focus', async () => {
  const { container } = render(MapView, { focusId: 'G:butterflies' });

  expect(await screen.findByRole('img', { name: /Map of the butterfly tree/ })).toBeTruthy();
  expect(container.querySelectorAll('circle')).toHaveLength(4);
  expect(container.querySelector('circle[data-id="A5"]').classList.contains('got')).toBe(true);
  expect(container.querySelector('circle[data-id="A6"]').classList.contains('got')).toBe(false);
  expect([...container.querySelectorAll('text')].map((t) => t.textContent)).toEqual(['Nymphalidae']);
});

test('opens a species when its circle is clicked', async () => {
  const { container } = render(MapView, { focusId: 'A1' });
  await screen.findByRole('img', { name: /Map of the butterfly tree/ });

  await fireEvent.click(container.querySelector('circle[data-id="A5"]'));

  expect(window.location.hash).toBe('#/species/A5');
});

test('zooms into a group and reports the new focus', async () => {
  const onFocus = vi.fn();
  const { container } = render(MapView, { focusId: 'G:butterflies', onFocus });
  await screen.findByRole('img', { name: /Map of the butterfly tree/ });

  await fireEvent.click(container.querySelector('circle[data-id="A1"]'));

  expect(onFocus).toHaveBeenCalledWith(expect.objectContaining({ id: 'A1' }));
  expect([...container.querySelectorAll('text')].map((t) => t.textContent)).toEqual(['Plain Tiger', 'Striped Tiger']);
});

test('clicking a species from the top level zooms one level, into its family', async () => {
  window.location.hash = '';
  const onFocus = vi.fn();
  const { container } = render(MapView, { focusId: 'G:butterflies', onFocus });
  await screen.findByRole('img', { name: /Map of the butterfly tree/ });

  await fireEvent.click(container.querySelector('circle[data-id="A5"]'));

  expect(onFocus).toHaveBeenCalledWith(expect.objectContaining({ id: 'A1' }));
  expect(window.location.hash).toBe('');
});

test('clicking the focused circle zooms back out', async () => {
  const onFocus = vi.fn();
  const { container } = render(MapView, { focusId: 'A1', onFocus });
  await screen.findByRole('img', { name: /Map of the butterfly tree/ });

  await fireEvent.click(container.querySelector('circle[data-id="A1"]'));

  expect(onFocus).toHaveBeenCalledWith(expect.objectContaining({ id: 'G:butterflies' }));
});
