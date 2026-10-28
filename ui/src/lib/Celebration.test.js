import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import Celebration from './Celebration.svelte';

afterEach(() => {
  delete Element.prototype.animate;
});

const taxon = { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae' };

test('names the new species, its number and the milestone; Keep going closes it', async () => {
  const onClose = vi.fn();
  render(Celebration, { taxon, count: 10, milestone: 10, onClose });

  const dialog = screen.getByRole('dialog', { name: 'New species in your atlas' });
  expect(dialog.textContent).toContain('Plain Tiger is species number 10.');
  expect(screen.getByText('Milestone reached: 10 species')).toBeTruthy();
  const button = screen.getByRole('button', { name: 'Keep going' });
  expect(document.activeElement).toBe(button);
  await fireEvent.click(button);
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledTimes(2);
});

test('butterflies fly out when motion is allowed and are gone after', () => {
  window.matchMedia = () => ({ matches: false });
  Element.prototype.animate = vi.fn(() => ({}));
  const { unmount } = render(Celebration, { taxon, count: 3, onClose: vi.fn() });

  expect(document.querySelectorAll('.flyer')).toHaveLength(18);
  expect(document.querySelector('.flyer').getAttribute('style')).toContain('--fam: var(--f-nym)');
  unmount();
  expect(document.querySelectorAll('.flyer')).toHaveLength(0);
});

test('focus goes back to the Add photos button after Keep going', async () => {
  const add = document.createElement('button');
  add.setAttribute('data-add-photos', '');
  document.body.append(add);
  const { unmount } = render(Celebration, { taxon, count: 3, onClose: vi.fn() });

  unmount();
  await Promise.resolve();
  expect(document.activeElement).toBe(add);
  add.remove();
});

test('an import celebrates all its new species at once, and gives focus back where it is told', async () => {
  const back = document.createElement('button');
  document.body.append(back);
  const { unmount } = render(Celebration, { count: 312, added: 27, milestone: 250, returnFocus: () => back, onClose: vi.fn() });

  const dialog = screen.getByRole('dialog', { name: 'New species in your atlas' });
  expect(dialog.textContent).toContain(`27 new species photographed! Your atlas now has ${(312).toLocaleString()} species.`);
  expect(screen.getByText('Milestone reached: 250 species')).toBeTruthy();
  unmount();
  await Promise.resolve();
  expect(document.activeElement).toBe(back);
  back.remove();
});

test('the milestone badge sits on a small daisy (Meadow §5); without a milestone there is none', () => {
  const { unmount } = render(Celebration, { taxon, count: 10, milestone: 10, onClose: vi.fn() });
  const badge = screen.getByText('Milestone reached: 10 species');
  const flower = badge.querySelector('svg.daisy');
  expect(flower.getAttribute('aria-hidden')).toBe('true');
  expect(flower.querySelectorAll('ellipse')).toHaveLength(12);
  unmount();

  render(Celebration, { taxon, count: 11, onClose: vi.fn() });
  expect(document.querySelector('svg.daisy')).toBeNull();
});
