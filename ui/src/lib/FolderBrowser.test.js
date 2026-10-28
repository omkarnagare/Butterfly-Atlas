import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import FolderBrowser from './FolderBrowser.svelte';
import { getFsRoots, listFolder } from './api.js';

vi.mock('./api.js', () => ({ getFsRoots: vi.fn(), listFolder: vi.fn() }));

beforeEach(() => {
  vi.resetAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});

const roots = [
  { name: 'Home', path: '/Users/you', kind: 'home' },
  { name: 'Pictures', path: '/Users/you/Pictures', kind: 'pictures' },
  { name: 'Photos SSD', path: '/Volumes/Photos SSD', kind: 'drive' },
];
const folder = (path, extra = {}) => {
  const names = path.split('/').filter(Boolean);
  const crumbs = names.slice(1).map((name, k) => ({ name, path: `/${names.slice(0, k + 2).join('/')}` }));
  return {
    path, name: names.at(-1), parent: `/${names.slice(0, -1).join('/')}`, crumbs, folders: [], photos: 0,
    isLibrary: false, openImport: null, ...extra,
  };
};
const pictures = folder('/Users/you/Pictures', {
  folders: [
    { name: 'Butterflies 2024', path: '/Users/you/Pictures/Butterflies 2024', folders: 12, photos: 1412 },
    { name: 'Empty', path: '/Users/you/Pictures/Empty', folders: 0, photos: 0 },
    { name: 'Uncounted', path: '/Users/you/Pictures/Uncounted', folders: null, photos: null },
  ],
  photos: 3,
});
const butterflies = folder('/Users/you/Pictures/Butterflies 2024', {
  folders: [{ name: 'Sinhagad, Aug 2024', path: '/Users/you/Pictures/Butterflies 2024/Sinhagad, Aug 2024', folders: 0, photos: 1 }],
  photos: 38,
});

function setUp(...answers) {
  getFsRoots.mockResolvedValue(roots);
  const byPath = new Map([pictures, butterflies, ...answers].map((f) => [f.path, f]));
  listFolder.mockImplementation(async (path) => {
    if (byPath.has(path)) return byPath.get(path);
    throw Object.assign(new Error("That folder can't be found."), { code: 'folder_not_found' });
  });
}

test('opens at Pictures with shortcuts, the path and the folders inside with their counts', async () => {
  setUp();
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });

  const dialog = screen.getByRole('dialog', { name: 'Choose a folder to import' });
  expect(dialog.contains(document.activeElement)).toBe(true);
  const list = await screen.findByRole('list', { name: 'Folders in Pictures' });
  expect(listFolder).toHaveBeenCalledWith('/Users/you/Pictures', expect.anything());
  expect(within(screen.getByRole('navigation', { name: 'Places' })).getAllByRole('button').map((b) => b.textContent.trim()))
    .toEqual(['Home', 'Pictures', 'Photos SSD']);
  expect(screen.getByRole('button', { name: 'Pictures' }).getAttribute('aria-current')).toBe('true');
  expect(screen.getByRole('button', { name: 'Home' }).getAttribute('aria-current')).toBeNull();
  const rows = within(list).getAllByRole('button').map((b) => b.textContent.replace(/\s+/g, ' ').trim());
  expect(rows).toEqual(['Butterflies 2024 12 folders · 1,412 photos', 'Empty Empty', 'Uncounted']);
  expect(screen.getByText('3 photos in this folder, more in the folders inside.')).toBeTruthy();
});

test('opening a folder lists it and moves focus to its first folder; the path goes back up', async () => {
  setUp();
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });

  await fireEvent.click(await screen.findByRole('button', { name: /^Butterflies 2024/ }));
  const list = await screen.findByRole('list', { name: 'Folders in Butterflies 2024' });
  await vi.waitFor(() => expect(document.activeElement).toBe(within(list).getAllByRole('button')[0]));
  const places = screen.getByRole('navigation', { name: 'Places' });
  expect(within(places).getByRole('button', { name: 'Pictures' }).getAttribute('aria-current')).toBe('true');
  const path = screen.getByRole('navigation', { name: 'Folder path' });
  expect(within(path).getByText('Butterflies 2024').getAttribute('aria-current')).toBe('page');

  await fireEvent.click(within(path).getByRole('button', { name: 'Pictures' }));
  expect(await screen.findByRole('list', { name: 'Folders in Pictures' })).toBeTruthy();
});

test('a pasted path opens that folder; one that cannot be found says so and keeps the folder shown', async () => {
  setUp();
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  await screen.findByRole('list', { name: 'Folders in Pictures' });
  const field = screen.getByRole('textbox', { name: 'Or paste the path of a folder' });

  await fireEvent.input(field, { target: { value: '/nowhere' } });
  await fireEvent.submit(field.closest('form'));
  expect((await screen.findByRole('alert')).textContent).toBe("That folder can't be found.");
  expect(screen.getByRole('list', { name: 'Folders in Pictures' })).toBeTruthy();

  await fireEvent.input(field, { target: { value: '  /Users/you/Pictures/Butterflies 2024 ' } });
  await fireEvent.submit(field.closest('form'));
  expect(await screen.findByRole('list', { name: 'Folders in Butterflies 2024' })).toBeTruthy();
  expect(screen.queryByRole('alert')).toBeNull();
  expect(field.value).toBe('');
});

test('chooses the folder shown, and shows why when that fails', async () => {
  setUp();
  const onChoose = vi.fn().mockRejectedValueOnce(new Error('Butterfly Atlas can\'t open that folder.')).mockResolvedValue(undefined);
  render(FolderBrowser, { startPath: '/Users/you/Pictures/Butterflies 2024', onChoose, onClose: vi.fn() });
  await screen.findByRole('list', { name: 'Folders in Butterflies 2024' });

  const button = screen.getByRole('button', { name: 'Import this folder' });
  expect(button.getAttribute('aria-describedby')).toBe(screen.getByText('38 photos in this folder, more in the folders inside.').id);
  await fireEvent.click(button);
  expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ path: '/Users/you/Pictures/Butterflies 2024' }));
  expect((await screen.findByRole('alert')).textContent).toBe("Butterfly Atlas can't open that folder.");

  await fireEvent.click(button);
  expect(onChoose).toHaveBeenCalledTimes(2);
  await vi.waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
});

test('the library folder cannot be chosen, and says why', async () => {
  setUp(folder('/Users/you/Pictures/Atlas', { isLibrary: true }));
  const onChoose = vi.fn();
  render(FolderBrowser, { startPath: '/Users/you/Pictures/Atlas', onChoose, onClose: vi.fn() });

  const reason = await screen.findByText("That's your library folder. Choose the folder your photos are in.");
  const button = screen.getByRole('button', { name: 'Import this folder' });
  expect(button.getAttribute('aria-disabled')).toBe('true');
  expect(button.getAttribute('aria-describedby')).toBe(reason.id);
  await fireEvent.click(button);
  expect(onChoose).not.toHaveBeenCalled();
});

test('a folder with an unfinished import offers to continue it; relocating reads Use this folder', async () => {
  setUp(folder('/Users/you/Pictures/Old', { photos: 9, openImport: { id: 3, filed: 3412, total: 9870 } }));
  const { unmount } = render(FolderBrowser, { startPath: '/Users/you/Pictures/Old', onChoose: vi.fn(), onClose: vi.fn() });

  expect(await screen.findByRole('button', { name: 'Continue your import' })).toBeTruthy();
  expect(screen.getByText("You've filed 3,412 of 9,870 photos from this folder.")).toBeTruthy();
  unmount();

  render(FolderBrowser, { title: 'Where is Old now?', relocating: true, startPath: '/Users/you/Pictures/Old', onChoose: vi.fn(), onClose: vi.fn() });
  expect(await screen.findByRole('button', { name: 'Use this folder' })).toBeTruthy();
  expect(screen.getByRole('dialog', { name: 'Where is Old now?' })).toBeTruthy();
});

test('Escape and Cancel close it, and focus goes back to where it was', async () => {
  setUp();
  const opener = document.createElement('button');
  document.body.append(opener);
  opener.focus();
  const onClose = vi.fn();
  const { unmount } = render(FolderBrowser, { onChoose: vi.fn(), onClose });
  await screen.findByRole('list', { name: 'Folders in Pictures' });

  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(onClose).toHaveBeenCalledTimes(2);
  unmount();
  await Promise.resolve();
  expect(document.activeElement).toBe(opener);
  opener.remove();
});

test('says why when the shortcuts cannot be listed', async () => {
  getFsRoots.mockRejectedValue(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });

  expect((await screen.findByRole('alert')).textContent).toContain('could not reach its service');
  expect(screen.getByRole('button', { name: 'Import this folder' }).getAttribute('aria-disabled')).toBe('true');
});

test('the server\'s refusal of the library folder shows beside the button', async () => {
  setUp();
  const refusal = "That's your library folder. Choose the folder your photos are in.";
  const onChoose = vi.fn().mockRejectedValue(Object.assign(new Error(refusal), { code: 'library_folder' }));
  render(FolderBrowser, { onChoose, onClose: vi.fn() });
  await screen.findByRole('list', { name: 'Folders in Pictures' });

  const button = screen.getByRole('button', { name: 'Import this folder' });
  await fireEvent.click(button);
  const alert = await screen.findByRole('alert');
  expect(alert.textContent).toBe(refusal);
  expect(alert.parentElement).toBe(button.parentElement);
  expect(button.getAttribute('aria-describedby')).toBe(alert.id);
});

test('Enter in the path field opens the folder', async () => {
  setUp();
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  await screen.findByRole('list', { name: 'Folders in Pictures' });
  const field = screen.getByRole('textbox', { name: 'Or paste the path of a folder' });

  await fireEvent.input(field, { target: { value: '/Users/you/Pictures/Butterflies 2024' } });
  await fireEvent.keyDown(field, { key: 'Enter' });
  expect(await screen.findByRole('list', { name: 'Folders in Butterflies 2024' })).toBeTruthy();
});

test('the folder list is one Tab stop: arrows, Home and End move in it, Enter opens, Backspace and Alt+Up go up a level', async () => {
  setUp(folder('/Users/you', { folders: [{ name: 'Pictures', path: '/Users/you/Pictures', folders: 3, photos: 3 }] }));
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  const list = await screen.findByRole('list', { name: 'Folders in Pictures' });
  const buttons = within(list).getAllByRole('button');
  expect(buttons.map((b) => b.getAttribute('tabindex'))).toEqual(['0', '-1', '-1']);

  buttons[0].focus();
  await fireEvent.keyDown(buttons[0], { key: 'ArrowDown' });
  expect(document.activeElement).toBe(buttons[1]);
  expect(buttons.map((b) => b.getAttribute('tabindex'))).toEqual(['-1', '0', '-1']);
  await fireEvent.keyDown(buttons[1], { key: 'End' });
  expect(document.activeElement).toBe(buttons[2]);
  await fireEvent.keyDown(buttons[2], { key: 'ArrowDown' });
  expect(document.activeElement).toBe(buttons[2]);
  await fireEvent.keyDown(buttons[2], { key: 'Home' });
  expect(document.activeElement).toBe(buttons[0]);
  await fireEvent.keyDown(buttons[0], { key: 'ArrowUp' });
  expect(document.activeElement).toBe(buttons[0]);

  await fireEvent.keyDown(buttons[0], { key: 'Backspace' });
  expect(await screen.findByRole('list', { name: 'Folders in you' })).toBeTruthy();
  expect(listFolder).toHaveBeenLastCalledWith('/Users/you', expect.anything());

  await fireEvent.click(screen.getByRole('button', { name: /^Pictures 3 folders/ }));
  const back = await screen.findByRole('list', { name: 'Folders in Pictures' });
  await fireEvent.keyDown(within(back).getAllByRole('button')[0], { key: 'ArrowUp', altKey: true });
  expect(await screen.findByRole('list', { name: 'Folders in you' })).toBeTruthy();
});

test('Backspace in the path field edits the path, it does not go up', async () => {
  setUp();
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  await screen.findByRole('list', { name: 'Folders in Pictures' });
  const field = screen.getByRole('textbox', { name: 'Or paste the path of a folder' });
  await fireEvent.keyDown(field, { key: 'Backspace' });
  expect(listFolder).toHaveBeenCalledTimes(1);
});

test('only the folder list is busy while a folder opens, and closing is ignored while a choice is being made', async () => {
  setUp();
  let answer;
  const onChoose = vi.fn(() => new Promise((resolve) => { answer = resolve; }));
  const onClose = vi.fn();
  render(FolderBrowser, { onChoose, onClose });
  await screen.findByRole('list', { name: 'Folders in Pictures' });
  const dialog = screen.getByRole('dialog');
  expect(dialog.hasAttribute('aria-busy')).toBe(false);
  expect(document.querySelector('.fb-main').getAttribute('aria-busy')).toBe('false');

  await fireEvent.click(screen.getByRole('button', { name: 'Import this folder' }));
  await fireEvent.keyDown(window, { key: 'Escape' });
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Close' }));
  expect(onClose).not.toHaveBeenCalled();
  answer();
  await vi.waitFor(() => expect(screen.getByRole('button', { name: 'Import this folder' })).toBeTruthy());
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
  expect(onClose).toHaveBeenCalledOnce();
});

test('focus goes back to the place it names when it closes', async () => {
  setUp();
  const target = document.createElement('button');
  document.body.append(target);
  const { unmount } = render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn(), returnFocus: () => target });
  await screen.findByRole('list', { name: 'Folders in Pictures' });
  unmount();
  await Promise.resolve();
  expect(document.activeElement).toBe(target);
  target.remove();
});

/* ---------- small fixes (polish) ---------- */

test('a newer listing wins over an older one that answers late, and the older one is aborted', async () => {
  getFsRoots.mockResolvedValue(roots);
  const answers = new Map();
  const signals = new Map();
  listFolder.mockImplementation((path, { signal } = {}) => new Promise((resolve) => { answers.set(path, resolve); signals.set(path, signal); }));
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  await vi.waitFor(() => expect(answers.has('/Users/you/Pictures')).toBe(true));
  answers.get('/Users/you/Pictures')(pictures);
  await screen.findByRole('list', { name: 'Folders in Pictures' });

  await fireEvent.click(screen.getByRole('button', { name: 'Home' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Photos SSD' }));
  expect(signals.get('/Users/you').aborted).toBe(true);
  expect(signals.get('/Volumes/Photos SSD').aborted).toBe(false);
  answers.get('/Volumes/Photos SSD')(folder('/Volumes/Photos SSD', { folders: [{ name: 'DCIM', path: '/Volumes/Photos SSD/DCIM', folders: 0, photos: 4 }] }));
  answers.get('/Users/you')(folder('/Users/you', { folders: [{ name: 'Pictures', path: '/Users/you/Pictures', folders: 3, photos: 3 }] }));
  expect(await screen.findByRole('list', { name: 'Folders in Photos SSD' })).toBeTruthy();
  await Promise.resolve();
  expect(screen.queryByRole('list', { name: 'Folders in you' })).toBeNull();
});

test('a typed path is kept when another folder is opened, and cleared once it is opened itself', async () => {
  setUp();
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  await screen.findByRole('list', { name: 'Folders in Pictures' });
  const field = screen.getByRole('textbox', { name: 'Or paste the path of a folder' });
  await fireEvent.input(field, { target: { value: '/Users/you/Pictures/Butterflies 2024' } });

  await fireEvent.click(screen.getByRole('button', { name: /^Butterflies 2024 12 folders/ }));
  await screen.findByRole('list', { name: 'Folders in Butterflies 2024' });
  expect(field.value).toBe('/Users/you/Pictures/Butterflies 2024');
  await fireEvent.click(within(screen.getByRole('navigation', { name: 'Folder path' })).getByRole('button', { name: 'Pictures' }));
  await screen.findByRole('list', { name: 'Folders in Pictures' });
  await fireEvent.keyDown(field, { key: 'Enter' });
  await screen.findByRole('list', { name: 'Folders in Butterflies 2024' });
  expect(field.value).toBe('');
});

test('long and right-to-left names keep their full name and their own direction', async () => {
  const long = 'מסע פרפרים בגליל העליון, אוגוסט 2024 — כל התמונות';
  setUp(folder('/Users/you/Pictures', { folders: [{ name: long, path: `/Users/you/Pictures/${long}`, folders: 0, photos: 2 }], photos: 0 }));
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  const list = await screen.findByRole('list', { name: 'Folders in Pictures' });
  const name = list.querySelector('.nm');
  expect(name.getAttribute('title')).toBe(long);
  expect(name.getAttribute('dir')).toBe('auto');
  expect(screen.getByRole('navigation', { name: 'Folder path' }).querySelector('[aria-current]').getAttribute('dir')).toBe('auto');
});

test('the path field\'s example looks like a path on this computer', async () => {
  getFsRoots.mockResolvedValue([{ name: 'C:', path: 'C:\\', kind: 'drive' }]);
  listFolder.mockRejectedValue(new Error("That folder can't be found."));
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  const field = screen.getByRole('textbox', { name: 'Or paste the path of a folder' });
  await vi.waitFor(() => expect(field.getAttribute('placeholder')).toBe('For example, C:\\Users\\you\\Pictures\\Butterflies'));
});

test('with no folder shown it suggests pasting a path', async () => {
  getFsRoots.mockResolvedValue([]);
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  expect(await screen.findByText('Paste the path of a folder below to open it.')).toBeTruthy();
});

test('"more in the folders inside" only when a folder inside has photos', async () => {
  setUp(folder('/Users/you/Pictures', { folders: [{ name: 'Empty', path: '/Users/you/Pictures/Empty', folders: 0, photos: 0 }], photos: 3 }));
  render(FolderBrowser, { onChoose: vi.fn(), onClose: vi.fn() });
  expect(await screen.findByText('3 photos in this folder.')).toBeTruthy();
});

test('for an export it is titled "Choose where to export", chooses with "Export here" and says nothing of imports or photo counts', async () => {
  setUp(folder('/Users/you/Pictures/Old', { photos: 9, openImport: { id: 3, filed: 3412, total: 9870 } }));
  const onChoose = vi.fn();
  render(FolderBrowser, { exporting: true, startPath: '/Users/you/Pictures/Old', onChoose, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Choose where to export' })).toBeTruthy();
  const here = await screen.findByText('The export goes inside Old.');
  const button = screen.getByRole('button', { name: 'Export here' });
  expect(button.getAttribute('aria-describedby')).toBe(here.id);
  expect(screen.queryByText(/filed|import/i)).toBeNull();
  expect(screen.queryByText(/in this folder/)).toBeNull();
  await fireEvent.click(button);
  expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ path: '/Users/you/Pictures/Old' }));
});

test('for an export the library folder cannot be chosen either, and says to choose one outside it', async () => {
  setUp(folder('/Users/you/Pictures/Atlas', { isLibrary: true }));
  const onChoose = vi.fn();
  render(FolderBrowser, { exporting: true, startPath: '/Users/you/Pictures/Atlas', onChoose, onClose: vi.fn() });

  const reason = await screen.findByText("That's your library folder. Choose a folder outside the library.");
  const button = screen.getByRole('button', { name: 'Export here' });
  expect(button.getAttribute('aria-disabled')).toBe('true');
  expect(button.getAttribute('aria-describedby')).toBe(reason.id);
  await fireEvent.click(button);
  button.focus();
  await fireEvent.keyDown(button, { key: 'Enter' });
  expect(onChoose).not.toHaveBeenCalled();
});

test('for an export a folder with no name of its own (the top of a drive) is named by its path, as the server names it', async () => {
  const top = { path: '/', name: '/', parent: null, crumbs: [{ name: '/', path: '/' }], folders: [], photos: 0, isLibrary: false, openImport: null };
  setUp(top);
  render(FolderBrowser, { exporting: true, startPath: '/', onChoose: vi.fn(), onClose: vi.fn() });
  expect(await screen.findByText('The export goes inside /.')).toBeTruthy();
});

test('for a backup it is titled "Choose where to back up", chooses with "Back up here", and the library folder cannot be chosen', async () => {
  setUp(folder('/Volumes/Backup', { photos: 9, openImport: { id: 3, filed: 3412, total: 9870 } }));
  const onChoose = vi.fn();
  const { unmount } = render(FolderBrowser, { backup: true, startPath: '/Volumes/Backup', onChoose, onClose: vi.fn() });

  expect(screen.getByRole('dialog', { name: 'Choose where to back up' })).toBeTruthy();
  const here = await screen.findByText('The backup goes inside Backup.');
  const button = screen.getByRole('button', { name: 'Back up here' });
  expect(button.getAttribute('aria-describedby')).toBe(here.id);
  expect(screen.queryByText(/filed|import/i)).toBeNull();
  await fireEvent.click(button);
  expect(onChoose).toHaveBeenCalledWith(expect.objectContaining({ path: '/Volumes/Backup' }));
  unmount();

  setUp(folder('/Users/you/Pictures/Atlas', { isLibrary: true }));
  render(FolderBrowser, { backup: true, startPath: '/Users/you/Pictures/Atlas', onChoose, onClose: vi.fn() });
  expect(await screen.findByText("That's your library folder. Choose a folder outside the library.")).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Back up here' }).getAttribute('aria-disabled')).toBe('true');
});
