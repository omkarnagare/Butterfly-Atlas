import { fireEvent, render, screen } from '@testing-library/svelte';
import { createRawSnippet } from 'svelte';
import { afterEach, expect, test, vi } from 'vitest';
import Popover from './Popover.svelte';

const body = (html) => createRawSnippet(() => ({ render: () => html }));

const { innerWidth, innerHeight } = window;
afterEach(() => {
  window.innerWidth = innerWidth;
  window.innerHeight = innerHeight;
  vi.restoreAllMocks();
});

test('is a labelled dialog in modal(): it takes focus, the page behind is inert, Escape closes it and focus goes back to the opener', async () => {
  const opener = document.createElement('button');
  opener.textContent = 'Taxa';
  document.body.append(opener);
  opener.focus();
  const onClose = vi.fn();
  const { container, unmount } = render(Popover, { props: { title: 'Taxa', anchor: opener, onClose, children: body('<div><input aria-label="Find"></div>') } });

  expect(screen.getByRole('dialog', { name: 'Taxa' })).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('textbox', { name: 'Find' }));
  expect(opener.hasAttribute('inert')).toBe(true);
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledOnce();

  unmount();
  await Promise.resolve();
  expect(opener.hasAttribute('inert')).toBe(false);
  expect(document.activeElement).toBe(opener);
  opener.remove();
  expect(container).toBeTruthy();
});

test('a click outside closes it; a click inside does not', async () => {
  const onClose = vi.fn();
  const { container } = render(Popover, { title: 'Tags', onClose, children: body('<div><button>egg</button></div>') });
  await fireEvent.click(screen.getByRole('button', { name: 'egg' }));
  expect(onClose).not.toHaveBeenCalled();
  await fireEvent.click(container.ownerDocument.querySelector('.pop-bg'));
  expect(onClose).toHaveBeenCalledOnce();
});

test('only the top popover answers Escape', async () => {
  const lower = vi.fn();
  const upper = vi.fn();
  render(Popover, { title: 'Lower', onClose: lower, children: body('<div><button>a</button></div>') });
  render(Popover, { title: 'Upper', onClose: upper, children: body('<div><button>b</button></div>') });
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(upper).toHaveBeenCalledOnce();
  expect(lower).not.toHaveBeenCalled();
});

test('sits under its opener, kept inside the window', () => {
  const opener = document.createElement('button');
  document.body.append(opener);
  opener.getBoundingClientRect = () => ({ left: 900, right: 960, top: 100, bottom: 130, width: 60, height: 30 });
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(360);
  window.innerWidth = 1024;
  render(Popover, { props: { title: 'Place', anchor: opener, onClose: () => {}, children: body('<div><button>x</button></div>') } });
  const style = document.querySelector('.pop').getAttribute('style');
  expect(style).toContain('top: 138px');
  expect(style).toContain('left: 648px');
  opener.remove();
});

test('kept inside the window from top to bottom too: moved up when there is no room under its opener, never past the top', async () => {
  const opener = document.createElement('button');
  document.body.append(opener);
  opener.getBoundingClientRect = () => ({ left: 100, right: 160, top: 670, bottom: 700, width: 60, height: 30 });
  vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(360);
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(300);
  window.innerWidth = 1024;
  window.innerHeight = 800;
  render(Popover, { props: { title: 'Place', anchor: opener, onClose: () => {}, children: body('<div><button>x</button></div>') } });
  let style = document.querySelector('.pop').getAttribute('style');
  expect(style).toContain('top: 484px');
  expect(style).toContain('--room: 300px');

  // a short window: at the top, as tall as the window allows
  window.innerHeight = 250;
  await fireEvent(window, new Event('resize'));
  style = document.querySelector('.pop').getAttribute('style');
  expect(style).toContain('top: 16px');
  expect(style).toContain('--room: 218px');
  opener.remove();
});

test('Escape while composing text (an input method) does not close it', async () => {
  const onClose = vi.fn();
  render(Popover, { title: 'Taxa', onClose, children: body('<div><input aria-label="Find"></div>') });
  await fireEvent.keyDown(window, { key: 'Escape', isComposing: true });
  expect(onClose).not.toHaveBeenCalled();
});
