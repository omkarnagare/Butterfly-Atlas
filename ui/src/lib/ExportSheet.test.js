import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import ExportSheet from './ExportSheet.svelte';
import { checkExport, getFsRoots, listFolder, startExport } from './api.js';
import { EMPTY } from './filter.js';

vi.mock('./api.js', () => ({ checkExport: vi.fn(), startExport: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn() }));

const backup = {
  path: '/Volumes/Backup', name: 'Backup', parent: '/Volumes', crumbs: [{ name: 'Backup', path: '/Volumes/Backup' }], folders: [], photos: 0,
  isLibrary: false, openImport: null,
};
const check = (over = {}) => ({
  photos: 312, species: 48, bytes: 4509715660, neededBytes: 4719430860, freeBytes: 332859965440, name: 'Butterfly_Atlas_export_2026-10-13',
  problem: null, ...over,
});
const count = { photos: 312, species: 48, photographedSpecies: 48, bytes: 4509715660, manualSpecies: false, filter: { ...EMPTY, query: 'taxa=A12' } };

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getFsRoots.mockResolvedValue([{ name: 'Backup', path: '/Volumes/Backup', kind: 'drive' }]);
  listFolder.mockResolvedValue(backup);
  checkExport.mockResolvedValue(check());
});
afterEach(() => vi.restoreAllMocks());

const show = (props = {}) => render(ExportSheet, { query: 'taxa=A12', count, onClose: vi.fn(), onStarted: vi.fn(), ...props });
const sheet = () => screen.getByRole('dialog', { name: 'Export' });
const exportButton = () => within(sheet()).getByRole('button', { name: 'Export' });

async function chooseFolder() {
  await fireEvent.click(screen.getByRole('button', { name: 'Choose where to export…' }));
  await fireEvent.click(await screen.findByRole('button', { name: 'Export here' }));
  await screen.findByText('/Volumes/Backup');
}

test('opens with the count line and the defaults: Photos, no folder, species folders, Remove location on, no Places', async () => {
  show();
  const dialog = sheet();
  expect(dialog.querySelector('.cl-n').textContent).toBe('312 photos of 48 species, 4.2 GB');
  await vi.waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));

  const what = screen.getByRole('group', { name: 'What to export' });
  expect(within(what).getAllByRole('radio').map((r) => [r.labels[0].textContent.trim(), r.checked])).toEqual([
    ['Photos', true], ['Species list (CSV)', false], ['Both', false],
  ]);
  expect(screen.getByRole('button', { name: 'Choose where to export…' })).toBeTruthy();
  const folders = screen.getByRole('group', { name: 'Folders' });
  expect(within(folders).getAllByRole('radio').map((r) => r.checked)).toEqual([true, false]);
  expect(folders.textContent).toContain('Nymphalidae/Danainae/Danaini/Danaus/Danaus_chrysippus/Danaus_chrysippus_03.jpg');
  expect(screen.getByRole('switch', { name: 'Remove location from photos' }).getAttribute('aria-checked')).toBe('true');
  expect(screen.queryByRole('group', { name: 'Places in the list' })).toBeNull();
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
  expect(screen.getByText('Choose where to export.')).toBeTruthy();
  expect(checkExport).not.toHaveBeenCalled();
});

test('choices that do not apply are hidden: a species list has no folders or location, photos have no places', async () => {
  show();
  await fireEvent.click(screen.getByRole('radio', { name: 'Species list (CSV)' }));
  expect(screen.queryByRole('group', { name: 'Folders' })).toBeNull();
  expect(screen.queryByRole('switch')).toBeNull();
  const places = screen.getByRole('group', { name: 'Places in the list' });
  expect(within(places).getAllByRole('radio').map((r) => [r.labels[0].textContent.trim(), r.checked])).toEqual([
    ['None', true], ['Country', false], ['Country + State', false], ['Country + State + District', false],
  ]);

  await fireEvent.click(screen.getByRole('radio', { name: 'Both' }));
  expect(screen.getByRole('group', { name: 'Folders' })).toBeTruthy();
  expect(screen.getByRole('switch', { name: 'Remove location from photos' })).toBeTruthy();
  expect(screen.getByRole('group', { name: 'Places in the list' })).toBeTruthy();
});

test('turning Remove location off warns that the photos keep their GPS position', async () => {
  show();
  const toggle = screen.getByRole('switch', { name: 'Remove location from photos' });
  const warning = 'Photos will keep their GPS position. Anyone you share them with can see where they were taken.';
  expect(screen.queryByText(warning)).toBeNull();
  await fireEvent.click(toggle);
  expect(toggle.getAttribute('aria-checked')).toBe('false');
  expect(screen.getByText(warning).id).toBe(toggle.getAttribute('aria-describedby'));
  await fireEvent.click(toggle);
  expect(screen.queryByText(warning)).toBeNull();
});

test('the folder browser opens over the sheet; Escape closes only it, and focus goes back to the button that opened it', async () => {
  show();
  const choose = screen.getByRole('button', { name: 'Choose where to export…' });
  await fireEvent.click(choose);
  const browser = await screen.findByRole('dialog', { name: 'Choose where to export' });
  await vi.waitFor(() => expect(browser.contains(document.activeElement)).toBe(true));
  expect(sheet().closest('[inert]')).toBeTruthy();

  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog', { name: 'Choose where to export' })).toBeNull();
  expect(sheet()).toBeTruthy();
  await vi.waitFor(() => expect(document.activeElement).toBe(choose));
});

test('a chosen folder is checked: its path, Change…, the name it creates and the space line; Export becomes active', async () => {
  show();
  await chooseFolder();
  expect(checkExport).toHaveBeenCalledWith('taxa=A12', 'photos', '/Volumes/Backup', { signal: expect.any(AbortSignal), rawWithLocation: false });
  const change = screen.getByRole('button', { name: 'Change…' });
  await vi.waitFor(() => expect(document.activeElement).toBe(change));
  expect(await screen.findByText('Creates Butterfly_Atlas_export_2026-10-13')).toBeTruthy();
  expect(screen.getByText('4.2 GB needed, 310.0 GB free')).toBeTruthy();
  expect(exportButton().getAttribute('aria-disabled')).toBe('false');

  // Change… opens the browser at the chosen folder
  await fireEvent.click(change);
  await vi.waitFor(() => expect(listFolder).toHaveBeenLastCalledWith('/Volumes/Backup', expect.anything()));
});

test('changing what to export checks again; an older answer arriving late is dropped, and Export waits for the newest', async () => {
  const answers = [];
  checkExport.mockImplementation(() => new Promise((resolve) => answers.push(resolve)));
  show();
  await chooseFolder();
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(screen.getByRole('radio', { name: 'Species list (CSV)' }));
  expect(checkExport).toHaveBeenCalledTimes(2);
  expect(checkExport.mock.calls[0][3].signal.aborted).toBe(true);
  expect(checkExport.mock.calls[1].slice(0, 3)).toEqual(['taxa=A12', 'csv', '/Volumes/Backup']);

  answers[1](check({ neededBytes: 0, name: 'Butterfly_Atlas_species_list_2026-10-13.csv' }));
  expect(await screen.findByText('Creates Butterfly_Atlas_species_list_2026-10-13.csv')).toBeTruthy();
  answers[0](check({ problem: { code: 'no_space', message: "There isn't enough space: 4.4 GB needed, 1.1 GB free." } }));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(screen.queryByRole('alert')).toBeNull();
  // a species list alone has no space line
  expect(screen.queryByText(/needed/)).toBeNull();
  expect(exportButton().getAttribute('aria-disabled')).toBe('false');
});

test('a problem the check finds is shown instead of the space line, and Export stays inactive', async () => {
  checkExport.mockResolvedValue(check({ problem: { code: 'not_writable', message: "Butterfly Atlas can't write to that folder." } }));
  const onStarted = vi.fn();
  show({ onStarted });
  await chooseFolder();
  expect((await screen.findByRole('alert')).textContent).toBe("Butterfly Atlas can't write to that folder.");
  expect(screen.queryByText(/needed/)).toBeNull();
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(exportButton());
  expect(startExport).not.toHaveBeenCalled();
});

test('a check that fails says why', async () => {
  checkExport.mockRejectedValue(new Error('Butterfly Atlas could not reach its service.'));
  show();
  await chooseFolder();
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
});

test('Export starts it with every choice, inactive while the request is on its way, and hands the answer on', async () => {
  let answer;
  startExport.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  const onStarted = vi.fn();
  const onClose = vi.fn();
  show({ onStarted, onClose });
  await fireEvent.click(screen.getByRole('radio', { name: 'Both' }));
  await fireEvent.click(screen.getByRole('radio', { name: 'All photos in one folder' }));
  await fireEvent.click(screen.getByRole('switch', { name: 'Remove location from photos' }));
  await fireEvent.click(screen.getByRole('radio', { name: 'Country + State' }));
  await chooseFolder();
  await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));

  await fireEvent.click(exportButton());
  expect(startExport).toHaveBeenCalledWith({
    filter: 'taxa=A12', what: 'both', path: '/Volumes/Backup', layout: 'flat', stripLocation: false, placeDepth: 'state', rawWithLocation: false,
    acceptedKinds: [],
  });
  const busy = within(sheet()).getByRole('button', { name: 'Starting…' });
  expect(busy.getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(busy);
  expect(startExport).toHaveBeenCalledTimes(1);
  // it cannot be closed while the export is starting
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(onClose).not.toHaveBeenCalled();

  const run = { id: 4, state: 'running' };
  answer(run);
  await vi.waitFor(() => expect(onStarted).toHaveBeenCalledWith(run));
});

test('a start that is refused says why and keeps the sheet open', async () => {
  startExport.mockRejectedValue(Object.assign(new Error('Finish or cancel the export in progress first.'), { code: 'export_running' }));
  const onClose = vi.fn();
  const onStarted = vi.fn();
  show({ onClose, onStarted });
  await chooseFolder();
  await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(exportButton());
  expect((await screen.findByRole('alert')).textContent).toBe('Finish or cancel the export in progress first.');
  expect(onStarted).not.toHaveBeenCalled();
  expect(onClose).not.toHaveBeenCalled();
});

test('Escape, Cancel and the backdrop close it; every control is a native, focusable one', async () => {
  const onClose = vi.fn();
  const { container } = show({ onClose });
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  await fireEvent.click(container.querySelector('.sheet-bg'));
  expect(onClose).toHaveBeenCalledTimes(3);

  const controls = [...sheet().querySelectorAll('input, button')];
  expect(controls.every((el) => el.tabIndex === 0)).toBe(true);
  expect(controls.every((el) => !el.disabled)).toBe(true);
});

test('a refused start\'s message goes as soon as any choice changes (the folders too: only the start knows a path is too long)', async () => {
  const tooLong = "The chosen folder's path is too long for these folders. Choose a folder nearer the top of the drive, or All photos in one folder.";
  startExport.mockRejectedValue(Object.assign(new Error(tooLong), { code: 'path_too_long' }));
  show();
  await fireEvent.click(screen.getByRole('radio', { name: 'Both' }));
  await chooseFolder();
  const refuse = async () => {
    await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));
    await fireEvent.click(exportButton());
    expect((await screen.findByRole('alert')).textContent).toBe(tooLong);
  };
  await refuse();
  await fireEvent.click(screen.getByRole('radio', { name: 'All photos in one folder' }));
  expect(screen.queryByRole('alert')).toBeNull();
  await refuse();
  await fireEvent.click(screen.getByRole('switch', { name: 'Remove location from photos' }));
  expect(screen.queryByRole('alert')).toBeNull();
  await refuse();
  await fireEvent.click(screen.getByRole('radio', { name: 'Country' }));
  expect(screen.queryByRole('alert')).toBeNull();
  expect(checkExport).toHaveBeenCalledTimes(1);
  await refuse();
  // the same folder chosen again
  await fireEvent.click(screen.getByRole('button', { name: 'Change…' }));
  await fireEvent.click(await screen.findByRole('button', { name: 'Export here' }));
  await vi.waitFor(() => expect(checkExport).toHaveBeenCalledTimes(2));
  expect(screen.queryByRole('alert')).toBeNull();
});

test('while the export is starting nothing in the sheet changes: Cancel, the backdrop and Change… do nothing, and no choice moves', async () => {
  let answer;
  startExport.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  const onClose = vi.fn();
  const onStarted = vi.fn();
  const { container } = show({ onClose, onStarted });
  await chooseFolder();
  await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));
  expect(screen.getAllByRole('radio').some((r) => r.getAttribute('aria-disabled') === 'true')).toBe(false);
  await fireEvent.click(exportButton());

  const cancel = within(sheet()).getByRole('button', { name: 'Cancel' });
  const change = within(sheet()).getByRole('button', { name: 'Change…' });
  expect([cancel.getAttribute('aria-disabled'), change.getAttribute('aria-disabled')]).toEqual(['true', 'true']);
  await fireEvent.click(cancel);
  await fireEvent.click(container.querySelector('.sheet-bg'));
  expect(onClose).not.toHaveBeenCalled();
  await fireEvent.click(change);
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(screen.queryByRole('dialog', { name: 'Choose where to export' })).toBeNull();

  // the choices say they are inactive
  expect(screen.getAllByRole('radio').every((r) => r.getAttribute('aria-disabled') === 'true')).toBe(true);
  expect(screen.getByRole('switch', { name: 'Remove location from photos' }).getAttribute('aria-disabled')).toBe('true');
  const csv = screen.getByRole('radio', { name: 'Species list (CSV)' });
  await fireEvent.click(csv);
  expect([csv.checked, screen.getByRole('radio', { name: 'Photos' }).checked]).toEqual([false, true]);
  const flat = screen.getByRole('radio', { name: 'All photos in one folder' });
  await fireEvent.click(flat);
  expect(flat.checked).toBe(false);
  const strip = screen.getByRole('switch', { name: 'Remove location from photos' });
  await fireEvent.click(strip);
  expect(strip.getAttribute('aria-checked')).toBe('true');
  expect(checkExport).toHaveBeenCalledTimes(1);
  expect(screen.queryByRole('group', { name: 'Places in the list' })).toBeNull();

  answer({ id: 4, state: 'running' });
  await vi.waitFor(() => expect(onStarted).toHaveBeenCalledTimes(1));
});

test('a check again that fails takes away the name the last one said it creates', async () => {
  show();
  await chooseFolder();
  expect(await screen.findByText('Creates Butterfly_Atlas_export_2026-10-13')).toBeTruthy();
  checkExport.mockRejectedValueOnce(new Error('Butterfly Atlas could not reach its service.'));
  await fireEvent.click(screen.getByRole('radio', { name: 'Both' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
  expect(screen.queryByText(/^Creates /)).toBeNull();
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
});

test('the same folder chosen again is checked again; the name it creates stays shown until the new answer comes', async () => {
  let answer;
  show();
  await chooseFolder();
  expect(await screen.findByText('Creates Butterfly_Atlas_export_2026-10-13')).toBeTruthy();
  checkExport.mockImplementationOnce(() => new Promise((resolve) => { answer = resolve; }));
  await fireEvent.click(screen.getByRole('button', { name: 'Change…' }));
  await fireEvent.click(await screen.findByRole('button', { name: 'Export here' }));
  await vi.waitFor(() => expect(checkExport).toHaveBeenCalledTimes(2));
  expect(screen.getByText('Creates Butterfly_Atlas_export_2026-10-13')).toBeTruthy();
  expect(screen.getByText('Checking the folder…')).toBeTruthy();
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
  answer(check({ name: 'Butterfly_Atlas_export_2026-10-13_2' }));
  expect(await screen.findByText('Creates Butterfly_Atlas_export_2026-10-13_2')).toBeTruthy();
  expect(exportButton().getAttribute('aria-disabled')).toBe('false');
});

/* ---------- RAW photos (Phase 7a contract §5) ---------- */

const RAW_PROBLEM = { code: 'raw_location', message: 'Tick the RAW photos box, or leave RAW photos out with File type.' };
const WARNING = '12 RAW photos will be exported exactly as they are, with any location they hold. 9 of them have GPS positions. '
  + 'Remove location applies only to JPEG, PNG, TIFF and WebP.';

test('RAW photos in the export: the warning and an unticked box; Export waits with the reason until it is ticked, then starts with it', async () => {
  checkExport.mockImplementation(async (query, what, path, { rawWithLocation }) => check({
    raw: { photos: 12, withGps: 9 }, problem: rawWithLocation ? null : RAW_PROBLEM,
  }));
  const onStarted = vi.fn();
  show({ onStarted });
  expect(screen.queryByRole('checkbox')).toBeNull();
  await chooseFolder();

  expect(await screen.findByText(WARNING)).toBeTruthy();
  const box = screen.getByRole('checkbox', { name: 'Export the RAW photos with their location' });
  expect(box.checked).toBe(false);
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toBe(RAW_PROBLEM.message));
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
  expect(document.getElementById(exportButton().getAttribute('aria-describedby')).textContent).toBe(RAW_PROBLEM.message);
  await fireEvent.click(exportButton());
  expect(startExport).not.toHaveBeenCalled();

  await fireEvent.click(box);
  expect(checkExport).toHaveBeenLastCalledWith('taxa=A12', 'photos', '/Volumes/Backup', { signal: expect.any(AbortSignal), rawWithLocation: true });
  await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));
  expect(screen.getByRole('checkbox').checked).toBe(true);
  await fireEvent.click(exportButton());
  expect(startExport).toHaveBeenCalledWith(expect.objectContaining({ what: 'photos', stripLocation: true, rawWithLocation: true }));
});

test('the warning shows with Remove location on or off, and not for a species list alone', async () => {
  checkExport.mockResolvedValue(check({ raw: { photos: 12, withGps: 9 }, problem: RAW_PROBLEM }));
  show();
  await chooseFolder();
  expect(await screen.findByText(WARNING)).toBeTruthy();
  await fireEvent.click(screen.getByRole('switch', { name: 'Remove location from photos' }));
  expect(screen.getByText(WARNING)).toBeTruthy();
  await fireEvent.click(screen.getByRole('radio', { name: 'Species list (CSV)' }));
  expect(screen.queryByText(WARNING)).toBeNull();
  expect(screen.queryByRole('checkbox')).toBeNull();
});

test('the box is never kept: it unticks when the number of RAW photos changes, and the sheet opens with it unticked', async () => {
  let raw = { photos: 12, withGps: 9 };
  checkExport.mockImplementation(async () => check({ raw }));
  const first = show();
  await chooseFolder();
  await fireEvent.click(await screen.findByRole('checkbox', { name: 'Export the RAW photos with their location' }));
  await vi.waitFor(() => expect(checkExport).toHaveBeenLastCalledWith('taxa=A12', 'photos', '/Volumes/Backup', expect.objectContaining({ rawWithLocation: true })));
  expect(screen.getByRole('checkbox').checked).toBe(true);

  raw = { photos: 13, withGps: 9 };
  await fireEvent.click(screen.getByRole('radio', { name: 'Both' }));
  await vi.waitFor(() => expect(screen.getByText(/^13 RAW photos/)).toBeTruthy());
  expect(screen.getByRole('checkbox').checked).toBe(false);
  first.unmount();

  show();
  await chooseFolder();
  expect((await screen.findByRole('checkbox')).checked).toBe(false);
});

test('a start refused with raw_location checks again, so the warning and the box appear (review fix U1)', async () => {
  let raw = { photos: 0, withGps: 0 };
  checkExport.mockImplementation(async (query, what, path, { rawWithLocation }) => check({
    raw, problem: raw.photos && !rawWithLocation ? RAW_PROBLEM : null,
  }));
  startExport.mockRejectedValue(Object.assign(new Error(RAW_PROBLEM.message), { code: 'raw_location' }));
  show();
  await chooseFolder();
  await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));
  expect(screen.queryByRole('checkbox')).toBeNull();

  raw = { photos: 12, withGps: 9 };
  await fireEvent.click(exportButton());
  expect(await screen.findByText(WARNING)).toBeTruthy();
  expect(checkExport).toHaveBeenCalledTimes(2);
  expect(screen.getByRole('alert').textContent).toBe(RAW_PROBLEM.message);
  expect(exportButton().getAttribute('aria-disabled')).toBe('true');
  const box = screen.getByRole('checkbox', { name: 'Export the RAW photos with their location' });
  expect(box.checked).toBe(false);

  await fireEvent.click(box);
  await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));
});

test('the warning says how many RAW photos couldn\'t be checked for a GPS position (review fixes U3, U9)', async () => {
  checkExport.mockResolvedValue(check({ raw: { photos: 12, withGps: 0, withUnknown: 4 }, problem: RAW_PROBLEM }));
  show();
  await chooseFolder();
  expect(await screen.findByText('12 RAW photos will be exported exactly as they are, with any location they hold. '
    + "4 of them couldn't be checked for a GPS position. Remove location applies only to JPEG, PNG, TIFF and WebP.")).toBeTruthy();
});

/* ---------- HEIC photos (Phase 7b contract §6, audit 17) ---------- */

const HEIC_PROBLEM = { code: 'raw_location', message: 'Tick the HEIC photos box, or leave HEIC photos out with File type.' };

test('HEIC photos in the export: their warning and box; the start says the kinds the box was shown for', async () => {
  const onStarted = vi.fn();
  checkExport.mockImplementation(async (query, what, path, { rawWithLocation }) => check({
    raw: { photos: 0, withGps: 0, withUnknown: 0 }, heic: { photos: 30, withGps: 25, withUnknown: 0 }, problem: rawWithLocation ? null : HEIC_PROBLEM,
  }));
  show({ onStarted });
  await chooseFolder();

  expect(await screen.findByText('30 HEIC photos will be exported exactly as they are, with any location they hold. 25 of them have GPS positions. '
    + 'Remove location applies only to JPEG, PNG, TIFF and WebP.')).toBeTruthy();
  const box = screen.getByRole('checkbox', { name: 'Export the HEIC photos with their location' });
  expect(box.checked).toBe(false);
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toBe(HEIC_PROBLEM.message));
  await fireEvent.click(box);
  await vi.waitFor(() => expect(exportButton().getAttribute('aria-disabled')).toBe('false'));
  await fireEvent.click(exportButton());
  expect(startExport).toHaveBeenCalledWith(expect.objectContaining({ rawWithLocation: true, acceptedKinds: ['heic'] }));
});

test('RAW and HEIC together: one warning, one box; it unticks when either count changes', async () => {
  let heic = { photos: 30, withGps: 25, withUnknown: 0 };
  checkExport.mockImplementation(async () => check({ raw: { photos: 12, withGps: 9, withUnknown: 0 }, heic }));
  const { rerender } = show();
  await chooseFolder();
  expect(await screen.findByText(/^12 RAW and 30 HEIC photos will be exported exactly as they are/)).toBeTruthy();
  const box = screen.getByRole('checkbox', { name: 'Export the RAW and HEIC photos with their location' });
  await fireEvent.click(box);
  await vi.waitFor(() => expect(checkExport).toHaveBeenLastCalledWith('taxa=A12', 'photos', '/Volumes/Backup', expect.objectContaining({ rawWithLocation: true })));
  expect(box.checked).toBe(true);

  heic = { photos: 31, withGps: 25, withUnknown: 0 };
  await fireEvent.click(screen.getByRole('radio', { name: 'Both' }));
  await vi.waitFor(() => expect(screen.getByText(/^12 RAW and 31 HEIC photos/)).toBeTruthy());
  expect(screen.getByRole('checkbox', { name: 'Export the RAW and HEIC photos with their location' }).checked).toBe(false);
  expect(rerender).toBeTruthy();
});
