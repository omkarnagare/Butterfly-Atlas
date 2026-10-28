import { expect, test } from 'vitest';
import { outlineSvg } from './butterfly.js';

test('the outline butterfly is four tinted wings and no body, for species not yet photographed', () => {
  const box = document.createElement('div');
  box.innerHTML = outlineSvg;
  const svg = box.firstElementChild;

  expect(svg.namespaceURI).toBe('http://www.w3.org/2000/svg');
  expect(svg.getAttribute('class')).toBe('bf-out');
  expect(svg.getAttribute('aria-hidden')).toBe('true');
  expect(svg.querySelectorAll('path.o')).toHaveLength(4);
  expect(svg.querySelectorAll('ellipse, circle')).toHaveLength(0);
});
