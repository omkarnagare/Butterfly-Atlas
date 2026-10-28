import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import BackupCard from './BackupCard.svelte';
import { cancelBackup, getBackup, getBackups, getStatus, pauseBackup, resumeBackup } from './api.js';
import { BACKUP_CHANGED, resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({
  getBackups: vi.fn(), getBackup: vi.fn(), getStatus: vi.fn(), pauseBackup: vi.fn(), resumeBackup: vi.fn(), cancelBackup: vi.fn(),
}));

const PATH = '/Volumes/B/Butterfly_Atlas_backup_2026-10-21';
const brief = (over = {}) => ({ id: 2, state: 'running', done: 3210, total: 12400, pausedBy: null, notice: null, path: PATH, ...over });
const full = (over = {}) => ({
  ...brief(), what: 'everything', folder: 'Butterfly_Atlas_backup_2026-10-21', copied: 3210, skipped: 0, failed: 0, bytesTotal: 4509715660,
  bytesDone: 1712345678, cancel: null, notRemoved: 0, startedAt: '2026-10-21T10:00:00Z', finishedAt: null, ...over,
});

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getStatus.mockImplementation(() => new Promise(() => {}));
  getBackups.mockResolvedValue({ current: full(), past: [] });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const show = (props = {}) => render(BackupCard, { run: brief(), ...props });
const buttons = (container) => [...container.querySelectorAll('button')].map((b) => b.textContent.trim());

test('a running backup: how many of how many, a bar, the size done, the path; Pause and Cancel…', async () => {
  const { container } = show();
  expect(container.textContent).toContain('Backing up your library — 3,210 of 12,400 photos');
  const bar = screen.getByRole('progressbar', { name: 'Photos backed up' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['12400', '3210']);
  expect(container.textContent).toContain(PATH);
  expect(buttons(container)).toEqual(['Pause', 'Cancel…']);
});

test('while running it asks for the backups every second and shows the live counts and size; paused, it stops asking', async () => {
  vi.useFakeTimers();
  getBackups.mockResolvedValue({ current: full({ done: 4000, bytesDone: 2000000000 }), past: [] });
  const { container, rerender } = show();
  await vi.advanceTimersByTimeAsync(999);
  expect(getBackups).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect(getBackups).toHaveBeenCalledTimes(1);
  expect(container.textContent).toContain('4,000 of 12,400');
  expect(container.textContent).toContain('1.9 of 4.2 GB');
  await vi.advanceTimersByTimeAsync(1000);
  expect(getBackups).toHaveBeenCalledTimes(2);

  // stopped, it reads once more (the counts as it stopped) and then no more
  await rerender({ run: brief({ state: 'paused', pausedBy: 'user' }) });
  await vi.advanceTimersByTimeAsync(5000);
  expect(getBackups).toHaveBeenCalledTimes(3);
});

test('a read that fails is asked again a second later, without a word (the footer says when nothing answers)', async () => {
  vi.useFakeTimers();
  getBackups.mockRejectedValueOnce(new Error('down')).mockResolvedValue({ current: full({ done: 4100 }), past: [] });
  const { container } = show();
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('4,100 of 12,400');
});

test('photos left out are counted on the card with their reasons behind Show details', async () => {
  getBackups.mockResolvedValue({ current: full({ skipped: 2, failed: 1 }), past: [] });
  getBackup.mockResolvedValue({
    ...full({ skipped: 2, failed: 1 }),
    problems: [
      { kind: 'skipped', rel: 'Nymphalidae/Danaus_chrysippus/a.jpg', reason: 'removed while copying' },
      { kind: 'failed', rel: 'Nymphalidae/Danaus_chrysippus/b.jpg', reason: 'could not be read' },
    ],
    moreProblems: 4,
  });
  vi.useFakeTimers();
  const { container } = show();
  await vi.advanceTimersByTimeAsync(1000);
  expect(container.textContent).toContain('2 skipped, 1 failed');
  vi.useRealTimers();
  await fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
  const list = await screen.findByRole('list', { name: 'Photos that were not copied' });
  expect(list.textContent).toContain('Skipped');
  expect(list.textContent).toContain('Nymphalidae/Danaus_chrysippus/a.jpg — removed while copying');
  expect(list.textContent).toContain('Failed');
  expect(list.textContent).toContain('b.jpg — could not be read');
  expect(container.textContent).toContain('and 4 more');
  await fireEvent.click(screen.getByRole('button', { name: 'Hide details' }));
  expect(screen.queryByRole('list', { name: 'Photos that were not copied' })).toBeNull();
});

test('open details follow the backup: photos that fail later are added to the list, and another backup is read afresh', async () => {
  vi.useFakeTimers();
  const one = { kind: 'skipped', rel: 'a.jpg', reason: 'removed while copying' };
  const two = { kind: 'failed', rel: 'b.jpg', reason: 'could not be read' };
  getBackups.mockResolvedValueOnce({ current: full({ skipped: 1 }), past: [] }).mockResolvedValue({ current: full({ skipped: 1, failed: 1 }), past: [] });
  getBackup.mockImplementation(async (id) => (id === 3 ? { ...full({ id: 3, skipped: 1 }), problems: [{ kind: 'skipped', rel: 'c.jpg', reason: 'other' }], moreProblems: 0 }
    : { ...full({ skipped: 1, failed: getBackup.mock.calls.length > 1 ? 1 : 0 }), problems: getBackup.mock.calls.length > 1 ? [one, two] : [one], moreProblems: 0 }));
  const { container, rerender } = show();
  await vi.advanceTimersByTimeAsync(1000);
  await fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByRole('list', { name: 'Photos that were not copied' }).textContent).not.toContain('b.jpg');
  await vi.advanceTimersByTimeAsync(1000);
  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('1 skipped, 1 failed');
  expect(screen.getByRole('list', { name: 'Photos that were not copied' }).textContent).toContain('b.jpg — could not be read');
  expect(getBackup).toHaveBeenCalledTimes(2);

  getBackups.mockResolvedValue({ current: full({ id: 3, skipped: 1 }), past: [] });
  await rerender({ run: brief({ id: 3 }) });
  await vi.advanceTimersByTimeAsync(1000);
  await vi.advanceTimersByTimeAsync(0);
  expect(getBackup).toHaveBeenLastCalledWith(3);
  const list = screen.getByRole('list', { name: 'Photos that were not copied' });
  expect(list.textContent).toContain('c.jpg');
  expect(list.textContent).not.toContain('a.jpg');
});

test('details that cannot be read say why and can be asked again', async () => {
  getBackups.mockResolvedValue({ current: full({ skipped: 1 }), past: [] });
  getBackup.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.')).mockResolvedValue({ ...full({ skipped: 1 }), problems: [], moreProblems: 0 });
  vi.useFakeTimers();
  show();
  await vi.advanceTimersByTimeAsync(1000);
  vi.useRealTimers();
  await fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
});

test('Pause answers through the status and the screen; "Pausing…" until it has stopped; Resume when paused', async () => {
  const heard = vi.fn();
  window.addEventListener(BACKUP_CHANGED, heard);
  pauseBackup.mockResolvedValue(full());
  const { container, rerender } = show();
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(pauseBackup).toHaveBeenCalledWith(2);
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Pausing…' }).getAttribute('aria-disabled')).toBe('true'));
  expect(heard).toHaveBeenCalledTimes(1);

  resumeBackup.mockResolvedValue(full());
  await rerender({ run: brief({ state: 'paused', pausedBy: 'user' }) });
  expect(container.textContent).toContain('Backup paused at 3,210 of 12,400.');
  expect(container.textContent).toContain('You paused a backup.');
  expect(buttons(container)).toEqual(['Resume', 'Cancel…']);
  await fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
  expect(resumeBackup).toHaveBeenCalledWith(2);
  window.removeEventListener(BACKUP_CHANGED, heard);
});

test('paused for a full or missing drive it says so with the notice', () => {
  const { container } = show({ run: brief({ state: 'paused', pausedBy: 'drive', notice: 'The drive with the backup is full. Free some space, then continue.' }) });
  expect(container.textContent).toContain('The drive with the backup is full. Free some space, then continue.');
  expect(buttons(container)).toEqual(['Resume', 'Cancel…']);
});

test('a backup the app\'s closing stopped says so and offers Continue and Discard…, never starting by itself', async () => {
  resumeBackup.mockResolvedValue(full());
  const { container } = show({ run: brief({ state: 'paused', pausedBy: 'closed' }) });
  expect(container.textContent).toContain('Backup stopped — the app was closed');
  expect(container.textContent).toContain('3,210 of 12,400 photos were copied to');
  expect(buttons(container)).toEqual(['Continue', 'Discard…']);
  expect(resumeBackup).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
  expect(resumeBackup).toHaveBeenCalledWith(2);
});

test('Cancel… and Discard… open the stop dialog (Keep or Remove); stopping closes it', async () => {
  cancelBackup.mockResolvedValue(full({ state: 'cancelled', cancel: 'keep' }));
  const { rerender } = show();
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel…' }));
  const dialog = screen.getByRole('dialog', { name: 'Stop this backup?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Stop backup' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(cancelBackup).toHaveBeenCalledWith(2, false);
  await rerender({ run: brief({ state: 'paused', pausedBy: 'closed' }) });
  await fireEvent.click(screen.getByRole('button', { name: 'Discard…' }));
  expect(screen.getByRole('dialog', { name: 'Stop this backup?' })).toBeTruthy();
});

test('a refused Pause says why on the card and the status is read again', async () => {
  pauseBackup.mockRejectedValue(Object.assign(new Error('This backup is not running.'), { code: 'not_running' }));
  show();
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect((await screen.findByRole('alert')).textContent).toBe('This backup is not running.');
  expect(getStatus).toHaveBeenCalled();
});

test('a database-only backup has no photo count or bar value, just the words', () => {
  const { container } = show({ run: brief({ done: 0, total: 0 }) });
  expect(container.textContent).toContain('Backing up your library…');
  expect(screen.getByRole('progressbar', { name: 'Photos backed up' }).hasAttribute('value')).toBe(false);
});

test('while the backup holds the photos the card says so, not muted, in words that follow its buttons; a backup that doesn\'t says nothing of it (A6, A22)', async () => {
  const hold = "While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or cancel it and start a new backup after your changes.";
  const stopped = "While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or discard it and start a new backup after your changes.";
  const { container, rerender } = show({ run: brief({ state: 'paused', pausedBy: 'you', holdsPhotos: true }) });
  const line = screen.getByText(hold);
  expect(line.classList.contains('muted')).toBe(false);
  expect(screen.getByRole('button', { name: 'Cancel…' })).toBeTruthy();
  await rerender({ run: brief({ state: 'paused', pausedBy: 'closed', holdsPhotos: true }) });
  expect(screen.getByText(stopped)).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Discard…' })).toBeTruthy();
  expect(container.textContent).not.toContain(hold);
  await rerender({ run: brief({ holdsPhotos: false }) });
  expect(container.textContent).not.toContain(hold);
  expect(container.textContent).not.toContain(stopped);
});
