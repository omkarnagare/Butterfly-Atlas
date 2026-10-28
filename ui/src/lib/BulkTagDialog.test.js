import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import BulkTagDialog from './BulkTagDialog.svelte';

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
