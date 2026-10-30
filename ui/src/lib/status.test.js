import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { getStatus } from './api.js';
import { changeStarted, refreshStatus, resetStatus, status } from './status.svelte.js';
import { showProblem, showToast, toast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ getStatus: vi.fn(), getExport: vi.fn(), getBackup: vi.fn(), checkHeic: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
});
afterEach(() => resetStatus());

const moving = (over = {}) => ({
  kind: 'move', to: '/Volumes/B/Butterfly_Atlas', step: 'copying', files: { done: 120, total: 3400 }, bytes: { done: 1048576, total: 13312000000 },
  canCancel: true, problem: null, left: null, reopen: null, ...over,
});

// Change the library folder (contract §2): the status's `changing` and `libraryMoved`.
test('the status carries the change of library folder and the one-time moved note; an older server has neither', async () => {
  getStatus.mockResolvedValue({ rescanning: false, changing: moving(), libraryMoved: { to: '/Volumes/B/Butterfly_Atlas' } });
  await refreshStatus();
  expect(status.changing).toEqual(moving());
  expect(status.libraryMoved).toEqual({ to: '/Volumes/B/Butterfly_Atlas' });
  getStatus.mockResolvedValue({ rescanning: false });
  await refreshStatus();
  expect(status.changing).toBeNull();
  expect(status.libraryMoved).toBeNull();
});

test('a change this page started shows at once from its answer (with or without the `changing` wrapper), and the status is asked', async () => {
  getStatus.mockResolvedValue({ rescanning: false, changing: moving({ files: { done: 5, total: 3400 } }) });
  changeStarted({ changing: moving({ files: { done: 0, total: 0 } }) });
  expect(status.changing.files).toEqual({ done: 0, total: 0 });
  await vi.waitFor(() => expect(status.changing.files).toEqual({ done: 5, total: 3400 }));
  resetStatus();
  getStatus.mockResolvedValue({ rescanning: false, changing: moving({ kind: 'switch' }) });
  changeStarted(moving({ kind: 'switch', step: 'checking' }));
  expect(status.changing.step).toBe('checking');
  expect(getStatus).toHaveBeenCalled();
});

test('once the change is reopening the app, the status is kept as it was and not asked again', async () => {
  const reopening = moving({ step: 'reopening', canCancel: false, reopen: { how: 'restart', folder: '/Volumes/B/Butterfly_Atlas', why: null } });
  getStatus.mockResolvedValue({ rescanning: false, changing: reopening });
  await refreshStatus();
  getStatus.mockReset();
  getStatus.mockRejectedValue(new Error('gone'));
  for (let i = 0; i < 4; i += 1) await refreshStatus();
  expect(getStatus).not.toHaveBeenCalled();
  expect(status.changing).toEqual(reopening);
  expect(status.unreachable).toBe(false);
});

test('while a change shows, no toast is shown: one on screen goes, and a refusal for the change is not shown', async () => {
  showToast('Taxonomy update finished.');
  getStatus.mockResolvedValue({ rescanning: false, changing: moving() });
  await refreshStatus();
  expect(toast.message).toBe('');
  showProblem(Object.assign(new Error('Butterfly Atlas is changing your library folder.'), { code: 'library_changing' }));
  showToast('Something else.');
  expect(toast.message).toBe('');
  getStatus.mockResolvedValue({ rescanning: false, changing: null });
  await refreshStatus();
  showToast('Back.');
  expect(toast.message).toBe('Back.');
});

test('a library_changing refusal holds toasts at once, before the status answers', async () => {
  getStatus.mockResolvedValue({ rescanning: false, changing: moving() });
  window.dispatchEvent(new CustomEvent('atlas:library-problem', { detail: { code: 'library_changing', message: 'Butterfly Atlas is changing your library folder.' } }));
  showToast('Butterfly Atlas is changing your library folder.');
  expect(toast.message).toBe('');
  await vi.waitFor(() => expect(status.changing).not.toBeNull());
});
