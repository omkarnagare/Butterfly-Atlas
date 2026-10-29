import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import TagsSheet from './TagsSheet.svelte';
import { createTag, deleteTag, getTags, renameTag } from './api.js';

vi.mock('./api.js', () => ({ createTag: vi.fn(), deleteTag: vi.fn(), getTags: vi.fn(), renameTag: vi.fn() }));

const larva = { id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 3 };
const puddling = { id: 10, key: 'mud_puddling', label: 'mud_puddling', source: 'user', photoCount: 4 };

beforeEach(() => {
  vi.resetAllMocks();
  getTags.mockResolvedValue([larva, puddling]);
});

test('lists built-in and user tags with their counts; built-in tags cannot be deleted', async () => {
  render(TagsSheet, { onClose: vi.fn() });

  expect(await screen.findByText('mud_puddling')).toBeTruthy();
  expect(screen.getByRole('dialog', { name: 'Tags' })).toBeTruthy();
  expect(screen.getByText('4 photos')).toBeTruthy();
  const builtinDelete = screen.getByRole('button', { name: 'Delete larva' });
  expect(builtinDelete.disabled).toBe(true);
  expect(builtinDelete.getAttribute('aria-describedby')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Delete mud_puddling' }).disabled).toBe(false);
});

test('adds a tag of your own', async () => {
  createTag.mockResolvedValue({ id: 11, key: 'nectaring', label: 'nectaring', source: 'user', photoCount: 0 });
  const onChanged = vi.fn();
  render(TagsSheet, { onChanged, onClose: vi.fn() });
  await screen.findByText('mud_puddling');
  getTags.mockResolvedValue([larva, puddling, { id: 11, key: 'nectaring', label: 'nectaring', source: 'user', photoCount: 0 }]);

  await fireEvent.input(screen.getByLabelText('New tag'), { target: { value: ' nectaring ' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  expect(createTag).toHaveBeenCalledWith('nectaring');
  expect(await screen.findByText('nectaring')).toBeTruthy();
  expect(onChanged).toHaveBeenCalledOnce();
});

test('renames in place: Enter saves, Escape leaves it as it was', async () => {
  renameTag.mockResolvedValue({ ...larva, label: 'caterpillar' });
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');

  await fireEvent.click(screen.getByRole('button', { name: 'Rename mud_puddling' }));
  await fireEvent.keyDown(screen.getByLabelText('New name for mud_puddling'), { key: 'Escape' });
  expect(screen.queryByLabelText('New name for mud_puddling')).toBeNull();
  expect(screen.getByRole('dialog', { name: 'Tags' })).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'Rename larva' }));
  const field = screen.getByLabelText('New name for larva');
  await fireEvent.input(field, { target: { value: 'caterpillar' } });
  await fireEvent.keyDown(field, { key: 'Enter' });
  expect(renameTag).toHaveBeenCalledWith(2, 'caterpillar');
});

test('deleting a user tag says how many photos carry it first', async () => {
  deleteTag.mockResolvedValue(4);
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');

  await fireEvent.click(screen.getByRole('button', { name: 'Delete mud_puddling' }));
  const confirm = screen.getByRole('dialog', { name: 'Delete “mud_puddling”?' });
  expect(confirm.textContent).toContain('It is on 4 photos. The photos stay; only the tag is taken off.');
  getTags.mockResolvedValue([larva]);
  await fireEvent.click(screen.getByRole('button', { name: 'Delete tag' }));

  expect(deleteTag).toHaveBeenCalledWith(10);
  await vi.waitFor(() => expect(screen.queryByText('mud_puddling')).toBeNull());
  expect(screen.getByText('No tags of your own yet.')).toBeTruthy();
});

test('a refused delete says why on the tag\'s own row, not under the Add field, and focus goes back to its Delete (A17)', async () => {
  const message = 'An import is filing photos with this tag. Wait for filing to finish, then delete it.';
  deleteTag.mockRejectedValue(Object.assign(new Error(message), { code: 'import_filing', status: 409 }));
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');

  await fireEvent.click(screen.getByRole('button', { name: 'Delete mud_puddling' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Delete tag' }));
  const said = await screen.findByText(message);
  expect(said.closest('li').textContent).toContain('mud_puddling');
  expect(said.getAttribute('role')).toBe('alert');
  const button = screen.getByRole('button', { name: 'Delete mud_puddling' });
  expect(button.getAttribute('aria-describedby')).toBe(said.id);
  await vi.waitFor(() => expect(document.activeElement).toBe(button));
  expect(screen.getByRole('textbox', { name: 'New tag' }).getAttribute('aria-invalid')).toBeNull();
});

test('a refused delete never shows on that tag\'s rename field (A17, review fix)', async () => {
  const message = 'An import is filing photos with this tag. Wait for filing to finish, then delete it.';
  deleteTag.mockRejectedValue(Object.assign(new Error(message), { code: 'import_filing', status: 409 }));
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');
  await fireEvent.click(screen.getByRole('button', { name: 'Delete mud_puddling' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Delete tag' }));
  await screen.findByText(message);

  await fireEvent.click(screen.getByRole('button', { name: 'Rename mud_puddling' }));
  const field = await screen.findByRole('textbox', { name: 'New name for mud_puddling' });
  expect(field.getAttribute('aria-invalid')).toBeNull();
  expect(field.getAttribute('aria-describedby')).toBeNull();
  expect(screen.queryByText(message)).toBeNull();
});

test('shows why a change was refused', async () => {
  // made in another window meanwhile: only the server knows
  createTag.mockRejectedValue(new Error('There is already a tag called "basking".'));
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');

  await fireEvent.input(screen.getByLabelText('New tag'), { target: { value: 'Basking' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  expect((await screen.findByRole('alert')).textContent).toBe('There is already a tag called "basking".');
});

test('a refusal is shown next to the field it belongs to', async () => {
  renameTag.mockRejectedValue(new Error('There is already a tag called "basking".'));
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');

  await fireEvent.click(screen.getByRole('button', { name: 'Rename mud_puddling' }));
  const field = screen.getByLabelText('New name for mud_puddling');
  await fireEvent.input(field, { target: { value: 'Basking' } });
  await fireEvent.keyDown(field, { key: 'Enter' });

  const alert = await screen.findByRole('alert');
  expect(field.getAttribute('aria-invalid')).toBe('true');
  expect(field.getAttribute('aria-describedby')).toBe(alert.id);
});

test('a change that went through is reported even if the list cannot be reloaded', async () => {
  createTag.mockResolvedValue({ id: 11, key: 'nectaring', label: 'nectaring', source: 'user', photoCount: 0 });
  const onChanged = vi.fn();
  render(TagsSheet, { onChanged, onClose: vi.fn() });
  await screen.findByText('mud_puddling');
  getTags.mockRejectedValue(new Error('offline'));

  await fireEvent.input(screen.getByLabelText('New tag'), { target: { value: 'nectaring' } });
  await fireEvent.click(screen.getByRole('button', { name: 'Add' }));

  await vi.waitFor(() => expect(onChanged).toHaveBeenCalledOnce());
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByText('mud_puddling')).toBeTruthy();
});

test('Unconfirmed is listed with the built-in tags; the note on them no longer names only the life-cycle strip', async () => {
  const unconfirmed = { id: 12, key: 'unconfirmed', label: 'unconfirmed', source: 'builtin', photoCount: 0 };
  getTags.mockResolvedValue([larva, unconfirmed, puddling]);
  render(TagsSheet, { onClose: vi.fn() });

  const del = await screen.findByRole('button', { name: 'Delete unconfirmed' });
  expect(del.disabled).toBe(true);
  expect(document.getElementById(del.getAttribute('aria-describedby')).textContent)
    .toBe('Built-in tags can be renamed but not deleted.');
  expect(screen.getByRole('button', { name: 'Rename unconfirmed' })).toBeTruthy();
});

test('the Add field shows the name it will be saved as, and why it cannot be, before Add', async () => {
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');
  const field = screen.getByLabelText('New tag');
  const add = screen.getByRole('button', { name: 'Add' });

  await fireEvent.input(field, { target: { value: 'nectaring' } });
  expect(screen.queryByText(/Saved as/)).toBeNull();
  expect(add.disabled).toBe(false);

  await fireEvent.input(field, { target: { value: 'Mud puddling site' } });
  const hint = screen.getByText('Saved as: mud_puddling_site');
  expect(field.getAttribute('aria-describedby')).toBe(hint.id);
  expect(add.disabled).toBe(false);

  await fireEvent.input(field, { target: { value: 'mud puddling on sand' } });
  expect(screen.getByText('Keep tag names to 3 words or fewer.')).toBeTruthy();
  expect(add.disabled).toBe(true);

  await fireEvent.input(field, { target: { value: 'Mud-Puddling' } });
  expect(screen.getByText('There is already a tag called "mud_puddling".')).toBeTruthy();
  expect(add.disabled).toBe(true);
  await fireEvent.submit(field.closest('form'));
  expect(createTag).not.toHaveBeenCalled();

  await fireEvent.input(field, { target: { value: '!!!' } });
  expect(screen.getByText('Type a name for the tag.')).toBeTruthy();
  expect(add.disabled).toBe(true);
});

test('the rename field says the same; its own name is not taken', async () => {
  render(TagsSheet, { onClose: vi.fn() });
  await screen.findByText('mud_puddling');
  await fireEvent.click(screen.getByRole('button', { name: 'Rename mud_puddling' }));
  const field = screen.getByLabelText('New name for mud_puddling');
  const save = screen.getByRole('button', { name: 'Save' });

  await fireEvent.input(field, { target: { value: 'Mud-Puddling' } });
  expect(screen.getByText('Saved as: mud_puddling')).toBeTruthy();
  expect(save.disabled).toBe(false);

  await fireEvent.input(field, { target: { value: 'larva' } });
  expect(screen.getByText('There is already a tag called "larva".')).toBeTruthy();
  expect(save.disabled).toBe(true);
  await fireEvent.keyDown(field, { key: 'Enter' });
  expect(renameTag).not.toHaveBeenCalled();
});

test('a tag of more than 3 words is marked on its row', async () => {
  const long = { id: 13, key: 'mud_puddling_on_wet_sand', label: 'mud_puddling_on_wet_sand', source: 'user', photoCount: 1 };
  getTags.mockResolvedValue([larva, puddling, long]);
  render(TagsSheet, { onClose: vi.fn() });

  const row = (await screen.findByText('mud_puddling_on_wet_sand')).closest('li');
  expect(row.textContent).toContain('over 3 words');
  expect(screen.getByText('mud_puddling').closest('li').textContent).not.toContain('over 3 words');
});
