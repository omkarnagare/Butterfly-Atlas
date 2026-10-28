import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import SpeciesPicker from './SpeciesPicker.svelte';
import { addCustomSpecies, checkCustomName, getChildren, search } from './api.js';

vi.mock('./api.js', () => ({ search: vi.fn(), addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), getChildren: vi.fn(), renameCustomSpecies: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

const hit = (id, rank, name, common) => ({ id, rank, name, common, family: 'Nymphalidae', active: true, species: { got: 0, total: 1 } });

test('finds species only, never the current one, and Enter picks the chosen one', async () => {
  search.mockResolvedValue([
    hit('A4', 'genus', 'Danaus', 'Tigers'), hit('A5', 'species', 'Danaus chrysippus', 'Plain Tiger'),
    hit('A6', 'species', 'Danaus genutia', 'Striped Tiger'), hit('A7', 'species', 'Tirumala limniace', 'Blue Tiger'),
  ]);
  const onPick = vi.fn();
  render(SpeciesPicker, { exclude: 'A5', onPick, onClose: vi.fn() });

  const input = screen.getByRole('combobox', { name: 'Species' });
  expect(document.activeElement).toBe(input);
  await fireEvent.input(input, { target: { value: 'tiger' } });
  expect(await screen.findByText('Striped Tiger')).toBeTruthy();
  expect(screen.getAllByRole('option')).toHaveLength(2);
  expect(search).toHaveBeenCalledWith('tiger', expect.objectContaining({ limit: 50 }));
  await fireEvent.keyDown(input, { key: 'ArrowDown' });
  await fireEvent.keyDown(input, { key: 'Enter' });

  expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: 'A7' }));
});

test('a click picks too, and it says when nothing matches', async () => {
  search.mockResolvedValueOnce([hit('A6', 'species', 'Danaus genutia', 'Striped Tiger')]).mockResolvedValueOnce([]);
  const onPick = vi.fn();
  render(SpeciesPicker, { onPick, onClose: vi.fn() });
  const input = screen.getByRole('combobox', { name: 'Species' });

  await fireEvent.input(input, { target: { value: 'striped' } });
  await fireEvent.click(await screen.findByRole('option', { name: /Striped Tiger/ }));
  expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ id: 'A6' }));

  await fireEvent.input(input, { target: { value: 'xyzzy' } });
  expect(await screen.findByText('No species matches “xyzzy”.')).toBeTruthy();
});

test('typing clears the old results so Enter cannot pick a stale one, and the count is announced', async () => {
  search.mockResolvedValueOnce([hit('A6', 'species', 'Danaus genutia', 'Striped Tiger')]).mockReturnValueOnce(new Promise(() => {}));
  const onPick = vi.fn();
  render(SpeciesPicker, { onPick, onClose: vi.fn() });
  const input = screen.getByRole('combobox', { name: 'Species' });

  await fireEvent.input(input, { target: { value: 'striped' } });
  await screen.findByRole('option', { name: /Striped Tiger/ });
  expect(screen.getByRole('status').textContent).toBe('1 species');
  await fireEvent.input(input, { target: { value: 'strip' } });
  await fireEvent.keyDown(input, { key: 'Enter' });

  expect(screen.queryByRole('option')).toBeNull();
  expect(onPick).not.toHaveBeenCalled();
});

const sankara = {
  id: 'U3', rank: 'species', name: 'Neptis sankara', common: 'Broad-banded Sailer', family: 'Nymphalidae', familyId: 'A1', source: 'manual',
  active: true, species: { got: 0, total: 1 }, path: [], synonyms: [], searchWords: [],
};

test('with nothing found for two words it offers to add them as a custom species, and the new one is picked', async () => {
  search.mockResolvedValue([]);
  getChildren.mockResolvedValue([{ id: 'A1', rank: 'family', name: 'Nymphalidae', common: null, family: 'Nymphalidae', species: { got: 0, total: 1 } }]);
  checkCustomName.mockResolvedValue({ ok: true, name: 'Neptis sankara' });
  addCustomSpecies.mockResolvedValue(sankara);
  const onPick = vi.fn();
  render(SpeciesPicker, { onPick, onClose: vi.fn() });
  const input = screen.getByRole('combobox', { name: 'Species' });

  await fireEvent.input(input, { target: { value: 'neptis' } });
  expect(await screen.findByText('No species matches “neptis”.')).toBeTruthy();
  expect(screen.queryByRole('option')).toBeNull();

  await fireEvent.input(input, { target: { value: ' Neptis  sankara ' } });
  const row = await screen.findByRole('option', { name: 'Add “Neptis sankara” as a custom species' });
  expect(screen.getByRole('status').textContent).toBe('No species matches “Neptis  sankara”.');
  expect(input.getAttribute('aria-activedescendant')).toBe(row.id);
  await fireEvent.keyDown(input, { key: 'Enter' });

  const dialog = await screen.findByRole('dialog', { name: 'Add a species' });
  expect(within(dialog).getByRole('textbox', { name: 'Scientific name' }).value).toBe('Neptis sankara');
  await fireEvent.click((await within(dialog).findAllByRole('radio'))[0]);
  await vi.waitFor(() => expect(within(dialog).getByRole('button', { name: 'Add' }).getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));

  await vi.waitFor(() => expect(onPick).toHaveBeenCalledWith({
    id: 'U3', rank: 'species', name: 'Neptis sankara', common: 'Broad-banded Sailer', family: 'Nymphalidae', source: 'manual',
  }, { added: true }));
  expect(screen.queryByRole('dialog', { name: 'Add a species' })).toBeNull();
});

test('a click on the add row opens the dialog too; Escape closes only it and focus goes back to the search', async () => {
  search.mockResolvedValue([]);
  getChildren.mockResolvedValue([]);
  checkCustomName.mockResolvedValue({ ok: true, name: 'Neptis sankara' });
  const onClose = vi.fn();
  render(SpeciesPicker, { onPick: vi.fn(), onClose });
  const input = screen.getByRole('combobox', { name: 'Species' });
  await fireEvent.input(input, { target: { value: 'Neptis sankara' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a custom species/ }));
  await screen.findByRole('dialog', { name: 'Add a species' });

  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: 'Add a species' })).toBeNull();
  expect(screen.getByRole('dialog', { name: 'Move to another species' })).toBeTruthy();
  expect(onClose).not.toHaveBeenCalled();
  await vi.waitFor(() => expect(document.activeElement).toBe(input));
});

test('no add row while something matches, or for one word or more than two', async () => {
  search.mockResolvedValueOnce([hit('A6', 'species', 'Danaus genutia', 'Striped Tiger')]).mockResolvedValue([]);
  render(SpeciesPicker, { onPick: vi.fn(), onClose: vi.fn() });
  const input = screen.getByRole('combobox', { name: 'Species' });

  await fireEvent.input(input, { target: { value: 'striped tiger' } });
  await screen.findByRole('option', { name: /Striped Tiger/ });
  expect(screen.queryByRole('option', { name: /as a custom species/ })).toBeNull();
  for (const text of ['xyzzy', 'a b c']) {
    await fireEvent.input(input, { target: { value: text } });
    expect(await screen.findByText(`No species matches “${text}”.`)).toBeTruthy();
    expect(screen.queryByRole('option')).toBeNull();
  }
});

async function addThroughPicker(onPick) {
  search.mockResolvedValue([]);
  getChildren.mockResolvedValue([{ id: 'A1', rank: 'family', name: 'Nymphalidae', common: null, family: 'Nymphalidae', species: { got: 0, total: 1 } }]);
  checkCustomName.mockResolvedValue({ ok: true, name: 'Neptis sankara' });
  addCustomSpecies.mockResolvedValue(sankara);
  render(SpeciesPicker, { onPick, onClose: vi.fn() });
  await fireEvent.input(screen.getByRole('combobox', { name: 'Species' }), { target: { value: 'Neptis sankara' } });
  await fireEvent.click(await screen.findByRole('option', { name: /as a custom species/ }));
  const dialog = await screen.findByRole('dialog', { name: 'Add a species' });
  await fireEvent.click((await within(dialog).findAllByRole('radio'))[0]);
  await vi.waitFor(() => expect(within(dialog).getByRole('button', { name: 'Add' }).getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Add' }));
}

test('a species added but then refused by the move says it was added, and why the move failed', async () => {
  const onPick = vi.fn().mockRejectedValue(new Error('That species folder is read-only.'));
  await addThroughPicker(onPick);

  expect((await screen.findByRole('alert')).textContent).toBe('Neptis sankara was added to Custom additions. That species folder is read-only.');
  expect(screen.getByRole('dialog', { name: 'Move to another species' })).toBeTruthy();
});

test('no add row when the name typed is the species the photos are already on (any case, extra spaces), and it says so', async () => {
  search.mockResolvedValue([hit('U3', 'species', 'Neptis sankara', 'Broad-banded Sailer')]);
  render(SpeciesPicker, { exclude: 'U3', onPick: vi.fn(), onClose: vi.fn() });
  const input = screen.getByRole('combobox', { name: 'Species' });
  for (const text of [' neptis  SANKARA ', 'broad-banded sailer']) {
    await fireEvent.input(input, { target: { value: text } });
    await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe("That's the species these photos are on."));
    expect(screen.queryByRole('option')).toBeNull();
  }
});

test('the current species found by another word (its genus) does not hide the add row for a new name', async () => {
  search.mockResolvedValue([hit('A6', 'species', 'Danaus genutia', 'Striped Tiger')]);
  render(SpeciesPicker, { exclude: 'A6', onPick: vi.fn(), onClose: vi.fn() });
  await fireEvent.input(screen.getByRole('combobox', { name: 'Species' }), { target: { value: 'Danaus melanippus' } });
  expect(await screen.findByRole('option', { name: 'Add “Danaus melanippus” as a custom species' })).toBeTruthy();
  expect(screen.getByRole('status').textContent).toBe('No species matches “Danaus melanippus”.');
});

test('only a name shaped like genus and species is offered (letters, a hyphen in the second word)', async () => {
  search.mockResolvedValue([]);
  render(SpeciesPicker, { onPick: vi.fn(), onClose: vi.fn() });
  const input = screen.getByRole('combobox', { name: 'Species' });
  for (const [text, offered] of [['Arhopala ace-ace', true], ['neptis sankara', true], ['Neptis 2', false], ['ne-ptis sankara', false], ['Neptis sankara.', false]]) {
    await fireEvent.input(input, { target: { value: text } });
    await screen.findByText(`No species matches “${text}”.`);
    expect(screen.queryByRole('option', { name: /as a custom species/ }) !== null).toBe(offered);
  }
});
