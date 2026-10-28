import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import BackupSheet from './BackupSheet.svelte';
import { checkBackup, getFsRoots, listFolder, startBackup } from './api.js';

vi.mock('./api.js', () => ({ checkBackup: vi.fn(), startBackup: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn() }));

const drive = {
  path: '/Volumes/Backup', name: 'Backup', parent: '/Volumes', crumbs: [{ name: 'Backup', path: '/Volumes/Backup' }], folders: [], photos: 0,
  isLibrary: false, openImport: null,
};
const check = (over = {}) => ({
  photos: 12400, bytes: 4509715660, neededBytes: 4719430860, freeBytes: 332859965440, name: 'Butterfly_Atlas_backup_2026-10-21',
  sameDrive: false, problem: null, ...over,
});

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getFsRoots.mockResolvedValue([{ name: 'Backup', path: '/Volumes/Backup', kind: 'drive' }]);
  listFolder.mockResolvedValue(drive);
  checkBackup.mockResolvedValue(check());
});
afterEach(() => vi.restoreAllMocks());

const show = (props = {}) => render(BackupSheet, { onClose: vi.fn(), onStarted: vi.fn(), ...props });
const sheet = () => screen.getByRole('dialog', { name: 'Back up your library' });
const backUp = () => within(sheet()).getByRole('button', { name: 'Back up' });

async function chooseFolder() {
  await fireEvent.click(screen.getByRole('button', { name: 'Choose where to back up…' }));
  await fireEvent.click(await screen.findByRole('button', { name: 'Back up here' }));
  await screen.findByText('/Volumes/Backup');
}

test('opens with two choices, nothing remembered: Everything chosen, no folder, Back up inactive', async () => {
  localStorage.setItem('backup', '{"path":"/Volumes/Old","what":"database"}');
  show();
  await vi.waitFor(() => expect(sheet().contains(document.activeElement)).toBe(true));
  const what = screen.getByRole('group', { name: 'What to back up' });
  expect(within(what).getAllByRole('radio').map((r) => [r.labels[0].textContent.trim(), r.checked])).toEqual([
    ['Database and settings only', false], ['Everything', true],
  ]);
  expect(screen.getByText('Photos in Recently deleted are not included.')).toBeTruthy();
  expect(screen.getByText('Choose where to back up.')).toBeTruthy();
  expect(backUp().getAttribute('aria-disabled')).toBe('true');
  expect(checkBackup).not.toHaveBeenCalled();
});

test('the Recently deleted line is only for Everything', async () => {
  show();
  await fireEvent.click(screen.getByRole('radio', { name: 'Database and settings only' }));
  expect(screen.queryByText('Photos in Recently deleted are not included.')).toBeNull();
  await fireEvent.click(screen.getByRole('radio', { name: 'Everything' }));
  expect(screen.getByText('Photos in Recently deleted are not included.')).toBeTruthy();
});

test('a chosen folder is checked: its path, Change…, the name it creates, the space line; Back up becomes active', async () => {
  show();
  await chooseFolder();
  expect(checkBackup).toHaveBeenCalledWith('everything', '/Volumes/Backup', { signal: expect.any(AbortSignal) });
  const change = screen.getByRole('button', { name: 'Change…' });
  await vi.waitFor(() => expect(document.activeElement).toBe(change));
  expect(await screen.findByText('Creates Butterfly_Atlas_backup_2026-10-21')).toBeTruthy();
  expect(screen.getByText('4.2 GB needed, 310.0 GB free')).toBeTruthy();
  expect(backUp().getAttribute('aria-disabled')).toBe('false');
});

test('changing the choice checks again and the space line follows; an older answer arriving late is dropped', async () => {
  const answers = [];
  checkBackup.mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  show();
  await chooseFolder();
  await fireEvent.click(screen.getByRole('radio', { name: 'Database and settings only' }));
  expect(checkBackup).toHaveBeenCalledTimes(2);
  expect(checkBackup.mock.calls[0][2].signal.aborted).toBe(true);
  expect(checkBackup.mock.calls[1].slice(0, 2)).toEqual(['database', '/Volumes/Backup']);

  answers[1](check({ photos: 0, bytes: 41943040, neededBytes: 83886080 }));
  expect(await screen.findByText('40.0 MB needed, 310.0 GB free')).toBeTruthy();
  answers[0](check({ problem: { code: 'no_space', message: "There isn't enough space." } }));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(screen.queryByRole('alert')).toBeNull();
  expect(backUp().getAttribute('aria-disabled')).toBe('false');
});

test('a problem the check finds is said in words, Back up stays inactive and starting does nothing', async () => {
  checkBackup.mockResolvedValue(check({ problem: { code: 'not_writable', message: "Butterfly Atlas can't write to that folder." } }));
  show();
  await chooseFolder();
  expect((await screen.findByRole('alert')).textContent).toBe("Butterfly Atlas can't write to that folder.");
  expect(screen.queryByText(/needed/)).toBeNull();
  expect(backUp().getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(backUp());
  expect(startBackup).not.toHaveBeenCalled();
});

test('a backup already running is a problem the check names, with the reason', async () => {
  checkBackup.mockResolvedValue(check({ problem: { code: 'backup_running', message: 'A backup is already running. Wait for it to finish, or cancel it.' } }));
  show();
  await chooseFolder();
  expect((await screen.findByRole('alert')).textContent).toBe('A backup is already running. Wait for it to finish, or cancel it.');
  expect(backUp().getAttribute('aria-disabled')).toBe('true');
});

test('a destination on the library\'s own drive gets one quiet line', async () => {
  checkBackup.mockResolvedValue(check({ sameDrive: true }));
  show();
  await chooseFolder();
  expect(await screen.findByText('This backup is on the same drive as your library; it will not protect against that drive failing.')).toBeTruthy();
  expect(backUp().getAttribute('aria-disabled')).toBe('false');
  // a different drive: no line
  checkBackup.mockResolvedValue(check({ sameDrive: false }));
  await fireEvent.click(screen.getByRole('radio', { name: 'Database and settings only' }));
  await vi.waitFor(() => expect(screen.queryByText(/same drive/)).toBeNull());
});

test('a check that fails says why', async () => {
  checkBackup.mockRejectedValue(new Error('Butterfly Atlas could not reach its service.'));
  show();
  await chooseFolder();
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(backUp().getAttribute('aria-disabled')).toBe('true');
});

test('Back up starts it with the choice and the folder, inactive while on its way, and hands the answer on', async () => {
  let answer;
  startBackup.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  const onStarted = vi.fn();
  const onClose = vi.fn();
  show({ onStarted, onClose });
  await fireEvent.click(screen.getByRole('radio', { name: 'Database and settings only' }));
  await chooseFolder();
  await vi.waitFor(() => expect(backUp().getAttribute('aria-disabled')).toBe('false'));

  await fireEvent.click(backUp());
  expect(startBackup).toHaveBeenCalledWith({ what: 'database', path: '/Volumes/Backup' });
  const busy = within(sheet()).getByRole('button', { name: 'Starting…' });
  expect(busy.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(busy);
  expect(startBackup).toHaveBeenCalledTimes(1);
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).not.toHaveBeenCalled();
  expect(screen.getAllByRole('radio').every((r) => r.getAttribute('aria-disabled') === 'true')).toBe(true);

  const run = { id: 2, state: 'running' };
  answer(run);
  await vi.waitFor(() => expect(onStarted).toHaveBeenCalledWith(run));
});

test('a start that is refused says why (a taxonomy update, a running backup) and keeps the sheet open; a choice changing clears it', async () => {
  startBackup.mockRejectedValue(Object.assign(new Error('Wait for the taxonomy update to finish, then back up.'), { code: 'taxonomy_updating' }));
  const onClose = vi.fn();
  const onStarted = vi.fn();
  show({ onClose, onStarted });
  await chooseFolder();
  await vi.waitFor(() => expect(backUp().getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(backUp());
  expect((await screen.findByRole('alert')).textContent).toBe('Wait for the taxonomy update to finish, then back up.');
  expect(onStarted).not.toHaveBeenCalled();
  expect(onClose).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('radio', { name: 'Database and settings only' }));
  expect(screen.queryByRole('alert')).toBeNull();
});

test('Escape, Cancel and the backdrop close it; every control is a native, focusable one', async () => {
  const onClose = vi.fn();
  const { container } = show({ onClose });
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  await fireEvent.click(container.querySelector('.sheet-bg'));
  expect(onClose).toHaveBeenCalledTimes(3);
  const controls = [...sheet().querySelectorAll('input, button')];
  expect(controls.every((el) => el.tabIndex === 0 && !el.disabled)).toBe(true);
});

test('the folder browser opens over the sheet; Escape closes only it, and focus goes back to the button that opened it', async () => {
  show();
  const choose = screen.getByRole('button', { name: 'Choose where to back up…' });
  await fireEvent.click(choose);
  await screen.findByRole('dialog', { name: 'Choose where to back up' });
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: 'Choose where to back up' })).toBeNull();
  expect(sheet()).toBeTruthy();
  await vi.waitFor(() => expect(document.activeElement).toBe(choose));
});
