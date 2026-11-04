import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Setup from '../screens/Setup.svelte';
import { continueDiscarded, getSetup, inspectFolder, openHereAnyway, retrySetup } from './api.js';
import { resetStatus } from './status.svelte.js';

vi.mock('./api.js', () => ({
  inspectFolder: vi.fn(), setUpLibrary: vi.fn(), retrySetup: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn(), usePreviousLibrary: vi.fn(),
  getSetup: vi.fn(), openHereAnyway: vi.fn(), continueDiscarded: vi.fn(),
  getStatus: vi.fn(), getExport: vi.fn(), getBackup: vi.fn(), checkHeic: vi.fn(),
}));

// One device at a time (contract §1, §5; design §4, §5, §7): the setup screen's pages in place of the folder chooser.
const at = (h, m) => new Date(2026, 10, 4, h, m).toISOString();
const base = { configured: false, suggestedFolder: '/Users/me/Butterfly Atlas', dataFolder: null, problem: null, retry: false };
const onOther = (over = {}) => ({ ...base, otherDevice: { name: "Omkar's MacBook Air", seen: at(11, 5), openAnywayFrom: at(11, 20), ...over } });

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  inspectFolder.mockResolvedValue({ kind: 'existing' });
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(new Date(2026, 10, 4, 11, 7));
});
afterEach(() => {
  vi.useRealTimers();
});

const page = () => screen.getByRole('main');

test('seen less than 15 minutes ago: the device in bold, when it was seen, when Open here anyway comes, and Try again only', () => {
  render(Setup, { status: onOther(), onDone: vi.fn() });
  expect(screen.getByRole('heading', { level: 1, name: 'Your library is open on another device' })).toBeTruthy();
  expect(screen.getByText("Omkar's MacBook Air", { selector: 'strong' })).toBeTruthy();
  expect(page().textContent).toContain("This library is open on Omkar's MacBook Air (last seen 4 Nov at 11:05, 2 minutes ago). Quit Butterfly"
    + ' Atlas there, let your sync app finish, then choose Try again. If that device is off or asleep, you can open the library here from 11:20.');
  expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Open here anyway…' })).toBeNull();
  expect(screen.queryByRole('button', { name: 'Use this folder' })).toBeNull();
});

test('15 minutes or more: the other wording, and Open here anyway… asks first; Cancel closes, Open here anyway opens it', async () => {
  vi.setSystemTime(new Date(2026, 10, 4, 11, 45));
  openHereAnyway.mockResolvedValue({ configured: true });
  const onDone = vi.fn();
  render(Setup, { status: onOther(), onDone });
  expect(page().textContent).toContain("This library is open on Omkar's MacBook Air (last seen 4 Nov at 11:05, 40 minutes ago). It may be off"
    + ' or asleep, or it may have closed without quitting properly. Quit Butterfly Atlas there if you can, let your sync app finish, then'
    + ' choose Try again.');
  await fireEvent.click(screen.getByRole('button', { name: 'Open here anyway…' }));
  let dialog = screen.getByRole('dialog', { name: 'Open the library here?' });
  expect(within(dialog).getByText("If Omkar's MacBook Air is still running Butterfly Atlas, or didn't send its last changes, those changes will"
    + ' be lost.')).toBeTruthy();
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(openHereAnyway).not.toHaveBeenCalled();

  await fireEvent.click(screen.getByRole('button', { name: 'Open here anyway…' }));
  dialog = screen.getByRole('dialog', { name: 'Open the library here?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Open here anyway' }));
  expect(openHereAnyway).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
});

test('Open here anyway… appears when the clock reaches 15 minutes, and goes when the minute\'s poll brings a newer seen', async () => {
  vi.setSystemTime(new Date(2026, 10, 4, 11, 19, 50));
  getSetup.mockResolvedValue(onOther({ seen: at(11, 20), openAnywayFrom: at(11, 35) }));
  render(Setup, { status: onOther(), onDone: vi.fn() });
  expect(screen.queryByRole('button', { name: 'Open here anyway…' })).toBeNull();
  await vi.advanceTimersByTimeAsync(15_000);
  expect(screen.getByRole('button', { name: 'Open here anyway…' })).toBeTruthy();
  expect(page().textContent).toContain('(last seen 4 Nov at 11:05, 15 minutes ago)');
  expect(getSetup).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(45_000);
  expect(getSetup).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'Open here anyway…' })).toBeNull());
  expect(page().textContent).toContain('(last seen 4 Nov at 11:20, just now)');
});

test('a poll that fails is said, and goes when one answers', async () => {
  getSetup.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  getSetup.mockResolvedValue(onOther());
  render(Setup, { status: onOther(), onDone: vi.fn() });
  await vi.advanceTimersByTimeAsync(60_000);
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.');
  await vi.advanceTimersByTimeAsync(60_000);
  await vi.waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
});

test('a device whose name couldn\'t be read is "another device", not bold', async () => {
  vi.setSystemTime(new Date(2026, 10, 4, 11, 45));
  render(Setup, { status: onOther({ name: null }), onDone: vi.fn() });
  expect(page().textContent).toContain('This library is open on another device (last seen');
  expect(page().querySelector('strong')).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: 'Open here anyway…' }));
  expect(screen.getByText("If the other device is still running Butterfly Atlas, or didn't send its last changes, those changes will be lost.")).toBeTruthy();
});

test('Open here anyway refused as too soon says why and reads the status again', async () => {
  vi.setSystemTime(new Date(2026, 10, 4, 11, 45));
  openHereAnyway.mockRejectedValue(Object.assign(new Error('You can open the library here from 11:50.'), { code: 'too_soon' }));
  getSetup.mockResolvedValue(onOther({ seen: at(11, 35), openAnywayFrom: at(11, 50) }));
  render(Setup, { status: onOther(), onDone: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'Open here anyway…' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Open here anyway' }));
  expect((await screen.findByRole('alert')).textContent).toBe('You can open the library here from 11:50.');
  expect(screen.queryByRole('dialog')).toBeNull();
  await vi.waitFor(() => expect(getSetup).toHaveBeenCalledOnce());
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'Open here anyway…' })).toBeNull());
});

test('Open here anyway that answers a discarded page shows it', async () => {
  vi.setSystemTime(new Date(2026, 10, 4, 11, 45));
  openHereAnyway.mockResolvedValue({ ...base, discarded: { by: 'DESKTOP-PUNE', at: at(14, 20), since: at(14, 2), photos: [], photosMore: 0 } });
  render(Setup, { status: onOther(), onDone: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'Open here anyway…' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Open here anyway' }));
  expect(await screen.findByRole('heading', { level: 1, name: 'Unsent changes were discarded' })).toBeTruthy();
});

test('Try again that is still refused says when it tried; one that fails says why; one that opens starts the app', async () => {
  retrySetup.mockResolvedValueOnce(onOther());
  retrySetup.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  retrySetup.mockResolvedValueOnce({ configured: true });
  const onDone = vi.fn();
  render(Setup, { status: onOther(), onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(screen.getByRole('status').textContent).toBe("Tried again at 11:07 — still couldn't open."));
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
});

test('not synced: what to do, the folder, Try again, and the minute\'s poll', async () => {
  getSetup.mockResolvedValue({ ...base, notSynced: { folder: '/Users/me/Google Drive/Atlas' } });
  render(Setup, { status: { ...base, notSynced: { folder: '/Users/me/Google Drive/Atlas' } }, onDone: vi.fn() });
  expect(screen.getByRole('heading', { level: 1, name: "Your library hasn't finished syncing" })).toBeTruthy();
  expect(screen.getByText("This library's database hasn't finished syncing to this device yet. Let your sync app finish, then choose Try again.")).toBeTruthy();
  expect(screen.getByText('/Users/me/Google Drive/Atlas')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(getSetup).toHaveBeenCalledOnce();
});

test('discarded: who used it and when, what was lost, up to 20 photos and how many more, and Continue', async () => {
  const photos = Array.from({ length: 20 }, (_, i) => `images/Nymphalidae/IMG_${2041 + i}.jpg`);
  continueDiscarded.mockResolvedValue({ configured: true });
  const onDone = vi.fn();
  render(Setup, { status: { ...base, discarded: { by: 'DESKTOP-PUNE', at: at(14, 20), since: at(14, 2), photos, photosMore: 3 } }, onDone });
  expect(screen.getByRole('heading', { level: 1, name: 'Unsent changes were discarded' })).toBeTruthy();
  expect(screen.getByText('DESKTOP-PUNE', { selector: 'strong' })).toBeTruthy();
  expect(page().textContent).toContain('This library was used on DESKTOP-PUNE (last on 4 Nov at 14:20) after this device last sent its changes.'
    + " Changes made here after 4 Nov, 14:02 couldn't be sent and have been discarded.");
  expect(screen.getByText("These photos are in your library's folder but no longer in your atlas; import them again to add them back:")).toBeTruthy();
  const list = screen.getByRole('list');
  expect(within(list).getAllByRole('listitem').map((li) => li.textContent)).toEqual([...photos, 'and 3 more']);
  // nothing waits on another device here: no poll
  await vi.advanceTimersByTimeAsync(60_000);
  expect(getSetup).not.toHaveBeenCalled();
  await fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
  expect(continueDiscarded).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
});

test('discarded with no photos lost has no list; a Continue that fails says why', async () => {
  continueDiscarded.mockRejectedValue(new Error("Your library couldn't be read or written. Check the drive it is on, then try again."));
  render(Setup, { status: { ...base, discarded: { by: null, at: at(14, 20), since: at(14, 2), photos: [], photosMore: 0 } }, onDone: vi.fn() });
  expect(page().textContent).toContain('This library was used on another device (last on 4 Nov at 14:20)');
  expect(screen.queryByRole('list')).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
  expect((await screen.findByRole('alert')).textContent).toBe("Your library couldn't be read or written. Check the drive it is on, then try again.");
  expect(screen.getByRole('button', { name: 'Continue' })).toBeTruthy();
});

test('Try again answered "nothing failed" (the library opened meanwhile) starts the app', async () => {
  retrySetup.mockRejectedValue(Object.assign(new Error('Nothing failed.'), { code: 'not_failed' }));
  const onDone = vi.fn();
  render(Setup, { status: onOther(), onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
  expect(screen.queryByRole('alert')).toBeNull();
});
