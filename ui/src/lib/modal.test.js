import { expect, test } from 'vitest';
import { anyModalOpen, isTopModal, modal } from './modal.js';

function page() {
  document.body.innerHTML = '<header><button id="opener">Open</button></header><main><p>text</p><div id="ov"><div id="inner"></div></div></main><div data-live></div>';
  return { opener: document.getElementById('opener'), overlay: document.getElementById('ov'), inner: document.getElementById('inner') };
}

test('makes the rest of the page inert while open and gives focus back after', async () => {
  const { opener, overlay } = page();
  opener.focus();

  const action = modal(overlay);
  expect(document.querySelector('header').hasAttribute('inert')).toBe(true);
  expect(document.querySelector('main p').hasAttribute('inert')).toBe(true);
  expect(document.querySelector('[data-live]').hasAttribute('inert')).toBe(false);
  expect(overlay.hasAttribute('inert')).toBe(false);
  expect(document.documentElement.style.overflow).toBe('hidden');
  expect(anyModalOpen()).toBe(true);

  overlay.querySelector('#inner').tabIndex = 0;
  overlay.querySelector('#inner').focus();
  action.destroy();
  await Promise.resolve();
  expect(document.querySelector('header').hasAttribute('inert')).toBe(false);
  expect(document.documentElement.style.overflow).toBe('');
  expect(document.activeElement).toBe(opener);
  expect(anyModalOpen()).toBe(false);
});

test('only the newest overlay is on top, and a chosen element can take focus back', async () => {
  const { overlay, inner } = page();
  const chosen = document.createElement('button');
  overlay.append(chosen);
  const outer = modal(overlay);
  const nested = modal(inner, { returnFocus: () => chosen });

  expect(isTopModal(inner)).toBe(true);
  expect(isTopModal(overlay)).toBe(false);
  nested.destroy();
  await Promise.resolve();
  expect(isTopModal(overlay)).toBe(true);
  expect(document.activeElement).toBe(chosen);
  outer.destroy();
});

/** Two sibling overlays, as two components opened by one update put them side by side. */
function twoOverlays() {
  document.body.innerHTML = '<header><button id="opener">Open</button></header>'
    + '<main><p>text</p><div id="a"><button id="a1">A one</button><button id="a2">A two</button></div><div id="b"><button id="b1">B</button></div></main>'
    + '<div data-live></div>';
  const $ = (id) => document.getElementById(id);
  return { opener: $('opener'), a: $('a'), b: $('b'), a2: $('a2'), b1: $('b1') };
}
const inert = (el) => el.closest('[inert]') !== null;
const flush = async () => {
  await Promise.resolve();
  await Promise.resolve();
};

for (const reverse of [false, true]) {
  test(`two overlays opened in one update: only the newest is live${reverse ? ' (opened the other way round)' : ''}`, async () => {
    const { opener, a, b, a2, b1 } = twoOverlays();
    opener.focus();
    const [first, second] = reverse ? [b, a] : [a, b];

    const lower = modal(first);
    const upper = modal(second);
    await flush();
    expect(isTopModal(second)).toBe(true);
    expect(inert(second)).toBe(false);
    expect(inert(first)).toBe(true);
    expect(inert(document.querySelector('header'))).toBe(true);
    expect(second.contains(document.activeElement)).toBe(true);

    upper.destroy();
    await flush();
    expect(inert(first)).toBe(false);
    expect(first.contains(document.activeElement)).toBe(true);
    expect(document.documentElement.style.overflow).toBe('hidden');

    // where focus was in the lower overlay is where it comes back to
    const back = reverse ? b1 : a2;
    back.focus();
    const again = modal(second);
    await flush();
    expect(second.contains(document.activeElement)).toBe(true);
    again.destroy();
    await flush();
    expect(document.activeElement).toBe(back);

    lower.destroy();
    await flush();
    expect(document.querySelector('[inert]')).toBeNull();
    expect(document.documentElement.style.overflow).toBe('');
    expect(document.activeElement).toBe(opener);
    expect(anyModalOpen()).toBe(false);
  });
}

test('closing the lower of two overlays leaves the top one live and keeps focus in it', async () => {
  const { opener, a, b, b1 } = twoOverlays();
  opener.focus();
  const lower = modal(a);
  const upper = modal(b);
  await flush();
  b1.focus();

  lower.destroy();
  await flush();
  expect(isTopModal(b)).toBe(true);
  expect(inert(b)).toBe(false);
  expect(inert(a)).toBe(true);
  expect(document.activeElement).toBe(b1);
  upper.destroy();
  await flush();
  expect(document.querySelector('[inert]')).toBeNull();
});

test('a chosen element outside the overlay now on top does not take focus: focus goes into that overlay', async () => {
  const { opener, a, b, a2, b1 } = twoOverlays();
  const lower = modal(a);
  a2.focus();
  const upper = modal(b);
  await flush();
  b1.focus();
  // a third overlay inside b names the opener (outside b, so inert) to take focus back
  const extra = document.createElement('div');
  extra.innerHTML = '<button>extra</button>';
  b.append(extra);
  const top = modal(extra, { returnFocus: () => opener });
  await flush();
  top.destroy();
  await flush();
  expect(document.activeElement).toBe(b1);
  upper.destroy();
  lower.destroy();
  await flush();
});

test('focus starts on [data-autofocus] wherever it is, unless it is disabled', async () => {
  document.body.innerHTML = '<main><button id="x">x</button></main><div id="ov"><button id="first">First</button><button id="auto" data-autofocus>Auto</button></div>';
  const ov = document.getElementById('ov');
  const one = modal(ov);
  await flush();
  expect(document.activeElement.id).toBe('auto');
  one.destroy();
  await flush();

  document.getElementById('auto').disabled = true;
  document.getElementById('x').focus();
  const two = modal(ov);
  await flush();
  expect(document.activeElement.id).toBe('first');
  two.destroy();
});

test('content added behind an open overlay becomes inert too, and inert set by others survives', async () => {
  const { overlay } = page();
  const foreign = document.createElement('aside');
  foreign.setAttribute('inert', '');
  document.body.append(foreign);
  const action = modal(overlay);

  const late = document.createElement('div');
  late.innerHTML = '<button>late</button>';
  document.body.append(late);
  const sibling = document.createElement('p');
  overlay.parentElement.append(sibling);
  await flush();
  expect(late.hasAttribute('inert')).toBe(true);
  expect(sibling.hasAttribute('inert')).toBe(true);

  action.destroy();
  await flush();
  expect(late.hasAttribute('inert')).toBe(false);
  expect(foreign.hasAttribute('inert')).toBe(true);
});

test('content added behind an open overlay is made inert without touching what was marked before', async () => {
  const { overlay } = page();
  const action = modal(overlay);
  const header = document.querySelector('header');
  header.removeAttribute('inert'); // as if someone else changed it: a full re-apply would mark it again
  const late = document.createElement('div');
  document.body.append(late);
  await flush();
  expect(late.hasAttribute('inert')).toBe(true);
  expect(header.hasAttribute('inert')).toBe(false);
  action.destroy();
  await flush();
  expect(late.hasAttribute('inert')).toBe(false);
});
