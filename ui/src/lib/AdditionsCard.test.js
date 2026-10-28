import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import AdditionsCard from './AdditionsCard.svelte';

const additions = (got, total) => ({
  id: 'U:butterflies', rank: 'additions', name: 'Custom additions', common: null, family: null, source: 'system', species: { got, total },
});

test('empty, it invites adding a species and opens the Add dialog', async () => {
  const onAdd = vi.fn();
  const { container } = render(AdditionsCard, { taxon: additions(0, 0), i: 6, onAdd });

  const button = screen.getByRole('button', { name: /Custom additions/ });
  expect(button.textContent).toContain("Add a species the list doesn't have");
  expect(button.classList.contains('add-card')).toBe(true);
  expect(button.style.getPropertyValue('--i').trim()).toBe('6');
  expect(container.querySelector('.flock')).toBeNull();
  await fireEvent.click(button);
  expect(onAdd).toHaveBeenCalledWith(button);
});

test('with species, it counts them and opens the Custom additions page', () => {
  const { container, unmount } = render(AdditionsCard, { taxon: additions(1, 3), onAdd: vi.fn() });
  const link = screen.getByRole('link', { name: /Custom additions/ });
  expect(link.getAttribute('href')).toBe('#/explore/U%3Abutterflies');
  expect(container.querySelector('.ct').textContent).toBe('3 species · 1 photographed');
  expect(container.querySelector('.flock')).toBeNull();
  unmount();

  const { container: one } = render(AdditionsCard, { taxon: additions(0, 1), onAdd: vi.fn() });
  expect(one.querySelector('.ct').textContent).toBe('1 species · 0 photographed');
});

test('without a way to add (Home), even an empty card is a link to its page', () => {
  render(AdditionsCard, { taxon: additions(0, 0) });
  expect(screen.getByRole('link', { name: /Custom additions/ }).getAttribute('href')).toBe('#/explore/U%3Abutterflies');
});
