import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import PastExports from './PastExports.svelte';
import { clearExports, deleteExport, getExport } from './api.js';

vi.mock('./api.js', () => ({ clearExports: vi.fn(), deleteExport: vi.fn(), getExport: vi.fn() }));

const at = (minutes) => new Date(2026, 9, 13, 21, minutes).toISOString();
const run = (id, over = {}) => ({
  id, state: 'done', what: 'both', layout: 'species', stripLocation: true, placeDepth: 'none', filter: '',
  path: `/Volumes/Backup/Butterfly_Atlas_export_2026-10-13_${id}`, csv: 'species-list.csv',
  species: 48, total: 312, done: 312, copied: 312, skipped: 0, failed: 0, renamed: 0,
  bytesTotal: 4509715660, bytesDone: 4509715660, pausedBy: null, notice: null, cancel: null, notRemoved: 0,
  startedAt: at(40), finishedAt: at(50), ...over,
});
const problem = (n, kind = 'skipped') => ({
  kind, imageId: n, file: `Danaus_chrysippus_0${n}.jpg`, species: 'Danaus chrysippus', target: `Nymphalidae/Danaus_chrysippus_0${n}.jpg`,
  reason: kind === 'skipped' ? 'The photo was deleted after the export started.' : 'The copy could not be written.',
});

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => vi.restoreAllMocks());

/** Renders the list; removing and clearing update it as the screen does. */
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
  view = render(PastExports, { past, onRemoved, onCleared, fallbackFocus: () => document.body, ...props });
  return { ...view, onRemoved, onCleared };
}
const rows = () => [...screen.getByRole('list', { name: 'Past exports' }).children];
const rowText = (li) => li.querySelector('.pe-t').textContent.replace(/\s+/g, ' ').trim();

test('each past export: done with the date it ended and what, or where it was cancelled and what became of the files; then the path', async () => {
  show([
    run(5),
    run(4, { what: 'csv', csv: 'Butterfly_Atlas_species_list_2026-10-13.csv', total: 0, done: 0, path: '/Volumes/Backup/Butterfly_Atlas_species_list_2026-10-13.csv' }),
    run(3, { what: 'photos', csv: null, finishedAt: new Date(2026, 9, 12, 9, 5).toISOString() }),
    run(2, { state: 'cancelled', cancel: 'keep', done: 120, finishedAt: null }),
    run(1, { state: 'cancelled', cancel: 'remove', done: 120, notRemoved: 120 }),
  ]);
  expect(screen.getByRole('heading', { level: 2, name: 'Past exports' })).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Show all 5 past exports' }));
  expect(rows().map(rowText)).toEqual([
    '✓ 13 Oct 2026, 21:50 · 312 photos and species list',
    '✓ 13 Oct 2026, 21:50 · Species list (CSV)',
    '✓ 12 Oct 2026, 09:05 · 312 photos',
    '13 Oct 2026, 21:40 · Cancelled at 120 of 312 (files kept)',
    '13 Oct 2026, 21:50 · Cancelled at 120 of 312 (files removed)',
  ]);
  expect(rows()[0].querySelector('[aria-hidden="true"]').textContent.trim()).toBe('✓');
  expect(rows()[1].textContent).toContain('/Volumes/Backup/Butterfly_Atlas_species_list_2026-10-13.csv');
  expect(rows()[4].textContent).toContain("120 files were kept: they had changed since the export, or couldn't be reached.");
  expect(screen.queryByRole('button', { name: 'Show details' })).toBeNull();
  expect(screen.getAllByRole('button', { name: 'Remove from the list' })).toHaveLength(5);
  expect(screen.getByRole('button', { name: 'Clear history' })).toBeTruthy();
});

test('Show details loads the export\'s problems (file, species, reason) and how many more; Hide details hides them', async () => {
  getExport.mockResolvedValue({ ...run(5, { skipped: 3, failed: 1 }), problems: [problem(1), problem(2, 'failed')], moreProblems: 1200 });
  show([run(5, { skipped: 3, failed: 1 })]);
  expect(rows()[0].textContent).toContain('3 skipped, 1 failed');
  const toggle = screen.getByRole('button', { name: 'Show details' });
  expect(toggle.getAttribute('aria-expanded')).toBe('false');
  await fireEvent.click(toggle);
  expect(getExport).toHaveBeenCalledWith(5);
  expect(toggle.getAttribute('aria-expanded')).toBe('true');
  expect(toggle.textContent).toBe('Hide details');
  const list = await screen.findByRole('list', { name: 'Skipped, failed and renamed photos' });
  expect(toggle.getAttribute('aria-controls')).toBe(list.parentElement.id);
  expect([...list.querySelectorAll('li')].map((li) => li.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Skipped Danaus_chrysippus_01.jpg · Danaus chrysippus — The photo was deleted after the export started.',
    'Failed Danaus_chrysippus_02.jpg · Danaus chrysippus — The copy could not be written.',
  ]);
  expect(screen.getByText('and 1,200 more')).toBeTruthy();

  await fireEvent.click(toggle);
  expect(screen.queryByRole('list', { name: 'Skipped, failed and renamed photos' })).toBeNull();
  await fireEvent.click(toggle);
  expect(await screen.findByRole('list', { name: 'Skipped, failed and renamed photos' })).toBeTruthy();
  expect(getExport).toHaveBeenCalledTimes(1);
});

test('a problem whose species is gone shows its file and reason, without an empty species part', async () => {
  getExport.mockResolvedValue({ ...run(5, { skipped: 1 }), problems: [{ ...problem(1), species: null }], moreProblems: 0 });
  show([run(5, { skipped: 1 })]);
  await fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
  const list = await screen.findByRole('list', { name: 'Skipped, failed and renamed photos' });
  const item = list.querySelector('li');
  expect(item.textContent.replace(/\s+/g, ' ').trim()).toBe('Skipped Danaus_chrysippus_01.jpg — The photo was deleted after the export started.');
  expect(item.querySelector('i')).toBeNull();
});

test('details that fail to load say why, with Try again', async () => {
  getExport.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'))
    .mockResolvedValueOnce({ ...run(5, { renamed: 1 }), problems: [{ ...problem(3, 'renamed'), reason: 'Saved as Danaus_chrysip_03.jpg: the full name was too long.' }], moreProblems: 0 });
  show([run(5, { renamed: 1 })]);
  expect(rows()[0].textContent).toContain('1 renamed');
  await fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText(/Saved as Danaus_chrysip_03\.jpg/)).toBeTruthy();
  expect(screen.queryByText(/more$/)).toBeNull();
});

test('Remove from the list asks first; removed, focus goes to the next row (else the one before, else past the list)', async () => {
  deleteExport.mockResolvedValue(null);
  const outside = document.createElement('button');
  document.body.append(outside);
  const { onRemoved } = show([run(3), run(2), run(1)], { fallbackFocus: () => outside });

  await fireEvent.click(within(rows()[1]).getByRole('button', { name: 'Remove from the list' }));
  const dialog = screen.getByRole('dialog', { name: 'Remove this export from the list?' });
  expect(dialog.textContent).toContain('The exported files stay where they are.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(onRemoved).toHaveBeenCalledWith(2));
  expect(deleteExport).toHaveBeenCalledWith(2);
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(within(rows()[1]).getByRole('button', { name: 'Remove from the list' })));
  expect(rows().map((li) => li.dataset.exportId)).toEqual(['3', '1']);

  await fireEvent.click(within(rows()[1]).getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(within(rows()[0]).getByRole('button', { name: 'Remove from the list' })));

  await fireEvent.click(within(rows()[0]).getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(outside));
  expect(screen.queryByRole('list', { name: 'Past exports' })).toBeNull();
  outside.remove();
});

test('Cancel keeps the entry and focus goes back to its button; a refusal says why; one already gone leaves the list too', async () => {
  const { onRemoved } = show([run(2), run(1)]);
  const button = within(rows()[0]).getByRole('button', { name: 'Remove from the list' });
  await fireEvent.click(button);
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(button));
  expect(deleteExport).not.toHaveBeenCalled();

  deleteExport.mockRejectedValueOnce(Object.assign(new Error('Finish or cancel the export first.'), { code: 'export_running' }));
  await fireEvent.click(button);
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Finish or cancel the export first.');
  expect(onRemoved).not.toHaveBeenCalled();

  deleteExport.mockRejectedValueOnce(Object.assign(new Error('No such export.'), { code: 'not_found', status: 404 }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(onRemoved).toHaveBeenCalledWith(2));
});

test('Clear history asks first, clears the list, and focus goes past it', async () => {
  clearExports.mockResolvedValue(2);
  const outside = document.createElement('button');
  document.body.append(outside);
  const { onCleared } = show([run(2), run(1)], { fallbackFocus: () => outside });
  await fireEvent.click(screen.getByRole('button', { name: 'Clear history' }));
  const dialog = screen.getByRole('dialog', { name: 'Remove all past exports from the list?' });
  expect(dialog.textContent).toContain('The exported files stay where they are.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Clear history' }));
  await vi.waitFor(() => expect(onCleared).toHaveBeenCalledTimes(1));
  await vi.waitFor(() => expect(document.activeElement).toBe(outside));
  expect(screen.queryByRole('heading', { name: 'Past exports' })).toBeNull();
  outside.remove();
});

test('while a removal is on its way the dialog cannot be closed and its buttons are inactive', async () => {
  let answer;
  deleteExport.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  show([run(1)]);
  await fireEvent.click(screen.getByRole('button', { name: 'Remove from the list' }));
  const dialog = screen.getByRole('dialog');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }));
  expect([...dialog.querySelectorAll('.dlg-f button')].map((b) => b.getAttribute('aria-disabled'))).toEqual(['true', 'true']);
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.getByRole('dialog')).toBeTruthy();
  answer(null);
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

test('each Remove from the list is described by its row\'s line; the list of problems can be scrolled from the keyboard', async () => {
  getExport.mockResolvedValue({ ...run(5, { skipped: 1 }), problems: [problem(1)], moreProblems: 0 });
  show([run(5, { skipped: 1 }), run(4)]);
  for (const li of rows()) {
    const button = within(li).getByRole('button', { name: 'Remove from the list' });
    expect(document.getElementById(button.getAttribute('aria-describedby'))).toBe(li.querySelector('.pe-t'));
  }
  await fireEvent.click(screen.getByRole('button', { name: 'Show details' }));
  const list = await screen.findByRole('list', { name: 'Skipped, failed and renamed photos' });
  expect(list.getAttribute('tabindex')).toBe('0');
});

test('the 3 newest show, then "Show all N past exports" / "Show fewer" (focus stays on it); Clear history stays under the list', async () => {
  deleteExport.mockResolvedValue(null);
  show([run(5), run(4), run(3), run(2), run(1)]);
  expect(rows().map((li) => li.dataset.exportId)).toEqual(['5', '4', '3']);
  const toggle = screen.getByRole('button', { name: 'Show all 5 past exports' });
  expect(toggle.getAttribute('aria-expanded')).toBe('false');
  toggle.focus();
  await fireEvent.click(toggle);
  expect(rows().map((li) => li.dataset.exportId)).toEqual(['5', '4', '3', '2', '1']);
  expect([toggle.textContent, toggle.getAttribute('aria-expanded')]).toEqual(['Show fewer', 'true']);
  expect(document.activeElement).toBe(toggle);
  const clear = screen.getByRole('button', { name: 'Clear history' });
  expect(toggle.compareDocumentPosition(clear) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  await fireEvent.click(toggle);
  expect(rows()).toHaveLength(3);
  expect(document.activeElement).toBe(toggle);

  // removing a shown entry brings the next one in
  await fireEvent.click(within(rows()[2]).getByRole('button', { name: 'Remove from the list' }));
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove' }));
  await vi.waitFor(() => expect(rows().map((li) => li.dataset.exportId)).toEqual(['5', '4', '2']));
  await vi.waitFor(() => expect(document.activeElement).toBe(within(rows()[2]).getByRole('button', { name: 'Remove from the list' })));
  expect(screen.getByRole('button', { name: 'Show all 4 past exports' })).toBeTruthy();
});

test('3 past exports or fewer: no Show all', () => {
  show([run(3), run(2), run(1)]);
  expect(rows()).toHaveLength(3);
  expect(screen.queryByRole('button', { name: /^Show all/ })).toBeNull();
});

test('a list shown in full that shrinks to 3 or fewer shows the 3 newest again when it grows past them', async () => {
  const { rerender } = show([run(4), run(3), run(2), run(1)]);
  await fireEvent.click(screen.getByRole('button', { name: 'Show all 4 past exports' }));
  expect(rows()).toHaveLength(4);
  await rerender({ past: [run(4), run(3), run(2)] });
  await rerender({ past: [run(6), run(5), run(4), run(3), run(2)] });
  expect(rows().map((li) => li.dataset.exportId)).toEqual(['6', '5', '4']);
  expect(screen.getByRole('button', { name: 'Show all 5 past exports' }).getAttribute('aria-expanded')).toBe('false');
});

test('Show details controls its details only while they are open', async () => {
  getExport.mockResolvedValue({ ...run(5, { skipped: 1 }), problems: [problem(1)], moreProblems: 0 });
  show([run(5, { skipped: 1 })]);
  const toggle = screen.getByRole('button', { name: 'Show details' });
  expect(toggle.hasAttribute('aria-controls')).toBe(false);
  await fireEvent.click(toggle);
  expect(document.getElementById(toggle.getAttribute('aria-controls'))).toBeTruthy();
  await fireEvent.click(toggle);
  expect(toggle.hasAttribute('aria-controls')).toBe(false);
});

test('Show all counts with thousands separators', () => {
  const many = Array.from({ length: 1200 }, (_, k) => run(k + 1));
  show(many);
  expect(screen.getByRole('button', { name: 'Show all 1,200 past exports' })).toBeTruthy();
});

test('an export with RAW photos says they went out with their location (Phase 7a)', () => {
  show([run(6, { rawCount: 12 }), run(5, { rawCount: 1 }), run(4, { rawCount: 0 })]);
  const raw = rows().map((li) => li.querySelector('.pe-raw')?.textContent ?? null);
  expect(raw).toEqual(['12 RAW photos exported with their location.', '1 RAW photo exported with its location.', null]);
});

test('an export with HEIC photos says how many went out with their location (Phase 7b)', () => {
  show([run(6, { rawCount: 12, heicCount: 30 }), run(5, { rawCount: 0, heicCount: 1 }), run(4, { rawCount: 0, heicCount: 0 })]);
  const lines = rows().map((li) => li.querySelector('.pe-raw')?.textContent ?? null);
  expect(lines).toEqual(['12 RAW and 30 HEIC photos exported with their location.', '1 HEIC photo exported with its location.', null]);
});
