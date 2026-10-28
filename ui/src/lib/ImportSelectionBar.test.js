import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import ImportSelectionBar from './ImportSelectionBar.svelte';

const handlers = () => ({ onAssign: vi.fn(), onTags: vi.fn(), onPlace: vi.fn(), onRemovePlace: vi.fn(), onSkip: vi.fn(), onClear: vi.fn() });

test('says how many are selected and offers the actions with their keys', async () => {
  const h = handlers();
  render(ImportSelectionBar, { count: 2140, ...h });

  expect(screen.getByRole('group', { name: 'Selected photos' }).textContent).toContain(`${(2140).toLocaleString()} photos selected`);
  for (const name of [/^Assign species/, /^Tags/, /^Skip/, 'Clear']) await fireEvent.click(screen.getByRole('button', { name }));
  expect([h.onAssign, h.onTags, h.onSkip, h.onClear].every((f) => f.mock.calls.length === 1)).toBe(true);
  expect(screen.getByRole('button', { name: /^Assign species/ }).getAttribute('aria-keyshortcuts')).toBe('S');
});

test('when every photo is skipped it offers to unskip; while busy or filing the actions do nothing but Clear still works', async () => {
  const h = handlers();
  const { rerender } = render(ImportSelectionBar, { count: 1, allSkipped: true, busy: true, ...h });
  const skip = screen.getByRole('button', { name: /^Don't skip/ });
  expect(skip.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(skip);
  expect(h.onSkip).not.toHaveBeenCalled();

  await rerender({ count: 1, blocked: 'Wait for filing to finish or pause it.', ...h });
  const assign = screen.getByRole('button', { name: /^Assign species/ });
  expect(assign.getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(assign.getAttribute('aria-describedby')).textContent).toBe('Wait for filing to finish or pause it.');
  await fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
  expect(h.onClear).toHaveBeenCalledOnce();
});

test('Place… and Remove place come after Tags; they do nothing while busy', async () => {
  const h = handlers();
  const { rerender } = render(ImportSelectionBar, { count: 3, ...h });

  expect(screen.getAllByRole('button').map((b) => b.textContent.replace(/\s+/g, ' ').trim()))
    .toEqual(['Assign species S', 'Tags T', 'Place…', 'Remove place', 'Skip X', 'Clear']);
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Remove place' }));
  expect(h.onPlace).toHaveBeenCalledOnce();
  expect(h.onRemovePlace).toHaveBeenCalledOnce();

  await rerender({ count: 3, busy: true, ...h });
  expect(screen.getByRole('button', { name: 'Remove place' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(screen.getByRole('button', { name: 'Place…' }));
  expect(h.onPlace).toHaveBeenCalledOnce();
});
