import { render, screen } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import LifeCycle from './LifeCycle.svelte';

test('lights the stages that are photographed and says which are missing', () => {
  const { container } = render(LifeCycle, { stages: { egg: true, larva: true, pupa: false, adult: false } });

  expect(screen.getByRole('img', { name: 'Life stages photographed: egg, larva. Not yet: pupa, adult.' })).toBeTruthy();
  expect([...container.querySelectorAll('.stage.on')].map((s) => s.textContent)).toEqual(['Egg', 'Larva']);
  expect(container.querySelectorAll('.lcl.on')).toHaveLength(1);
  expect(screen.queryByText('Full life cycle photographed')).toBeNull();
});

test('awards the badge when every stage is photographed', () => {
  render(LifeCycle, { stages: { egg: true, larva: true, pupa: true, adult: true } });

  expect(screen.getByRole('img', { name: 'Life stages photographed: egg, larva, pupa, adult.' })).toBeTruthy();
  expect(screen.getByText('Full life cycle photographed')).toBeTruthy();
});
