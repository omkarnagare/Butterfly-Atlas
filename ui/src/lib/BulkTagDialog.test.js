import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import BulkTagDialog from './BulkTagDialog.svelte';
import { createTag } from './api.js';

vi.mock('./api.js', () => ({ createTag: vi.fn() }));

beforeEach(() => vi.resetAllMocks());

const tags = [
  { id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 2 },
  { id: 6, key: 'male', label: 'male', source: 'builtin', photoCount: 1 },
  { id: 7, key: 'female', label: 'female', source: 'builtin', photoCount: 0 },
];
const photos = [{ id: 1, tags: ['larva', 'male'] }, { id: 2, tags: ['larva'] }];

test('shows which tags all, some or none of the photos carry', () => {
  render(BulkTagDialog, { photos, tags, onApply: vi.fn(), onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Tag 2 photos' })).toBeTruthy();
  expect(screen.getByRole('checkbox', { name: 'larva' }).getAttribute('aria-checked')).toBe('true');
  expect(screen.getByRole('checkbox', { name: 'male' }).getAttribute('aria-checked')).toBe('mixed');
  expect(screen.getByRole('checkbox', { name: 'female' }).getAttribute('aria-checked')).toBe('false');
  expect(screen.getByRole('button', { name: 'Apply' }).disabled).toBe(true);
});

test('sends only what changed', async () => {
  const onApply = vi.fn(async () => {});
  render(BulkTagDialog, { photos, tags, onApply, onClose: vi.fn() });

  await fireEvent.click(screen.getByRole('checkbox', { name: 'male' }));
  await fireEvent.click(screen.getByRole('checkbox', { name: 'larva' }));
  await fireEvent.click(screen.getByRole('checkbox', { name: 'female' }));
  await fireEvent.click(screen.getByRole('checkbox', { name: 'female' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Apply' }));

  expect(onApply).toHaveBeenCalledWith(['male'], ['larva']);
});

const mine = [
  { id: 20, key: 'basking', label: 'basking', source: 'user', photoCount: 1 },
  { id: 21, key: 'wet_sand', label: 'wet_sand', source: 'user', photoCount: 0 },
];
const mixed = [{ id: 1, tags: ['larva', 'basking'] }, { id: 2, tags: ['larva'] }];

test('a user tag some of the photos have shows (mixed) as a chosen one; the user\'s other tags are found by typing', async () => {
  const onApply = vi.fn(async () => {});
  render(BulkTagDialog, { photos: mixed, tags: [...tags, ...mine], onApply, onClose: vi.fn() });

  const group = within(screen.getByRole('group', { name: 'Tags' }));
  expect(group.getByRole('checkbox', { name: 'basking' }).getAttribute('aria-checked')).toBe('mixed');
  expect(group.queryByRole('checkbox', { name: 'wet_sand' })).toBeNull();

  await fireEvent.input(screen.getByRole('searchbox', { name: 'Find or add a tag' }), { target: { value: 'wet' } });
  await fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' });
  expect(group.getByRole('checkbox', { name: 'wet_sand' }).getAttribute('aria-checked')).toBe('true');
  await fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
  expect(onApply).toHaveBeenCalledWith(['wet_sand'], []);
});

test('a tag created here: the screen reads its tags again, then the new tag is marked "all" and sent on Apply', async () => {
  const roosting = { id: 22, key: 'roosting', label: 'roosting', source: 'user', photoCount: 0 };
  createTag.mockResolvedValue(roosting);
  const onApply = vi.fn(async () => {});
  const view = render(BulkTagDialog, { photos, tags, onApply, onClose: vi.fn() });
  const onTagsChanged = vi.fn(async () => view.rerender({ tags: [...tags, roosting] }));
  await view.rerender({ onTagsChanged });

  await fireEvent.input(screen.getByRole('searchbox'), { target: { value: 'Roosting' } });
  await fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' });

  const group = within(screen.getByRole('group', { name: 'Tags' }));
  await vi.waitFor(() => expect(group.getByRole('checkbox', { name: 'roosting' }).getAttribute('aria-checked')).toBe('true'));
  const chip = group.getByRole('checkbox', { name: 'roosting' });
  expect(onTagsChanged).toHaveBeenCalledWith(roosting);
  await fireEvent.click(chip);
  expect(chip.getAttribute('aria-checked')).toBe('false');
  // on none of them before, none now: nothing to send
  expect(screen.getByRole('button', { name: 'Apply' }).disabled).toBe(true);
  await fireEvent.click(chip);
  await fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
  expect(onApply).toHaveBeenCalledWith(['roosting'], []);
});

test('a tag another window made, found after the re-read, starts from what the photos carry', async () => {
  const onApply = vi.fn(async () => {});
  const view = render(BulkTagDialog, { photos, tags, onApply, onClose: vi.fn() });
  await view.rerender({ tags: [...tags, mine[1]] });

  await fireEvent.input(screen.getByRole('searchbox'), { target: { value: 'wet sand' } });
  await fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' });
  await fireEvent.click(screen.getByRole('checkbox', { name: 'wet_sand' }));
  expect(screen.getByRole('button', { name: 'Apply' }).disabled).toBe(true);
});

test('while Apply runs the tags do nothing', async () => {
  let finish;
  const onApply = vi.fn(() => new Promise((resolve) => { finish = resolve; }));
  render(BulkTagDialog, { photos, tags, onApply, onClose: vi.fn() });

  await fireEvent.click(screen.getByRole('checkbox', { name: 'female' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Apply' }));
  const chip = screen.getByRole('checkbox', { name: 'male' });
  expect(chip.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(chip);
  expect(chip.getAttribute('aria-checked')).toBe('mixed');
  finish();
});

test('Esc in the box with text clears it and keeps the dialog; in the empty box it closes the dialog', async () => {
  const onClose = vi.fn();
  render(BulkTagDialog, { photos, tags, onApply: vi.fn(), onClose });
  const box = screen.getByRole('searchbox');
  await fireEvent.input(box, { target: { value: 'lar' } });
  await fireEvent.keyDown(box, { key: 'Escape' });
  expect(box.value).toBe('');
  expect(onClose).not.toHaveBeenCalled();
  await fireEvent.keyDown(box, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();
});

test('while a tag is being created, Apply waits: it is aria-disabled and sends nothing', async () => {
  let finish;
  createTag.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
  const onApply = vi.fn(async () => {});
  render(BulkTagDialog, { photos, tags, onApply, onClose: vi.fn() });
  await fireEvent.click(screen.getByRole('checkbox', { name: 'female' }));
  await fireEvent.input(screen.getByRole('searchbox'), { target: { value: 'roosting' } });
  await fireEvent.keyDown(screen.getByRole('searchbox'), { key: 'Enter' });

  const apply = screen.getByRole('button', { name: 'Apply' });
  expect(apply.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(apply);
  expect(onApply).not.toHaveBeenCalled();

  finish({ id: 22, key: 'roosting', label: 'roosting', source: 'user', photoCount: 0 });
  await vi.waitFor(() => expect(apply.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(apply);
  expect(onApply).toHaveBeenCalledOnce();
});
