import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import FileTypePopover from './FileTypePopover.svelte';
import { EMPTY } from './filter.js';

test('the File type popover: JPEG, PNG, TIFF, WebP and RAW as toggles, any number at once, each change at once (Phase 7a)', async () => {
  const onChange = vi.fn();
  const { rerender } = render(FileTypePopover, { filter: { ...EMPTY, types: ['raw'] }, onChange, onClose: vi.fn() });
  const group = within(screen.getByRole('dialog', { name: 'File type' })).getByRole('group', { name: 'File type' });
  const toggles = within(group).getAllByRole('button');
  expect(toggles.map((b) => [b.textContent, b.getAttribute('aria-pressed')])).toEqual([
    ['JPEG', 'false'], ['PNG', 'false'], ['TIFF', 'false'], ['WebP', 'false'], ['RAW', 'true'], ['HEIC', 'false'],
  ]);
  await fireEvent.click(toggles[0]);
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, types: ['raw', 'jpeg'] });
  await rerender({ filter: { ...EMPTY, types: ['raw', 'jpeg'] } });
  await fireEvent.click(screen.getByRole('button', { name: 'RAW' }));
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, types: ['jpeg'] });
  expect(screen.getByText('None chosen: every file type.')).toBeTruthy();
});
