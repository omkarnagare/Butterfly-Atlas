import { fireEvent, render, screen } from '@testing-library/svelte';
import { createRawSnippet } from 'svelte';
import { expect, test, vi } from 'vitest';
import Dialog from './Dialog.svelte';

test('is a labelled dialog that takes focus and closes with Escape', async () => {
  const onClose = vi.fn();
  const children = createRawSnippet(() => ({ render: () => '<p><button>Yes</button></p>' }));
  render(Dialog, { title: 'Delete for good?', onClose, children });

  expect(screen.getByRole('dialog', { name: 'Delete for good?' })).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Yes' }));
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();
});
