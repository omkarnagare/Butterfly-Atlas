import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import ImportFiling from './ImportFiling.svelte';

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});

const summary = (extra = {}) => ({ rootName: 'Butterflies 2024', folderMissing: false, paused: false, phase: 'idle', progress: null, stopped: null, ...extra });

test('offers to file the assigned photos, and says why it can\'t when none are', async () => {
  const onFile = vi.fn();
  const { rerender } = render(ImportFiling, { summary: summary(), count: 2140, onFile, onPause: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'File 2,140 assigned photos' }));
  expect(onFile).toHaveBeenCalledOnce();

  await rerender({ summary: summary(), count: 0, onFile, onPause: vi.fn() });
  const button = screen.getByRole('button', { name: 'File 0 assigned photos' });
  expect(button.getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(button.getAttribute('aria-describedby')).textContent).toBe('Assign a species to some photos first.');
  await fireEvent.click(button);
  expect(onFile).toHaveBeenCalledOnce();

  await rerender({ summary: summary({ folderMissing: true }), count: 1, onFile, onPause: vi.fn() });
  expect(screen.getByRole('button', { name: 'File 1 assigned photo' }).getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByText('Connect the folder or choose where it moved first.')).toBeTruthy();
});

test('while filing shows its progress and Pause', async () => {
  const onPause = vi.fn();
  render(ImportFiling, { summary: summary({ phase: 'filing', progress: { done: 1200, total: 2140 } }), count: 940, onFile: vi.fn(), onPause });

  expect(screen.getByRole('group', { name: 'Filing' }).textContent).toContain('Filing photos… 1,200 of 2,140');
  expect(screen.getByRole('progressbar', { name: 'Photos filed' }).getAttribute('value')).toBe('1200');
  expect(screen.queryByRole('button', { name: /assigned/ })).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(onPause).toHaveBeenCalledOnce();
});

test('a paused import says only Paused; filing cut short by closing says so and offers Continue filing', async () => {
  const onFile = vi.fn();
  const { rerender } = render(ImportFiling, { summary: summary({ paused: true }), count: 3, onFile, onPause: vi.fn() });
  expect(screen.getByText('Paused')).toBeTruthy();
  expect(screen.queryByText(/won't carry on/)).toBeNull();

  await rerender({ summary: summary({ stopped: { phase: 'filing', done: 1200, total: 2140, at: '2026-10-12T09:00:00Z' } }), count: 940, onFile, onPause: vi.fn() });
  const line = screen.getByText("Your import of Butterflies 2024 stopped when Butterfly Atlas closed (filing 1,200 of 2,140). It won't carry on by itself.");
  const button = screen.getByRole('button', { name: 'Continue filing' });
  expect(button.getAttribute('aria-describedby')).toBe(line.id);
  await fireEvent.click(button);
  expect(onFile).toHaveBeenCalledOnce();
});

test('focus follows the button as filing starts and stops, and a status region says so', async () => {
  const props = { count: 3, onFile: vi.fn(), onPause: vi.fn() };
  const { rerender } = render(ImportFiling, { summary: summary(), ...props });
  const region = screen.getByRole('status');
  expect(region.textContent).toBe('');
  screen.getByRole('button', { name: 'File 3 assigned photos' }).focus();

  await rerender({ summary: summary({ phase: 'filing', progress: { done: 0, total: 3 } }), ...props });
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Pause' })));
  expect(region.textContent).toBe('Filing started.');

  await rerender({ summary: summary({ paused: true }), ...props });
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'File 3 assigned photos' })));
  expect(region.textContent).toBe('Paused.');

  await rerender({ summary: summary({ phase: 'filing', progress: { done: 0, total: 3 } }), ...props });
  await rerender({ summary: summary(), count: 0, onFile: props.onFile, onPause: props.onPause });
  expect(region.textContent).toBe('Filing finished.');
});

test('filing that stopped for a library notice says Filing stopped', async () => {
  const props = { count: 3, onFile: vi.fn(), onPause: vi.fn() };
  const { rerender } = render(ImportFiling, { summary: summary({ phase: 'filing', progress: { done: 0, total: 3 } }), ...props });
  await rerender({ summary: summary({ notice: "Your library's drive is full. Free up some space, then continue filing." }), ...props });
  expect(screen.getByRole('status').textContent).toBe('Filing stopped.');
});

test('pairs to file without a choice: filing waits, and says why with the number of pairs (Phase 7a)', async () => {
  const onFile = vi.fn();
  const { rerender } = render(ImportFiling, { summary: summary({ pairs: { count: 340, toFile: 212, choice: null, locked: false } }), count: 400, onFile, onPause: vi.fn() });
  const button = screen.getByRole('button', { name: 'File 400 assigned photos' });
  expect(button.getAttribute('aria-disabled')).toBe('true');
  const reason = document.getElementById(button.getAttribute('aria-describedby'));
  expect(reason.textContent).toBe('Choose how to file the 212 RAW + JPEG pairs first.');
  // something to act on is never said muted (review fix U6)
  expect(reason.classList.contains('muted')).toBe(false);
  await fireEvent.click(button);
  expect(onFile).not.toHaveBeenCalled();

  await rerender({ summary: summary({ pairs: { count: 340, toFile: 212, choice: 'both', locked: false } }), count: 400, onFile, onPause: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'File 400 assigned photos' }));
  expect(onFile).toHaveBeenCalledOnce();
});

test('with Both chosen, the button counts files and shots (review fix U7)', async () => {
  const onFile = vi.fn();
  render(ImportFiling, { summary: summary({ pairs: { count: 1, toFile: 1, choice: 'both', locked: false } }), count: 11, files: 12, onFile, onPause: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'File 12 photos (11 shots)' }));
  expect(onFile).toHaveBeenCalledOnce();
});
