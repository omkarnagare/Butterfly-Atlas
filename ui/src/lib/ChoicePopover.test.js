import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import ChoicePopover from './ChoicePopover.svelte';

const STATUS = [{ value: 'all', label: 'All' }, { value: 'photographed', label: 'Photographed' }, { value: 'not', label: 'Not photographed' }];

test('a radio group of the choices, the current one checked and focused; arrows choose, a click chooses and closes', async () => {
  const onPick = vi.fn();
  const onClose = vi.fn();
  render(ChoicePopover, { title: 'Photo status', options: STATUS, value: 'photographed', onPick, onClose });
  expect(screen.getByRole('dialog', { name: 'Photo status' })).toBeTruthy();
  const group = screen.getByRole('radiogroup', { name: 'Photo status' });
  const radios = screen.getAllByRole('radio');
  expect(radios.map((r) => [r.textContent, r.getAttribute('aria-checked'), r.tabIndex])).toEqual([
    ['All', 'false', -1], ['Photographed', 'true', 0], ['Not photographed', 'false', -1],
  ]);
  await vi.waitFor(() => expect(document.activeElement).toBe(radios[1]));

  await fireEvent.keyDown(group, { key: 'ArrowDown' });
  expect(onPick).toHaveBeenCalledWith('not');
  expect(document.activeElement).toBe(radios[2]);
  await fireEvent.keyDown(group, { key: 'ArrowDown' });
  expect(onPick).toHaveBeenLastCalledWith('all');
  expect(onClose).not.toHaveBeenCalled();

  await fireEvent.click(radios[2]);
  expect(onPick).toHaveBeenLastCalledWith('not');
  expect(onClose).toHaveBeenCalledOnce();
});
