import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import StopExportDialog from './StopExportDialog.svelte';
import { cancelExport, getStatus } from './api.js';
import { EXPORT_CHANGED, refreshStatus, resetStatus, status } from './status.svelte.js';
import { toast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ cancelExport: vi.fn(), getStatus: vi.fn() }));

const running = { id: 4, state: 'running', done: 3200, total: 50000, pausedBy: null, notice: null, path: '/Volumes/Backup/Butterfly_Atlas_export_2026-10-13' };

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getStatus.mockResolvedValue({ rescanning: false, export: null });
});
afterEach(() => vi.restoreAllMocks());

const show = (props = {}) => render(StopExportDialog, { run: running, onClose: vi.fn(), onStopped: vi.fn(), ...props });

test('asks whether to keep the copied files (the default, with focus) or remove what this export wrote', async () => {
  show();
  const dialog = screen.getByRole('dialog', { name: 'Stop this export?' });
  expect(dialog.textContent).toContain('3,200 of 50,000 photos were copied to /Volumes/Backup/Butterfly_Atlas_export_2026-10-13.');
  const keep = screen.getByRole('radio', { name: 'Keep the copied files' });
  const remove = screen.getByRole('radio', { name: 'Remove the files this export wrote' });
  expect([keep.checked, remove.checked]).toEqual([true, false]);
  expect(document.getElementById(remove.getAttribute('aria-describedby')).textContent).toBe('Nothing else in that folder is touched.');
  await vi.waitFor(() => expect(document.activeElement).toBe(keep));
  expect([...dialog.querySelectorAll('.dlg-f button')].map((b) => b.textContent)).toEqual(['Go back', 'Stop export']);
});

test('Stop export cancels it keeping the files; the status follows at once, the screen hears of it, then the caller', async () => {
  // the status's own answer never comes: only the stop's answer can take the export out of it
  getStatus.mockImplementation(() => new Promise(() => {}));
  status.export = running;
  const cancelled = { ...running, state: 'cancelled', cancel: 'keep' };
  cancelExport.mockResolvedValue(cancelled);
  const heard = vi.fn();
  window.addEventListener(EXPORT_CHANGED, heard);
  const onStopped = vi.fn();
  show({ onStopped });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop export' }));
  await vi.waitFor(() => expect(onStopped).toHaveBeenCalledWith(cancelled));
  expect(cancelExport).toHaveBeenCalledWith(4, false);
  expect(status.export).toBeNull();
  expect(getStatus).toHaveBeenCalled();
  expect(heard.mock.calls[0][0].detail).toEqual(cancelled);
  window.removeEventListener(EXPORT_CHANGED, heard);
});

test('Remove asks the server to remove the files this export wrote', async () => {
  cancelExport.mockResolvedValue({ ...running, state: 'cancelled', cancel: 'remove' });
  show();
  await fireEvent.click(screen.getByRole('radio', { name: 'Remove the files this export wrote' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Stop export' }));
  expect(cancelExport).toHaveBeenCalledWith(4, true);
});

test('while it is stopping both buttons are inactive and it cannot be closed; a failure says why and lets it be tried again', async () => {
  let fail;
  cancelExport.mockImplementationOnce(() => new Promise((resolve, reject) => { fail = reject; }));
  const onClose = vi.fn();
  const onStopped = vi.fn();
  show({ onClose, onStopped });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop export' }));
  const busy = screen.getByRole('button', { name: 'Stopping…' });
  expect(busy.getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByRole('button', { name: 'Go back' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(busy);
  await fireEvent.click(screen.getByRole('button', { name: 'Go back' }));
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(cancelExport).toHaveBeenCalledTimes(1);
  expect(onClose).not.toHaveBeenCalled();

  fail(Object.assign(new Error('Butterfly Atlas could not reach its service.'), { code: 'offline' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(screen.getByRole('button', { name: 'Stop export' }).getAttribute('aria-disabled')).toBe('false');
  expect(onStopped).not.toHaveBeenCalled();
});

test('Go back and Escape close it without stopping anything', async () => {
  const onClose = vi.fn();
  show({ onClose });
  await fireEvent.click(screen.getByRole('button', { name: 'Go back' }));
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).toHaveBeenCalledTimes(2);
  expect(cancelExport).not.toHaveBeenCalled();
});

test('a poll that sees the export gone before the stop has answered does not say "Export finished."; a failed stop lets it again', async () => {
  status.export = running;
  const answers = [];
  cancelExport.mockImplementation(() => new Promise((resolve, reject) => answers.push({ resolve, reject })));
  const seq = toast.seq;
  show();
  await fireEvent.click(screen.getByRole('button', { name: 'Stop export' }));
  await refreshStatus();
  expect(status.export).toBeNull();
  expect(toast.seq).toBe(seq);
  answers[0].resolve({ ...running, state: 'cancelled', cancel: 'keep' });
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalledTimes(2));
  expect(toast.seq).toBe(seq);

  // a stop that fails: the export really finished meanwhile, and its end is said
  status.export = running;
  getStatus.mockResolvedValue({ rescanning: false, export: running });
  await refreshStatus();
  show();
  await fireEvent.click(screen.getAllByRole('button', { name: 'Stop export' }).at(-1));
  answers[1].reject(Object.assign(new Error('Butterfly Atlas could not reach its service.'), { code: 'offline' }));
  await screen.findByRole('alert');
  getStatus.mockResolvedValue({ rescanning: false, export: null });
  await refreshStatus();
  expect(toast.message).toBe('Export finished.');
});

test('refused because the export has already finished, it says nothing: the status and the screen read it again, then onGone', async () => {
  cancelExport.mockRejectedValue(Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 }));
  const heard = vi.fn();
  window.addEventListener(EXPORT_CHANGED, heard);
  const onGone = vi.fn();
  const onStopped = vi.fn();
  show({ onGone, onStopped });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop export' }));
  await vi.waitFor(() => expect(onGone).toHaveBeenCalledTimes(1));
  expect(screen.queryByRole('alert')).toBeNull();
  expect(onStopped).not.toHaveBeenCalled();
  expect(getStatus).toHaveBeenCalled();
  expect(heard.mock.calls[0][0].detail).toBeNull();
  window.removeEventListener(EXPORT_CHANGED, heard);
});

test('the export finishing while a stop is on its way: seen gone first, then the stop fails or is refused as finished, "Export finished." is said once', async () => {
  for (const failure of [
    Object.assign(new Error('Butterfly Atlas could not reach its service.'), { code: 'offline' }),
    Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 }),
  ]) {
    resetStatus();
    getStatus.mockResolvedValue({ rescanning: false, export: running });
    await refreshStatus();
    let fail;
    cancelExport.mockImplementationOnce(() => new Promise((resolve, reject) => { fail = reject; }));
    const seq = toast.seq;
    const { unmount } = show({ onGone: vi.fn() });
    await fireEvent.click(screen.getByRole('button', { name: 'Stop export' }));
    getStatus.mockResolvedValue({ rescanning: false, export: null });
    await refreshStatus();
    expect(toast.seq).toBe(seq);
    fail(failure);
    await vi.waitFor(() => expect(toast.seq).toBe(seq + 1));
    expect(toast.message).toBe('Export finished.');
    await refreshStatus();
    expect(toast.seq).toBe(seq + 1);
    unmount();
  }
});

test('refused as finished before any poll has seen it gone: "Export finished." is said once, by the status read again', async () => {
  getStatus.mockResolvedValue({ rescanning: false, export: running });
  await refreshStatus();
  cancelExport.mockRejectedValue(Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 }));
  getStatus.mockResolvedValue({ rescanning: false, export: null });
  const seq = toast.seq;
  const onGone = vi.fn();
  show({ onGone });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop export' }));
  await vi.waitFor(() => expect(onGone).toHaveBeenCalled());
  await vi.waitFor(() => expect(toast.seq).toBe(seq + 1));
  await refreshStatus();
  expect([toast.seq, toast.message]).toEqual([seq + 1, 'Export finished.']);
});
