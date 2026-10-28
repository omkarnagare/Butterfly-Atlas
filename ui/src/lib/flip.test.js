import { expect, test, vi } from 'vitest';
import { fadeOut, growFrom, measure, play, rememberRect, takeRect } from './flip.js';

const box = (left, top, width = 100, height = 100) => () => ({ left, top, width, height });

function grid(...keys) {
  const container = document.createElement('div');
  for (const key of keys) {
    const child = document.createElement('a');
    child.dataset.key = key;
    child.animate = vi.fn();
    container.append(child);
  }
  return container;
}

test('moved photos glide from their old place and new ones fade in', () => {
  window.matchMedia = () => ({ matches: false });
  const container = grid('1', '2');
  const [one, two] = container.children;
  one.getBoundingClientRect = box(0, 0);
  two.getBoundingClientRect = box(120, 0);
  const before = measure(container);

  one.remove();
  two.getBoundingClientRect = box(0, 0);
  const three = grid('3').firstElementChild;
  three.getBoundingClientRect = box(120, 0);
  container.append(three);
  play(container, before);

  expect(two.animate.mock.calls[0][0][0].transform).toBe('translate(120px, 0px)');
  expect(three.animate.mock.calls[0][0][0]).toEqual({ opacity: 0, transform: 'scale(.9)' });
});

test('nothing moves when motion is reduced', () => {
  const container = grid('1');
  container.firstElementChild.getBoundingClientRect = box(0, 0);
  const before = measure(container);
  container.firstElementChild.getBoundingClientRect = box(50, 50);

  play(container, before);

  expect(container.firstElementChild.animate).not.toHaveBeenCalled();
});

test('the viewer photo grows out of the clicked thumbnail', () => {
  window.matchMedia = () => ({ matches: false });
  const thumb = document.createElement('img');
  thumb.getBoundingClientRect = box(100, 100, 200, 150);
  const photo = document.createElement('img');
  photo.getBoundingClientRect = box(0, 0, 800, 600);
  photo.animate = vi.fn();

  rememberRect(thumb);
  growFrom(photo, takeRect());

  expect(photo.animate.mock.calls[0][0][0].transform).toBe('translate(-200px, -125px) scale(0.25)');
  expect(takeRect()).toBeNull();
});

test('photos far outside the window just land', () => {
  window.matchMedia = () => ({ matches: false });
  const container = grid('1', '2');
  const [near, far] = container.children;
  near.getBoundingClientRect = box(0, 0);
  far.getBoundingClientRect = box(0, 5000);
  const before = measure(container);
  near.getBoundingClientRect = box(120, 0);
  far.getBoundingClientRect = box(120, 5200);

  play(container, before);

  expect(near.animate).toHaveBeenCalledOnce();
  expect(far.animate).not.toHaveBeenCalled();
});

test('a click while photos are still gliding stops the old glide first', () => {
  window.matchMedia = () => ({ matches: false });
  const container = grid('1');
  const child = container.firstElementChild;
  const running = { cancel: vi.fn() };
  child.getAnimations = vi.fn(() => [running]);
  child.getBoundingClientRect = box(0, 0);
  const before = measure(container);
  child.getBoundingClientRect = box(50, 0);

  play(container, before);

  expect(running.cancel).toHaveBeenCalledOnce();
  expect(child.animate).toHaveBeenCalledOnce();
});

test('a grid\'s tiles inside its rows can be measured and played as the items given', () => {
  window.matchMedia = () => ({ matches: false });
  const container = document.createElement('div');
  const row = grid('1', '2');
  container.append(row);
  const [one, two] = row.children;
  one.getBoundingClientRect = box(0, 0);
  two.getBoundingClientRect = box(120, 0);
  const before = measure(container, container.querySelectorAll('[data-key]'));
  expect([...before.keys()]).toEqual(['1', '2']);

  one.remove();
  two.getBoundingClientRect = box(0, 0);
  play(container, before, container.querySelectorAll('[data-key]'));
  expect(two.animate.mock.calls[0][0][0].transform).toBe('translate(120px, 0px)');
});

test('leaving tiles fade out before they go; at once under reduced motion', async () => {
  const [a, b] = grid('1', '2').children;
  let finish;
  a.animate = vi.fn(() => ({ finished: new Promise((resolve) => { finish = resolve; }) }));
  b.animate = vi.fn(() => ({ finished: Promise.resolve() }));
  await fadeOut([a, b]);
  expect(a.animate).not.toHaveBeenCalled();

  window.matchMedia = () => ({ matches: false });
  let done = false;
  const out = fadeOut([a, b]).then(() => { done = true; });
  expect(a.animate.mock.calls[0][0]).toEqual([{ opacity: 1, transform: 'none' }, { opacity: 0, transform: 'scale(.85)' }]);
  expect(b.animate).toHaveBeenCalled();
  await Promise.resolve();
  expect(done).toBe(false);
  finish();
  await out;
  expect(done).toBe(true);
});
