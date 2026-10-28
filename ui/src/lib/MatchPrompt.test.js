import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import MatchPrompt from './MatchPrompt.svelte';
import { declineCustomMatch, getStatus, mergeCustomMatch } from './api.js';
import { resetStatus, status } from './status.svelte.js';
import { toast } from './toast.svelte.js';

vi.mock('./api.js', () => ({ mergeCustomMatch: vi.fn(), declineCustomMatch: vi.fn(), getStatus: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  getStatus.mockImplementation(() => new Promise(() => {}));
});

const match = {
  custom: { id: 'U3', name: 'Neptis sankara', common: null, photos: 1 },
  official: { id: 'A90', name: 'Neptis sankara', common: null, family: 'Nymphalidae' },
  offeredVersion: '2026-12-01',
};
const job = { state: 'waiting', kind: 'match', later: false, progress: { step: 1, steps: 1, stage: 'plan', done: 0, total: 0, continuing: false, waitingFor: 'export' }, notice: null };

test('the prompt names both species in italics; one photo reads as one', () => {
  render(MatchPrompt, { match });
  const group = screen.getByRole('group', { name: 'Custom species Neptis sankara matches an official one' });
  expect(group.textContent.replace(/\s+/g, ' ')).toContain('Your custom Neptis sankara (1 photo) now matches the official Neptis sankara.');
  expect([...group.querySelectorAll('i.bn')].map((i) => i.textContent)).toEqual(['Neptis sankara', 'Neptis sankara']);
});

test('while merging, both choices and the dialog\'s buttons are inactive, Escape does nothing; a refusal shows in the dialog', async () => {
  let refuse;
  mergeCustomMatch.mockImplementationOnce(() => new Promise((_, reject) => { refuse = reject; })).mockResolvedValueOnce(job);
  const onDone = vi.fn();
  render(MatchPrompt, { match, onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Merge' }));
  const dialog = screen.getByRole('dialog', { name: 'Merge Neptis sankara?' });
  await vi.waitFor(() => expect(document.activeElement).toBe(within(dialog).getByRole('button', { name: 'Cancel' })));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Merge' }));
  const confirm = within(dialog).getByRole('button', { name: 'Merging…' });
  expect(confirm.getAttribute('aria-disabled')).toBe('true');
  expect(within(dialog).getByRole('button', { name: 'Cancel' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(confirm);
  expect(screen.getByRole('dialog')).toBeTruthy();
  expect(mergeCustomMatch).toHaveBeenCalledTimes(1);

  refuse(Object.assign(new Error('Wait for the taxonomy update to finish.'), { code: 'taxonomy_updating', status: 409 }));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Wait for the taxonomy update to finish.');
  expect(onDone).not.toHaveBeenCalled();

  await fireEvent.click(within(dialog).getByRole('button', { name: 'Merge' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(onDone).toHaveBeenCalledWith(match);
  expect(status.taxonomyUpdate).toMatchObject({ state: 'waiting', kind: 'match', waitingFor: 'export' });
});

test('Cancel closes the dialog and focus goes back to Merge', async () => {
  render(MatchPrompt, { match });
  const merge = screen.getByRole('button', { name: 'Merge' });
  merge.focus();
  await fireEvent.click(merge);
  await fireEvent.keyDown(window, { key: 'Escape' });
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(merge));
});

test('Not now declines (inactive while asked) and says it will be offered again; a refusal is said in a toast', async () => {
  let answer;
  declineCustomMatch.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }))
    .mockRejectedValueOnce(new Error('This match is no longer open.'));
  const onDone = vi.fn();
  render(MatchPrompt, { match, onDone });
  const notNow = screen.getByRole('button', { name: 'Not now' });
  await fireEvent.click(notNow);
  expect(declineCustomMatch).toHaveBeenCalledWith('U3', 'A90');
  expect(notNow.getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByRole('button', { name: 'Merge' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(screen.getByRole('button', { name: 'Merge' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  answer(null);
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledWith(match));
  expect(toast.message).toBe('Not merged. Neptis sankara will be offered again after the next taxonomy update.');

  await fireEvent.click(notNow);
  await vi.waitFor(() => expect(toast.message).toBe('This match is no longer open.'));
  expect(onDone).toHaveBeenCalledTimes(1);
});

test('the confirmation\'s title italicises the scientific name, as its body does', async () => {
  render(MatchPrompt, { match });
  await fireEvent.click(screen.getByRole('button', { name: 'Merge' }));
  const dialog = screen.getByRole('dialog', { name: 'Merge Neptis sankara?' });
  const title = dialog.querySelector('h2');
  expect(title.textContent).toBe('Merge Neptis sankara?');
  expect(title.querySelector('i.bn').textContent).toBe('Neptis sankara');
});

test('a match withdrawn meanwhile (404 match_withdrawn) goes, with the message as a toast, from Merge or Not now', async () => {
  const gone = () => Object.assign(new Error('Neptis sankara is no longer on the official list, so this match was withdrawn.'), { code: 'match_withdrawn', status: 404 });
  mergeCustomMatch.mockRejectedValue(gone());
  declineCustomMatch.mockRejectedValue(gone());
  const onDone = vi.fn();
  const { unmount } = render(MatchPrompt, { match, onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Merge' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Merge' }));
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(onDone).toHaveBeenCalledWith(match);
  expect(toast.message).toBe('Neptis sankara is no longer on the official list, so this match was withdrawn.');
  unmount();

  onDone.mockClear();
  render(MatchPrompt, { match, onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Not now' }));
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledWith(match));
});

test('the confirmation counts the photos that move: one, several, or none', async () => {
  for (const [photos, words] of [
    [1, 'Its photo moves to the official Neptis sankara, with its tags and places, and the custom species is removed.'],
    [3, 'Its 3 photos move to the official Neptis sankara, with their tags and places, and the custom species is removed.'],
    [0, 'It has no photos to move to the official Neptis sankara, and the custom species is removed.'],
  ]) {
    const { unmount } = render(MatchPrompt, { match: { ...match, custom: { ...match.custom, photos } } });
    await fireEvent.click(screen.getByRole('button', { name: 'Merge' }));
    expect(screen.getByRole('dialog').textContent.replace(/\s+/g, ' ')).toContain(words);
    unmount();
  }
});
