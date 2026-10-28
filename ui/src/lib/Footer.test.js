import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Footer from './Footer.svelte';
import Toast from './Toast.svelte';
import { showToast, toast } from './toast.svelte.js';
import { readFileSync } from 'node:fs';
import { exportStopping } from './status.svelte.js';
import { BACKUP_CHANGED, EXPORT_CHANGED, HEIC_CHANGED, LIBRARY_CHANGED, THUMBS_AGAIN, backupChanged, checkHeicAgain, setImportPreviews, exportChanged, refreshStatus, rescanMessage, resetStatus, status, updateChanged } from './status.svelte.js';
import { cancelBackup, cancelExport, checkHeic, getBackup, getExport, getStatus, resumeBackup, resumeExport, retryUpdate } from './api.js';
import { resetSession, session } from './session.svelte.js';

vi.mock('./api.js', () => ({ getStatus: vi.fn(), checkHeic: vi.fn(), resumeExport: vi.fn(), cancelExport: vi.fn(), retryUpdate: vi.fn(), getBackup: vi.fn(), resumeBackup: vi.fn(), cancelBackup: vi.fn(), getExport: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  resetSession();
  // pin the number format, so the tests do not depend on the machine's locale
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  for (const main of document.querySelectorAll('main')) main.remove();
});

/** The page's main, which focus goes to when the paused line goes (removed after each test). */
function addMain() {
  const main = document.createElement('main');
  main.tabIndex = -1;
  document.body.append(main);
  return main;
}

const idle = { rescanning: false, rescan: null, previews: { pending: 0, done: 0 }, deletedCount: 0 };
const checking = (checked, total) => ({ ...idle, rescanning: true, rescan: { checked, total, added: 0, removed: 0, finishedAt: null } });

test('links to Recently deleted only when something is there, and shows no work when there is none', async () => {
  getStatus.mockResolvedValue({ ...idle, deletedCount: 3 });
  const { container, unmount } = render(Footer);

  const link = await screen.findByRole('link', { name: 'Recently deleted (3)' });
  expect(link.getAttribute('href')).toBe('#/deleted');
  expect(container.querySelector('progress')).toBeNull();
  unmount();

  getStatus.mockResolvedValue(idle);
  render(Footer);
  await vi.waitFor(() => expect(screen.queryByRole('link')).toBeNull());
});

test('shows the photo check counting, then its progress, asking again every 2 seconds', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(checking(0, 0)).mockResolvedValueOnce(checking(40, 120)).mockResolvedValue(idle);
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Checking your photos…');
  expect(container.querySelector('progress').hasAttribute('value')).toBe(false);

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Checking your photos… 40 of 120');
  expect(screen.getByRole('progressbar', { name: 'Photos checked' }).getAttribute('value')).toBe('40');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('Checking');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(3);
});

test('shows previews being prepared while any are pending', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce({ ...idle, previews: { pending: 3, done: 9 } }).mockResolvedValue({ ...idle, previews: { pending: 0, done: 12 } });
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Preparing previews… 9 of 12');
  expect(screen.getByRole('progressbar', { name: 'Previews ready' }).getAttribute('max')).toBe('12');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('Preparing previews');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(2);
});

test('when a check finishes it says what it found and tells the open screen to reload', async () => {
  vi.useFakeTimers();
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  getStatus.mockResolvedValueOnce(checking(119, 120))
    .mockResolvedValue({ ...idle, rescan: { checked: 120, total: 120, added: 3, removed: 1, finishedAt: '2026-10-11T09:00:00Z' } });
  render(Footer);
  render(Toast);

  await vi.advanceTimersByTimeAsync(2000);

  expect(screen.getByText('Found 3 new photos in your folders. Removed 1 photo that was deleted outside Butterfly Atlas.')).toBeTruthy();
  expect(changed).toHaveBeenCalledOnce();
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('a libraryVersion that moves after the first answer tells the open screens to reload; the same one, or none, does not', async () => {
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  getStatus.mockResolvedValueOnce({ ...idle, libraryVersion: 7 });
  await refreshStatus();
  expect(changed).not.toHaveBeenCalled();
  getStatus.mockResolvedValueOnce({ ...idle, libraryVersion: 7 });
  await refreshStatus();
  expect(changed).not.toHaveBeenCalled();
  // a photo moved in another window
  getStatus.mockResolvedValueOnce({ ...idle, libraryVersion: 8 });
  await refreshStatus();
  expect(changed).toHaveBeenCalledTimes(1);
  // an older server leaves it out: nothing to compare
  getStatus.mockResolvedValueOnce({ ...idle });
  await refreshStatus();
  getStatus.mockResolvedValueOnce({ ...idle });
  await refreshStatus();
  expect(changed).toHaveBeenCalledTimes(1);
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('a libraryVersion moving while an import files tells the open screens to reload once, when filing ends', async () => {
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  const filing = { id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 0, total: 40 };
  getStatus.mockResolvedValueOnce({ ...idle, import: filing, libraryVersion: 7 });
  await refreshStatus();
  for (const v of [8, 9, 10]) {
    getStatus.mockResolvedValueOnce({ ...idle, import: { ...filing, done: v }, libraryVersion: v });
    await refreshStatus();
  }
  expect(changed).not.toHaveBeenCalled();
  // filing has left: one reload for all it filed
  getStatus.mockResolvedValueOnce({ ...idle, import: { ...filing, phase: 'idle' }, libraryVersion: 10 });
  await refreshStatus();
  expect(changed).toHaveBeenCalledTimes(1);
  getStatus.mockResolvedValueOnce({ ...idle, libraryVersion: 10 });
  await refreshStatus();
  expect(changed).toHaveBeenCalledTimes(1);
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('says nothing about a check that changed nothing', () => {
  expect(rescanMessage({ added: 0, removed: 0 })).toBe('');
  expect(rescanMessage({ added: 1, removed: 0 })).toBe('Found 1 new photo in your folders.');
  expect(rescanMessage({ added: 0, removed: 2 })).toBe('Removed 2 photos that were deleted outside Butterfly Atlas.');
});

test('says what a rescan updated, alone or with the rest', () => {
  expect(rescanMessage({ updated: 1 })).toBe('Updated 1 photo that was renamed or edited.');
  expect(rescanMessage({ updated: 2 })).toBe('Updated 2 photos that were renamed or edited.');
  expect(rescanMessage({ added: 1, updated: 2 })).toBe('Found 1 new photo in your folders. Updated 2 photos that were renamed or edited.');
});

test('a check that only renamed photos still reloads the screen', async () => {
  vi.useFakeTimers();
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  getStatus.mockResolvedValueOnce(checking(119, 120))
    .mockResolvedValue({ ...idle, rescan: { checked: 120, total: 120, added: 0, removed: 0, updated: 2, finishedAt: '2026-10-11T09:00:00Z' } });
  render(Footer);
  render(Toast);

  await vi.advanceTimersByTimeAsync(2000);

  expect(screen.getByText('Updated 2 photos that were renamed or edited.')).toBeTruthy();
  expect(changed).toHaveBeenCalledOnce();
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('a check that ended before the page asked is reported once', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-11T09:00:00Z'));
  resetStatus();
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  const done = { ...idle, rescan: { checked: 5, total: 5, added: 2, removed: 0, updated: 0, finishedAt: '2026-10-11T09:00:05Z' } };
  getStatus.mockResolvedValue(done);
  render(Footer);
  render(Toast);

  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByText('Found 2 new photos in your folders.')).toBeTruthy();
  expect(changed).toHaveBeenCalledOnce();
  await vi.advanceTimersByTimeAsync(20000);
  expect(changed).toHaveBeenCalledOnce();

  // a later check that ended between two polls
  getStatus.mockResolvedValue({ ...done, rescan: { ...done.rescan, added: 0, removed: 1, finishedAt: '2026-10-11T09:01:00Z' } });
  await refreshStatus(); // a page asks again after its own change
  expect(changed).toHaveBeenCalledTimes(2);
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('a check that finished before the page loaded says nothing', async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-10-11T09:10:00Z'));
  resetStatus();
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  getStatus.mockResolvedValue({ ...idle, rescan: { checked: 5, total: 5, added: 2, removed: 0, updated: 0, finishedAt: '2026-10-11T09:00:05Z' } });
  render(Footer);

  await vi.advanceTimersByTimeAsync(0);

  expect(changed).not.toHaveBeenCalled();
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test('has one status region from the start, whether or not there is any work', async () => {
  getStatus.mockResolvedValue(idle);
  const { unmount } = render(Footer);
  expect(screen.getAllByRole('status')).toHaveLength(1);
  expect(screen.getByRole('status').textContent.trim()).toBe('');
  unmount();

  getStatus.mockResolvedValue(checking(1, 2));
  render(Footer);
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe('Checking your photos.'));
  expect(screen.getAllByRole('status')).toHaveLength(1);
});


test('the status region only names the phase, so it does not change as counts move', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(checking(10, 100)).mockResolvedValueOnce(checking(60, 100)).mockResolvedValue(idle);
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  const region = screen.getByRole('status');
  const first = region.textContent;
  expect(first).toBe('Checking your photos.');
  expect(container.textContent).toContain('10 of 100');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('60 of 100');
  expect(region.textContent).toBe(first);
  expect(screen.getByRole('progressbar', { name: 'Photos checked' }).getAttribute('value')).toBe('60');

  await vi.advanceTimersByTimeAsync(2000);
  expect(region.textContent).toBe('');
});

test('never has two requests in flight: the next poll waits for the last answer', async () => {
  vi.useFakeTimers();
  let release;
  getStatus.mockResolvedValueOnce(checking(1, 9)).mockImplementationOnce(() => new Promise((r) => { release = r; })).mockResolvedValue(idle);
  render(Footer);

  await vi.advanceTimersByTimeAsync(2000);
  expect(getStatus).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(10000);
  expect(getStatus).toHaveBeenCalledTimes(2);

  release(checking(2, 9));
  await vi.advanceTimersByTimeAsync(2000);
  expect(getStatus).toHaveBeenCalledTimes(3);
});

test('a finished check that changed nothing says nothing and sends no event', async () => {
  vi.useFakeTimers();
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  getStatus.mockResolvedValueOnce(checking(119, 120))
    .mockResolvedValue({ ...idle, rescan: { checked: 120, total: 120, added: 0, removed: 0, finishedAt: '2026-10-11T09:00:00Z' } });
  const seq = toast.seq;
  render(Footer);

  await vi.advanceTimersByTimeAsync(2000);

  expect(toast.seq).toBe(seq);
  expect(changed).not.toHaveBeenCalled();
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

const updating = (done, total, step = 'details') => ({ ...idle, processing: { step, done, total } });

test('shows photo details being updated, asking again every 2 seconds until they are done', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(updating(3200, 50000)).mockResolvedValueOnce(updating(10, 400, 'update')).mockResolvedValue({ ...idle, processing: null });
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Reading photo details… 3,200 of 50,000');
  const bar = screen.getByRole('progressbar', { name: 'Photo details read' });
  expect(bar.getAttribute('max')).toBe('50000');
  expect(bar.getAttribute('value')).toBe('3200');
  expect(screen.getByRole('status').textContent).toBe('Reading photo details.');

  // the second step reads the same, from its own count
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Updating photo details… 10 of 400');
  expect(screen.getByRole('status').textContent).toBe('Updating photo details.');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('photo details');
  expect(container.querySelector('progress')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe('');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(3);
});

test('the status region names the photo check first, then photo details, then previews', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce({ ...checking(5, 10), processing: updating(0, 9).processing, previews: { pending: 2, done: 1 } })
    .mockResolvedValueOnce({ ...updating(4, 9), previews: { pending: 2, done: 1 } })
    .mockResolvedValueOnce({ ...idle, previews: { pending: 1, done: 2 } })
    .mockResolvedValue(idle);
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByRole('status').textContent).toBe('Checking your photos.');
  expect(screen.getAllByRole('progressbar')).toHaveLength(3);

  await vi.advanceTimersByTimeAsync(2000);
  expect(screen.getByRole('status').textContent).toBe('Reading photo details.');
  expect(container.textContent).toContain('Reading photo details… 4 of 9');
  expect(container.textContent).toContain('Preparing previews… 1 of 3');

  await vi.advanceTimersByTimeAsync(2000);
  expect(screen.getByRole('status').textContent).toBe('Preparing previews.');
  expect(container.textContent).not.toContain('photo details');
});

test('a failed request leaves the photo details showing and keeps asking', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(updating(1, 9)).mockRejectedValueOnce(new Error('offline')).mockResolvedValue(updating(5, 9));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Reading photo details… 1 of 9');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Reading photo details… 5 of 9');
  expect(getStatus).toHaveBeenCalledTimes(3);
});

test('an answer without photo details (an older server) shows none and stops asking', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(updating(1, 9)).mockResolvedValue(idle);
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Reading photo details… 1 of 9');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('photo details');
  expect(container.querySelector('progress')).toBeNull();
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(2);
});

test('photo details with no total yet show no count and a bar without an end', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue(updating(0, 0));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Reading photo details…');
  expect(container.textContent).not.toContain('0 of 0');
  const bar = screen.getByRole('progressbar', { name: 'Counting photo details' });
  expect(bar.hasAttribute('max')).toBe(false);
  expect(bar.hasAttribute('value')).toBe(false);
});

test('the two catch-up steps read differently, an unknown step reads as the update, and big counts get separators', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(updating(50000, 50000, 'details')).mockResolvedValueOnce(updating(0, 50000, 'update'))
    .mockResolvedValueOnce(updating(1200, 50000, 'later')).mockResolvedValue(idle);
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Reading photo details… 50,000 of 50,000');
  expect(screen.getByRole('status').textContent).toBe('Reading photo details.');
  expect(screen.getByRole('progressbar', { name: 'Photo details read' })).toBeTruthy();

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Updating photo details… 0 of 50,000');
  expect(screen.getByRole('status').textContent).toBe('Updating photo details.');
  expect(screen.getByRole('progressbar', { name: 'Photo details updated' })).toBeTruthy();

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Updating photo details… 1,200 of 50,000');
});

const importing = (phase, done, total) => ({ ...idle, processing: null, import: { id: 3, rootName: 'Butterflies 2024', phase, done, total } });

test('shows an import reading its folder, then filing, asking again until it is idle', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(importing('reading', 812, 9870)).mockResolvedValueOnce(importing('filing', 1200, 2140))
    .mockResolvedValue({ ...idle, import: null });
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Reading Butterflies 2024… 812 of 9,870');
  expect(screen.getByRole('progressbar', { name: 'Photos read' }).getAttribute('value')).toBe('812');
  expect(screen.getByRole('status').textContent).toBe('Reading Butterflies 2024.');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Filing photos… 1,200 of 2,140');
  expect(screen.getByRole('progressbar', { name: 'Photos filed' }).getAttribute('max')).toBe('2140');
  expect(screen.getByRole('status').textContent).toBe('Filing photos.');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('Filing');
  expect(container.querySelector('progress')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe('');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(3);
});

test('an import still looking through its folder shows a bar without an end, and the status region keeps to the phase', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(importing('walking', 0, 0)).mockResolvedValueOnce(importing('reading', 1, 9870))
    .mockResolvedValue(importing('reading', 400, 9870));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Looking through Butterflies 2024…');
  expect(container.textContent).not.toContain('0 of 0');
  expect(screen.getByRole('progressbar', { name: 'Looking for photos' }).hasAttribute('value')).toBe(false);
  expect(screen.getByRole('status').textContent).toBe('Looking through Butterflies 2024.');

  await vi.advanceTimersByTimeAsync(2000);
  const region = screen.getByRole('status');
  expect(region.textContent).toBe('Reading Butterflies 2024.');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('400 of 9,870');
  expect(region.textContent).toBe('Reading Butterflies 2024.');
});

test('photo details are named before an import in the status region; both lines show', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue({ ...importing('reading', 5, 10), processing: { step: 'details', done: 1, total: 2 } });
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByRole('status').textContent).toBe('Reading photo details.');
  expect(container.textContent).toContain('Reading Butterflies 2024… 5 of 10');
});

test('a quiet line for each import the app\'s closing stopped; it does not keep the footer asking', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue({ ...idle, stoppedImports: [{ id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 1200, total: 2140 }] });
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain("Your import of Butterflies 2024 stopped when Butterfly Atlas closed (filing 1,200 of 2,140). It won't carry on by itself.");
  expect(screen.getByRole('link', { name: 'Continue import of Butterflies 2024' }).getAttribute('href')).toBe('#/import/3');
  expect(screen.getByRole('status').textContent).toBe('');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(1);
});

test('an import looking through its folder says how many files it has found so far', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue(importing('walking', 1234, 0));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Looking through Butterflies 2024… 1,234 found');
  expect(screen.getByRole('progressbar', { name: 'Looking for photos' }).hasAttribute('value')).toBe(false);
  expect(screen.getByRole('status').textContent).toBe('Looking through Butterflies 2024.');
});

test('a phase it does not know gets neutral words, not the walking ones', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue(importing('checking', 3, 10));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Working on Butterflies 2024… 3 of 10');
  expect(container.textContent).not.toContain('Looking through');
  expect(screen.getByRole('progressbar', { name: 'Import progress' })).toBeTruthy();
  expect(screen.getByRole('status').textContent).toBe('Working on Butterflies 2024.');
});

test('shows places being found, then an unknown step as the update; the status region names only the step', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(updating(3200, 50000, 'places')).mockResolvedValueOnce(updating(3900, 50000, 'places'))
    .mockResolvedValueOnce(updating(5, 9, 'later')).mockResolvedValue({ ...idle, processing: null });
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Finding places… 3,200 of 50,000');
  expect(container.textContent).not.toContain('photo details');
  const bar = screen.getByRole('progressbar', { name: 'Places found' });
  expect(bar.getAttribute('max')).toBe('50000');
  expect(bar.getAttribute('value')).toBe('3200');
  const region = screen.getByRole('status');
  expect(region.textContent).toBe('Finding places.');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Finding places… 3,900 of 50,000');
  expect(region.textContent).toBe('Finding places.');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Updating photo details… 5 of 9');
  expect(region.textContent).toBe('Updating photo details.');

  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('Finding places');
  expect(region.textContent).toBe('');
});

test('after a place step the places step is the re-check: "Checking photo places… 1,200 of 20,000", its bar "Places checked"', async () => {
  vi.useFakeTimers();
  const rechecking = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: true, failed: false, lost: 0 };
  getStatus.mockResolvedValueOnce({ ...updating(1200, 20000, 'places'), placeUpdate: rechecking })
    .mockResolvedValueOnce({ ...updating(0, 0, 'places'), placeUpdate: rechecking })
    .mockResolvedValueOnce({ ...updating(5, 9, 'places'), placeUpdate: { ...rechecking, rechecking: false } })
    .mockResolvedValue({ ...updating(5, 9, 'details'), placeUpdate: rechecking });
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Checking photo places… 1,200 of 20,000');
  expect(container.textContent).not.toContain('Finding places');
  const bar = screen.getByRole('progressbar', { name: 'Places checked' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['20000', '1200']);
  const region = screen.getByRole('status');
  expect(region.textContent).toBe('Checking photo places.');

  // no total yet: no count, a bar without an end
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Checking photo places…');
  expect(container.textContent).not.toContain('0 of 0');
  expect(screen.getByRole('progressbar', { name: 'Places checked' }).hasAttribute('max')).toBe(false);

  // the re-check done (or not this run's): places are found as today
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Finding places… 5 of 9');
  expect(region.textContent).toBe('Finding places.');

  // another step while the re-check is said: that step's words
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Reading photo details… 5 of 9');
  expect(container.textContent).not.toContain('Checking photo places');
});

test('photos a place update left unchecked, with the re-check not running: the footer says it finishes at the next start', async () => {
  getStatus.mockResolvedValue({ ...idle, placeUpdate: { note: false, formerPhotos: 0, notChecked: 0, pending: 1200, moved: 0, rechecking: false, failed: false, lost: 0 } });
  const { container } = render(Footer);
  await vi.waitFor(() => expect(container.textContent).toContain("1,200 photos haven't been checked yet; Butterfly Atlas will finish when it next starts."));
  expect(container.querySelector('.foot-pending').classList.contains('muted')).toBe(false);
});

test('no "not checked yet" line while the re-check runs', async () => {
  getStatus.mockResolvedValue({ ...updating(5, 9, 'places'), placeUpdate: { note: true, formerPhotos: 0, notChecked: 0, pending: 1200, moved: 0, rechecking: true, failed: false, lost: 0 } });
  const { container } = render(Footer);
  await vi.waitFor(() => expect(container.textContent).toContain('Checking photo places… 5 of 9'));
  expect(container.textContent).not.toContain("haven't been checked yet");
});

test('places with no total yet show no count and a bar without an end', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue(updating(0, 0, 'places'));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Finding places…');
  expect(container.textContent).not.toContain('0 of 0');
  const bar = screen.getByRole('progressbar', { name: 'Places found' });
  expect(bar.hasAttribute('max')).toBe(false);
  expect(bar.hasAttribute('value')).toBe(false);
  expect(screen.getByRole('status').textContent).toBe('Finding places.');
});

test('the status holds the running or paused export (null when none, and after a reset)', async () => {
  expect(status.export).toBeNull();
  const running = { id: 4, state: 'running', done: 3200, total: 50000, pausedBy: null, notice: null, path: '/Volumes/Backup/Butterfly_Atlas_export_2026-10-13' };
  getStatus.mockResolvedValueOnce({ ...idle, export: running }).mockResolvedValueOnce(idle);
  await refreshStatus();
  expect(status.export).toEqual(running);
  await refreshStatus();
  expect(status.export).toBeNull();

  getStatus.mockResolvedValueOnce({ ...idle, export: running });
  await refreshStatus();
  resetStatus();
  expect(status.export).toBeNull();
});

test('after an export action of this page the status follows its answer at once, asks again and tells the open screen', async () => {
  const running = { id: 4, state: 'running', done: 3200, total: 50000, pausedBy: null, notice: null, path: '/Volumes/Backup/x' };
  const heard = vi.fn();
  window.addEventListener(EXPORT_CHANGED, heard);
  getStatus.mockResolvedValue(idle);

  const paused = { ...running, state: 'paused', pausedBy: 'you', copied: 3200, bytesDone: 1 };
  exportChanged(paused);
  expect(status.export).toEqual({ id: 4, state: 'paused', done: 3200, total: 50000, pausedBy: 'you', notice: null, path: '/Volumes/Backup/x', rawCount: 0, heicCount: 0 });
  expect(heard.mock.calls[0][0].detail).toBe(paused);
  expect(getStatus).toHaveBeenCalledTimes(1);

  exportChanged({ ...running, state: 'cancelled' });
  expect(status.export).toBeNull();
  window.removeEventListener(EXPORT_CHANGED, heard);
});

const exporting = (over = {}) => ({
  ...idle,
  export: { id: 4, state: 'running', done: 3200, total: 50000, pausedBy: null, notice: null, path: '/Volumes/Backup/Butterfly_Atlas_export_2026-10-13', ...over },
});

test('a running export: its line and bar, the phase in the status region, asking again every 2 seconds while it runs', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(exporting()).mockResolvedValueOnce(exporting({ done: 3300 }))
    .mockResolvedValue(exporting({ state: 'paused', pausedBy: 'you', done: 3400 }));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Exporting photos… 3,200 of 50,000');
  const bar = screen.getByRole('progressbar', { name: 'Photos exported' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['50000', '3200']);
  expect(screen.getByRole('status').textContent).toBe('Exporting photos.');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Exporting photos… 3,300 of 50,000');
  await vi.advanceTimersByTimeAsync(2000);
  expect(screen.getByRole('status').textContent).toBe('');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(3);
});

test('the status region names an import before an export, and an export before previews', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce({ ...exporting(), import: { id: 3, rootName: 'Butterflies 2024', phase: 'filing', done: 1, total: 2 } })
    .mockResolvedValue({ ...exporting(), previews: { pending: 3, done: 1 } });
  render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  expect(screen.getByRole('status').textContent).toBe('Filing photos.');
  await vi.advanceTimersByTimeAsync(2000);
  expect(screen.getByRole('status').textContent).toBe('Exporting photos.');
});

test('a paused export: a quiet line with Continue export and Discard…; it does not keep the footer asking', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue(exporting({ state: 'paused', pausedBy: 'closing', notice: 'This export stopped when Butterfly Atlas closed.' }));
  const { container } = render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  const line = within(container).getByRole('group', { name: 'Paused export' });
  expect(line.textContent).toContain('Export paused at 3,200 of 50,000.');
  expect([...line.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Continue export', 'Discard…']);
  expect(container.querySelector('progress')).toBeNull();
  expect(screen.getByRole('status').textContent).toBe('');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(1);
});

test('Continue export resumes it (inactive while it is asked); a refusal is said in a toast', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockImplementation(() => new Promise(() => {}));
  let answer;
  resumeExport.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }))
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  render(Footer);
  const button = await screen.findByRole('button', { name: 'Continue export' });
  await fireEvent.click(button);
  expect(resumeExport).toHaveBeenCalledWith(4);
  expect(button.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(button);
  expect(resumeExport).toHaveBeenCalledTimes(1);
  answer({ ...exporting().export, state: 'paused', pausedBy: 'drive_gone' });
  await vi.waitFor(() => expect(button.getAttribute('aria-disabled')).toBe('false'));
  expect(status.export.pausedBy).toBe('drive_gone');

  await fireEvent.click(button);
  await vi.waitFor(() => expect(toast.message).toBe('Butterfly Atlas could not reach its service.'));
});

test('Discard… opens the stop dialog; stopped, the line goes and focus goes to the page', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockResolvedValue(idle);
  cancelExport.mockResolvedValue({ ...exporting().export, state: 'cancelled', cancel: 'remove' });
  const main = addMain();
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Discard…' }));
  const dialog = screen.getByRole('dialog', { name: 'Stop this export?' });
  await fireEvent.click(within(dialog).getByRole('radio', { name: 'Remove the files this export wrote' }));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Stop export' }));
  expect(cancelExport).toHaveBeenCalledWith(4, true);
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.queryByRole('group', { name: 'Paused export' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(main));
});

test('"Export finished." when a running export ends while the app is open; not when this page stopped it, nor when it pauses', async () => {
  render(Toast);
  getStatus.mockResolvedValueOnce(exporting()).mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'drive_full' }))
    .mockResolvedValueOnce(exporting()).mockResolvedValueOnce(idle);
  const seq = toast.seq;
  await refreshStatus();
  await refreshStatus();
  expect(toast.seq).toBe(seq);
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Export finished.');
  expect(toast.seq).toBe(seq + 1);

  getStatus.mockResolvedValueOnce(exporting()).mockResolvedValue(idle);
  await refreshStatus();
  exportChanged({ ...exporting().export, state: 'cancelled', cancel: 'keep' });
  await refreshStatus();
  expect(toast.seq).toBe(seq + 1);
});

test('a followed export cancelled in another window says so, not "Export finished."; one that can\'t be read says finished (G2)', async () => {
  render(Toast);
  getStatus.mockResolvedValueOnce(exporting()).mockResolvedValueOnce(idle);
  getExport.mockResolvedValue({ ...exporting().export, state: 'cancelled', cancel: 'keep' });
  await refreshStatus();
  await refreshStatus();
  expect(getExport).toHaveBeenCalledWith(4);
  expect(toast.message).toBe('The export was cancelled in another window.');

  getStatus.mockResolvedValueOnce(exporting()).mockResolvedValueOnce(idle);
  getExport.mockResolvedValue({ ...exporting().export, state: 'done', done: 50000 });
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Export finished.');

  getStatus.mockResolvedValueOnce(exporting({ rawCount: 2 })).mockResolvedValueOnce(idle);
  getExport.mockRejectedValue(new Error('down'));
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Export finished. 2 RAW photos exported with their location.');
});

test('a dialog opened from the footer has the page\'s type, not the footer\'s small one', () => {
  const atlasCss = readFileSync(`${process.cwd()}/src/styles/atlas.css`, 'utf8');
  expect(atlasCss).toMatch(/\.dlg-bg \{[^}]*font: 15px\/1\.55 var\(--body\)/);
});

test('Discard… on an export that has already finished closes the dialog and reads the status again', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockResolvedValue(idle);
  cancelExport.mockRejectedValue(Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 }));
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Discard…' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop export' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await vi.waitFor(() => expect(screen.queryByRole('group', { name: 'Paused export' })).toBeNull());
  expect(getStatus).toHaveBeenCalledTimes(2);
});

test('while Continue export is asked, Discard… is inactive and opens nothing', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockImplementation(() => new Promise(() => {}));
  let answer;
  resumeExport.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Continue export' }));
  const discard = screen.getByRole('button', { name: 'Discard…' });
  expect(discard.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(discard);
  expect(screen.queryByRole('dialog')).toBeNull();
  answer({ ...exporting().export, state: 'paused', pausedBy: 'drive_gone' });
  await vi.waitFor(() => expect(discard.getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(discard);
  expect(screen.getByRole('dialog', { name: 'Stop this export?' })).toBeTruthy();
});

test('Continue export refused because the export has already finished says nothing: the status is read again and the line goes', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockResolvedValue(idle);
  resumeExport.mockRejectedValue(Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 }));
  const heard = vi.fn();
  window.addEventListener(EXPORT_CHANGED, heard);
  const seq = toast.seq;
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Continue export' }));
  await vi.waitFor(() => expect(screen.queryByRole('group', { name: 'Paused export' })).toBeNull());
  expect(toast.seq).toBe(seq);
  expect(heard.mock.calls[0][0].detail).toBeNull();
  window.removeEventListener(EXPORT_CHANGED, heard);
});

test('the paused line going away with its stop dialog open (resumed or finished elsewhere) sends focus to the page\'s main', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockResolvedValue(exporting());
  const main = addMain();
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Discard…' }));
  expect(screen.getByRole('dialog', { name: 'Stop this export?' })).toBeTruthy();
  await refreshStatus();
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.queryByRole('group', { name: 'Paused export' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(main));
});

test('after Continue export focus goes to the page\'s main only from the line or the page, not from elsewhere', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockImplementation(() => new Promise(() => {}));
  const main = addMain();
  const other = document.createElement('button');
  main.append(other);
  let answer;
  resumeExport.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  const { unmount } = render(Footer);
  const resume = await screen.findByRole('button', { name: 'Continue export' });
  resume.focus();
  await fireEvent.click(resume);
  other.focus();
  answer({ ...exporting().export });
  await vi.waitFor(() => expect(screen.queryByRole('group', { name: 'Paused export' })).toBeNull());
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(document.activeElement).toBe(other);
  unmount();

  resetStatus();
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockImplementation(() => new Promise(() => {}));
  render(Footer);
  const again = await screen.findByRole('button', { name: 'Continue export' });
  again.focus();
  await fireEvent.click(again);
  answer({ ...exporting().export });
  await vi.waitFor(() => expect(document.activeElement).toBe(main));
});

const taxonomy = (over = {}) => ({
  ...idle,
  taxonomyUpdate: { state: 'running', kind: 'bundle', later: false, done: 120, total: 340, step: 1, steps: 2, continuing: false, waitingFor: null, notice: null, ...over },
  splitsToSort: 0,
  customMatches: 0,
});

test('the status holds the place update (null when none, and after a reset)', async () => {
  expect(status.placeUpdate).toBeNull();
  const placeUpdate = { note: true, formerPhotos: 12, notChecked: 0, pending: 0, moved: 0, rechecking: true, failed: false, lost: 0 };
  getStatus.mockResolvedValueOnce({ ...idle, placeUpdate }).mockResolvedValueOnce(idle);
  await refreshStatus();
  expect(status.placeUpdate).toEqual(placeUpdate);
  await refreshStatus();
  expect(status.placeUpdate).toBeNull();

  getStatus.mockResolvedValueOnce({ ...idle, placeUpdate });
  await refreshStatus();
  resetStatus();
  expect(status.placeUpdate).toBeNull();
});

test('the status holds the taxonomy update, the splits to sort and the open matches (none after a reset)', async () => {
  expect([status.taxonomyUpdate, status.splitsToSort, status.customMatches]).toEqual([null, 0, 0]);
  getStatus.mockResolvedValueOnce({ ...taxonomy(), splitsToSort: 2, customMatches: 1 }).mockResolvedValueOnce(idle);
  await refreshStatus();
  expect(status.taxonomyUpdate).toEqual(taxonomy().taxonomyUpdate);
  expect([status.splitsToSort, status.customMatches]).toEqual([2, 1]);
  await refreshStatus();
  expect([status.taxonomyUpdate, status.splitsToSort, status.customMatches]).toEqual([null, 0, 0]);

  getStatus.mockResolvedValueOnce({ ...taxonomy(), splitsToSort: 2 });
  await refreshStatus();
  resetStatus();
  expect([status.taxonomyUpdate, status.splitsToSort]).toEqual([null, 0]);
});

test('a running taxonomy update: its line and bar, named first in the status region, asking again every 2 seconds', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce({ ...taxonomy(), rescanning: true, rescan: { checked: 1, total: 2, finishedAt: null } })
    .mockResolvedValueOnce(taxonomy({ done: 200 })).mockResolvedValue(taxonomy({ state: 'done', done: 340 }));
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Updating taxonomy… 120 of 340 photos');
  const bar = screen.getByRole('progressbar', { name: 'Photos moved' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['340', '120']);
  expect(screen.getByRole('status').textContent).toBe('Updating the taxonomy.');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Updating taxonomy… 200 of 340 photos');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('Updating taxonomy');
  expect(screen.getByRole('status').textContent).toBe('');
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(3);
});

test('an update waiting for an import or an export says so with a bar without an end, and keeps asking', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(taxonomy({ state: 'waiting', waitingFor: 'import', done: 0, total: 0 }))
    .mockResolvedValue(taxonomy({ state: 'waiting', waitingFor: 'export', done: 0, total: 0 }));
  const { container } = render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Waiting for the import to finish filing…');
  expect(screen.getByRole('progressbar', { name: 'Photos moved' }).hasAttribute('value')).toBe(false);
  expect(screen.getByRole('status').textContent).toBe('Updating the taxonomy.');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Waiting for the export to finish copying…');
  expect(getStatus).toHaveBeenCalledTimes(2);
});

test('a paused taxonomy update: "Taxonomy update paused." and Retry; it does not keep the footer asking', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue(taxonomy({ state: 'paused', notice: { code: 'drive_full', message: 'The drive with your library is full.' } }));
  const { container } = render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  const line = within(container).getByRole('group', { name: 'Paused taxonomy update' });
  expect(line.textContent).toContain('Taxonomy update paused.');
  expect([...line.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Retry']);
  expect(container.querySelector('progress')).toBeNull();
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(1);
});

test('Retry runs the update again (inactive while it is asked), the footer follows its answer; a refusal is said in a toast', async () => {
  getStatus.mockResolvedValueOnce(taxonomy({ state: 'paused', notice: { code: 'locked', message: 'Close it.' } }))
    .mockImplementation(() => new Promise(() => {}));
  let answer;
  retryUpdate.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }))
    .mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  const main = addMain();
  const { container } = render(Footer);
  const button = await screen.findByRole('button', { name: 'Retry' });
  button.focus();
  await fireEvent.click(button);
  expect(retryUpdate).toHaveBeenCalledTimes(1);
  expect(button.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(button);
  expect(retryUpdate).toHaveBeenCalledTimes(1);
  answer({ state: 'running', kind: 'bundle', later: false, progress: { step: 1, steps: 2, stage: 'moves', done: 121, total: 340, continuing: false, waitingFor: null }, notice: null });
  await vi.waitFor(() => expect(container.textContent).toContain('Updating taxonomy… 121 of 340 photos'));
  expect(screen.queryByRole('group', { name: 'Paused taxonomy update' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(main));

  status.taxonomyUpdate = taxonomy({ state: 'paused', notice: { code: 'locked', message: 'Close it.' } }).taxonomyUpdate;
  await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
  await vi.waitFor(() => expect(toast.message).toBe('Butterfly Atlas could not reach its service.'));
});

test('Retry refused because the update is no longer paused reads the status again, with no toast', async () => {
  getStatus.mockResolvedValueOnce(taxonomy({ state: 'paused', notice: { code: 'locked', message: 'Close it.' } })).mockResolvedValue(taxonomy());
  retryUpdate.mockRejectedValue(Object.assign(new Error('The taxonomy update is not paused.'), { code: 'not_paused', status: 409 }));
  const seq = toast.seq;
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Retry' }));
  await vi.waitFor(() => expect(screen.queryByRole('group', { name: 'Paused taxonomy update' })).toBeNull());
  expect(toast.seq).toBe(seq);
});

test('an update that finishes while the app is open says so and tells the open screen to reload', async () => {
  const heard = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, heard);
  getStatus.mockResolvedValueOnce(taxonomy()).mockResolvedValueOnce(taxonomy({ state: 'done', done: 340 }))
    .mockResolvedValueOnce(taxonomy({ state: 'done', done: 340 }))
    .mockResolvedValueOnce(taxonomy({ state: 'running', kind: 'match', done: 0, total: 4 })).mockResolvedValueOnce(idle);
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Taxonomy update finished.');
  expect(toast.link).toEqual({ href: '#/update', label: 'See what changed' });
  expect(heard).toHaveBeenCalledTimes(1);
  const seq = toast.seq;
  await refreshStatus();
  expect(toast.seq).toBe(seq);
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Custom species merged.');
  expect(heard).toHaveBeenCalledTimes(2);
  window.removeEventListener(LIBRARY_CHANGED, heard);
});

test('after an update action of this page the status follows its answer at once and asks again', async () => {
  getStatus.mockResolvedValue(idle);
  updateChanged({ state: 'waiting', kind: 'bundle', later: false, progress: { step: 1, steps: 1, stage: 'plan', done: 0, total: 0, continuing: false, waitingFor: 'import' }, notice: null });
  expect(status.taxonomyUpdate).toMatchObject({ state: 'waiting', waitingFor: 'import', steps: 1 });
  expect(getStatus).toHaveBeenCalledTimes(1);
});

test('Continue export refused while the taxonomy updates: the toast carries a Taxonomy update link', async () => {
  getStatus.mockResolvedValueOnce(exporting({ state: 'paused', pausedBy: 'you' })).mockImplementation(() => new Promise(() => {}));
  resumeExport.mockRejectedValue(Object.assign(new Error('Wait for the taxonomy update to finish.'), { code: 'taxonomy_updating', status: 409 }));
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Continue export' }));
  await vi.waitFor(() => expect(toast.message).toBe('Wait for the taxonomy update to finish.'));
  expect(toast.link).toEqual({ href: '#/update', label: 'Taxonomy update' });
});

test('the paused line says why and links to the update screen', async () => {
  getStatus.mockResolvedValue(taxonomy({ state: 'paused', notice: { code: 'drive_gone', message: "The drive with your library can't be found. Reconnect it, then choose Retry." } }));
  const { container } = render(Footer);
  const line = await within(container).findByRole('group', { name: 'Paused taxonomy update' });
  expect(line.textContent).toContain("Taxonomy update paused. The drive with your library can't be found. Reconnect it, then choose Retry.");
  expect(within(line).getByRole('link', { name: 'Taxonomy update' }).getAttribute('href')).toBe('#/update');
});

test('an update that pauses while the app is open is announced, with a link to see why', async () => {
  getStatus.mockResolvedValueOnce(taxonomy()).mockResolvedValueOnce(taxonomy({ state: 'paused', notice: { code: 'drive_full', message: 'The drive with your library is full.' } }))
    .mockResolvedValueOnce(taxonomy({ state: 'paused', notice: { code: 'drive_full', message: 'The drive with your library is full.' } }));
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('The taxonomy update paused: The drive with your library is full.');
  expect(toast.link).toEqual({ href: '#/update', label: 'Taxonomy update' });
  const seq = toast.seq;
  await refreshStatus();
  expect(toast.seq).toBe(seq);
});

const pausedLocked = () => taxonomy({ state: 'paused', notice: { code: 'locked', message: 'Danaus_chrysippus_03.jpg is open in another program. Close it, then choose Retry.' } });

test('a Retry that does not take (409 not_resumed) says so under the notice, with the time and the message', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date(2026, 9, 15, 14, 5));
  getStatus.mockResolvedValue(pausedLocked());
  retryUpdate.mockRejectedValue(Object.assign(new Error('Tried again, but the file is still open in another program. Close it, then choose Retry.'),
    { code: 'not_resumed', status: 409 }));
  const { container } = render(Footer);
  const line = await within(container).findByRole('group', { name: 'Paused taxonomy update' });
  await fireEvent.click(within(line).getByRole('button', { name: 'Retry' }));
  await vi.waitFor(() => expect(line.textContent).toContain('Tried again at 14:05 — still paused. Tried again, but the file is still open in another program. Close it, then choose Retry.'));
});

test('the same pause coming back right after a Retry says so; once it runs the note goes', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date(2026, 9, 15, 9, 7));
  getStatus.mockResolvedValueOnce(pausedLocked()).mockResolvedValueOnce(pausedLocked()).mockResolvedValueOnce(taxonomy());
  retryUpdate.mockResolvedValue({ state: 'running', kind: 'bundle', progress: { step: 1, steps: 2, stage: 'moves', done: 120, total: 340 }, notice: null });
  const { container } = render(Footer);
  await fireEvent.click(await within(container).findByRole('button', { name: 'Retry' }));
  const line = await within(container).findByRole('group', { name: 'Paused taxonomy update' });
  // the notice is not said twice: the note is only that it is still paused
  await vi.waitFor(() => expect(line.querySelector('span').textContent).toMatch(/open in another program\. Close it, then choose Retry\. Tried again at 09:07 — still paused\.$/));
  await refreshStatus();
  await vi.waitFor(() => expect(within(container).queryByRole('group', { name: 'Paused taxonomy update' })).toBeNull());
  expect(status.retryNote).toBeNull();
});

test('three failed reads in a row while the update runs: "Butterfly Atlas isn\'t answering…"; gone once it answers', async () => {
  vi.useFakeTimers();
  const gone = new Error('offline');
  getStatus.mockResolvedValueOnce(taxonomy()).mockRejectedValueOnce(gone).mockRejectedValueOnce(gone).mockRejectedValueOnce(gone)
    .mockResolvedValue(taxonomy({ done: 200 }));
  const { container } = render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(4000);
  expect(within(container).queryByRole('alert')).toBeNull();
  await vi.advanceTimersByTimeAsync(2000);
  expect(within(container).getByRole('alert').textContent)
    .toBe("Butterfly Atlas isn't answering. If you closed it, open it again — the update carries on where it stopped.");
  expect(container.textContent).toContain('Updating taxonomy… 120 of 340 photos');
  await vi.advanceTimersByTimeAsync(2000);
  expect(within(container).queryByRole('alert')).toBeNull();
  expect(container.textContent).toContain('Updating taxonomy… 200 of 340 photos');
});

test('a Retry refused with a pause code (409 drive_full) goes to the note, not a toast; a new pause code clears the note', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date(2026, 9, 15, 14, 5));
  getStatus.mockResolvedValueOnce(pausedLocked())
    .mockResolvedValue(taxonomy({ state: 'paused', notice: { code: 'drive_gone', message: "The drive with your library can't be found." } }));
  retryUpdate.mockRejectedValue(Object.assign(new Error('The drive with your library is full. Free some space, then choose Retry.'), { code: 'drive_full', status: 409 }));
  const seq = toast.seq;
  const { container } = render(Footer);
  const line = await within(container).findByRole('group', { name: 'Paused taxonomy update' });
  await fireEvent.click(within(line).getByRole('button', { name: 'Retry' }));
  await vi.waitFor(() => expect(line.textContent).toContain('Tried again at 14:05 — still paused. The drive with your library is full. Free some space, then choose Retry.'));
  expect(toast.seq).toBe(seq);
  expect(status.retryNote.code).toBe('locked');

  await refreshStatus();
  await vi.waitFor(() => expect(status.retryNote).toBeNull());
  expect(within(container).getByRole('group', { name: 'Paused taxonomy update' }).textContent).not.toContain('Tried again');
});

test('not answering while other work runs (an export) says so without the update\'s words', async () => {
  vi.useFakeTimers();
  const gone = new Error('offline');
  getStatus.mockResolvedValueOnce(exporting()).mockRejectedValue(gone);
  const { container } = render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  await vi.advanceTimersByTimeAsync(6000);
  expect(within(container).getByRole('alert').textContent).toBe("Butterfly Atlas isn't answering. If you closed it, open it again.");
});

test('a merge that finishes while a bundle update is pending (the status reads pending at once) is still said, with its link', async () => {
  const heard = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, heard);
  getStatus.mockResolvedValueOnce(taxonomy({ kind: 'match', done: 1, total: 4 })).mockResolvedValueOnce(taxonomy({ state: 'pending', kind: 'bundle', done: 0, total: 0 }));
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Custom species merged.');
  expect(toast.link).toEqual({ href: '#/update', label: 'See what changed' });
  expect(status.finished).toEqual({ kind: 'match' });
  expect(heard).toHaveBeenCalledTimes(1);
  window.removeEventListener(LIBRARY_CHANGED, heard);
});

test('after a Retry the same pause coming back gives the retry note and no pause toast, even through a poll that saw it running', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date(2026, 9, 15, 11, 30));
  getStatus.mockResolvedValueOnce(pausedLocked()).mockResolvedValueOnce(taxonomy({ done: 121 })).mockResolvedValueOnce(pausedLocked());
  retryUpdate.mockResolvedValue({ state: 'running', kind: 'bundle', progress: { step: 1, steps: 2, stage: 'moves', done: 120, total: 340 }, notice: null });
  const { container } = render(Footer);
  await fireEvent.click(await within(container).findByRole('button', { name: 'Retry' }));
  const seq = toast.seq;
  await refreshStatus();
  await refreshStatus();
  expect(status.retryNote).toEqual({ at: '11:30', message: null, code: 'locked' });
  expect(toast.seq).toBe(seq);
});

test('a toast never links to the page already shown', () => {
  window.location.hash = '#/update';
  showToast('The taxonomy update paused: The drive is full.', { link: { href: '#/update', label: 'Taxonomy update' } });
  expect(toast.link).toBeNull();
  expect(toast.ms).toBe(2600);
  window.location.hash = '#/';
  showToast('Wait for the taxonomy update to finish.', { link: { href: '#/update', label: 'Taxonomy update' } });
  expect(toast.link).toEqual({ href: '#/update', label: 'Taxonomy update' });
});

test('a request refused for the library\'s drive reads the status at once; while the drive is full the status is asked every 5 s until it clears', async () => {
  vi.useFakeTimers();
  const library = { code: 'drive_full', message: 'The drive with your library is full.' };
  getStatus.mockResolvedValueOnce(idle).mockResolvedValueOnce({ ...idle, library }).mockResolvedValueOnce({ ...idle, library })
    .mockResolvedValue(idle);
  render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  expect(getStatus).toHaveBeenCalledTimes(1);
  window.dispatchEvent(new CustomEvent('atlas:library-problem'));
  await vi.advanceTimersByTimeAsync(0);
  expect(getStatus).toHaveBeenCalledTimes(2);
  expect(status.library).toEqual(library);
  await vi.advanceTimersByTimeAsync(4900);
  expect(getStatus).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(100);
  expect(getStatus).toHaveBeenCalledTimes(3);
  await vi.advanceTimersByTimeAsync(5000);
  expect(getStatus).toHaveBeenCalledTimes(4);
  expect(status.library).toBeNull();
  await vi.advanceTimersByTimeAsync(20000);
  expect(getStatus).toHaveBeenCalledTimes(4);
});

test('the library back (library set, then null) tells the open screen to read again', async () => {
  const heard = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, heard);
  getStatus.mockResolvedValueOnce({ ...idle, library: { code: 'drive_full', message: 'Full.' } }).mockResolvedValueOnce({ ...idle, library: { code: 'drive_full', message: 'Full.' } })
    .mockResolvedValueOnce(idle).mockResolvedValueOnce(idle);
  await refreshStatus();
  await refreshStatus();
  expect(heard).not.toHaveBeenCalled();
  await refreshStatus();
  expect(heard).toHaveBeenCalledTimes(1);
  await refreshStatus();
  expect(heard).toHaveBeenCalledTimes(1);
  window.removeEventListener(LIBRARY_CHANGED, heard);
});

test('says in the footer that the app runs in a browser tab, and that the drive cannot prevent a second copy; neither can be dismissed', async () => {
  getStatus.mockResolvedValue(idle);
  session.tabNote = 'Running in a browser tab. Closing it quits the app.';
  session.lockNote = 'This drive cannot prevent a second copy from opening this library. Open only one.';
  render(Footer);

  expect(screen.getByText('Running in a browser tab. Closing it quits the app.')).toBeTruthy();
  expect(screen.getByText('This drive cannot prevent a second copy from opening this library. Open only one.')).toBeTruthy();
  expect(screen.queryByRole('button')).toBeNull();
});

test('has neither note by default', async () => {
  getStatus.mockResolvedValue(idle);
  render(Footer);
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalled());
  expect(screen.queryByText(/browser tab/)).toBeNull();
  expect(screen.queryByText(/second copy/)).toBeNull();
});

const backingUp = (over = {}) => ({
  ...idle,
  backup: { id: 2, state: 'running', done: 3210, total: 12400, pausedBy: null, notice: null, path: '/Volumes/B/Butterfly_Atlas_backup_2026-10-21', ...over },
});

test('a running backup: its line and bar, the phase in the status region, asking again every 2 seconds while it runs', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValueOnce(backingUp()).mockResolvedValueOnce(backingUp({ done: 4000 })).mockResolvedValue(idle);
  const { container } = render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Backing up your library — 3,210 of 12,400 photos');
  expect(screen.getByRole('progressbar', { name: 'Photos backed up' }).getAttribute('value')).toBe('3210');
  expect(screen.getByRole('status').textContent).toBe('Backing up your library.');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('4,000 of 12,400');
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).not.toContain('Backing up');
});

test('a backup of the database alone, which has no photo count, says so without numbers', async () => {
  getStatus.mockResolvedValue(backingUp({ done: 0, total: 0 }));
  const { container } = render(Footer);
  await vi.waitFor(() => expect(container.textContent).toContain('Backing up your library…'));
  expect(container.textContent).not.toContain('0 of 0');
});

test('the status holds the running or paused backup (null when none, and after a reset)', async () => {
  expect(status.backup).toBeNull();
  getStatus.mockResolvedValueOnce(backingUp()).mockResolvedValueOnce(idle);
  await refreshStatus();
  expect(status.backup).toEqual(backingUp().backup);
  await refreshStatus();
  expect(status.backup).toBeNull();
  getStatus.mockResolvedValueOnce(backingUp());
  await refreshStatus();
  resetStatus();
  expect(status.backup).toBeNull();
});

test('after a backup action of this page the status follows its answer, asks again and tells the open screen', async () => {
  const heard = vi.fn();
  window.addEventListener(BACKUP_CHANGED, heard);
  getStatus.mockResolvedValue(idle);
  const paused = { ...backingUp().backup, state: 'paused', pausedBy: 'user', copied: 3210, bytesDone: 1 };
  backupChanged(paused);
  expect(status.backup).toEqual({ id: 2, state: 'paused', done: 3210, total: 12400, pausedBy: 'user', notice: null, path: paused.path, holdsPhotos: false });
  expect(heard.mock.calls[0][0].detail).toBe(paused);
  expect(getStatus).toHaveBeenCalledTimes(1);
  backupChanged({ ...paused, state: 'cancelled' });
  expect(status.backup).toBeNull();
  window.removeEventListener(BACKUP_CHANGED, heard);
});

test('"Backup finished." when a running backup ends while the app is open, with what could not be copied when the server says so; not when it pauses', async () => {
  render(Toast);
  const heard = vi.fn();
  window.addEventListener(BACKUP_CHANGED, heard);
  getStatus.mockResolvedValueOnce(backingUp()).mockResolvedValueOnce(backingUp({ state: 'paused', pausedBy: 'drive' }))
    .mockResolvedValueOnce(backingUp()).mockResolvedValueOnce(idle);
  getBackup.mockResolvedValue({ id: 2, state: 'done', skipped: 3, failed: 1 });
  const seq = toast.seq;
  await refreshStatus();
  await refreshStatus();
  expect(toast.seq).toBe(seq);
  await refreshStatus();
  await refreshStatus();
  await vi.waitFor(() => expect(toast.message).toBe('Backup finished. 4 photos could not be copied.'));
  expect(toast.link).toMatchObject({ href: '#/settings', label: 'See which' });
  expect(heard).toHaveBeenCalled();
  window.removeEventListener(BACKUP_CHANGED, heard);
});

test('"Backup finished." says only that when nothing was left out, or when its details cannot be read', async () => {
  render(Toast);
  getBackup.mockResolvedValueOnce({ id: 2, state: 'done', skipped: 0, failed: 0 });
  getStatus.mockResolvedValueOnce(backingUp()).mockResolvedValueOnce(idle).mockResolvedValueOnce(backingUp()).mockResolvedValueOnce(idle);
  await refreshStatus();
  await refreshStatus();
  await vi.waitFor(() => expect(toast.message).toBe('Backup finished.'));
  const seq = toast.seq;
  getBackup.mockRejectedValueOnce(new Error('down'));
  await refreshStatus();
  await refreshStatus();
  await vi.waitFor(() => expect(toast.seq).toBe(seq + 1));
  expect(toast.message).toBe('Backup finished.');
});

test('a backup this page cancelled is not "Backup finished."; a database-only one that is done in the answer says so at once', async () => {
  render(Toast);
  getStatus.mockResolvedValue(idle);
  const seq = toast.seq;
  getStatus.mockResolvedValueOnce(backingUp()).mockResolvedValue(idle);
  await refreshStatus();
  backupChanged({ ...backingUp().backup, state: 'cancelled' });
  await refreshStatus();
  expect(toast.seq).toBe(seq);

  getBackup.mockResolvedValue({ id: 3, state: 'done', skipped: 0, failed: 0 });
  backupChanged({ id: 3, state: 'done', what: 'database', done: 0, total: 0, skipped: 0, failed: 0, path: '/Volumes/B/x' });
  await vi.waitFor(() => expect(toast.message).toBe('Backup finished.'));
});

test('a backup the app\'s closing stopped: "Backup stopped — the app was closed" with Continue backup and Discard…, not asking again', async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue(backingUp({ state: 'paused', pausedBy: 'closed' }));
  const { container } = render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  const line = within(container).getByRole('group', { name: 'Paused backup' });
  expect(line.textContent).toContain('Backup stopped — the app was closed');
  expect([...line.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Continue backup', 'Discard…']);
  expect(container.querySelector('progress')).toBeNull();
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(1);
});

test('a paused backup says where it paused and why: the drive\'s notice is in the footer too', async () => {
  getStatus.mockResolvedValue(backingUp({ state: 'paused', pausedBy: 'drive', notice: 'The drive with the backup is full. Free some space, then continue.' }));
  const { container } = render(Footer);
  const line = await screen.findByRole('group', { name: 'Paused backup' });
  expect(line.textContent).toContain('Backup paused at 3,210 of 12,400.');
  expect(line.textContent).toContain('The drive with the backup is full. Free some space, then continue.');
  expect(container.querySelectorAll('[role="group"]')).toHaveLength(1);
});

test('Continue backup resumes it (inactive while it is asked); a refusal is said in a toast', async () => {
  getStatus.mockResolvedValueOnce(backingUp({ state: 'paused', pausedBy: 'closed' })).mockImplementation(() => new Promise(() => {}));
  let answer;
  resumeBackup.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }))
    .mockRejectedValueOnce(new Error('A taxonomy update is running. Wait for it to finish.'));
  render(Footer);
  const button = await screen.findByRole('button', { name: 'Continue backup' });
  await fireEvent.click(button);
  expect(resumeBackup).toHaveBeenCalledWith(2);
  expect(button.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(button);
  expect(resumeBackup).toHaveBeenCalledTimes(1);
  answer({ ...backingUp().backup, state: 'paused', pausedBy: 'drive' });
  await vi.waitFor(() => expect(button.getAttribute('aria-disabled')).toBe('false'));
  expect(status.backup.pausedBy).toBe('drive');
  await fireEvent.click(button);
  await vi.waitFor(() => expect(toast.message).toBe('A taxonomy update is running. Wait for it to finish.'));
});

test('Discard… on a backup opens the stop dialog; stopped, the line goes and focus goes to the page', async () => {
  getStatus.mockResolvedValueOnce(backingUp({ state: 'paused', pausedBy: 'closed' })).mockResolvedValue(idle);
  cancelBackup.mockResolvedValue({ ...backingUp().backup, state: 'cancelled', cancel: 'keep', notRemoved: 0 });
  const main = addMain();
  render(Footer);
  await fireEvent.click(await screen.findByRole('button', { name: 'Discard…' }));
  const dialog = screen.getByRole('dialog', { name: 'Stop this backup?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Stop backup' }));
  expect(cancelBackup).toHaveBeenCalledWith(2, false);
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(screen.queryByRole('group', { name: 'Paused backup' })).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(main));
});

test('"Export finished." says how many RAW photos went out with their location (Phase 7a)', async () => {
  render(Toast);
  getStatus.mockResolvedValueOnce(exporting({ rawCount: 12 })).mockResolvedValueOnce(idle);
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Export finished. 12 RAW photos exported with their location.');
});

/* ---------- Phase 7b: the HEIC decoder and import previews ---------- */

const heicAs = (state, extra = {}) => ({ state, decoder: state === 'available' ? 'sips' : null, system: 'mac', reason: state === 'missing' ? 'failed' : null, ...extra });

test('while the HEIC decoder check runs the status is asked every 2 s, and a change of its state reloads the lists (audit 1)', async () => {
  vi.useFakeTimers();
  const changed = vi.fn();
  const heicChanged = vi.fn();
  const again = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  window.addEventListener(HEIC_CHANGED, heicChanged);
  window.addEventListener(THUMBS_AGAIN, again);
  getStatus.mockResolvedValueOnce({ ...idle, heic: heicAs('checking') }).mockResolvedValueOnce({ ...idle, heic: heicAs('checking') })
    .mockResolvedValue({ ...idle, heic: heicAs('available') });
  render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(status.heic.state).toBe('checking');
  // The first answer is how the page found it: nothing changed.
  expect(changed).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(2000);
  expect(changed).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(2000);
  expect(status.heic).toEqual(heicAs('available'));
  expect(changed).toHaveBeenCalledOnce();
  expect(heicChanged).toHaveBeenCalledOnce();
  expect(again).toHaveBeenCalledOnce();
  // Nothing to follow any more.
  await vi.advanceTimersByTimeAsync(6000);
  expect(getStatus).toHaveBeenCalledTimes(3);
  window.removeEventListener(LIBRARY_CHANGED, changed);
  window.removeEventListener(HEIC_CHANGED, heicChanged);
  window.removeEventListener(THUMBS_AGAIN, again);
});

test('the status is read again when the window comes back to the front (audit 1)', async () => {
  getStatus.mockResolvedValue(idle);
  render(Footer);
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalledTimes(1));

  window.dispatchEvent(new Event('focus'));
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalledTimes(2));
  Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalledTimes(3));
  Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
  document.dispatchEvent(new Event('visibilitychange'));
  await Promise.resolve();
  expect(getStatus).toHaveBeenCalledTimes(3);
  delete document.visibilityState;
});

test('placeholder thumbnails are asked for again when the previews still being made reach none (audit 19)', async () => {
  vi.useFakeTimers();
  const again = vi.fn();
  window.addEventListener(THUMBS_AGAIN, again);
  getStatus.mockResolvedValueOnce({ ...idle, previews: { pending: 2, done: 1 } }).mockResolvedValue({ ...idle, previews: { pending: 0, done: 3 } });
  render(Footer);

  await vi.advanceTimersByTimeAsync(0);
  expect(again).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(2000);
  expect(again).toHaveBeenCalledOnce();
  window.removeEventListener(THUMBS_AGAIN, again);
});

test('Check again takes the answer at once and tells the open screens when the state changed', async () => {
  const changed = vi.fn();
  window.addEventListener(LIBRARY_CHANGED, changed);
  getStatus.mockResolvedValue({ ...idle, heic: heicAs('missing') });
  await refreshStatus();
  checkHeic.mockResolvedValueOnce(heicAs('missing')).mockResolvedValueOnce(heicAs('available'));

  await expect(checkHeicAgain()).resolves.toEqual(heicAs('missing'));
  expect(changed).not.toHaveBeenCalled();
  await expect(checkHeicAgain()).resolves.toEqual(heicAs('available'));
  expect(status.heic.state).toBe('available');
  expect(changed).toHaveBeenCalledOnce();
  window.removeEventListener(LIBRARY_CHANGED, changed);
});

test("an open import's HEIC previews being made count in Preparing previews, and are followed (audit 10)", async () => {
  vi.useFakeTimers();
  getStatus.mockResolvedValue({ ...idle, previews: { pending: 1, done: 4 } });
  const { container } = render(Footer);
  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).toContain('Preparing previews… 4 of 5');

  setImportPreviews({ count: 214, unshowable: 0, making: 200 });
  getStatus.mockResolvedValue(idle);
  await vi.advanceTimersByTimeAsync(2000);
  expect(container.textContent).toContain('Preparing previews… 14 of 214');
  expect(screen.getByRole('progressbar', { name: 'Previews ready' }).getAttribute('max')).toBe('214');
  // Still made: the status keeps being asked.
  await vi.advanceTimersByTimeAsync(2000);
  expect(getStatus).toHaveBeenCalledTimes(3);

  setImportPreviews(null);
  await vi.advanceTimersByTimeAsync(0);
  expect(container.textContent).not.toContain('Preparing previews');
});

test("a page's rescan note and decoder state come from the status, null and empty from an older server", async () => {
  getStatus.mockResolvedValue({ ...idle, heic: heicAs('missing'), rescanLeft: [{ reason: 'HEIC image sequences aren\'t supported.', count: 1 }] });
  await refreshStatus();
  expect(status.rescanLeft).toEqual([{ reason: "HEIC image sequences aren't supported.", count: 1 }]);
  getStatus.mockResolvedValue(idle);
  await refreshStatus();
  expect(status.heic).toBeNull();
  expect(status.rescanLeft).toEqual([]);
});

test('"Export finished." says how many HEIC photos went out with their location (Phase 7b)', async () => {
  render(Toast);
  getStatus.mockResolvedValueOnce(exporting({ rawCount: 0, heicCount: 30 })).mockResolvedValueOnce(idle);
  await refreshStatus();
  await refreshStatus();
  expect(toast.message).toBe('Export finished. 30 HEIC photos exported with their location.');
});

test('an export that finished while this page was stopping it, the stop then failing, says its HEIC photos too (7b review fix U9)', async () => {
  render(Toast);
  getStatus.mockResolvedValueOnce(exporting({ rawCount: 0, heicCount: 1 })).mockResolvedValueOnce(idle);
  await refreshStatus();
  exportStopping(status.export.id);
  await refreshStatus();
  exportStopping(null);
  expect(toast.message).toBe('Export finished. 1 HEIC photo exported with its location.');
});

const HOLD = "While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or cancel it and start a new backup after your changes.";
const HOLD_STOPPED = "While this backup is unfinished, photos can't be added, moved or deleted. Let it finish, or discard it and start a new backup after your changes.";

test('while a backup holds the photos the footer says so, not muted: discard for one stopped by closing the app, cancel otherwise; not once it lets go (A6, A22)', async () => {
  getStatus.mockResolvedValueOnce(backingUp({ holdsPhotos: true }));
  await refreshStatus();
  const { container } = render(Footer);
  const line = await screen.findByText(HOLD);
  expect(line.className).toBe('foot-hold');
  expect(container.querySelectorAll('.foot-pending, .muted').length).toBe(0);

  getStatus.mockResolvedValue(backingUp({ state: 'paused', pausedBy: 'you', holdsPhotos: true }));
  await refreshStatus();
  await vi.waitFor(() => expect(screen.getByRole('group', { name: 'Paused backup' })).toBeTruthy());
  expect(screen.getByText(HOLD)).toBeTruthy();

  // stopped by closing the app: Continue backup / Discard…
  getStatus.mockResolvedValue(backingUp({ state: 'paused', pausedBy: 'closed', holdsPhotos: true }));
  await refreshStatus();
  await vi.waitFor(() => expect(screen.getByText(HOLD_STOPPED)).toBeTruthy());
  expect(screen.queryByText(HOLD)).toBeNull();

  getStatus.mockResolvedValue(backingUp({ holdsPhotos: false }));
  await refreshStatus();
  await vi.waitFor(() => expect(screen.queryByText(HOLD_STOPPED)).toBeNull());
  expect(screen.queryByText(HOLD)).toBeNull();
});

test('a backup answer this page applied keeps whether the backup holds the photos until the status says otherwise', async () => {
  getStatus.mockResolvedValueOnce(backingUp({ state: 'paused', pausedBy: 'you', holdsPhotos: true })).mockImplementation(() => new Promise(() => {}));
  await refreshStatus();
  backupChanged({ ...backingUp().backup, state: 'running' });
  expect(status.backup.holdsPhotos).toBe(true);
  backupChanged({ ...backingUp().backup, id: 3, state: 'running', holdsPhotos: false });
  expect(status.backup.holdsPhotos).toBe(false);
});
