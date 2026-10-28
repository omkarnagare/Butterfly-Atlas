import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import Explore from './Explore.svelte';
import { addCustomSpecies, checkCustomName, getChildren, getTaxon, getTree } from '../lib/api.js';

vi.mock('../lib/api.js', () => ({
  getTaxon: vi.fn(), getChildren: vi.fn(), getTree: vi.fn(), addSearchWord: vi.fn(), removeSearchWord: vi.fn(), getStatus: vi.fn(),
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), renameCustomSpecies: vi.fn(),
}));

beforeEach(() => vi.resetAllMocks());

const path = [
  { id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null },
  { id: 'A1', rank: 'family', name: 'Nymphalidae', common: 'Brush-footed Butterflies' },
  { id: 'A2', rank: 'subfamily', name: 'Danainae', common: null },
];
const detail = (over) => ({
  id: 'A3', rank: 'tribe', name: 'Danaini', common: null, family: 'Nymphalidae', active: true, path,
  species: { got: 0, total: 3 }, ranks: [], synonyms: [], searchWords: [], folder: null, ...over,
});
const card = (over) => ({ family: 'Nymphalidae', active: true, common: null, species: { got: 0, total: 1 }, ...over });

test('shows the header, breadcrumbs and child cards', async () => {
  getTaxon.mockResolvedValue(detail());
  getChildren.mockResolvedValue([card({ id: 'A4', rank: 'genus', name: 'Danaus', common: 'Tigers' }), card({ id: 'A7', rank: 'genus', name: 'Tirumala' })]);
  render(Explore, { id: 'A3' });

  expect(await screen.findByRole('heading', { level: 1, name: 'Danaini' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Nymphalidae' }).getAttribute('href')).toBe('#/explore/A1');
  expect(screen.getByRole('link', { name: 'Butterflies' }).getAttribute('href')).toBe('#/explore');
  expect(screen.getByRole('link', { name: /Danaus/ }).getAttribute('href')).toBe('#/explore/A4');
  expect(screen.getByRole('link', { name: /Tirumala/ })).toBeTruthy();
  expect(getTaxon).toHaveBeenCalledWith('A3');
  expect(getChildren).toHaveBeenCalledWith('A3');
});

test('shows species as species cards', async () => {
  getTaxon.mockResolvedValue(detail({ id: 'A4', rank: 'genus', name: 'Danaus' }));
  getChildren.mockResolvedValue([
    card({ id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger' }),
    card({ id: 'A6', rank: 'species', name: 'Danaus genutia' }),
  ]);
  render(Explore, { id: 'A4' });

  expect(await screen.findByText('Plain Tiger')).toBeTruthy();
  expect(screen.getByText('Danaus chrysippus')).toBeTruthy();
  expect(screen.getByText('Danaus genutia')).toBeTruthy();
  expect(screen.getAllByText('Not yet photographed')).toHaveLength(2);
});

test('loads the new taxon when the id changes', async () => {
  getTaxon.mockResolvedValue(detail());
  getChildren.mockResolvedValue([]);
  const { rerender } = render(Explore, { id: 'A3' });
  await screen.findByRole('heading', { level: 1, name: 'Danaini' });

  getTaxon.mockResolvedValue(detail({ id: 'A4', rank: 'genus', name: 'Danaus' }));
  await rerender({ id: 'A4' });

  expect(await screen.findByRole('heading', { level: 1, name: 'Danaus' })).toBeTruthy();
  expect(getTaxon).toHaveBeenLastCalledWith('A4');
});

test('shows the problem when the taxon cannot be loaded', async () => {
  getTaxon.mockRejectedValue(new Error('There is no taxon A999 in your atlas.'));
  getChildren.mockResolvedValue([]);
  render(Explore, { id: 'A999' });

  expect((await screen.findByRole('alert')).textContent).toBe('There is no taxon A999 in your atlas.');
});

test('ignoresAnAnswerForAPageYouAlreadyLeft', async () => {
  let resolveOld;
  getTaxon.mockImplementation((wanted) =>
    wanted === 'A3' ? new Promise((resolve) => { resolveOld = resolve; }) : Promise.resolve(detail({ id: 'A4', rank: 'genus', name: 'Danaus' })));
  getChildren.mockResolvedValue([]);
  const { rerender } = render(Explore, { id: 'A3' });

  await rerender({ id: 'A4' });
  expect(await screen.findByRole('heading', { level: 1, name: 'Danaus' })).toBeTruthy();
  resolveOld(detail());
  await new Promise((r) => setTimeout(r, 0));

  expect(screen.getByRole('heading', { level: 1, name: 'Danaus' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Danaini' })).toBeNull();
});

test('switches to the map and remembers the choice', async () => {
  getTaxon.mockResolvedValue(detail());
  getChildren.mockResolvedValue([]);
  getTree.mockResolvedValue({
    id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null, family: null, species: { got: 0, total: 1 },
    children: [{ id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae',
      species: { got: 0, total: 1 }, children: [] }],
  });
  render(Explore, { id: 'A3' });

  await fireEvent.click(await screen.findByRole('button', { name: 'Map' }));

  expect(await screen.findByRole('img', { name: /Map of the butterfly tree/ })).toBeTruthy();
  expect(localStorage.getItem('atlas.exploreView')).toBe('map');
});

test('reloads in place when the photo check finds new photos', async () => {
  getTaxon.mockResolvedValue(detail());
  getChildren.mockResolvedValue([card({ id: 'A4', rank: 'genus', name: 'Danaus', common: 'Tigers' })]);
  const { container } = render(Explore, { id: 'A3' });
  await screen.findByRole('heading', { level: 1, name: 'Danaini' });
  expect(container.querySelector('.node .ct').textContent).toBe('0 of 1 species');

  getTaxon.mockResolvedValue(detail({ species: { got: 1, total: 3 } }));
  getChildren.mockResolvedValue([card({ id: 'A4', rank: 'genus', name: 'Danaus', common: 'Tigers', species: { got: 1, total: 1 } })]);
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));

  await vi.waitFor(() => expect(container.querySelector('.node .ct').textContent).toBe('1 of 1 species'));
});

const group = () => detail({ id: 'G:butterflies', rank: 'group', name: 'Butterflies', family: null, path: [], species: { got: 1, total: 1400 } });
const additions = (got, total) => card({ id: 'U:butterflies', rank: 'additions', name: 'Custom additions', family: null, source: 'system', species: { got, total } });
const families = [card({ id: 'A1', rank: 'family', name: 'Nymphalidae' }), card({ id: 'A2', rank: 'family', name: 'Lycaenidae', family: 'Lycaenidae' })];

test('family cards carry the family band as on Home; the Custom additions card keeps its dashed look (Meadow §4)', async () => {
  getTaxon.mockResolvedValue(group());
  getChildren.mockImplementation(async (id) => (id === 'G:butterflies' ? [...families, additions(1, 2)] : []));
  const { container } = render(Explore, { id: 'G:butterflies' });

  await screen.findByRole('heading', { level: 1, name: 'Butterflies' });
  const cards = [...container.querySelector('.grid').children];
  expect(cards.map((el) => [el.classList.contains('banded'), Boolean(el.querySelector('.band'))])).toEqual([[true, true], [true, true], [false, false]]);
  expect(cards[1].style.getPropertyValue('--fam')).toBe('var(--f-lyc)');
  expect(cards[2].classList.contains('add-card')).toBe(true);
  // Explore keeps its own grid; three across is Home's
  expect(container.querySelector('.fam-grid')).toBeNull();
});

test('at group level the Custom additions card comes last after the families; empty, it opens the Add dialog', async () => {
  getTaxon.mockResolvedValue(group());
  getChildren.mockImplementation(async (id) => (id === 'G:butterflies' ? [additions(0, 0), ...families] : []));
  checkCustomName.mockResolvedValue({ ok: true, name: 'Neptis sankara' });
  addCustomSpecies.mockResolvedValue({ id: 'U1', rank: 'species', name: 'Neptis sankara', common: null, family: 'Nymphalidae', source: 'manual' });
  const { container } = render(Explore, { id: 'G:butterflies' });

  await screen.findByRole('heading', { level: 1, name: 'Butterflies' });
  const cards = [...container.querySelector('.grid').children];
  expect(cards.map((el) => el.querySelector('.nm').textContent)).toEqual(['Nymphalidae', 'Lycaenidae', 'Custom additions']);
  const add = screen.getByRole('button', { name: /Add a species the list doesn't have/ });
  expect(cards[2]).toBe(add);
  expect(add.style.getPropertyValue('--i').trim()).toBe('2');

  await fireEvent.click(add);
  const dialog = await screen.findByRole('dialog', { name: 'Add a species' });
  await fireEvent.click((await screen.findAllByRole('radio'))[0]);
  await fireEvent.input(screen.getByRole('textbox', { name: 'Scientific name' }), { target: { value: 'Neptis sankara' } });
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Add' }).getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  expect(addCustomSpecies).toHaveBeenCalledWith('A1', 'Neptis sankara', null);
  await vi.waitFor(() => expect(window.location.hash).toBe('#/species/U1'));
  expect(dialog.isConnected).toBe(false);
});

test('the Add dialog closed without adding gives focus back to the card', async () => {
  getTaxon.mockResolvedValue(group());
  getChildren.mockImplementation(async (id) => (id === 'G:butterflies' ? [...families, additions(0, 0)] : []));
  render(Explore, { id: 'G:butterflies' });
  const add = await screen.findByRole('button', { name: /Add a species the list doesn't have/ });

  await fireEvent.click(add);
  await screen.findByRole('dialog', { name: 'Add a species' });
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(add));
});

test('with species the card opens the Custom additions page, whose header offers Add a species', async () => {
  getTaxon.mockImplementation(async (id) => (id === 'G:butterflies' ? group() : detail({
    id: 'U:butterflies', rank: 'additions', name: 'Custom additions', family: null, source: 'system',
    path: [{ id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null }], species: { got: 1, total: 2 },
  })));
  getChildren.mockImplementation(async (id) => (id === 'G:butterflies' ? [...families, additions(1, 2)] : [
    card({ id: 'U1', rank: 'species', name: 'Neptis sankara', source: 'manual', species: { got: 1, total: 1 } }),
    card({ id: 'U2', rank: 'species', name: 'Arhopala ace', family: 'Lycaenidae', source: 'manual' }),
  ]));
  const { rerender, container } = render(Explore, { id: 'G:butterflies' });
  const link = await screen.findByRole('link', { name: /Custom additions/ });
  expect(link.textContent).toContain('2 species · 1 photographed');
  expect(link.getAttribute('href')).toBe('#/explore/U%3Abutterflies');

  await rerender({ id: 'U:butterflies' });
  expect(await screen.findByRole('heading', { level: 1, name: 'Custom additions' })).toBeTruthy();
  expect(container.querySelector('.xh .eyebrow').textContent).toBe('Butterflies');
  expect(screen.queryByRole('button', { name: /Search words/ })).toBeNull();
  expect(container.querySelector('.xh .ct').textContent).toBe('1 of 2 species photographed');
  expect(screen.getByText('Neptis sankara')).toBeTruthy();
  expect(screen.getAllByText('Custom')).toHaveLength(2);
  expect(container.querySelectorAll('.grid .spc')).toHaveLength(2);
  await fireEvent.click(screen.getByRole('button', { name: 'Add a species' }));
  expect(await screen.findByRole('dialog', { name: 'Add a species' })).toBeTruthy();
});

test('an empty Custom additions page says so and still offers Add a species', async () => {
  getTaxon.mockResolvedValue(detail({ id: 'U:butterflies', rank: 'additions', name: 'Custom additions', family: null, species: { got: 0, total: 0 } }));
  getChildren.mockResolvedValue([]);
  const { container } = render(Explore, { id: 'U:butterflies' });
  expect(await screen.findByText("No custom species yet. Add one the list doesn't have, and file photos under it.")).toBeTruthy();
  expect(container.querySelector('.xh .ct')).toBeNull();
  expect(container.querySelector('.xh .meter, .xh [role="img"]')).toBeNull();
  expect(screen.getByRole('button', { name: 'Add a species' })).toBeTruthy();
});
