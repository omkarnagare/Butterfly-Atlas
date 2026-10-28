import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import PastBackups from './PastBackups.svelte';
import { clearBackups, deleteBackup, getBackup } from './api.js';

vi.mock('./api.js', () => ({ clearBackups: vi.fn(), deleteBackup: vi.fn(), getBackup: vi.fn() }));

const at = (minutes) => new Date(2026, 9, 13, 21, minutes).toISOString();
const run = (id, over = {}) => ({
  id, state: 'done', what: 'everything', folder: `Butterfly_Atlas_backup_2026-10-13_${id}`, path: `/Volumes/B/Butterfly_Atlas_backup_2026-10-13_${id}`,
  total: 312, done: 312, copied: 312, skipped: 0, failed: 0, bytesTotal: 4509715660, bytesDone: 4509715660, pausedBy: null, notice: null,
  cancel: null, notRemoved: 0, startedAt: at(40), finishedAt: at(50), ...over,
});

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => vi.restoreAllMocks());

function show(past, props = {}) {
  let view;
  let current = past;
  const onRemoved = vi.fn(async (id) => {
    current = current.filter((r) => r.id !== id);
    await view.rerender({ past: current });
  });
  const onCleared = vi.fn(async () => {
    current = [];
    await view.rerender({ past: current });
  });
  view = render(PastBackups, { past, onRemoved, onCleared, fallbackFocus: () => document.body, ...props });
  return { ...view, onRemoved, onCleared };
}
const rows = () => [...screen.getByRole('list', { name: 'Past backups' }).children];
const rowText = (li) => li.querySelector('.pe-t').textContent.replace(/\s+/g, ' ').trim();

test('each past backup: done with the date it ended and what, or where it was cancelled and what became of the files; then the path', async () => {
  show([
    run(4),
    run(3, { what: 'database', total: 0, done: 0 }),
    run(2, { state: 'cancelled', cancel: 'keep', done: 120, finishedAt: null }),
    run(1, { state: 'cancelled', cancel: 'remove', done: 120, notRemoved: 120 }),
  ]);
  expect(screen.getByRole('heading', { level: 2, name: 'Past backups' })).toBeTruthy();
  expect(rows().map(rowText)).toEqual([
    '✓ 13 Oct 2026, 21:50 · Everything, 312 photos',
    '✓ 13 Oct 2026, 21:50 · Database and settings only',
    '13 Oct 2026, 21:40 · Cancelled at 120 of 312 (files kept)',
  ]);
  await fireEvent.click(screen.getByRole('button', { name: 'Show all 4 past backups' }));
  expect(rowText(rows()[3])).toBe('13 Oct 2026, 21:50 · Cancelled at 120 of 312 (files removed)');
  expect(rows()[3].textContent).toContain("120 files were kept: they had changed since the backup, or couldn't be reached.");
  expect(rows()[0].textContent).toContain('/Volumes/B/Butterfly_Atlas_backup_2026-10-13_4');
  expect(screen.queryByRole('button', { name: 'Show details' })).toBeNull();
  expect(screen.getByRole('button', { name: 'Clear history' })).toBeTruthy();
});

test('nothing past, nothing shown', () => {
  show([]);
  expect(screen.queryByRole('heading', { name: 'Past backups' })).toBeNull();
});

test('Show details loads the photos that were not copied with their reasons and how many more; failing says why, with Try again', async () => {
  getBackup.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.')).mockResolvedValue({
    ...run(5, { skipped: 1, failed: 1 }),
    problems: [{ kind: 'skipped', rel: 'Nymphalidae/a.jpg', reason: 'removed while copying' }, { kind: 'failed', rel: 'Nymphalidae/b.jpg', reason: 'could not be read' }],
    moreProblems: 12,
  });
  show([run(5, { skipped: 1, failed: 1 })]);
  expect(rows()[0].textContent).toContain('1 skipped, 1 failed');
  const toggle = screen.getByRole('button', { name: 'Show details' });
  await fireEvent.click(toggle);
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  const list = await screen.findByRole('list', { name: 'Photos that were not copied' });
  expect([...list.querySelectorAll('li')].map((li) => li.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Skipped Nymphalidae/a.jpg — removed while copying',
    'Failed Nymphalidae/b.jpg — could not be read',
  ]);
  expect(screen.getByText('and 12 more')).toBeTruthy();
  await fireEvent.click(toggle);
  expect(screen.queryByRole('list', { name: 'Photos that were not copied' })).toBeNull();
});

test('Remove from the list asks first and removes only the record; focus goes to the next row, else the one before, else past the list', async () => {
  deleteBackup.mockResolvedValue(null);
  const outside = document.createElement('button');
  document.body.append(outside);
  const { onRemoved } = show([run(3), run(2), run(1)], { fallbackFocus: () => outside });

  await fireEvent.click(within(rows()[1]).getByRole('button', { name: 'Remove from the list' }));
  const dialog = screen.getByRole('dialog', { name: 'Remove this backup from the list?' });
  expect(dialog.textContent).toContain('The backed up files stay where they are.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(onRemoved).toHaveBeenCalledWith(2));
  expect(deleteBackup).toHaveBeenCalledWith(2);
  await vi.waitFor(() => expect(document.activeElement).toBe(within(rows()[1]).getByRole('button', { name: 'Remove from the list' })));
  expect(rows().map((li) => li.dataset.backupId)).toEqual(['3', '1']);

  await fireEvent.click(within(rows()[1]).getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(within(rows()[0]).getByRole('button', { name: 'Remove from the list' })));
  await fireEvent.click(within(rows()[0]).getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(outside));
  expect(screen.queryByRole('list', { name: 'Past backups' })).toBeNull();
  outside.remove();
});

test('a removal that fails says why and keeps the row; one already gone leaves the list all the same', async () => {
  deleteBackup.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.')).mockRejectedValueOnce(Object.assign(new Error('gone'), { code: 'not_found' }));
  const { onRemoved } = show([run(2), run(1)]);
  await fireEvent.click(within(rows()[0]).getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  expect((await within(screen.getByRole('dialog')).findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(onRemoved).not.toHaveBeenCalled();
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(onRemoved).toHaveBeenCalledWith(2));
});

test('Clear history asks first, clears only the records, and tells the screen', async () => {
  clearBackups.mockResolvedValue(2);
  const { onCleared } = show([run(2), run(1)]);
  await fireEvent.click(screen.getByRole('button', { name: 'Clear history' }));
  const dialog = screen.getByRole('dialog', { name: 'Remove all past backups from the list?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Clear history' }));
  await vi.waitFor(() => expect(onCleared).toHaveBeenCalled());
  expect(clearBackups).toHaveBeenCalled();
});
