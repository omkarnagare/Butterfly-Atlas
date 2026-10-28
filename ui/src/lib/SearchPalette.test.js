import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import SearchPalette from './SearchPalette.svelte';
import { search } from './api.js';
import { anyModalOpen } from './modal.js';

vi.mock('./api.js', () => ({ search: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

const hit = (over) => ({
  id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true,
  species: { got: 0, total: 1 }, path: 'Nymphalidae › Danainae › Danaini › Danaus', matched: 'Plain Tiger', matchKind: 'common',
  ...over,
});

async function type(text) {
  await fireEvent.input(screen.getByRole('combobox', { name: 'Search' }), { target: { value: text } });
}

test('searches as you type', async () => {
  search.mockResolvedValue([hit()]);
  render(SearchPalette, { onClose: vi.fn() });

  await type('plain');

  expect(await screen.findByText('Plain Tiger')).toBeTruthy();
  expect(screen.getByText('Danaus chrysippus')).toBeTruthy();
  expect(search).toHaveBeenCalledWith('plain', expect.objectContaining({ signal: expect.any(AbortSignal) }));
});

test('says which other name matched', async () => {
  search.mockResolvedValue([hit({ matched: 'Limnas klugii', matchKind: 'synonym' })]);
  render(SearchPalette, { onClose: vi.fn() });

  await type('limnas');

  expect(await screen.findByText('Limnas klugii')).toBeTruthy();
  expect(screen.getByText(/also known as/)).toBeTruthy();
});

test('arrow keys and Enter open the chosen result', async () => {
  search.mockResolvedValue([hit(), hit({ id: 'A6', name: 'Danaus genutia', common: 'Striped Tiger' })]);
  const onClose = vi.fn();
  render(SearchPalette, { onClose });

  await type('tiger');
  await screen.findByText('Striped Tiger');
  const input = screen.getByRole('combobox', { name: 'Search' });
  await fireEvent.keyDown(input, { key: 'ArrowDown' });
  await fireEvent.keyDown(input, { key: 'Enter' });

  expect(window.location.hash).toBe('#/species/A6');
  expect(onClose).toHaveBeenCalledOnce();
});

test('Escape closes it', async () => {
  const onClose = vi.fn();
  render(SearchPalette, { onClose });

  await fireEvent.keyDown(screen.getByRole('combobox', { name: 'Search' }), { key: 'Escape' });

  expect(onClose).toHaveBeenCalledOnce();
});

test('says when nothing matches', async () => {
  search.mockResolvedValue([]);
  render(SearchPalette, { onClose: vi.fn() });

  await type('xyzzy');

  expect(await screen.findByText(/No species or group matches “xyzzy”/)).toBeTruthy();
});

test('results are not tab stops and Escape works from anywhere', async () => {
  search.mockResolvedValue([hit()]);
  const onClose = vi.fn();
  render(SearchPalette, { onClose });

  await type('plain');
  const option = await screen.findByRole('option');
  expect(option.getAttribute('tabindex')).toBe('-1');
  await fireEvent.keyDown(window, { key: 'Escape' });

  expect(onClose).toHaveBeenCalledOnce();
});

test('keeps the chosen result in view', async () => {
  const scroll = vi.fn();
  Element.prototype.scrollIntoView = scroll;
  try {
    search.mockResolvedValue([hit(), hit({ id: 'A6', common: 'Striped Tiger' })]);
    render(SearchPalette, { onClose: vi.fn() });

    await type('tiger');
    await screen.findByText('Striped Tiger');
    await fireEvent.keyDown(screen.getByRole('combobox', { name: 'Search' }), { key: 'ArrowDown' });

    await vi.waitFor(() => expect(scroll).toHaveBeenCalled());
  } finally {
    delete Element.prototype.scrollIntoView;
  }
});

test('shows a search word upright and flags a taxon outside the current list', async () => {
  search.mockResolvedValue([hit({ matched: 'monarch-ish', matchKind: 'word', active: false })]);
  render(SearchPalette, { onClose: vi.fn() });

  await type('monarch');

  expect((await screen.findByText('monarch-ish')).classList.contains('bn')).toBe(false);
  expect(screen.getByText('Not in the current India list')).toBeTruthy();
});

test('counts as an open overlay, so page-level shortcuts and file drops stay quiet', () => {
  const { unmount } = render(SearchPalette, { onClose: vi.fn() });
  expect(anyModalOpen()).toBe(true);
  unmount();
  expect(anyModalOpen()).toBe(false);
});

test('a custom species in the results carries the Custom badge in its family colour', async () => {
  search.mockResolvedValue([
    hit({ id: 'U3', name: 'Neptis sankara', common: null, source: 'manual', matched: 'Neptis sankara', matchKind: 'name', path: 'Custom additions' }),
    hit({ source: 'official' }),
  ]);
  render(SearchPalette, { onClose: vi.fn() });

  await type('neptis');

  const badge = await screen.findByText('Custom');
  expect(badge.classList.contains('custom')).toBe(true);
  expect(badge.closest('.res').style.getPropertyValue('--fam').trim()).toBe('var(--f-nym)');
  expect(screen.getAllByText('Custom')).toHaveLength(1);
});

test('a split species in the results says how many photos are still to sort', async () => {
  search.mockResolvedValue([hit({ active: false, toSort: 3 }), hit({ id: 'A6', name: 'Danaus genutia', common: 'Striped Tiger', toSort: 0 })]);
  render(SearchPalette, { onClose: vi.fn() });

  await type('danaus');

  expect((await screen.findByText('Split — 3 photos to sort')).closest('.res').getAttribute('href')).toBe('#/species/A5');
  expect(screen.getAllByText(/Split —/)).toHaveLength(1);
});
