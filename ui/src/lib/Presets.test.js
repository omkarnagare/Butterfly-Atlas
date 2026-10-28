import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import Presets from './Presets.svelte';
import { EMPTY } from './filter.js';

const filter = { ...EMPTY, taxa: ['A12'], tags: ['larva'], tagMode: 'all', places: ['P20'], status: 'photographed', stages: null };

test('the presets line sets the filter in one click, keeping taxa and places except Clear', async () => {
  const onChange = vi.fn();
  render(Presets, { filter, onChange });
  const line = screen.getByRole('group', { name: 'Presets' });
  expect([...line.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Full checklist', 'Not yet photographed', 'Missing life stages', 'Clear']);

  await fireEvent.click(screen.getByRole('button', { name: 'Full checklist' }));
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, taxa: ['A12'], places: ['P20'] });
  await fireEvent.click(screen.getByRole('button', { name: 'Not yet photographed' }));
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, taxa: ['A12'], places: ['P20'], status: 'not' });
  await fireEvent.click(screen.getByRole('button', { name: 'Missing life stages' }));
  expect(onChange).toHaveBeenLastCalledWith({ ...EMPTY, taxa: ['A12'], places: ['P20'], stages: 'missing' });
  await fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
  expect(onChange).toHaveBeenLastCalledWith(EMPTY);
});

test('Clear is unavailable while there is nothing to clear', () => {
  render(Presets, { filter: EMPTY, onChange: vi.fn() });
  expect(screen.getByRole('button', { name: 'Clear' }).getAttribute('aria-disabled')).toBe('true');
});
