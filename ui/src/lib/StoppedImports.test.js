import { render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import StoppedImports from './StoppedImports.svelte';

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});

const stop = (id, rootName, phase = 'filing', done = 1200, total = 2140) => ({ id, rootName, phase, done, total });

test('says each stopped import won\'t carry on by itself, with Continue import', () => {
  render(StoppedImports, { stops: [stop(3, 'Butterflies 2024'), stop(4, 'Card', 'reading', 812, 9870)] });

  const group = screen.getByRole('group', { name: 'Imports that stopped' });
  expect(group.textContent).toContain("Your import of Butterflies 2024 stopped when Butterfly Atlas closed (filing 1,200 of 2,140). It won't carry on by itself.");
  expect(group.textContent).toContain('(reading 812 of 9,870)');
  expect(within(group).getByRole('link', { name: 'Continue import of Butterflies 2024' }).getAttribute('href')).toBe('#/import/3');
  expect(within(group).getByRole('link', { name: 'Continue import of Card' }).getAttribute('href')).toBe('#/import/4');
});

test('shows at most three, then how many more', () => {
  render(StoppedImports, { stops: [1, 2, 3, 4, 5].map((id) => stop(id, `Folder ${id}`)), quiet: true });

  expect(screen.getAllByRole('link', { name: /^Continue import of/ })).toHaveLength(3);
  expect(screen.getByRole('link', { name: 'and 2 more' }).getAttribute('href')).toBe('#/import');
  expect(screen.getByRole('group').classList.contains('quiet')).toBe(true);
});

test('Continue import on its own grid asks that grid to continue, since the address would not change', async () => {
  const { fireEvent } = await import('@testing-library/svelte');
  const asked = vi.fn();
  window.addEventListener('atlas:continue-import', asked);
  window.location.hash = '#/import/3';
  render(StoppedImports, { stops: [stop(3, 'Butterflies 2024')], quiet: true });
  await fireEvent.click(screen.getByRole('link', { name: 'Continue import of Butterflies 2024' }));
  window.removeEventListener('atlas:continue-import', asked);
  expect(asked).toHaveBeenCalledOnce();
  expect(asked.mock.calls[0][0].detail).toEqual({ id: 3 });
});
