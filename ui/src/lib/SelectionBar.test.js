import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import SelectionBar from './SelectionBar.svelte';

test('says how many are selected and offers the actions once one is', async () => {
  const handlers = { onAll: vi.fn(), onTag: vi.fn(), onMove: vi.fn(), onPlace: vi.fn(), onRemovePlace: vi.fn(), onDelete: vi.fn(), onCancel: vi.fn() };
  const { rerender } = render(SelectionBar, { count: 0, total: 3, ...handlers });

  expect(screen.getByRole('group', { name: 'Selected photos' }).textContent).toContain('0 photos selected');
  expect(screen.getByRole('button', { name: 'Delete' }).disabled).toBe(true);

  await rerender({ count: 3, total: 3, ...handlers });
  expect(screen.getByText('3 photos selected')).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Select all' })).toBeNull();
  for (const name of ['Tag', 'Move', 'Delete', 'Cancel']) await fireEvent.click(screen.getByRole('button', { name }));
  expect([handlers.onTag, handlers.onMove, handlers.onDelete, handlers.onCancel].every((h) => h.mock.calls.length === 1)).toBe(true);
});

test('Place… and Remove place sit between Move and Delete; while busy they do nothing', async () => {
  const handlers = { onAll: vi.fn(), onTag: vi.fn(), onMove: vi.fn(), onPlace: vi.fn(), onRemovePlace: vi.fn(), onDelete: vi.fn(), onCancel: vi.fn() };
  const { rerender } = render(SelectionBar, { count: 2, total: 3, ...handlers });

  const names = screen.getAllByRole('button').map((b) => b.textContent);
  expect(names).toEqual(['Select all', 'Tag', 'Move', 'Place…', 'Remove place', 'Delete', 'Cancel']);
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove place' }));
  expect(handlers.onPlace).toHaveBeenCalledOnce();
  expect(handlers.onRemovePlace).toHaveBeenCalledOnce();

  await rerender({ count: 2, total: 3, busy: true, ...handlers });
  expect(screen.getByRole('button', { name: 'Place…' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(screen.getByRole('button', { name: 'Remove place' }));
  expect(handlers.onRemovePlace).toHaveBeenCalledOnce();

  await rerender({ count: 0, total: 3, ...handlers });
  expect(screen.getByRole('button', { name: 'Place…' }).disabled).toBe(true);
  expect(screen.getByRole('button', { name: 'Remove place' }).disabled).toBe(true);
});
