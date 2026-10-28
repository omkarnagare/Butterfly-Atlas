import { readFileSync } from 'node:fs';
import { expect, test } from 'vitest';
import { reserveBelow } from './reserve.js';

test('a bottom bar notes its height for the footer while it is there', () => {
  const bar = document.createElement('div');
  Object.defineProperty(bar, 'offsetHeight', { value: 58 });
  const action = reserveBelow(bar);
  expect(document.documentElement.style.getPropertyValue('--selbar-h')).toBe('58px');
  action.destroy();
  expect(document.documentElement.style.getPropertyValue('--selbar-h')).toBe('');
});

test('the footer leaves room under a selection bar, so its lines are not covered', () => {
  const css = readFileSync(`${process.cwd()}/src/styles/atlas.css`, 'utf8');
  expect(css).toMatch(/html:has\(\.selbar\) \.foot \{ padding-bottom: calc\(var\(--selbar-h, 72px\) \+ 34px\); \}/);
});

test('both selection bars note their height', () => {
  for (const file of ['SelectionBar.svelte', 'ImportSelectionBar.svelte']) {
    expect(readFileSync(`${process.cwd()}/src/lib/${file}`, 'utf8')).toContain('<div class="selbar" use:reserveBelow');
  }
});
