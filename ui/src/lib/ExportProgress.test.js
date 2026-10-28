import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import ExportProgress from './ExportProgress.svelte';
import { getExports, getStatus, pauseExport, resumeExport } from './api.js';
import { EXPORT_CHANGED, resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({ getExports: vi.fn(), getStatus: vi.fn(), pauseExport: vi.fn(), resumeExport: vi.fn() }));

const run = (over = {}) => ({
  id: 4, state: 'running', what: 'both', layout: 'species', stripLocation: true, placeDepth: 'none', filter: '',
  path: '/Volumes/Backup/Butterfly_Atlas_export_2026-10-13', csv: 'species-list.csv',
  species: 48, total: 50000, done: 3200, copied: 3200, skipped: 0, failed: 0, renamed: 0,
  bytesTotal: 4509715660, bytesDone: 1712345678, pausedBy: null, notice: null, cancel: null, notRemoved: 0,
  startedAt: '2026-10-13T16:10:04Z', finishedAt: null, ...over,
});

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  // the status is asked again after an action; it answers later than these tests look
  getStatus.mockImplementation(() => new Promise(() => {}));
  getExports.mockResolvedValue({ current: run(), past: [] });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const show = (props = {}) => render(ExportProgress, { run: run(), onRun: vi.fn(), onList: vi.fn(), onCancel: vi.fn(), ...props });

test('a running export: how many of how many, a bar, the size done, the path; Pause and Cancel…', async () => {
  const onCancel = vi.fn();
  const { container } = show({ onCancel });
  expect(container.textContent).toContain('Exporting photos… 3,200 of 50,000');
  const bar = screen.getByRole('progressbar', { name: 'Photos exported' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['50000', '3200']);
  expect(container.textContent).toContain('1.6 of 4.2 GB');
  expect(container.textContent).toContain('/Volumes/Backup/Butterfly_Atlas_export_2026-10-13');
  expect([...container.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Pause', 'Cancel…']);
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel…' }));
  expect(onCancel).toHaveBeenCalledTimes(1);
});

test('while running it asks for the exports every second and hands them on; paused, it stops asking', async () => {
  vi.useFakeTimers();
  const onList = vi.fn();
  const later = { current: run({ done: 3300 }), past: [] };
  getExports.mockResolvedValueOnce({ current: run(), past: [] }).mockResolvedValueOnce(later);
  const { rerender } = show({ onList });
  await vi.advanceTimersByTimeAsync(999);
  expect(getExports).not.toHaveBeenCalled();
  await vi.advanceTimersByTimeAsync(1);
  expect(getExports).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1000);
  expect(getExports).toHaveBeenCalledTimes(2);
  expect(onList).toHaveBeenLastCalledWith(later);

  await rerender({ run: run({ state: 'paused', pausedBy: 'you' }) });
  await vi.advanceTimersByTimeAsync(5000);
  expect(getExports).toHaveBeenCalledTimes(2);
});

test('a failed poll is asked again a second later', async () => {
  vi.useFakeTimers();
  getExports.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  const onList = vi.fn();
  show({ onList });
  await vi.advanceTimersByTimeAsync(2000);
  expect(getExports).toHaveBeenCalledTimes(2);
  expect(onList).toHaveBeenCalledTimes(1);
});

test('Pause: both buttons are inactive while it is asked, then "Pausing…" until the export says it is paused', async () => {
  let answer;
  pauseExport.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  const onRun = vi.fn();
  const { rerender, container } = show({ onRun });
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(pauseExport).toHaveBeenCalledWith(4);
  expect([...container.querySelectorAll('button')].map((b) => b.getAttribute('aria-disabled'))).toEqual(['true', 'true']);
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(pauseExport).toHaveBeenCalledTimes(1);

  answer(run());
  await vi.waitFor(() => expect(onRun).toHaveBeenCalledWith(run()));
  expect(status.export).toMatchObject({ id: 4, state: 'running' });
  const pausing = screen.getByRole('button', { name: 'Pausing…' });
  expect(pausing.getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByRole('button', { name: 'Cancel…' }).getAttribute('aria-disabled')).toBe('false');

  await rerender({ run: run({ state: 'paused', pausedBy: 'you' }) });
  expect(container.textContent).toContain('Export paused at 3,200 of 50,000.');
  expect([...container.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Resume', 'Cancel…']);
});

test('a paused export shows its notice; Resume asks the server and hands the answer on', async () => {
  const notice = 'The drive is full: about 2.1 GB more is needed.';
  const resumed = run();
  resumeExport.mockResolvedValue(resumed);
  const onRun = vi.fn();
  const { container } = show({ run: run({ state: 'paused', pausedBy: 'drive_full', notice }), onRun });
  expect(container.textContent).toContain('Export paused at 3,200 of 50,000.');
  expect(screen.getByText(notice)).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
  expect(resumeExport).toHaveBeenCalledWith(4);
  await vi.waitFor(() => expect(onRun).toHaveBeenCalledWith(resumed));
  expect(status.export).toMatchObject({ state: 'running' });
});

test('an action that fails says why, and the buttons work again', async () => {
  pauseExport.mockRejectedValue(Object.assign(new Error('Butterfly Atlas could not reach its service.'), { code: 'offline' }));
  show();
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(screen.getByRole('button', { name: 'Pause' }).getAttribute('aria-disabled')).toBe('false');
});

test('the card can take focus (after a start) and its buttons are native ones in the tab order', () => {
  const { container } = show();
  const card = container.querySelector('section');
  expect(card.getAttribute('tabindex')).toBe('-1');
  expect(card.getAttribute('aria-labelledby')).toBeTruthy();
  expect([...card.querySelectorAll('button')].every((b) => b.tabIndex === 0 && !b.disabled)).toBe(true);
});

test('after "Pausing…" and a pause, a resume from elsewhere (the footer, Home) shows Pause active again', async () => {
  pauseExport.mockResolvedValue(run());
  const { rerender } = show();
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  expect(await screen.findByRole('button', { name: 'Pausing…' })).toBeTruthy();
  await rerender({ run: run({ state: 'paused', pausedBy: 'you' }) });
  await rerender({ run: run({ done: 3300 }) });
  const pause = screen.getByRole('button', { name: 'Pause' });
  expect(pause.getAttribute('aria-disabled')).toBe('false');
});

test('focus stays on the pause button as it turns: Resume → Pause after a resume, Pause → Pausing… → Resume after a pause', async () => {
  resumeExport.mockResolvedValue(run());
  pauseExport.mockResolvedValue(run());
  const onRun = vi.fn();
  const { rerender } = show({ run: run({ state: 'paused', pausedBy: 'you' }), onRun });
  // WebKit does not focus a clicked button, so focus is put there after the answer
  await fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
  await vi.waitFor(() => expect(onRun).toHaveBeenCalledTimes(1));
  await rerender({ run: run() });
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Pause' })));

  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Pausing…' })));
  await rerender({ run: run({ state: 'paused', pausedBy: 'you' }) });
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Resume' }));
});

test('a poll on its way when the card stops running, or goes, is dropped and no other is asked', async () => {
  vi.useFakeTimers();
  const answers = [];
  getExports.mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  const onList = vi.fn();
  const { rerender, unmount } = show({ onList });
  await vi.advanceTimersByTimeAsync(1000);
  expect(getExports).toHaveBeenCalledTimes(1);
  await rerender({ run: run({ state: 'paused', pausedBy: 'you' }) });
  answers[0]({ current: run(), past: [] });
  await vi.advanceTimersByTimeAsync(5000);
  expect([onList.mock.calls.length, getExports.mock.calls.length]).toEqual([0, 1]);

  await rerender({ run: run() });
  await vi.advanceTimersByTimeAsync(1000);
  expect(getExports).toHaveBeenCalledTimes(2);
  unmount();
  answers[1]({ current: run(), past: [] });
  await vi.advanceTimersByTimeAsync(5000);
  expect([onList.mock.calls.length, getExports.mock.calls.length]).toEqual([0, 2]);
  expect(vi.getTimerCount()).toBe(0);
});

test('Resume or Pause refused because the export has already finished says nothing: the status and the screen read it again', async () => {
  const finished = () => Object.assign(new Error('This export has already finished.'), { code: 'export_finished', status: 409 });
  resumeExport.mockRejectedValue(finished());
  pauseExport.mockRejectedValue(finished());
  const heard = vi.fn();
  window.addEventListener(EXPORT_CHANGED, heard);
  const onRun = vi.fn();
  const { unmount } = show({ run: run({ state: 'paused', pausedBy: 'you' }), onRun });
  await fireEvent.click(screen.getByRole('button', { name: 'Resume' }));
  await vi.waitFor(() => expect(heard).toHaveBeenCalledTimes(1));
  expect(heard.mock.calls[0][0].detail).toBeNull();
  expect(getStatus).toHaveBeenCalled();
  expect(screen.queryByRole('alert')).toBeNull();
  expect(onRun).not.toHaveBeenCalled();
  unmount();

  show({ onRun });
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  await vi.waitFor(() => expect(heard).toHaveBeenCalledTimes(2));
  expect(screen.queryByRole('alert')).toBeNull();
  window.removeEventListener(EXPORT_CHANGED, heard);
});

test('focus moved to Cancel… while Pause is asked stays there when the answer comes', async () => {
  let answer;
  pauseExport.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  const onRun = vi.fn();
  show({ onRun });
  await fireEvent.click(screen.getByRole('button', { name: 'Pause' }));
  const cancel = screen.getByRole('button', { name: 'Cancel…' });
  cancel.focus();
  answer(run());
  await vi.waitFor(() => expect(onRun).toHaveBeenCalledTimes(1));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(document.activeElement).toBe(cancel);
});
