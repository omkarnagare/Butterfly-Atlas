import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { THEME_KEY, chooseTheme, resetTheme, serverTheme, startTheme, theme } from './theme.svelte.js';
import { getAppearance, getStatus, setAppearance } from './api.js';
import { refreshStatus, resetStatus } from './status.svelte.js';

vi.mock('./api.js', () => ({ getAppearance: vi.fn(), setAppearance: vi.fn(), getStatus: vi.fn() }));

// The device's setting, which a test can change (and say it changed) while the page is open.
let deviceDark = false;
let listeners = [];
function device(dark) {
  deviceDark = dark;
  window.matchMedia = (query) => ({
    get matches() { return query.includes('dark') ? deviceDark : query.includes('reduce'); },
    media: query,
    addEventListener: (type, fn) => listeners.push(fn),
    removeEventListener: (type, fn) => { listeners = listeners.filter((l) => l !== fn); },
  });
}
function deviceTurns(dark) {
  deviceDark = dark;
  for (const fn of listeners) fn();
}
const shown = () => document.documentElement.dataset.theme;

beforeEach(() => {
  vi.resetAllMocks();
  listeners = [];
  device(false);
  resetTheme();
  resetStatus();
  getAppearance.mockResolvedValue({ theme: 'device' });
});
afterEach(() => {
  resetTheme();
  vi.restoreAllMocks();
});

test('the theme this window showed last is put on the page at once, and the server choice wins once it answers', async () => {
  localStorage.setItem(THEME_KEY, 'dark');
  getAppearance.mockResolvedValue({ theme: 'light' });

  startTheme();
  expect(shown()).toBe('dark');

  await vi.waitFor(() => expect(shown()).toBe('light'));
  expect(theme.saved).toBe('light');
  expect(localStorage.getItem(THEME_KEY)).toBe('light');
});

test('Match device follows the device, also when the device changes', async () => {
  device(true);
  startTheme();
  expect(shown()).toBe('dark');
  await vi.waitFor(() => expect(getAppearance).toHaveBeenCalledOnce());
  expect(theme.saved).toBe('device');
  expect(localStorage.getItem(THEME_KEY)).toBe('device');

  deviceTurns(false);
  expect(shown()).toBe('light');
  expect(localStorage.getItem(THEME_KEY)).toBe('device');
});

test("a window that last had Match device paints the device's setting at once, so a launch never shows the old look", () => {
  localStorage.setItem(THEME_KEY, 'device');
  device(true);

  startTheme();

  expect(shown()).toBe('dark');
});

test("an older window's cached look shows once, then the server's Match device follows the device", async () => {
  localStorage.setItem(THEME_KEY, 'light');
  device(true);

  startTheme();
  expect(shown()).toBe('light');

  await vi.waitFor(() => expect(shown()).toBe('dark'));
  expect(theme.saved).toBe('device');
  expect(localStorage.getItem(THEME_KEY)).toBe('device');
});

test("an unknown cached value is the device's setting", () => {
  localStorage.setItem(THEME_KEY, 'blue');
  device(true);

  startTheme();

  expect(shown()).toBe('dark');
});

test('a saved choice stays whatever the device says', async () => {
  getAppearance.mockResolvedValue({ theme: 'dark' });
  startTheme();
  await vi.waitFor(() => expect(shown()).toBe('dark'));

  deviceTurns(true);
  deviceTurns(false);
  expect(shown()).toBe('dark');
});

test('storage that cannot be read or written is ignored', async () => {
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
  getAppearance.mockResolvedValue({ theme: 'dark' });

  startTheme();
  expect(shown()).toBe('light');
  await vi.waitFor(() => expect(shown()).toBe('dark'));
});

test('an appearance that cannot be read leaves the page following the device, and an unknown value is Match device', async () => {
  getAppearance.mockRejectedValue(new Error('offline'));
  device(true);
  startTheme();
  expect(shown()).toBe('dark');

  serverTheme('dark');
  serverTheme('blue');
  expect(theme.saved).toBe('device');
  expect(shown()).toBe('dark');
  serverTheme('light');
  serverTheme(null);
  expect(theme.saved).toBe('device');
  expect(shown()).toBe('dark');
});

test('choosing saves the choice and shows it', async () => {
  startTheme();
  setAppearance.mockResolvedValue({ theme: 'dark' });

  await chooseTheme('dark');

  expect(setAppearance).toHaveBeenCalledWith('dark');
  expect(theme.saved).toBe('dark');
  expect(shown()).toBe('dark');
  expect(localStorage.getItem(THEME_KEY)).toBe('dark');
});

test('choosing Match device after Dark follows the device again', async () => {
  getAppearance.mockResolvedValue({ theme: 'dark' });
  startTheme();
  await vi.waitFor(() => expect(theme.saved).toBe('dark'));
  setAppearance.mockResolvedValue({ theme: 'device' });

  await chooseTheme('device');

  expect(setAppearance).toHaveBeenCalledWith('device');
  expect(theme.saved).toBe('device');
  expect(shown()).toBe('light');
  expect(localStorage.getItem(THEME_KEY)).toBe('device');
  deviceTurns(true);
  expect(shown()).toBe('dark');
});

test('a choice that cannot be saved keeps the saved one, and says why', async () => {
  getAppearance.mockResolvedValue({ theme: 'light' });
  startTheme();
  await vi.waitFor(() => expect(theme.saved).toBe('light'));
  setAppearance.mockRejectedValue(new Error("Couldn't save the appearance: Permission denied"));

  await expect(chooseTheme('dark')).rejects.toThrow("Couldn't save the appearance: Permission denied");

  expect(theme.saved).toBe('light');
  expect(shown()).toBe('light');
});

test('a choice made in another window arrives with the status poll', async () => {
  startTheme();
  getStatus.mockResolvedValue({ rescanning: false, theme: 'dark' });

  await refreshStatus();
  expect(theme.saved).toBe('dark');
  expect(shown()).toBe('dark');

  // a status without the field (an older server) changes nothing
  getStatus.mockResolvedValue({ rescanning: false });
  await refreshStatus();
  expect(shown()).toBe('dark');
});

test('a poll answered while a choice is being saved does not undo it', async () => {
  startTheme();
  let answer;
  setAppearance.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
  const saving = chooseTheme('dark');

  serverTheme('device');
  answer({ theme: 'dark' });
  await saving;
  expect(shown()).toBe('dark');
});
