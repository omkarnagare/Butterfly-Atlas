import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import OpenedElsewhere from './OpenedElsewhere.svelte';
import { getStatus, quitOpenedElsewhere } from './api.js';
import { resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({ quitOpenedElsewhere: vi.fn(), getStatus: vi.fn(), getExport: vi.fn(), getBackup: vi.fn(), checkHeic: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  getStatus.mockResolvedValue({ rescanning: false });
});

// One device at a time (design §6; contract §2, §5): the page every window shows once the library was opened elsewhere.
const at = (h, m) => new Date(2026, 10, 4, h, m).toISOString();
const page = () => screen.getByRole('main');

test('says where and when it was opened, that this device stopped saving, how to settle it, and Quit', () => {
  status.openedElsewhere = { name: 'DESKTOP-PUNE', at: at(14, 20), unsentSince: null };
  render(OpenedElsewhere);
  expect(screen.getByRole('heading', { level: 1, name: 'Your library was opened on another device' })).toBeTruthy();
  expect(screen.getByText('DESKTOP-PUNE', { selector: 'strong' })).toBeTruthy();
  expect(page().textContent).toContain('Your library was opened on DESKTOP-PUNE on 4 Nov at 14:20 while Butterfly Atlas was still open here.'
    + ' To keep one version of your library, this device has stopped saving. To use the library here again, quit Butterfly Atlas on'
    + ' DESKTOP-PUNE and let your sync app finish, then quit and reopen it here.');
  expect(page().textContent).not.toContain("haven't reached the library yet");
  expect(screen.getByRole('button', { name: 'Quit' })).toBeTruthy();
});

test('unsent changes are said with when they start; a nameless device is "another device" and "the other device"', () => {
  status.openedElsewhere = { name: null, at: at(14, 20), unsentSince: at(14, 2) };
  render(OpenedElsewhere);
  expect(page().querySelector('strong')).toBeNull();
  expect(page().textContent).toContain('Your library was opened on another device on 4 Nov at 14:20');
  expect(screen.getByText("Changes made here since 4 Nov, 14:02 haven't reached the library yet. They will be sent when you reopen"
    + ' Butterfly Atlas here, unless changes are saved on the other device first.')).toBeTruthy();
});

test('Quit asks once, then the page says the app has closed here and Quit goes', async () => {
  let answer;
  quitOpenedElsewhere.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  status.openedElsewhere = { name: 'DESKTOP-PUNE', at: at(14, 20), unsentSince: null };
  render(OpenedElsewhere);
  await fireEvent.click(screen.getByRole('button', { name: 'Quit' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Quitting…' }));
  expect(quitOpenedElsewhere).toHaveBeenCalledOnce();
  answer(null);
  expect((await screen.findByRole('status')).textContent).toBe('Butterfly Atlas has closed here. You can close this window.');
  expect(screen.queryByRole('button')).toBeNull();
  expect(status.closedHere).toBe(true);
});

test('a Quit that fails says why and stays; one refused as nothing opened elsewhere reads the status again', async () => {
  status.openedElsewhere = { name: 'DESKTOP-PUNE', at: at(14, 20), unsentSince: null };
  quitOpenedElsewhere.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  render(OpenedElsewhere);
  await fireEvent.click(screen.getByRole('button', { name: 'Quit' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.');
  expect(screen.getByRole('button', { name: 'Quit' })).toBeTruthy();
  expect(status.closedHere).toBe(false);

  quitOpenedElsewhere.mockRejectedValueOnce(Object.assign(new Error('The library was not opened on another device.'), { code: 'not_opened_elsewhere' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Quit' }));
  expect((await screen.findByRole('alert')).textContent).toBe('The library was not opened on another device.');
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalled());
});

test('a status that can\'t be read is said (the app quit from another window)', () => {
  status.openedElsewhere = { name: 'DESKTOP-PUNE', at: at(14, 20), unsentSince: null };
  status.unreachable = true;
  render(OpenedElsewhere);
  expect(screen.getByRole('alert').textContent).toBe("Butterfly Atlas isn't answering. If you closed it, open it again.");
});
