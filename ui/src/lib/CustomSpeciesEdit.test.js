import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import CustomSpeciesEdit from './CustomSpeciesEdit.svelte';
import { ApiError, checkCustomName, deleteCustomSpecies, getDeleted, renameCustomSpecies } from './api.js';

vi.mock('./api.js', async (original) => ({
  ApiError: (await original()).ApiError,
  addCustomSpecies: vi.fn(), checkCustomName: vi.fn(), deleteCustomSpecies: vi.fn(), getChildren: vi.fn(), getDeleted: vi.fn(),
  renameCustomSpecies: vi.fn(),
}));

const sankara = (over) => ({
  id: 'U3', rank: 'species', name: 'Neptis sankara', common: 'Broad-banded Sailer', family: 'Nymphalidae', familyId: 'A1', source: 'manual',
  ...over,
});
const binned = (id, taxonId) => ({ id, taxonId, taxon: { id: taxonId } });

beforeEach(() => {
  vi.resetAllMocks();
  getDeleted.mockResolvedValue([binned(9, 'A5')]);
  checkCustomName.mockImplementation(async (name) => ({ ok: true, name }));
});

const edit = () => screen.getByRole('button', { name: 'Edit…' });
const item = (name) => screen.getByRole('menuitem', { name });

async function openMenu() {
  await fireEvent.click(edit());
  await screen.findByRole('menu', { name: /^Edit / });
  await vi.waitFor(() => expect(getDeleted).toHaveBeenCalled());
  await vi.waitFor(() => expect(document.getElementById(item('Delete…').getAttribute('aria-describedby') ?? '')?.textContent).not.toBe('Checking…'));
}

test('Edit… opens a menu of Rename… and Delete…; arrow keys move between them and Escape closes it', async () => {
  render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 0, onRenamed: vi.fn(), onDeleted: vi.fn() });
  expect(edit().getAttribute('aria-haspopup')).toBe('menu');
  await openMenu();

  expect(screen.getByRole('menu', { name: 'Edit Broad-banded Sailer' })).toBeTruthy();
  expect(screen.getAllByRole('menuitem').map((el) => el.textContent)).toEqual(['Rename…', 'Delete…']);
  expect(document.activeElement).toBe(item('Rename…'));
  await fireEvent.keyDown(document.activeElement, { key: 'ArrowDown' });
  expect(document.activeElement).toBe(item('Delete…'));
  expect(item('Delete…').getAttribute('aria-disabled')).toBeNull();
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('menu')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(edit()));
});

test('Delete… is aria-disabled with the reason while the species has photos, live or in Recently deleted', async () => {
  getDeleted.mockResolvedValue([binned(9, 'U3'), binned(10, 'A5')]);
  const { rerender } = render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 3, onRenamed: vi.fn(), onDeleted: vi.fn() });
  await openMenu();

  const del = item('Delete…');
  await vi.waitFor(() => expect(del.getAttribute('aria-disabled')).toBe('true'));
  const why = document.getElementById(del.getAttribute('aria-describedby'));
  expect(why.textContent).toBe('Move its 3 photos and empty or restore 1 in Recently deleted first.');
  await fireEvent.click(del);
  expect(screen.queryByRole('dialog')).toBeNull();

  await rerender({ photoCount: 1 });
  getDeleted.mockResolvedValue([]);
  await fireEvent.keyDown(window, { key: 'Escape' });
  await openMenu();
  await vi.waitFor(() => expect(document.getElementById(item('Delete…').getAttribute('aria-describedby'))?.textContent).toBe('Move its photo first.'));

  await rerender({ photoCount: 0 });
  getDeleted.mockResolvedValue([binned(9, 'U3'), binned(11, 'U3')]);
  await fireEvent.keyDown(window, { key: 'Escape' });
  await openMenu();
  await vi.waitFor(() => expect(document.getElementById(item('Delete…').getAttribute('aria-describedby'))?.textContent)
    .toBe('Empty or restore its 2 photos in Recently deleted first.'));

  await rerender({ photoCount: 1 });
  getDeleted.mockResolvedValue([binned(9, 'U3'), binned(11, 'U3')]);
  await fireEvent.keyDown(window, { key: 'Escape' });
  await openMenu();
  await vi.waitFor(() => expect(document.getElementById(item('Delete…').getAttribute('aria-describedby'))?.textContent)
    .toBe('Move its photo and empty or restore 2 in Recently deleted first.'));
});

test('Delete… confirms; Delete removes the species and calls onDeleted', async () => {
  deleteCustomSpecies.mockResolvedValue(null);
  const onDeleted = vi.fn();
  render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 0, onRenamed: vi.fn(), onDeleted });
  await openMenu();
  await fireEvent.click(item('Delete…'));

  const dialog = await screen.findByRole('dialog', { name: 'Delete Broad-banded Sailer?' });
  expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' }));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
  expect(deleteCustomSpecies).toHaveBeenCalledWith('U3');
  await vi.waitFor(() => expect(onDeleted).toHaveBeenCalled());
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('a delete refused because photos arrived meanwhile shows the server’s reason and stays open', async () => {
  deleteCustomSpecies.mockRejectedValue(new ApiError('has_photos', 'Move its 4 photos first.', 409));
  const onDeleted = vi.fn();
  render(CustomSpeciesEdit, { taxon: sankara({ common: null }), photoCount: 0, onRenamed: vi.fn(), onDeleted });
  await openMenu();
  await fireEvent.click(item('Delete…'));
  const dialog = await screen.findByRole('dialog', { name: 'Delete Neptis sankara?' });

  await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Move its 4 photos first.');
  expect(onDeleted).not.toHaveBeenCalled();
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(edit()));
});

test('while deleting, Delete and Cancel are aria-disabled and Escape does not close it', async () => {
  let finish;
  deleteCustomSpecies.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
  render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 0, onRenamed: vi.fn(), onDeleted: vi.fn() });
  await openMenu();
  await fireEvent.click(item('Delete…'));
  const dialog = await screen.findByRole('dialog');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

  expect(within(dialog).getByRole('button', { name: 'Deleting…' }).getAttribute('aria-disabled')).toBe('true');
  expect(within(dialog).getByRole('button', { name: 'Cancel' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(dialog.isConnected).toBe(true);
  finish(null);
});

test('Rename… opens the Add dialog in rename mode; the renamed species goes to onRenamed and focus back to Edit…', async () => {
  renameCustomSpecies.mockResolvedValue(sankara({ name: 'Neptis soma' }));
  const onRenamed = vi.fn();
  render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 2, onRenamed, onDeleted: vi.fn() });
  await openMenu();
  await fireEvent.click(item('Rename…'));

  const dialog = await screen.findByRole('dialog', { name: 'Rename species' });
  const field = within(dialog).getByRole('textbox', { name: 'Scientific name' });
  await fireEvent.input(field, { target: { value: 'Neptis soma' } });
  const rename = within(dialog).getByRole('button', { name: 'Rename' });
  await vi.waitFor(() => expect(rename.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(rename);

  expect(renameCustomSpecies).toHaveBeenCalledWith('U3', 'Neptis soma', 'Broad-banded Sailer');
  await vi.waitFor(() => expect(onRenamed).toHaveBeenCalledWith(expect.objectContaining({ name: 'Neptis soma' })));
  expect(screen.queryByRole('dialog')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(edit()));
});

test('Delete… waits, aria-disabled as "Checking…", until Recently deleted has answered, each time the menu opens', async () => {
  let answer;
  getDeleted.mockReturnValueOnce(new Promise((resolve) => { answer = resolve; }));
  render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 0, onRenamed: vi.fn(), onDeleted: vi.fn() });
  await fireEvent.click(edit());
  const del = await screen.findByRole('menuitem', { name: 'Delete…' });
  expect(del.getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(del.getAttribute('aria-describedby')).textContent).toBe('Checking…');
  await fireEvent.click(del);
  expect(screen.queryByRole('dialog')).toBeNull();

  answer([binned(9, 'U3')]);
  await vi.waitFor(() => expect(document.getElementById(item('Delete…').getAttribute('aria-describedby')).textContent)
    .toBe('Empty or restore its photo in Recently deleted first.'));

  // Opened again: the old count is not trusted while the new answer is on its way.
  getDeleted.mockReturnValueOnce(new Promise((resolve) => { answer = resolve; }));
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(edit());
  await vi.waitFor(() => expect(document.getElementById(item('Delete…').getAttribute('aria-describedby')).textContent).toBe('Checking…'));
  answer([]);
  await vi.waitFor(() => expect(item('Delete…').getAttribute('aria-disabled')).toBeNull());
});

test('an answer to an earlier opening of the menu is dropped', async () => {
  const answers = [];
  getDeleted.mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 0, onRenamed: vi.fn(), onDeleted: vi.fn() });
  await fireEvent.click(edit());
  await screen.findByRole('menu');
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(edit());
  await screen.findByRole('menu');
  answers[1]([]);
  await vi.waitFor(() => expect(item('Delete…').getAttribute('aria-disabled')).toBeNull());
  answers[0]([binned(9, 'U3')]);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(item('Delete…').getAttribute('aria-disabled')).toBeNull();
});

test('the delete confirmation says only what goes with the species', async () => {
  render(CustomSpeciesEdit, { taxon: sankara(), photoCount: 0, onRenamed: vi.fn(), onDeleted: vi.fn() });
  await openMenu();
  await vi.waitFor(() => expect(item('Delete…').getAttribute('aria-disabled')).toBeNull());
  await fireEvent.click(item('Delete…'));
  const dialog = await screen.findByRole('dialog', { name: 'Delete Broad-banded Sailer?' });
  expect(dialog.querySelector('p').textContent).toBe('It has no photos. Its search words are removed with it.');
});
