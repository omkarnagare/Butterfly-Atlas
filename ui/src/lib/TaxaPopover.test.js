import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import TaxaPopover from './TaxaPopover.svelte';
import { search } from './api.js';
import { EMPTY, mergeNames } from './filter.js';

vi.mock('./api.js', () => ({ search: vi.fn() }));

const hit = (id, rank, name, common, family) => ({ id, rank, name, common, family, path: null, species: { got: 1, total: 4 }, matchKind: 'name', active: true });
const lycaenidae = hit('A12', 'family', 'Lycaenidae', 'Gossamer-winged butterflies', 'Lycaenidae');
const danaus = hit('A230', 'genus', 'Danaus', 'Tigers', 'Nymphalidae');
const tiger = hit('A1042', 'species', 'Danaus chrysippus', 'Plain Tiger', 'Nymphalidae');
const group = hit('G:butterflies', 'group', 'Butterflies', null, null);

beforeEach(() => vi.resetAllMocks());

const field = () => screen.getByRole('combobox', { name: 'Find a taxon' });

test('typing searches after a pause; results show rank and family; Enter and a click add taxa, and their names are passed on', async () => {
  search.mockResolvedValue([group, lycaenidae, danaus, tiger]);
  const onChange = vi.fn();
  const { rerender } = render(TaxaPopover, { filter: EMPTY, names: mergeNames(undefined, {}), onChange, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Taxa' })).toBeTruthy();
  expect(document.activeElement).toBe(field());
  await fireEvent.input(field(), { target: { value: 'dan' } });
  expect(search).not.toHaveBeenCalled();
  await vi.waitFor(() => expect(search).toHaveBeenCalledWith('dan', expect.objectContaining({ signal: expect.any(AbortSignal) })));
  const options = await screen.findAllByRole('option');
  // the whole group is what no taxa means: it is not offered
  expect(options.map((o) => o.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Lycaenidae Gossamer-winged butterflies Family', 'Danaus Tigers · Nymphalidae Genus', 'Plain Tiger Danaus chrysippus · Nymphalidae Species',
  ]);
  expect(screen.getByRole('status').textContent).toBe('3 results');

  await fireEvent.keyDown(field(), { key: 'ArrowDown' });
  await fireEvent.keyDown(field(), { key: 'Enter' });
  expect(onChange).toHaveBeenCalledWith({ ...EMPTY, taxa: ['A230'] }, { taxa: [danaus] });

  await rerender({ filter: { ...EMPTY, taxa: ['A230'] }, names: mergeNames(undefined, { taxa: [danaus] }) });
  expect(screen.getAllByRole('option')[1].getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(screen.getAllByRole('option')[0]);
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, taxa: ['A230', 'A12'] }, { taxa: [lycaenidae] });
  expect(document.activeElement).toBe(field());
});

test('chosen taxa are listed with remove buttons', async () => {
  const onChange = vi.fn();
  render(TaxaPopover, {
    filter: { ...EMPTY, taxa: ['A12', 'A230'], status: 'not' }, names: mergeNames(undefined, { taxa: [lycaenidae, danaus] }), onChange, onClose: vi.fn(),
  });
  const chosen = screen.getByRole('list', { name: 'Chosen taxa' });
  expect([...chosen.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['Lycaenidae ×', 'Danaus ×']);
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Lycaenidae' }));
  expect(onChange).toHaveBeenCalledWith({ ...EMPTY, taxa: ['A230'], status: 'not' }, {});
});

test('a chosen taxon whose name is not known yet is listed as "…"', () => {
  render(TaxaPopover, { filter: { ...EMPTY, taxa: ['A12', 'A99'] }, names: mergeNames(undefined, { taxa: [lycaenidae] }), onChange: vi.fn(), onClose: vi.fn() });
  const chosen = screen.getByRole('list', { name: 'Chosen taxa' });
  expect([...chosen.querySelectorAll('li')].map((li) => li.textContent)).toEqual(['Lycaenidae ×', '… ×']);
  // its Remove button is named by the id until then
  expect(screen.getByRole('button', { name: 'Remove A99' })).toBeTruthy();
});

test('no match says so; a failure shows its message', async () => {
  search.mockResolvedValueOnce([]).mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  render(TaxaPopover, { filter: EMPTY, names: mergeNames(undefined, {}), onChange: vi.fn(), onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'zz' } });
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe('No species or group matches “zz”.'));
  await fireEvent.input(field(), { target: { value: 'zzz' } });
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
});

test('at most 200 taxa can be chosen', async () => {
  search.mockResolvedValue([tiger]);
  const onChange = vi.fn();
  const taxa = Array.from({ length: 200 }, (_, i) => `A${i + 1}`);
  render(TaxaPopover, { filter: { ...EMPTY, taxa }, names: mergeNames(undefined, {}), onChange, onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'tiger' } });
  await fireEvent.click(await screen.findByRole('option'));
  expect(onChange).not.toHaveBeenCalled();
  expect(screen.getByRole('alert').textContent).toBe('Choose at most 200 taxa.');
});

test('removing a chosen taxon moves focus to the next Remove button, else the previous, else the search field', async () => {
  const names = mergeNames(undefined, { taxa: [lycaenidae, danaus, tiger] });
  const onChange = vi.fn();
  const { rerender } = render(TaxaPopover, { filter: { ...EMPTY, taxa: ['A12', 'A230', 'A1042'] }, names, onChange, onClose: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Danaus' }));
  await rerender({ filter: { ...EMPTY, taxa: ['A12', 'A1042'] } });
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Remove Danaus chrysippus' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Danaus chrysippus' }));
  await rerender({ filter: { ...EMPTY, taxa: ['A12'] } });
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Remove Lycaenidae' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove Lycaenidae' }));
  await rerender({ filter: EMPTY });
  expect(document.activeElement).toBe(field());
});

test('Enter right after typing more adds nothing: the results of the older words are gone', async () => {
  search.mockResolvedValueOnce([danaus]).mockImplementation(() => new Promise(() => {}));
  const onChange = vi.fn();
  render(TaxaPopover, { filter: EMPTY, names: mergeNames(undefined, {}), onChange, onClose: vi.fn() });
  await fireEvent.input(field(), { target: { value: 'dan' } });
  expect(await screen.findByRole('option')).toBeTruthy();
  await fireEvent.input(field(), { target: { value: 'lyc' } });
  await fireEvent.keyDown(field(), { key: 'Enter' });
  expect(onChange).not.toHaveBeenCalled();
  expect(screen.queryByRole('option')).toBeNull();
});
