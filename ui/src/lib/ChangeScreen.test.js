import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import ChangeScreen from './ChangeScreen.svelte';
import { cancelChange, dismissChange, getStatus } from './api.js';
import { resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({
  cancelChange: vi.fn(), dismissChange: vi.fn(), getStatus: vi.fn(), getExport: vi.fn(), getBackup: vi.fn(), checkHeic: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getStatus.mockResolvedValue({ rescanning: false });
});
afterEach(() => {
  vi.restoreAllMocks();
  window.location.hash = '';
});

const GB = 1024 ** 3;
const change = (over = {}) => ({
  kind: 'move', to: '/Volumes/B/Butterfly_Atlas', step: 'copying', files: { done: 1200, total: 3400 }, bytes: { done: 1.6 * GB, total: 4.2 * GB },
  canCancel: true, problem: null, left: null, reopen: null, ...over,
});

test('a move copying says what it does, where to, how far in files and bytes, with a bar and Cancel', () => {
  render(ChangeScreen, { changing: change() });
  expect(screen.getByRole('heading', { level: 1, name: 'Moving your library' })).toBeTruthy();
  expect(screen.getByText('To /Volumes/B/Butterfly_Atlas')).toBeTruthy();
  expect(screen.getByRole('status').textContent).toBe('Copying your photos and files…');
  expect(screen.getByText('1,200 of 3,400 files · 1.6 of 4.2 GB')).toBeTruthy();
  const bar = screen.getByRole('progressbar', { name: 'Files copied' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['3400', '1200']);
  expect(screen.getByText("Butterfly Atlas can't be used in any window until this is done or cancelled.")).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Cancel' })).toBeTruthy();
});

test('each step says itself; Cancel goes once the switch point is reached', async () => {
  const { rerender } = render(ChangeScreen, { changing: change({ step: 'copying_database' }) });
  expect(screen.getByRole('status').textContent).toBe('Copying the database…');
  await rerender({ changing: change({ step: 'checking' }) });
  expect(screen.getByRole('status').textContent).toBe('Checking the copy…');
  await rerender({ changing: change({ step: 'switching', canCancel: false }) });
  expect(screen.getByRole('status').textContent).toBe('Switching to the new folder…');
  expect(screen.queryByRole('button', { name: 'Cancel' })).toBeNull();
  await rerender({ changing: change({ step: 'cancelling', canCancel: false }) });
  expect(screen.getByRole('status').textContent).toBe('Cancelling — removing the copy…');
  await rerender({ changing: change({ kind: 'switch', step: 'checking', files: { done: 0, total: 0 }, bytes: { done: 0, total: 0 } }) });
  expect(screen.getByRole('heading', { level: 1, name: 'Changing to another library' })).toBeTruthy();
  expect(screen.getByRole('status').textContent).toBe('Checking the library…');
  expect(screen.queryByText(/files/)).toBeNull();
});

test('Cancel asks the server once; a refusal says why beside it', async () => {
  let answer;
  cancelChange.mockImplementation(() => new Promise((resolve, reject) => { answer = { resolve, reject }; }));
  render(ChangeScreen, { changing: change() });
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Cancelling…' }));
  expect(cancelChange).toHaveBeenCalledOnce();
  answer.reject(Object.assign(new Error('The library is already being switched; it can\'t be cancelled now.'), { code: 'cannot_cancel' }));
  expect((await screen.findByRole('alert')).textContent).toBe('The library is already being switched; it can\'t be cancelled now.');
  expect(screen.getByRole('button', { name: 'Cancel' })).toBeTruthy();
});

test('a status that can\'t be read is said, while the change runs', () => {
  status.unreachable = true;
  render(ChangeScreen, { changing: change() });
  expect(screen.getByRole('alert').textContent).toBe("Butterfly Atlas isn't answering. If you closed it, open it again.");
});

test('a failed move says why, that the library is as it was, what of the copy is left, and Back to Settings dismisses it', async () => {
  dismissChange.mockResolvedValue(null);
  render(ChangeScreen, { changing: change({ step: 'failed', canCancel: false, problem: 'The drive is full.', left: { folder: '/Volumes/B/Butterfly_Atlas', files: 3 } }) });
  expect(screen.getByRole('heading', { level: 1, name: "Your library couldn't be moved" })).toBeTruthy();
  expect(screen.getByRole('alert').textContent).toBe('The drive is full.');
  expect(screen.getByText('Your library is where it was, and nothing in it was changed.')).toBeTruthy();
  expect(screen.getByText("Some of the copy at /Volumes/B/Butterfly_Atlas couldn't be removed (3 files). You can delete it yourself.")).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Back to Settings' }));
  expect(dismissChange).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(window.location.hash).toBe('#/settings'));
  expect(status.changing).toBeNull();
  expect(getStatus).toHaveBeenCalled();
});

test('a cancel that left files says so, and a dismiss that fails says why', async () => {
  dismissChange.mockRejectedValue(new Error("Butterfly Atlas isn't answering."));
  status.changing = change({ step: 'failed', canCancel: false, problem: null, left: { folder: '/Volumes/B/Butterfly_Atlas', files: 1 } });
  render(ChangeScreen, { changing: status.changing });
  expect(screen.getByRole('heading', { level: 1, name: 'The move was cancelled' })).toBeTruthy();
  expect(screen.getByText("Some of the copy at /Volumes/B/Butterfly_Atlas couldn't be removed (1 file). You can delete it yourself.")).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Back to Settings' }));
  expect((await screen.findByRole('alert')).textContent).toBe("Butterfly Atlas isn't answering.");
  expect(status.changing).not.toBeNull();
});

test('a failed switch has its own heading', () => {
  render(ChangeScreen, { changing: change({ kind: 'switch', step: 'failed', canCancel: false, problem: "That library can't be opened." }) });
  expect(screen.getByRole('heading', { level: 1, name: "The library couldn't be changed" })).toBeTruthy();
});

const LONG = '/Volumes/A very long drive name/Some/Deeply/Nested/Folder/Of/Photographs/Butterfly_Atlas';
const lines = (main) => [...main.querySelectorAll('h1, p')].map((el) => el.textContent.replace(/\s+/g, ' ').trim());
// The folder is on its own line, wrapping anywhere (a long path must not run off the window), not in the heading.
const folderLine = (folder) => {
  const path = screen.getByText(folder);
  expect(path.classList.contains('cs-to')).toBe(true);
  expect(path.classList.contains('mono')).toBe(true);
  expect(screen.getByRole('heading', { level: 1 }).textContent).not.toContain(folder);
  return path;
};

test('reopening by restart: the app is reopening in a new window; the folder on its own line; this window can be closed', () => {
  render(ChangeScreen, { changing: change({ step: 'reopening', canCancel: false, reopen: { how: 'restart', folder: LONG, why: null } }) });
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Reopening Butterfly Atlas');
  folderLine(LONG);
  expect(lines(screen.getByRole('main'))).toEqual([
    'Reopening Butterfly Atlas', `Your library is now at ${LONG}`, 'It opens in a new window. You can close this one.',
  ]);
  expect(screen.queryByRole('button')).toBeNull();
});

test('reopening by quitting: the app has closed, the folder on its own line, open it again; first why it couldn\'t reopen itself', async () => {
  const quit = (why) => change({ step: 'reopening', canCancel: false, reopen: { how: 'quit', folder: LONG, why } });
  const { rerender } = render(ChangeScreen, { changing: quit(null) });
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Butterfly Atlas has closed');
  folderLine(LONG);
  expect(lines(screen.getByRole('main'))).toEqual([
    'Butterfly Atlas has closed', `Your library is now at ${LONG}`, 'Open Butterfly Atlas again to continue.',
  ]);
  await rerender({ changing: quit('This copy was started from a development build.') });
  expect(lines(screen.getByRole('main'))).toEqual([
    "Butterfly Atlas couldn't reopen itself.", 'This copy was started from a development build.',
    'Butterfly Atlas has closed', `Your library is now at ${LONG}`, 'Open Butterfly Atlas again to continue.',
  ]);
});
