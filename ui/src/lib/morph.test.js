import { expect, test, vi } from 'vitest';
import { morphInto, rememberMorph } from './morph.js';

const box = (left, top, width, height) => () => ({ left, top, width, height });

test('grows the new header out of the clicked card', () => {
  window.matchMedia = () => ({ matches: false });
  const card = document.createElement('span');
  card.getBoundingClientRect = box(100, 300, 200, 40);
  const header = document.createElement('h1');
  header.getBoundingClientRect = box(20, 80, 400, 80);
  header.animate = vi.fn();

  rememberMorph(card);
  morphInto(header);

  expect(header.animate).toHaveBeenCalledOnce();
  expect(header.animate.mock.calls[0][0][0].transform).toBe('translate(80px, 220px) scale(0.5, 0.5)');
});

test('does nothing when motion is reduced or nothing was clicked', () => {
  const card = document.createElement('span');
  card.getBoundingClientRect = box(100, 300, 200, 40);
  const header = document.createElement('h1');
  header.getBoundingClientRect = box(20, 80, 400, 80);
  header.animate = vi.fn();

  rememberMorph(card);
  morphInto(header);
  morphInto(header);

  expect(header.animate).not.toHaveBeenCalled();
});
