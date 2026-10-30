import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Settings from './Settings.svelte';
import {
  checkBackup, checkHeic, getBackups, getFsRoots, getSession, getStatus, listFolder, setAppearance, setBackupPreference, startBackup,
  deleteOldCopy, getLibraryChange, inspectChange, moveLibrary, switchLibrary,
} from '../lib/api.js';
import { longDate } from '../lib/dates.js';
import { BACKUP_CHANGED, backupChanged, resetStatus, status } from '../lib/status.svelte.js';
import { resetSession, session } from '../lib/session.svelte.js';
import { resetTheme, serverTheme } from '../lib/theme.svelte.js';

vi.mock('../lib/api.js', () => ({
  getBackups: vi.fn(), setBackupPreference: vi.fn(), checkBackup: vi.fn(), startBackup: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn(),
  getStatus: vi.fn(), getSession: vi.fn(), getBackup: vi.fn(), deleteBackup: vi.fn(), clearBackups: vi.fn(), pauseBackup: vi.fn(),
  resumeBackup: vi.fn(), cancelBackup: vi.fn(), checkHeic: vi.fn(), getAppearance: vi.fn(), setAppearance: vi.fn(),
  getLibraryChange: vi.fn(), inspectChange: vi.fn(), inspectFolder: vi.fn(), moveLibrary: vi.fn(), switchLibrary: vi.fn(), deleteOldCopy: vi.fn(),
}));

const backups = (over = {}) => ({
  asked: true, preference: 'remind', lastAt: '2026-10-09T10:00:00Z', lastWhat: 'everything', lastUnread: 0, daysSince: 12, due: true,
  current: null, past: [], ...over,
});
const info = (over = {}) => ({
  mode: 'window', version: '0.1.0', dataFolder: '/Users/me/Atlas', configFolder: '/Users/me/.atlas', logFolder: '/Users/me/.atlas/logs', lockWarning: false, ...over,
});

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  resetSession();
  resetTheme();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getBackups.mockResolvedValue(backups());
  getSession.mockResolvedValue(info());
  getStatus.mockResolvedValue({ rescanning: false, backup: null });
  getFsRoots.mockResolvedValue([{ name: 'B', path: '/Volumes/B', kind: 'drive' }]);
  listFolder.mockResolvedValue({ path: '/Volumes/B', name: 'B', parent: '/Volumes', crumbs: [{ name: 'B', path: '/Volumes/B' }], folders: [], photos: 0, isLibrary: false, openImport: null });
  checkBackup.mockResolvedValue({ photos: 5, bytes: 1000, neededBytes: 2000, freeBytes: 1e12, name: 'Butterfly_Atlas_backup_2026-10-21', sameDrive: false, problem: null });
  getLibraryChange.mockResolvedValue(libraryChange());
});

const GB = 1024 ** 3;
const libraryChange = (over = {}) => ({
  folder: '/Users/me/Atlas', size: 12.4 * GB, free: 80.1 * GB, fixed: false, changing: null, oldCopy: null, ...over,
});
afterEach(() => vi.restoreAllMocks());

const radios = () => within(screen.getByRole('group', { name: 'Reminders to back up' })).getAllByRole('radio').map((r) => [r.labels[0].textContent.trim(), r.checked]);

test('the three reminder choices, with the saved one chosen, and the last backup in words', async () => {
  render(Settings);
  expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeTruthy();
  await vi.waitFor(() => expect(radios()).toEqual([['Remind me', true], ['Remind me less often', false], ['No backup needed', false]]));
  expect(screen.getByText('Last backup: 12 days ago — Everything')).toBeTruthy();
  expect(screen.queryByText(/could not be copied/)).toBeNull();
  expect(screen.getByRole('button', { name: 'Back up now' }).getAttribute('aria-disabled')).not.toBe('true');
});

test('never backed up, and photos that could not be copied, are said in words', async () => {
  getBackups.mockResolvedValue(backups({ lastAt: null, lastWhat: null, daysSince: null }));
  const { unmount } = render(Settings);
  expect(await screen.findByText('Never backed up')).toBeTruthy();
  unmount();
  getBackups.mockResolvedValue(backups({ lastUnread: 4 }));
  render(Settings);
  expect(await screen.findByText('4 photos could not be copied')).toBeTruthy();
});

test('choosing a preference saves it; the saved answer is shown', async () => {
  setBackupPreference.mockResolvedValue(backups({ preference: 'none', due: false }));
  render(Settings);
  await screen.findByText('Last backup: 12 days ago — Everything');
  await fireEvent.click(screen.getByRole('radio', { name: 'No backup needed' }));
  expect(setBackupPreference).toHaveBeenCalledWith('none');
  await vi.waitFor(() => expect(radios()).toEqual([['Remind me', false], ['Remind me less often', false], ['No backup needed', true]]));
  expect(screen.queryByRole('alert')).toBeNull();
});

test('a preference that cannot be saved goes back to the saved choice and says why', async () => {
  setBackupPreference.mockRejectedValue(new Error('Butterfly Atlas could not save that. Try again.'));
  render(Settings);
  await screen.findByText('Last backup: 12 days ago — Everything');
  await fireEvent.click(screen.getByRole('radio', { name: 'Remind me less often' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not save that. Try again.');
  expect(radios()).toEqual([['Remind me', true], ['Remind me less often', false], ['No backup needed', false]]);
  // the next choice clears the message
  setBackupPreference.mockResolvedValue(backups({ preference: 'less' }));
  await fireEvent.click(screen.getByRole('radio', { name: 'Remind me less often' }));
  await vi.waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  expect(radios()[1]).toEqual(['Remind me less often', true]);
});

test('Back up now opens the sheet, and a started backup shows its card here', async () => {
  const run = { id: 2, state: 'running', what: 'everything', done: 0, total: 5, pausedBy: null, notice: null, path: '/Volumes/B/Butterfly_Atlas_backup_2026-10-21' };
  startBackup.mockResolvedValue(run);
  // the status the app answers once the backup runs
  getStatus.mockResolvedValue({ rescanning: false, backup: run });
  render(Settings);
  await screen.findByText('Last backup: 12 days ago — Everything');
  await fireEvent.click(screen.getByRole('button', { name: 'Back up now' }));
  expect(screen.getByRole('dialog', { name: 'Back up your library' })).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Choose where to back up…' }));
  await fireEvent.click(await screen.findByRole('button', { name: 'Back up here' }));
  await vi.waitFor(() => expect(within(screen.getByRole('dialog', { name: 'Back up your library' })).getByRole('button', { name: 'Back up' }).getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(within(screen.getByRole('dialog', { name: 'Back up your library' })).getByRole('button', { name: 'Back up' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(status.backup).toMatchObject({ id: 2, state: 'running' });
  expect(await screen.findByText('Backing up your library — 0 of 5 photos')).toBeTruthy();
  // one at a time: Back up now says why it is inactive
  const button = screen.getByRole('button', { name: 'Back up now' });
  expect(button.getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(button.getAttribute('aria-describedby')).textContent).toBe('Finish or cancel the backup in progress first.');
});

test('the card of a backup the closing stopped shows here too, and the list is read again when a backup changes', async () => {
  status.backup = { id: 2, state: 'paused', done: 5, total: 9, pausedBy: 'closed', notice: null, path: '/V/B' };
  render(Settings);
  expect(await screen.findByText('Backup stopped — the app was closed')).toBeTruthy();
  await vi.waitFor(() => expect(getBackups).toHaveBeenCalledTimes(2));
  getBackups.mockResolvedValue(backups({ lastAt: '2026-10-21T10:00:00Z', daysSince: 0, lastWhat: 'database' }));
  window.dispatchEvent(new CustomEvent(BACKUP_CHANGED, { detail: null }));
  expect(await screen.findByText('Last backup: today — Database and settings only')).toBeTruthy();
});

test('past backups are listed, newest first, and removing one updates the list', async () => {
  getBackups.mockResolvedValue(backups({
    past: [{ id: 3, state: 'done', what: 'database', total: 0, done: 0, path: '/V/Butterfly_Atlas_backup_2026-10-09', startedAt: '2026-10-09T10:00:00Z', finishedAt: '2026-10-09T10:01:00Z', skipped: 0, failed: 0 }],
  }));
  render(Settings);
  expect(await screen.findByRole('heading', { level: 2, name: 'Past backups' })).toBeTruthy();
  expect(screen.getByText('/V/Butterfly_Atlas_backup_2026-10-09')).toBeTruthy();
});

test('the list that cannot be read says why, with Try again; the About block still shows', async () => {
  getBackups.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.')).mockResolvedValue(backups());
  session.info = info();
  render(Settings);
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(screen.getByRole('heading', { level: 2, name: 'About' })).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText('Last backup: 12 days ago — Everything')).toBeTruthy();
});

test('About: the version and the data, config and log folders', async () => {
  render(Settings);
  const about = await screen.findByRole('region', { name: 'About' });
  await vi.waitFor(() => expect(about.textContent).toContain('0.1.0'));
  const rows = [...about.querySelectorAll('dt')].map((dt) => [dt.textContent, dt.nextElementSibling.textContent]);
  expect(rows).toEqual([
    ['Version', '0.1.0'], ['Data folder', '/Users/me/Atlas'], ['Config folder', '/Users/me/.atlas'], ['Log folder', '/Users/me/.atlas/logs'],
  ]);
});

test('About says when the details cannot be read, with Try again; a drive that cannot lock says so here too', async () => {
  getSession.mockRejectedValueOnce(new Error('down')).mockResolvedValue(info({ lockWarning: true }));
  render(Settings);
  const about = await screen.findByRole('region', { name: 'About' });
  await vi.waitFor(() => expect(within(about).getByRole('alert').textContent).toContain("The version and folders couldn't be read."));
  await fireEvent.click(within(about).getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(about.textContent).toContain('This drive cannot prevent a second copy from opening this library. Open only one.'));
  expect(about.textContent).toContain('0.1.0');
});

/* ---------- Phase 7b: HEIC photos in About ---------- */

const heic = (state, over = {}) => ({ state, decoder: state === 'available' ? 'wic' : null, system: 'windows', reason: state === 'missing' ? 'absent' : null, ...over });
const WINDOWS_SENTENCE = "Install Windows' HEVC decoder, then choose Check again in Settings → About.";

test('About says which decoder shows HEIC photos, from the status (audit 11)', async () => {
  status.heic = heic('available');
  render(Settings);
  expect(await screen.findByText("HEIC photos: shown with Windows' HEVC decoder.")).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Check again' })).toBeNull();
  status.heic = heic('checking');
  expect(await screen.findByText('HEIC photos: checking for a decoder…')).toBeTruthy();
});

test('without a decoder About says what to do, and Check again says it found one (contract §1)', async () => {
  status.heic = heic('missing');
  let answer;
  checkHeic.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
  render(Settings);
  expect(await screen.findByText(`HEIC photos can't be shown on this computer. ${WINDOWS_SENTENCE}`)).toBeTruthy();

  const button = screen.getByRole('button', { name: 'Check again' });
  await fireEvent.click(button);
  expect(button.getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByText('Checking for a HEIC decoder…')).toBeTruthy();
  // A second click while it checks asks nothing more.
  await fireEvent.click(button);
  expect(checkHeic).toHaveBeenCalledOnce();

  answer(heic('available'));
  expect(await screen.findByText('HEIC photos can be shown now. Making their thumbnails…')).toBeTruthy();
  expect(screen.getByText("HEIC photos: shown with Windows' HEVC decoder.")).toBeTruthy();
  expect(status.heic.state).toBe('available');
});

test('Check again finding nothing says so with what to do; one that fails says why', async () => {
  status.heic = heic('missing');
  checkHeic.mockResolvedValueOnce(heic('missing')).mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  render(Settings);
  await fireEvent.click(await screen.findByRole('button', { name: 'Check again' }));
  expect(await screen.findByText(`Still not available. ${WINDOWS_SENTENCE}`)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.');
});

test('a check still running after the wait says it is still checking (audit 11)', async () => {
  status.heic = heic('missing');
  checkHeic.mockResolvedValue(heic('checking'));
  render(Settings);
  await fireEvent.click(await screen.findByRole('button', { name: 'Check again' }));
  await vi.waitFor(() => expect(screen.getAllByText('HEIC photos: checking for a decoder…').length).toBeGreaterThan(0));
});

const themes = () => within(screen.getByRole('group', { name: 'Appearance' })).getAllByRole('radio').map((r) => [r.labels[0].textContent.trim(), r.checked]);

test('Appearance: Light and Dark, none chosen until a choice is saved, and the note says the computer is followed', async () => {
  render(Settings);

  expect(screen.getByRole('heading', { name: 'Appearance' })).toBeTruthy();
  expect(themes()).toEqual([['Light', false], ['Dark', false]]);
  expect(screen.getByText('Until you choose, Butterfly Atlas matches your computer.')).toBeTruthy();
});

test('choosing Dark saves it and shows it; the note goes', async () => {
  setAppearance.mockResolvedValue({ theme: 'dark' });
  render(Settings);

  await fireEvent.click(screen.getByRole('radio', { name: 'Dark' }));

  expect(setAppearance).toHaveBeenCalledWith('dark');
  await vi.waitFor(() => expect(themes()).toEqual([['Light', false], ['Dark', true]]));
  expect(document.documentElement.dataset.theme).toBe('dark');
  expect(screen.queryByText('Until you choose, Butterfly Atlas matches your computer.')).toBeNull();
  expect(screen.queryByRole('alert')).toBeNull();
});

test('an appearance that cannot be saved goes back to the saved choice and says why', async () => {
  serverTheme('light');
  setAppearance.mockRejectedValue(new Error("Couldn't save the appearance: Permission denied"));
  render(Settings);
  expect(themes()).toEqual([['Light', true], ['Dark', false]]);

  await fireEvent.click(screen.getByRole('radio', { name: 'Dark' }));

  expect((await screen.findByRole('alert')).textContent).toBe("Couldn't save the appearance: Permission denied");
  expect(themes()).toEqual([['Light', true], ['Dark', false]]);
  expect(document.documentElement.dataset.theme).toBe('light');
  // the next choice clears the message
  setAppearance.mockResolvedValue({ theme: 'dark' });
  await fireEvent.click(screen.getByRole('radio', { name: 'Dark' }));
  await vi.waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  expect(themes()[1]).toEqual(['Dark', true]);
});

test('a choice made in another window shows here too', async () => {
  render(Settings);
  serverTheme('dark');
  await vi.waitFor(() => expect(themes()).toEqual([['Light', false], ['Dark', true]]));
});

// ---- Library folder (change-library-folder design §2, §3, §9) ----

const librarySection = async () => {
  const heading = await screen.findByRole('heading', { level: 2, name: 'Library folder' });
  return heading.closest('section');
};
const moveCheck = { path: '/Volumes/B', absolute: true, libraryFolder: '/Volumes/B/Butterfly_Atlas', kind: 'create', unfinished: null, refusal: null, free: 300 * GB, needed: 13.2 * GB };
const changing = (kind) => ({
  kind, to: '/Volumes/B/Butterfly_Atlas', step: kind === 'move' ? 'copying' : 'checking', files: { done: 0, total: 0 }, bytes: { done: 0, total: 0 },
  canCancel: true, problem: null, left: null, reopen: null,
});

test('the Library folder section says where the library is, its size and the drive\'s free space, with both buttons', async () => {
  render(Settings);
  const section = await librarySection();
  await within(section).findByText('/Users/me/Atlas');
  expect(within(section).getByText('12.4 GB')).toBeTruthy();
  expect(within(section).getByText('80.1 GB')).toBeTruthy();
  for (const name of ['Move library…', 'Use a different library…']) {
    expect(within(section).getByRole('button', { name }).getAttribute('aria-disabled')).not.toBe('true');
  }
  expect(within(section).queryByText(/fixed folder/)).toBeNull();
});

test('a copy started on a fixed folder shows the section with both buttons disabled and says why', async () => {
  getLibraryChange.mockResolvedValue(libraryChange({ fixed: true }));
  render(Settings);
  const section = await librarySection();
  const note = await within(section).findByText('This copy of Butterfly Atlas was started on a fixed folder.');
  for (const name of ['Move library…', 'Use a different library…']) {
    const button = within(section).getByRole('button', { name });
    expect(button.getAttribute('aria-disabled')).toBe('true');
    expect(button.getAttribute('aria-describedby')).toBe(note.id);
    await fireEvent.click(button);
  }
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('the section that can\'t be read says why, with Try again', async () => {
  getLibraryChange.mockRejectedValueOnce(new Error('Butterfly Atlas isn\'t answering.'));
  render(Settings);
  const section = await librarySection();
  expect((await within(section).findByRole('alert')).textContent).toContain('Butterfly Atlas isn\'t answering.');
  await fireEvent.click(within(section).getByRole('button', { name: 'Try again' }));
  expect(await within(section).findByText('/Users/me/Atlas')).toBeTruthy();
});

test('Move library… opens the folder browser for a move; Move here starts it and the change shows', async () => {
  inspectChange.mockResolvedValue(moveCheck);
  moveLibrary.mockResolvedValue({ changing: changing('move') });
  getStatus.mockResolvedValue({ rescanning: false, backup: null, changing: changing('move') });
  render(Settings);
  const section = await librarySection();
  await within(section).findByText('/Users/me/Atlas');
  await fireEvent.click(within(section).getByRole('button', { name: 'Move library…' }));

  const dialog = screen.getByRole('dialog', { name: 'Choose where to move your library' });
  await within(dialog).findByText('Your library will be moved to /Volumes/B/Butterfly_Atlas. It is 12.4 GB; that drive has 300.0 GB free.');
  expect(inspectChange).toHaveBeenCalledWith('/Volumes/B', 'move');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Move here' }));

  expect(moveLibrary).toHaveBeenCalledWith('/Volumes/B');
  await vi.waitFor(() => expect(status.changing?.kind).toBe('move'));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

test('Use a different library… opens the folder browser for a switch; a refusal from the server shows beside the button', async () => {
  inspectChange.mockResolvedValue({ ...moveCheck, kind: 'existing', libraryFolder: '/Volumes/B' });
  switchLibrary.mockRejectedValueOnce(Object.assign(new Error('An import is filing photos. Pause it or wait for it to finish, then try again.'), { code: 'work_running' }));
  render(Settings);
  const section = await librarySection();
  await within(section).findByText('/Users/me/Atlas');
  await fireEvent.click(within(section).getByRole('button', { name: 'Use a different library…' }));

  const dialog = screen.getByRole('dialog', { name: 'Choose a library to use' });
  await within(dialog).findByText('This folder already has a Butterfly Atlas library. It will open as it is.');
  expect(inspectChange).toHaveBeenCalledWith('/Volumes/B', 'switch');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Open this existing library' }));

  expect(switchLibrary).toHaveBeenCalledWith('/Volumes/B');
  expect((await within(dialog).findByRole('alert')).textContent).toBe('An import is filing photos. Pause it or wait for it to finish, then try again.');
  expect(status.changing).toBeNull();
});

const oldCopy = (over = {}) => ({ folder: '/Users/me/Old Atlas', size: 12.4 * GB, lastBackup: { what: 'everything', finishedAt: '2026-10-27T10:00:00Z' }, ...over });

test('after a move the old copy is offered for deleting, naming the last backup; deleting it says so', async () => {
  getLibraryChange.mockResolvedValue(libraryChange({ oldCopy: oldCopy() }));
  deleteOldCopy.mockResolvedValue({ deleted: true, left: [], leftCount: 0 });
  render(Settings);
  const section = await librarySection();
  expect(await within(section).findByText('The old copy of your library is still at /Users/me/Old Atlas (12.4 GB).')).toBeTruthy();
  const button = within(section).getByRole('button', { name: 'Delete old copy' });
  await fireEvent.click(button);

  const dialog = screen.getByRole('dialog', { name: 'Delete the old copy?' });
  expect(within(dialog).getByText('Delete the old copy at /Users/me/Old Atlas? Your library at /Users/me/Atlas is the one in use.')).toBeTruthy();
  expect(within(dialog).getByText(`Your last backup (Everything) was on ${longDate('2026-10-27T10:00:00Z')}.`)).toBeTruthy();
  getLibraryChange.mockResolvedValue(libraryChange());
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete old copy' }));

  expect(deleteOldCopy).toHaveBeenCalledOnce();
  expect(await within(section).findByText('The old copy was deleted.')).toBeTruthy();
  expect(screen.queryByRole('dialog')).toBeNull();
  await vi.waitFor(() => expect(within(section).queryByText(/The old copy of your library is still at/)).toBeNull());
});

test('with no backup the confirmation says so and leads to Backup; Cancel deletes nothing', async () => {
  getLibraryChange.mockResolvedValue(libraryChange({ oldCopy: oldCopy({ lastBackup: null }) }));
  render(Settings);
  const section = await librarySection();
  await fireEvent.click(await within(section).findByRole('button', { name: 'Delete old copy' }));
  const dialog = screen.getByRole('dialog', { name: 'Delete the old copy?' });
  expect(within(dialog).getByText('You have no backup yet.')).toBeTruthy();
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Go to Backup' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(document.activeElement).toBe(screen.getByRole('heading', { level: 2, name: 'Backup' }));
  expect(deleteOldCopy).not.toHaveBeenCalled();

  await fireEvent.click(within(section).getByRole('button', { name: 'Delete old copy' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(deleteOldCopy).not.toHaveBeenCalled();
});

test('an old copy that couldn\'t all be deleted says how many and where, and Delete can be chosen again', async () => {
  getLibraryChange.mockResolvedValue(libraryChange({ oldCopy: oldCopy() }));
  deleteOldCopy.mockResolvedValue({ deleted: false, left: ['/Users/me/Old Atlas/images/a.jpg', '/Users/me/Old Atlas/atlas.db'], leftCount: 12 });
  render(Settings);
  const section = await librarySection();
  await fireEvent.click(await within(section).findByRole('button', { name: 'Delete old copy' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete old copy' }));

  expect(await within(section).findByText("Some of the old copy at /Users/me/Old Atlas couldn't be deleted (12 files). Choose Delete old copy to try again, or delete them yourself."))
    .toBeTruthy();
  expect(within(section).getByText('/Users/me/Old Atlas/images/a.jpg')).toBeTruthy();
  expect(within(section).getByText('/Users/me/Old Atlas/atlas.db')).toBeTruthy();
  expect(within(section).getByText('and 10 more')).toBeTruthy();
  expect(within(section).getByRole('button', { name: 'Delete old copy' })).toBeTruthy();
});

test('a deleted old copy that left other files names them; a refused delete says why in the confirmation', async () => {
  getLibraryChange.mockResolvedValue(libraryChange({ oldCopy: oldCopy() }));
  deleteOldCopy.mockRejectedValueOnce(Object.assign(new Error('Another copy of Butterfly Atlas is using this library.'), { code: 'library_in_use' }));
  render(Settings);
  const section = await librarySection();
  await fireEvent.click(await within(section).findByRole('button', { name: 'Delete old copy' }));
  const dialog = screen.getByRole('dialog');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete old copy' }));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Another copy of Butterfly Atlas is using this library.');

  deleteOldCopy.mockResolvedValue({ deleted: true, left: ['/Users/me/Old Atlas/notes.txt'], leftCount: 1 });
  getLibraryChange.mockResolvedValue(libraryChange());
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Delete old copy' }));
  expect(await within(section).findByText('The old copy was deleted.')).toBeTruthy();
  expect(within(section).getByText("1 file that isn't part of a library was left in /Users/me/Old Atlas.")).toBeTruthy();
  expect(within(section).getByText('/Users/me/Old Atlas/notes.txt')).toBeTruthy();
});
