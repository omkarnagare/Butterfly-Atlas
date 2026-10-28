import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import AddSpeciesDialog from './AddSpeciesDialog.svelte';
import { ApiError, addCustomSpecies, checkCustomName, getChildren, renameCustomSpecies } from './api.js';

vi.mock('./api.js', async (original) => ({
  ApiError: (await original()).ApiError,
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), getChildren: vi.fn(), renameCustomSpecies: vi.fn(),
}));

const fam = (id, name, common) => ({ id, rank: 'family', name, common, family: name, species: { got: 0, total: 1 } });
const additions = { id: 'U:butterflies', rank: 'additions', name: 'Custom additions', common: null, family: null, species: { got: 0, total: 0 } };
const detail = (over) => ({
  id: 'U3', rank: 'species', name: 'Neptis sankara', common: null, family: 'Nymphalidae', familyId: 'A1', source: 'manual', ...over,
});

beforeEach(() => {
  vi.resetAllMocks();
  getChildren.mockResolvedValue([fam('A1', 'Nymphalidae', 'Brush-footed Butterflies'), fam('A2', 'Lycaenidae', 'Blues'), additions]);
  checkCustomName.mockImplementation(async (name) => ({ ok: true, name: name.trim().replace(/\s+/g, ' ').replace(/^./, (c) => c.toUpperCase()) }));
});

afterEach(() => vi.useRealTimers());

const nameField = () => screen.getByRole('textbox', { name: 'Scientific name' });
const addButton = () => screen.getByRole('button', { name: 'Add' });

test('offers the group’s families in their colours; Add waits for a family and a name that is ok', async () => {
  const onAdded = vi.fn();
  addCustomSpecies.mockResolvedValue(detail({ common: 'Broad-banded Sailer' }));
  render(AddSpeciesDialog, { onAdded, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Add a species' })).toBeTruthy();
  expect(document.activeElement).toBe(nameField());
  const group = screen.getByRole('group', { name: 'Family' });
  const radios = await within(group).findAllByRole('radio');
  expect(radios.map((radio) => radio.closest('label').textContent.trim())).toEqual(['NymphalidaeBrush-footed Butterflies', 'LycaenidaeBlues']);
  expect(getChildren).toHaveBeenCalledWith('G:butterflies');
  expect(radios[0].closest('label').style.getPropertyValue('--fam').trim()).toBe('var(--f-nym)');
  expect(addButton().getAttribute('aria-disabled')).toBe('true');

  await fireEvent.input(nameField(), { target: { value: 'neptis  sankara' } });
  await vi.waitFor(() => expect(checkCustomName).toHaveBeenCalledWith('neptis  sankara', expect.objectContaining({ signal: expect.any(AbortSignal) })));
  expect(await screen.findByText('Neptis sankara', { selector: 'i' })).toBeTruthy();
  expect(addButton().getAttribute('aria-disabled')).toBe('true');
  await fireEvent.submit(addButton().closest('form'));
  expect(screen.getByRole('alert').textContent).toBe("Choose the species' family.");
  expect(addCustomSpecies).not.toHaveBeenCalled();

  await fireEvent.click(radios[0]);
  expect(screen.queryByRole('alert')).toBeNull();
  await fireEvent.input(screen.getByRole('textbox', { name: 'Common name (optional)' }), { target: { value: ' Broad-banded Sailer ' } });
  expect(addButton().getAttribute('aria-disabled')).toBe('false');
  await fireEvent.click(addButton());

  expect(addCustomSpecies).toHaveBeenCalledWith('A1', 'Neptis sankara', 'Broad-banded Sailer');
  await vi.waitFor(() => expect(onAdded).toHaveBeenCalledWith(detail({ common: 'Broad-banded Sailer' })));
});

test('the name is checked 250 ms after the last key, and an answer for older text is dropped', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  let answerOld;
  checkCustomName.mockImplementationOnce(() => new Promise((resolve) => { answerOld = resolve; }));
  render(AddSpeciesDialog, { onAdded: vi.fn(), onClose: vi.fn() });

  await fireEvent.input(nameField(), { target: { value: 'Neptis hylas' } });
  await vi.advanceTimersByTimeAsync(200);
  expect(checkCustomName).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(60);
  expect(checkCustomName).toHaveBeenCalledTimes(1);

  await fireEvent.input(nameField(), { target: { value: 'Neptis sankara' } });
  answerOld({ ok: false, code: 'in_list', message: 'Neptis hylas is already in the list as Common Sailer.', taxon: { id: 'A88' } });
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.queryByText(/already in the list/)).toBeNull();
  await vi.advanceTimersByTimeAsync(250);
  expect(checkCustomName).toHaveBeenLastCalledWith('Neptis sankara', expect.anything());
  expect(screen.queryByText(/already in the list/)).toBeNull();
});

test('a blocked name says why and offers to open the species already in the list', async () => {
  checkCustomName.mockResolvedValue({
    ok: false, code: 'in_list', message: 'Euploea core is already in the list as Euploea coreta (Common Crow).',
    taxon: { id: 'A120', name: 'Euploea coreta', common: 'Common Crow' },
  });
  const onClose = vi.fn();
  render(AddSpeciesDialog, { onAdded: vi.fn(), onClose });
  await screen.findAllByRole('radio');
  await fireEvent.click(screen.getAllByRole('radio')[0]);

  await fireEvent.input(nameField(), { target: { value: 'Euploea core' } });
  expect(await screen.findByText(/already in the list as Euploea coreta/)).toBeTruthy();
  const open = screen.getByRole('link', { name: 'Open it' });
  expect(open.getAttribute('href')).toBe('#/species/A120');
  expect(open.parentElement.textContent).toBe('Euploea core is already in the list as Euploea coreta (Common Crow). Open it');
  expect(addButton().getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(open);
  expect(onClose).toHaveBeenCalled();

  checkCustomName.mockResolvedValue({ ok: false, code: 'invalid', message: 'Enter the scientific name as genus and species, e.g. Neptis sankara.', taxon: null });
  await fireEvent.input(nameField(), { target: { value: 'Euploea' } });
  expect(await screen.findByText('Enter the scientific name as genus and species, e.g. Neptis sankara.')).toBeTruthy();
  expect(screen.queryByRole('link', { name: 'Open it' })).toBeNull();
});

test('a name taken meanwhile is refused on Add with the same message and Open it; other failures show as they are', async () => {
  addCustomSpecies.mockRejectedValueOnce(new ApiError('name_exists', 'You already added Neptis sankara.', 409, { id: 'U2', name: 'Neptis sankara', common: null }))
    .mockRejectedValueOnce(new ApiError('offline', 'Butterfly Atlas could not reach its service. Close the app and open it again.', 0));
  const onAdded = vi.fn();
  render(AddSpeciesDialog, { name: 'Neptis sankara', onAdded, onClose: vi.fn() });
  await fireEvent.click((await screen.findAllByRole('radio'))[1]);
  await vi.waitFor(() => expect(addButton().getAttribute('aria-disabled')).toBe('false'));

  await fireEvent.click(addButton());
  expect(await screen.findByText('You already added Neptis sankara.')).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Open it' }).getAttribute('href')).toBe('#/species/U2');
  expect(addButton().getAttribute('aria-disabled')).toBe('true');

  await fireEvent.input(nameField(), { target: { value: 'Neptis sankara ' } });
  await vi.waitFor(() => expect(addButton().getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(addButton());
  expect((await screen.findByRole('alert')).textContent).toMatch(/could not reach its service/);
  expect(onAdded).not.toHaveBeenCalled();
});

test('pre-filled from the picker, it checks the name at once and focus moves to the families', async () => {
  render(AddSpeciesDialog, { name: 'Neptis sankara', onAdded: vi.fn(), onClose: vi.fn() });
  expect(nameField().value).toBe('Neptis sankara');
  const radios = await screen.findAllByRole('radio');
  await vi.waitFor(() => expect(document.activeElement).toBe(radios[0]));
  expect(checkCustomName).toHaveBeenCalledWith('Neptis sankara', expect.anything());
});

test('while adding, Add and Cancel are aria-disabled and Escape does not close it', async () => {
  let finish;
  addCustomSpecies.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  const onClose = vi.fn();
  const onAdded = vi.fn();
  render(AddSpeciesDialog, { name: 'Neptis sankara', onAdded, onClose });
  await fireEvent.click((await screen.findAllByRole('radio'))[0]);
  await vi.waitFor(() => expect(addButton().getAttribute('aria-disabled')).toBe('false'));

  await fireEvent.click(addButton());
  const adding = screen.getByRole('button', { name: 'Adding…' });
  expect(adding.getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByRole('button', { name: 'Cancel' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(onClose).not.toHaveBeenCalled();

  finish(detail());
  await vi.waitFor(() => expect(onAdded).toHaveBeenCalled());
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalled();
});

test('rename mode shows the family without a choice, checks with the species’ id and saves a changed name or common name', async () => {
  const taxon = detail({ common: 'Broad-banded Sailer' });
  renameCustomSpecies.mockResolvedValue(detail({ name: 'Neptis soma', common: 'Broad-banded Sailer' }));
  const onAdded = vi.fn();
  render(AddSpeciesDialog, { taxon, onAdded, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Rename species' })).toBeTruthy();
  expect(screen.queryByRole('radio')).toBeNull();
  expect(screen.getByText('Nymphalidae')).toBeTruthy();
  expect(getChildren).not.toHaveBeenCalled();
  expect(nameField().value).toBe('Neptis sankara');
  expect(screen.getByRole('textbox', { name: 'Common name (optional)' }).value).toBe('Broad-banded Sailer');
  await vi.waitFor(() => expect(checkCustomName).toHaveBeenCalledWith('Neptis sankara', expect.objectContaining({ id: 'U3' })));
  const rename = screen.getByRole('button', { name: 'Rename' });
  expect(rename.getAttribute('aria-disabled')).toBe('true');

  await fireEvent.input(nameField(), { target: { value: 'Neptis soma' } });
  await vi.waitFor(() => expect(rename.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(rename);
  expect(renameCustomSpecies).toHaveBeenCalledWith('U3', 'Neptis soma', 'Broad-banded Sailer');
  await vi.waitFor(() => expect(onAdded).toHaveBeenCalledWith(expect.objectContaining({ name: 'Neptis soma' })));
});

test('in rename mode a common name alone may change, and emptied it is sent as none', async () => {
  renameCustomSpecies.mockResolvedValue(detail());
  render(AddSpeciesDialog, { taxon: detail({ common: 'Broad-banded Sailer' }), onAdded: vi.fn(), onClose: vi.fn() });
  await vi.waitFor(() => expect(checkCustomName).toHaveBeenCalled());

  await fireEvent.input(screen.getByRole('textbox', { name: 'Common name (optional)' }), { target: { value: '  ' } });
  const rename = screen.getByRole('button', { name: 'Rename' });
  await vi.waitFor(() => expect(rename.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(rename);
  expect(renameCustomSpecies).toHaveBeenCalledWith('U3', 'Neptis sankara', null);
});

test('the name’s status region is always rendered, never display: none, so screen readers announce what appears in it', async () => {
  render(AddSpeciesDialog, { onAdded: vi.fn(), onClose: vi.fn() });
  const region = document.getElementById(nameField().getAttribute('aria-describedby'));
  expect(region.getAttribute('role')).toBe('status');
  expect(region.textContent.trim()).toBe('');
  // Component CSS is not applied under Vitest: read the rule itself.
  const source = readFileSync(resolve(__dirname, 'AddSpeciesDialog.svelte'), 'utf8');
  expect(source).not.toMatch(/\.as-check[^{]*\{[^}]*display:\s*none/);
});

test('Enter before the name has been checked checks it at once, then adds (one check, not two)', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  addCustomSpecies.mockResolvedValue(detail());
  const onAdded = vi.fn();
  render(AddSpeciesDialog, { onAdded, onClose: vi.fn() });
  await fireEvent.click((await screen.findAllByRole('radio'))[0]);

  await fireEvent.input(nameField(), { target: { value: 'neptis sankara' } });
  await fireEvent.submit(nameField().closest('form'));
  await vi.waitFor(() => expect(addCustomSpecies).toHaveBeenCalledWith('A1', 'Neptis sankara', null));
  await vi.advanceTimersByTimeAsync(300);
  expect(checkCustomName).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(onAdded).toHaveBeenCalled());
});

test('Enter before the answer, for a blocked name, shows why and adds nothing', async () => {
  checkCustomName.mockResolvedValue({ ok: false, code: 'in_list', message: 'Neptis hylas is already in the list as Common Sailer.', taxon: { id: 'A88' } });
  render(AddSpeciesDialog, { onAdded: vi.fn(), onClose: vi.fn() });
  await fireEvent.click((await screen.findAllByRole('radio'))[0]);
  await fireEvent.input(nameField(), { target: { value: 'Neptis hylas' } });
  await fireEvent.submit(nameField().closest('form'));

  expect(await screen.findByText(/already in the list as Common Sailer/)).toBeTruthy();
  expect(addCustomSpecies).not.toHaveBeenCalled();
});

test('a check that failed leaves Add usable: it checks again, then adds', async () => {
  checkCustomName.mockRejectedValueOnce(new ApiError('offline', 'Butterfly Atlas could not reach its service. Close the app and open it again.', 0));
  addCustomSpecies.mockResolvedValue(detail());
  render(AddSpeciesDialog, { onAdded: vi.fn(), onClose: vi.fn() });
  await fireEvent.click((await screen.findAllByRole('radio'))[0]);
  await fireEvent.input(nameField(), { target: { value: 'Neptis sankara' } });

  expect((await screen.findByRole('alert')).textContent).toMatch(/could not reach its service/);
  expect(addButton().getAttribute('aria-disabled')).toBe('false');
  await fireEvent.click(addButton());
  await vi.waitFor(() => expect(addCustomSpecies).toHaveBeenCalledWith('A1', 'Neptis sankara', null));
  expect(checkCustomName).toHaveBeenCalledTimes(2);
});

test('in rename mode too, a failed check is asked again on Rename', async () => {
  checkCustomName.mockRejectedValueOnce(new ApiError('offline', 'Butterfly Atlas could not reach its service. Close the app and open it again.', 0));
  renameCustomSpecies.mockResolvedValue(detail({ name: 'Neptis soma' }));
  render(AddSpeciesDialog, { taxon: detail(), onAdded: vi.fn(), onClose: vi.fn() });
  expect(await screen.findByRole('alert')).toBeTruthy();
  await fireEvent.input(nameField(), { target: { value: 'Neptis soma' } });
  checkCustomName.mockRejectedValueOnce(new ApiError('offline', 'Still offline.', 0));
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Still offline.'));

  await fireEvent.submit(nameField().closest('form'));
  await vi.waitFor(() => expect(renameCustomSpecies).toHaveBeenCalledWith('U3', 'Neptis soma', null));
});

test('a second submit while adding does nothing, and the fields are read-only until the answer', async () => {
  let finish;
  addCustomSpecies.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  const onAdded = vi.fn();
  render(AddSpeciesDialog, { name: 'Neptis sankara', onAdded, onClose: vi.fn() });
  const radios = await screen.findAllByRole('radio');
  await fireEvent.click(radios[0]);
  await vi.waitFor(() => expect(addButton().getAttribute('aria-disabled')).toBe('false'));

  const form = nameField().closest('form');
  await fireEvent.submit(form);
  await fireEvent.submit(form);
  await vi.waitFor(() => expect(addCustomSpecies).toHaveBeenCalledTimes(1));
  const common = screen.getByRole('textbox', { name: 'Common name (optional)' });
  expect(nameField().readOnly).toBe(true);
  expect(common.readOnly).toBe(true);
  expect(radios[1].getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(radios[1]);
  expect(radios[1].checked).toBe(false);

  finish(detail());
  await vi.waitFor(() => expect(onAdded).toHaveBeenCalledTimes(1));
  expect(nameField().readOnly).toBe(false);
});

test('a rename refused for another reason (a file in the way, the species gone) shows the message as it is', async () => {
  renameCustomSpecies
    .mockRejectedValueOnce(new ApiError('file_exists', 'A file named Neptis_soma_01.jpg is already in the species folder.', 409))
    .mockRejectedValueOnce(new ApiError('not_found', 'No such species.', 404));
  const onAdded = vi.fn();
  render(AddSpeciesDialog, { taxon: detail(), onAdded, onClose: vi.fn() });
  await fireEvent.input(nameField(), { target: { value: 'Neptis soma' } });
  const rename = screen.getByRole('button', { name: 'Rename' });
  await vi.waitFor(() => expect(rename.getAttribute('aria-disabled')).toBe('false'));

  await fireEvent.click(rename);
  expect((await screen.findByRole('alert')).textContent).toBe('A file named Neptis_soma_01.jpg is already in the species folder.');
  expect(screen.queryByRole('link', { name: 'Open it' })).toBeNull();
  await vi.waitFor(() => expect(rename.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(rename);
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toBe('No such species.'));
  expect(onAdded).not.toHaveBeenCalled();
});

test('families that fail to load say why and offer Retry', async () => {
  getChildren.mockRejectedValueOnce(new ApiError('offline', 'Butterfly Atlas could not reach its service. Close the app and open it again.', 0));
  render(AddSpeciesDialog, { onAdded: vi.fn(), onClose: vi.fn() });
  const group = screen.getByRole('group', { name: 'Family' });
  expect((await within(group).findByRole('alert')).textContent).toMatch(/could not reach its service/);

  // A second failure: Retry stays where it is (focus with it) and says it is busy meanwhile.
  let fail;
  getChildren.mockReturnValueOnce(new Promise((resolve, reject) => { fail = reject; }));
  const retry = within(group).getByRole('button', { name: 'Retry' });
  retry.focus();
  await fireEvent.click(retry);
  expect(retry.textContent).toBe('Retrying…');
  expect(retry.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(retry);
  fail(new ApiError('offline', 'Still offline.', 0));
  await vi.waitFor(() => expect(within(group).getByRole('alert').textContent).toBe('Still offline.'));
  expect(retry.isConnected).toBe(true);
  expect(retry.textContent).toBe('Retry');
  expect(document.activeElement).toBe(retry);
  expect(getChildren).toHaveBeenCalledTimes(2);

  await fireEvent.click(retry);
  const radios = await within(group).findAllByRole('radio');
  expect(radios).toHaveLength(2);
  await vi.waitFor(() => expect(document.activeElement).toBe(radios[0]));
  expect(within(group).queryByRole('alert')).toBeNull();
  expect(getChildren).toHaveBeenCalledTimes(3);
});
