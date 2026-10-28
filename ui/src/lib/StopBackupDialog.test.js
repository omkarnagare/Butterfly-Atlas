import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import StopBackupDialog from './StopBackupDialog.svelte';
import { cancelBackup, getBackup, getStatus } from './api.js';
import { BACKUP_CHANGED, refreshStatus, resetStatus, status } from './status.svelte.js';
import { toast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ cancelBackup: vi.fn(), getBackup: vi.fn(), getStatus: vi.fn() }));

const running = { id: 2, state: 'running', done: 3210, total: 12400, pausedBy: null, notice: null, path: '/Volumes/B/Butterfly_Atlas_backup_2026-10-21' };

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getStatus.mockResolvedValue({ rescanning: false, backup: null });
});
afterEach(() => vi.restoreAllMocks());

const show = (props = {}) => render(StopBackupDialog, { run: running, onClose: vi.fn(), onStopped: vi.fn(), ...props });

test('asks whether to keep the copied files (the default, with focus) or remove what this backup wrote', async () => {
  show();
  const dialog = screen.getByRole('dialog', { name: 'Stop this backup?' });
  expect(dialog.textContent).toContain('3,210 of 12,400 photos were copied to /Volumes/B/Butterfly_Atlas_backup_2026-10-21.');
  const keep = screen.getByRole('radio', { name: 'Keep the copied files' });
  const remove = screen.getByRole('radio', { name: 'Remove the files this backup wrote' });
  expect([keep.checked, remove.checked]).toEqual([true, false]);
  expect(document.getElementById(remove.getAttribute('aria-describedby')).textContent)
    .toBe('Nothing else in that folder is touched. A file that has changed since the backup is kept.');
  await vi.waitFor(() => expect(document.activeElement).toBe(keep));
  expect([...dialog.querySelectorAll('.dlg-f button')].map((b) => b.textContent)).toEqual(['Go back', 'Stop backup']);
});

test('Stop backup cancels it keeping the files; the status follows at once, the screen hears of it, then the caller', async () => {
  getStatus.mockImplementation(() => new Promise(() => {}));
  status.backup = running;
  const cancelled = { ...running, state: 'cancelled', cancel: 'keep', notRemoved: 0 };
  cancelBackup.mockResolvedValue(cancelled);
  const heard = vi.fn();
  window.addEventListener(BACKUP_CHANGED, heard);
  const onStopped = vi.fn();
  show({ onStopped });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  await vi.waitFor(() => expect(onStopped).toHaveBeenCalledWith(cancelled));
  expect(cancelBackup).toHaveBeenCalledWith(2, false);
  expect(status.backup).toBeNull();
  expect(heard.mock.calls[0][0].detail).toEqual(cancelled);
  window.removeEventListener(BACKUP_CHANGED, heard);
});

test('Remove removes what this backup wrote, and says in a toast how many files were kept (changed since, or unreachable)', async () => {
  const seq = toast.seq;
  cancelBackup.mockResolvedValue({ ...running, state: 'cancelled', cancel: 'remove', notRemoved: 120 });
  show();
  await fireEvent.click(screen.getByRole('radio', { name: 'Remove the files this backup wrote' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  expect(cancelBackup).toHaveBeenCalledWith(2, true);
  await vi.waitFor(() => expect(toast.seq).toBe(seq + 1));
  expect(toast.message).toBe("120 files were kept: they had changed since the backup, or couldn't be reached.");
});

test('Remove with nothing kept, or Keep, says nothing more', async () => {
  const seq = toast.seq;
  cancelBackup.mockResolvedValue({ ...running, state: 'cancelled', cancel: 'remove', notRemoved: 0 });
  const onStopped = vi.fn();
  show({ onStopped });
  await fireEvent.click(screen.getByRole('radio', { name: 'Remove the files this backup wrote' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  await vi.waitFor(() => expect(onStopped).toHaveBeenCalled());
  expect(toast.seq).toBe(seq);
});

test('while it is stopping both buttons are inactive and it cannot be closed; a failure says why and lets it be tried again', async () => {
  let fail;
  cancelBackup.mockImplementationOnce(() => new Promise((resolve, reject) => { fail = reject; }));
  const onClose = vi.fn();
  const onStopped = vi.fn();
  show({ onClose, onStopped });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  const busy = screen.getByRole('button', { name: 'Stopping…' });
  expect(busy.getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByRole('button', { name: 'Go back' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(screen.getByRole('button', { name: 'Go back' }));
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).not.toHaveBeenCalled();

  fail(new Error('Butterfly Atlas could not reach its service.'));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(onStopped).not.toHaveBeenCalled();
  cancelBackup.mockResolvedValue({ ...running, state: 'cancelled', cancel: 'keep', notRemoved: 0 });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  await vi.waitFor(() => expect(onStopped).toHaveBeenCalled());
});

test('a backup that has already finished closes the dialog and reads the status again', async () => {
  cancelBackup.mockRejectedValue(Object.assign(new Error('This backup has already finished.'), { code: 'backup_finished', status: 409 }));
  const onClose = vi.fn();
  show({ onClose });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
  expect(getStatus).toHaveBeenCalled();
  expect(screen.queryByRole('alert')).toBeNull();
});

test('a stop that loses the race with the backup finishing, after a poll saw it leave, still says it finished and how many photos were left out', async () => {
  status.backup = running;
  let refuse;
  cancelBackup.mockImplementation(() => new Promise((resolve, reject) => { refuse = reject; }));
  getBackup.mockResolvedValue({ ...running, state: 'done', skipped: 2, failed: 1 });
  const seq = toast.seq;
  const onClose = vi.fn();
  show({ onClose });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  await refreshStatus();
  expect(status.backup).toBeNull();
  expect(toast.seq).toBe(seq);
  refuse(Object.assign(new Error('This backup has already finished.'), { code: 'backup_finished', status: 409 }));
  await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
  await vi.waitFor(() => expect(toast.seq).toBe(seq + 1));
  expect(toast.message).toBe('Backup finished. 3 photos could not be copied.');
});

test('a stop refused as already finished, before any poll saw it leave, says it finished once the status reads it', async () => {
  status.backup = running;
  cancelBackup.mockRejectedValue(Object.assign(new Error('This backup has already finished.'), { code: 'backup_finished', status: 409 }));
  getBackup.mockResolvedValue({ ...running, state: 'done', skipped: 0, failed: 0 });
  const seq = toast.seq;
  const onClose = vi.fn();
  show({ onClose });
  await fireEvent.click(screen.getByRole('button', { name: 'Stop backup' }));
  await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
  await vi.waitFor(() => expect(toast.seq).toBe(seq + 1));
  expect(toast.message).toBe('Backup finished.');
});
