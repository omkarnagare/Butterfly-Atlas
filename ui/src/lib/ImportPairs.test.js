import { fireEvent, render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import ImportPairs from './ImportPairs.svelte';
import { resetStatus, status } from './status.svelte.js';

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});

const pairs = (extra = {}) => ({ count: 340, toFile: 212, choice: null, locked: false, ...extra });

test('pairs to file and no choice: the bar asks, with nothing chosen, in the agreed order, and saves the choice at once', async () => {
  const onChoose = vi.fn(async () => {});
  const { container } = render(ImportPairs, { pairs: pairs(), onChoose });

  const bar = screen.getByRole('group', { name: 'This folder has 340 RAW + JPEG pairs (same name, same folder).' });
  expect(bar.textContent).toContain('File:');
  const radios = screen.getAllByRole('radio');
  expect(radios.map((r) => r.closest('label').textContent.trim())).toEqual(['Both', 'JPEG only', 'RAW only']);
  expect(radios.some((r) => r.checked)).toBe(false);
  expect(screen.getByText('Both: two photos of each shot.')).toBeTruthy();
  expect(container.textContent).not.toContain('Change');

  await fireEvent.click(radios[1]);
  expect(onChoose).toHaveBeenCalledWith('jpeg');
});

test('after a choice it is one line with Change, which opens the choices again with the current one chosen', async () => {
  // as the page does: the summary that says the new choice comes back before the save is over
  let rerender;
  const onChoose = vi.fn(async (choice) => rerender({ pairs: pairs({ choice }), onChoose }));
  ({ rerender } = render(ImportPairs, { pairs: pairs({ choice: 'both' }), onChoose }));
  expect(screen.getByText(/RAW \+ JPEG pairs: Both/).textContent.replace(/\s+/g, ' ').trim()).toBe('RAW + JPEG pairs: Both · Change');
  expect(screen.queryByRole('radio')).toBeNull();

  await fireEvent.click(screen.getByRole('button', { name: 'Change' }));
  expect(screen.getByRole('radio', { name: 'Both' }).checked).toBe(true);
  await fireEvent.click(screen.getByRole('radio', { name: 'RAW only' }));
  expect(onChoose).toHaveBeenCalledWith('raw');
  await vi.waitFor(() => expect(screen.queryByRole('radio')).toBeNull());
  expect(screen.getByText(/RAW \+ JPEG pairs: RAW only/)).toBeTruthy();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Change' }));
});

test('once a pair was filed the choice is locked and said as done', () => {
  const { container } = render(ImportPairs, { pairs: pairs({ choice: 'jpeg', locked: true, toFile: 0 }), onChoose: vi.fn() });
  expect(container.textContent.trim()).toBe('RAW + JPEG pairs filed: JPEG only.');
  expect(screen.queryByRole('button')).toBeNull();
});

test('nothing shows without pairs, or with pairs but none to file and no choice; one pair is said in the singular', async () => {
  const { container, rerender } = render(ImportPairs, { pairs: null, onChoose: vi.fn() });
  expect(container.textContent.trim()).toBe('');
  await rerender({ pairs: pairs({ toFile: 0 }), onChoose: vi.fn() });
  expect(container.textContent.trim()).toBe('');
  await rerender({ pairs: pairs({ count: 1, toFile: 1 }), onChoose: vi.fn() });
  expect(screen.getByRole('group', { name: 'This folder has 1 RAW + JPEG pair (same name, same folder).' })).toBeTruthy();
});

test('a choice made while a change is being saved does nothing', async () => {
  const onChoose = vi.fn();
  render(ImportPairs, { pairs: pairs(), busy: true, onChoose });
  const radio = screen.getByRole('radio', { name: 'Both' });
  expect(radio.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(radio);
  expect(onChoose).not.toHaveBeenCalled();
  expect(radio.checked).toBe(false);
});

test('Change can be closed without changing anything: "Keep Both" (review fix U6)', async () => {
  const onChoose = vi.fn();
  render(ImportPairs, { pairs: pairs({ choice: 'both' }), onChoose });
  await fireEvent.click(screen.getByRole('button', { name: 'Change' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Keep Both' }));
  expect(screen.queryByRole('radio')).toBeNull();
  expect(onChoose).not.toHaveBeenCalled();
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Change' }));
});

test('a choice that could not be saved puts the radios back as they were (review fix U6)', async () => {
  // the page says why (the server's words); the summary keeps its choice
  const onChoose = vi.fn(async () => {});
  const { rerender } = render(ImportPairs, { pairs: pairs(), onChoose });
  await fireEvent.click(screen.getByRole('radio', { name: 'JPEG only' }));
  await vi.waitFor(() => expect(onChoose).toHaveBeenCalledWith('jpeg'));
  await vi.waitFor(() => expect(screen.getAllByRole('radio').some((r) => r.checked)).toBe(false));

  await rerender({ pairs: pairs({ choice: 'both' }), onChoose });
  await fireEvent.click(screen.getByRole('button', { name: 'Change' }));
  await fireEvent.click(screen.getByRole('radio', { name: 'RAW only' }));
  await vi.waitFor(() => expect(onChoose).toHaveBeenCalledWith('raw'));
  await vi.waitFor(() => expect(screen.getByRole('radio', { name: 'Both' }).checked).toBe(true));
  expect(screen.getByRole('radio', { name: 'RAW only' }).checked).toBe(false);
});

/* ---------- RAW + HEIC pairs (Phase 7b) ---------- */

test('RAW + HEIC pairs: the bar, its choices and its lines say HEIC (contract §1, audit 14)', async () => {
  const { rerender, container } = render(ImportPairs, { pairs: pairs({ count: 120, kinds: ['heic'] }), onChoose: vi.fn() });
  expect(screen.getByRole('group', { name: 'This folder has 120 RAW + HEIC pairs (same name, same folder).' })).toBeTruthy();
  expect(screen.getAllByRole('radio').map((r) => r.closest('label').textContent.trim())).toEqual(['Both', 'HEIC only', 'RAW only']);

  await rerender({ pairs: pairs({ count: 120, kinds: ['heic'], choice: 'jpeg' }), onChoose: vi.fn() });
  expect(container.textContent.replace(/\s+/g, ' ').trim()).toBe('RAW + HEIC pairs: HEIC only · Change');
  await rerender({ pairs: pairs({ count: 120, kinds: ['heic'], choice: 'both', locked: true, toFile: 0 }), onChoose: vi.fn() });
  expect(container.textContent.trim()).toBe('RAW + HEIC pairs filed: Both.');
});

test('with both kinds: "RAW + JPEG or RAW + HEIC pairs" and "JPEG or HEIC only"', () => {
  render(ImportPairs, { pairs: pairs({ kinds: ['jpeg', 'heic'] }), onChoose: vi.fn() });
  expect(screen.getByRole('group', { name: 'This folder has 340 RAW + JPEG or RAW + HEIC pairs (same name, same folder).' })).toBeTruthy();
  expect(screen.getByRole('radio', { name: 'JPEG or HEIC only' })).toBeTruthy();
});

test('without a HEIC decoder the bar says HEIC only would file photos that can\'t be seen here yet (audit 15)', async () => {
  const { rerender } = render(ImportPairs, { pairs: pairs({ kinds: ['heic'] }), heicMissing: true, onChoose: vi.fn() });
  const line = "This computer can't show HEIC photos yet. HEIC only would file photos you can't see here until it can.";
  expect(screen.getByText(line)).toBeTruthy();
  await rerender({ pairs: pairs({ kinds: ['jpeg'] }), heicMissing: true, onChoose: vi.fn() });
  expect(screen.queryByText(line)).toBeNull();
});

test('the extra line names the choice as the bar says it (7b review fix U6)', () => {
  render(ImportPairs, { pairs: pairs({ kinds: ['jpeg', 'heic'] }), heicMissing: true, onChoose: vi.fn() });
  expect(screen.getByText("This computer can't show HEIC photos yet. JPEG or HEIC only would file HEIC photos you can't see here until it can.")).toBeTruthy();
});

test('on another system the pair bar\'s line says it has no decoder Butterfly Atlas can use (7b review fix U11)', () => {
  status.heic = { state: 'missing', decoder: null, system: 'other', reason: 'absent' };
  render(ImportPairs, { pairs: pairs({ kinds: ['heic'] }), heicMissing: true, onChoose: vi.fn() });
  expect(screen.getByText("This computer has no HEIC decoder Butterfly Atlas can use. HEIC only would file photos you can't see here.")).toBeTruthy();
  resetStatus();
});
