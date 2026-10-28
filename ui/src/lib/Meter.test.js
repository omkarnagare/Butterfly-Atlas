import { render } from '@testing-library/svelte';
import { expect, test } from 'vitest';
import Meter from './Meter.svelte';

test('fills in proportion and stays empty when there is nothing to count', async () => {
  const { container, rerender } = render(Meter, { got: 1, total: 4 });
  expect(container.querySelector('.meter i').style.width).toBe('25%');
  await rerender({ got: 0, total: 0 });
  expect(container.querySelector('.meter i').style.width).toBe('0%');
});

test('a first photo shows on the bar however long the list', async () => {
  const { container, rerender } = render(Meter, { got: 1, total: 1139 });
  expect(container.querySelector('.meter i').classList.contains('some')).toBe(true);
  await rerender({ got: 0, total: 1139 });
  expect(container.querySelector('.meter i').classList.contains('some')).toBe(false);
});
