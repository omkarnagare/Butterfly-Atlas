import { afterEach, expect, test, vi } from 'vitest';
import { closeViewer, closeViewerTo, forgetViewerOpen, noteViewerOpen } from './viewer.js';

// jsdom fires its own hashchange later, after a test sets the address: start each test with nothing opened.
afterEach(() => {
  vi.restoreAllMocks();
  forgetViewerOpen();
});

test('closing a viewer opened from a thumbnail goes back where the user was', () => {
  const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  noteViewerOpen();

  closeViewer('A5');

  expect(back).toHaveBeenCalledOnce();
});

test('closing a viewer opened from its address shows the species page', () => {
  const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  window.location.hash = '#/species/A5/photo/12';

  closeViewer('A5');

  expect(back).not.toHaveBeenCalled();
  expect(window.location.hash).toBe('#/species/A5');
});

test('an import\'s large view closes back to its grid the same way', () => {
  const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  window.location.hash = '#/import/3/photo/812';
  closeViewerTo('#/import/3');
  expect(back).not.toHaveBeenCalled();
  expect(window.location.hash).toBe('#/import/3');

  noteViewerOpen();
  closeViewerTo('#/import/3');
  expect(back).toHaveBeenCalledOnce();
});

test('a viewer left another way (Back, a replaced address) no longer counts as opened in the app', () => {
  const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  noteViewerOpen();
  forgetViewerOpen();
  window.location.hash = '#/import/3/photo/812';
  closeViewerTo('#/import/3');
  expect(back).not.toHaveBeenCalled();
  expect(window.location.hash).toBe('#/import/3');
});

test('a viewer closed with the browser\'s Back no longer counts as opened in the app; paging to another photo still does', () => {
  const back = vi.spyOn(window.history, 'back').mockImplementation(() => {});
  const go = (hash) => {
    window.location.hash = hash;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  };

  noteViewerOpen();
  go('#/species/A5/photo/12');
  go('#/species/A5/photo/13?tag=male');
  closeViewer('A5');
  expect(back).toHaveBeenCalledOnce();

  noteViewerOpen();
  go('#/import/3/photo/812');
  go('#/import/3');
  go('#/species/A5/photo/12');
  closeViewer('A5');
  expect(back).toHaveBeenCalledOnce();
  expect(window.location.hash).toBe('#/species/A5');
});
