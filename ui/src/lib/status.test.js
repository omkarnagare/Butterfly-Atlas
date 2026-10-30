import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { getStatus } from './api.js';
import { changeStarted, refreshStatus, resetStatus, status } from './status.svelte.js';

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
