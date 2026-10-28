import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { THEME_KEY, chooseTheme, resetTheme, serverTheme, startTheme, theme } from './theme.svelte.js';
import { getAppearance, getStatus, setAppearance } from './api.js';
import { refreshStatus, resetStatus } from './status.svelte.js';

vi.mock('./api.js', () => ({ getAppearance: vi.fn(), setAppearance: vi.fn(), getStatus: vi.fn() }));

// The computer's setting, which a test can change (and say it changed) while the page is open.
let computerDark = false;
let listeners = [];
function computer(dark) {
  computerDark = dark;
  window.matchMedia = (query) => ({
    get matches() { return query.includes('dark') ? computerDark : query.includes('reduce'); },
    media: query,
    addEventListener: (type, fn) => listeners.push(fn),
    removeEventListener: (type, fn) => { listeners = listeners.filter((l) => l !== fn); },
  });
}
function computerTurns(dark) {
  computerDark = dark;
  for (const fn of listeners) fn();
}
const shown = () => document.documentElement.dataset.theme;

beforeEach(() => {
  vi.resetAllMocks();
  listeners = [];
  computer(false);
  resetTheme();
  resetStatus();
  getAppearance.mockResolvedValue({ theme: null });
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

test('with no saved choice the page follows the computer, also when the computer changes', async () => {
  computer(true);
  startTheme();
  expect(shown()).toBe('dark');
  await vi.waitFor(() => expect(getAppearance).toHaveBeenCalledOnce());
  expect(theme.saved).toBeNull();

  computerTurns(false);
  expect(shown()).toBe('light');
  expect(localStorage.getItem(THEME_KEY)).toBe('light');
});

test('a saved choice stays whatever the computer says', async () => {
  getAppearance.mockResolvedValue({ theme: 'dark' });
  startTheme();
  await vi.waitFor(() => expect(shown()).toBe('dark'));

  computerTurns(true);
  computerTurns(false);
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

test('an appearance that cannot be read leaves the page following the computer, and an unknown value is no choice', async () => {
  getAppearance.mockRejectedValue(new Error('offline'));
  computer(true);
  startTheme();
  expect(shown()).toBe('dark');

  serverTheme('blue');
  expect(theme.saved).toBeNull();
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

  serverTheme(null);
  answer({ theme: 'dark' });
  await saving;
  expect(shown()).toBe('dark');
});
