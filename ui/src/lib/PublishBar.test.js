import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import PublishBar from './PublishBar.svelte';
import { getStatus, publishAgain } from './api.js';
import { resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({ publishAgain: vi.fn(), getStatus: vi.fn(), getExport: vi.fn(), getBackup: vi.fn(), checkHeic: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  getStatus.mockResolvedValue({ rescanning: false });
});

// One device at a time (design §5, §10; contract §2, §5): the standing bar while changes can't be sent to the library.
const FULL = 'The drive with your library is full. Free some space, then try again.';
const bar = () => screen.getByRole('region', { name: 'Sending changes to your library' });

test('nothing shows while publishing works', () => {
  render(PublishBar);
  expect(screen.queryByRole('region')).toBeNull();
});

test('during the 5 automatic tries: why, that it tries again in a minute, and no Try again or Dismiss', () => {
  status.publish = { state: 'retrying', reason: FULL, tries: 2 };
  render(PublishBar);
  expect(bar().textContent).toContain("Your changes are saved on this device but couldn't be sent to your library folder: the drive with your"
    + " library is full. Free some space, then try again. Trying again in a minute. Don't open your library on another device until this"
    + ' message goes away.');
  expect(within(bar()).queryByRole('button')).toBeNull();
});

test('after the 5th failure: the other wording and Try again, which makes one attempt; one that works clears the bar', async () => {
  status.publish = { state: 'stopped', reason: FULL, tries: 5 };
  publishAgain.mockResolvedValue({ publish: null });
  render(PublishBar);
  expect(bar().textContent).toContain("couldn't be sent to your library folder after 5 tries: the drive with your library is full. Free some"
    + ' space, then try again. Fix that (for example, start your sync app or free some space), then choose Try again.');
  expect(within(bar()).getAllByRole('button').map((b) => b.textContent)).toEqual(['Try again']);
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(publishAgain).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(screen.queryByRole('region')).toBeNull());
  expect(getStatus).toHaveBeenCalled();
});

test('a Try again that fails again starts another round: the bar says it is trying again', async () => {
  status.publish = { state: 'stopped', reason: FULL, tries: 5 };
  publishAgain.mockResolvedValue({ publish: { state: 'retrying', reason: FULL, tries: 1 } });
  getStatus.mockResolvedValue({ rescanning: false, publish: { state: 'retrying', reason: FULL, tries: 1 } });
  render(PublishBar);
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(bar().textContent).toContain('Trying again in a minute.'));
  expect(screen.queryByRole('button')).toBeNull();
});

test('Try again asks once; one that fails says why; one refused as not stopped reads the status, and the bar follows it', async () => {
  let answer;
  status.publish = { state: 'stopped', reason: FULL, tries: 5 };
  publishAgain.mockImplementationOnce(() => new Promise((resolve, reject) => { answer = { resolve, reject }; }));
  render(PublishBar);
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Trying again…' }));
  expect(publishAgain).toHaveBeenCalledOnce();
  answer.reject(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.');
  expect(getStatus).not.toHaveBeenCalled();

  getStatus.mockResolvedValue({ rescanning: false, publish: { state: 'retrying', reason: FULL, tries: 1 } });
  publishAgain.mockRejectedValueOnce(Object.assign(new Error('Your changes are already being sent again.'), { code: 'not_stopped' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(getStatus).toHaveBeenCalled());
  await vi.waitFor(() => expect(bar().textContent).toContain('Trying again in a minute.'));
  expect(screen.queryByRole('button')).toBeNull();
});
