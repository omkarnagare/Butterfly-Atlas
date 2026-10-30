import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import Setup from './Setup.svelte';
import { getFsRoots, inspectFolder, listFolder, retrySetup, setUpLibrary, usePreviousLibrary } from '../lib/api.js';
import { resetStatus } from '../lib/status.svelte.js';
import { toast } from '../lib/toast.svelte.js';

vi.mock('../lib/api.js', () => ({
  inspectFolder: vi.fn(), setUpLibrary: vi.fn(), retrySetup: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn(), usePreviousLibrary: vi.fn(),
  getStatus: vi.fn(), getExport: vi.fn(), getBackup: vi.fn(), checkHeic: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
});

// A fresh setup: no library yet, so no dataFolder (the server sends one with configured false only when an open failed).
const status = { configured: false, suggestedFolder: '/Users/me/Documents/Butterfly Atlas', dataFolder: null, problem: null, retry: false };

test('uses the suggested folder', async () => {
  setUpLibrary.mockResolvedValue({ configured: true });
  const onDone = vi.fn();
  render(Setup, { status, onDone });

  expect(screen.getByText('/Users/me/Documents/Butterfly Atlas')).toBeTruthy();
  await fireEvent.click(screen.getByRole('button', { name: 'Use this folder' }));

  expect(setUpLibrary).toHaveBeenCalledWith('/Users/me/Documents/Butterfly Atlas', true);
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
});

test('shows why a folder cannot be used', async () => {
  setUpLibrary.mockRejectedValue(new Error('Butterfly Atlas cannot save files in that folder. Choose another place.'));
  render(Setup, { status, onDone: vi.fn() });

  await fireEvent.click(screen.getByRole('button', { name: 'Use this folder' }));

  expect((await screen.findByRole('alert')).textContent).toContain('cannot save files in that folder');
});

// The folder browser in setup mode, at Documents, where the library would be created.
function browserAt(check = { kind: 'create', libraryFolder: '/Users/me/Documents/Butterfly_Atlas' }) {
  getFsRoots.mockResolvedValue([{ name: 'Documents', path: '/Users/me/Documents', kind: 'documents' }]);
  listFolder.mockResolvedValue({
    path: '/Users/me/Documents', name: 'Documents', parent: '/Users/me', crumbs: [{ name: 'Documents', path: '/Users/me/Documents' }],
    folders: [], photos: 0, isLibrary: false, openImport: null,
  });
  inspectFolder.mockImplementation(async (path, exact) => (exact === false
    ? { path, absolute: true, unfinished: null, ...check } : null));
}

test('"Choose a different folder" opens the folder browser; choosing there sets the library up inside that folder', async () => {
  browserAt();
  setUpLibrary.mockResolvedValue({ configured: true });
  const onDone = vi.fn();
  render(Setup, { status, onDone });

  await fireEvent.click(screen.getByRole('button', { name: 'Choose a different folder' }));
  const dialog = screen.getByRole('dialog', { name: 'Choose where to keep your library' });
  expect(screen.queryByPlaceholderText('For example, /Volumes/Photos/Butterflies')).toBeNull();
  await screen.findByText('Your library will be created in /Users/me/Documents/Butterfly_Atlas.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Use this folder' }));

  expect(setUpLibrary).toHaveBeenCalledWith('/Users/me/Documents', false);
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
});

test('Cancel in the folder browser goes back to the suggested folder', async () => {
  browserAt();
  render(Setup, { status, onDone: vi.fn() });

  await fireEvent.click(screen.getByRole('button', { name: 'Choose a different folder' }));
  await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.getByText('/Users/me/Documents/Butterfly Atlas')).toBeTruthy();
});

test('the suggested folder\'s library says what unfinished work opening it clears; a new one says nothing more', async () => {
  inspectFolder.mockResolvedValue({
    path: status.suggestedFolder, absolute: true, libraryFolder: status.suggestedFolder, kind: 'existing',
    unfinished: { imports: 0, exports: 2, backups: 0 },
  });
  const { unmount } = render(Setup, { status, onDone: vi.fn() });

  expect(await screen.findByText(/^This folder already has a Butterfly Atlas library\. It will open as it is, except for work left unfinished where it was used before: 2 paused exports\. They will be cleared/))
    .toBeTruthy();
  expect(inspectFolder).toHaveBeenCalledWith(status.suggestedFolder, true);
  unmount();

  inspectFolder.mockResolvedValue({ path: status.suggestedFolder, absolute: true, libraryFolder: status.suggestedFolder, kind: 'create', unfinished: null });
  render(Setup, { status, onDone: vi.fn() });
  await vi.waitFor(() => expect(inspectFolder).toHaveBeenCalledTimes(2));
  expect(screen.queryByText(/unfinished|will be created/)).toBeNull();
});

test('explains a problem found at startup', () => {
  render(Setup, { status: { ...status, problem: 'Butterfly Atlas could not find your library at /Volumes/Card.' }, onDone: vi.fn() });

  expect(screen.getByRole('alert').textContent).toContain('/Volumes/Card');
});

test('continues when the library was already set up elsewhere', async () => {
  setUpLibrary.mockRejectedValue(Object.assign(new Error('Already set up.'), { code: 'already_configured' }));
  const onDone = vi.fn();
  render(Setup, { status, onDone });

  await fireEvent.click(screen.getByRole('button', { name: 'Use this folder' }));

  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
  expect(screen.queryByRole('alert')).toBeNull();
});

// The remembered library failed to open: its folder comes with the problem.
const failedStep = {
  ...status, dataFolder: '/Volumes/Photos/Atlas', retry: true,
  problem: "Place names couldn't be updated: the drive with your library is full. Your library is unchanged.",
};

test('a library that failed to open: Try again under the problem opens it again, and the app starts as usual', async () => {
  retrySetup.mockResolvedValue({ configured: true, suggestedFolder: status.suggestedFolder, dataFolder: '/Volumes/Photos/Atlas', problem: null, retry: false });
  const onDone = vi.fn();
  render(Setup, { status: failedStep, onDone });
  expect(screen.getByRole('alert').textContent).toBe(failedStep.problem);
  const again = screen.getByRole('button', { name: 'Try again' });
  // under the problem
  expect(screen.getByRole('alert').compareDocumentPosition(again) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  await fireEvent.click(again);
  expect(retrySetup).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
});

test('a Try again that fails again shows the new problem, with Try again still there', async () => {
  const problem = "Place names couldn't be updated: the drive with your library can't be found. Your library is unchanged.";
  retrySetup.mockResolvedValue({ ...failedStep, problem });
  const onDone = vi.fn();
  render(Setup, { status: failedStep, onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toBe(problem));
  expect(screen.getAllByRole('alert')).toHaveLength(1);
  expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
  expect(onDone).not.toHaveBeenCalled();
});

test('a Try again refused says why; one refused because the library is open already starts the app', async () => {
  retrySetup.mockRejectedValueOnce(Object.assign(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'), { code: 'offline' }))
    .mockRejectedValueOnce(Object.assign(new Error('Your library is open.'), { code: 'not_failed' }));
  const onDone = vi.fn();
  render(Setup, { status: failedStep, onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
});

test('no Try again for a problem that is not a library failing to open', () => {
  render(Setup, { status: { ...status, problem: 'Butterfly Atlas could not find your library at /Volumes/Card.' }, onDone: vi.fn() });
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
});

test('a Try again that fails with the same problem says when it tried, in a status a screen reader hears', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 9, 16, 14, 2));
  retrySetup.mockResolvedValue(failedStep);
  render(Setup, { status: failedStep, onDone: vi.fn() });
  const said = screen.getByRole('status');
  expect(said.textContent).toBe('');
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(said.textContent).toBe("Tried again at 14:02 — still couldn't open."));
  expect(screen.getByRole('alert').textContent).toBe(failedStep.problem);
  vi.useRealTimers();
});

test('a Try again refused as not failed clears what an earlier one said before the app starts', async () => {
  retrySetup.mockRejectedValueOnce(Object.assign(new Error('Butterfly Atlas answered with status 500.'), { code: 'http_500' }))
    .mockRejectedValueOnce(Object.assign(new Error('Your library is open.'), { code: 'not_failed' }));
  const onDone = vi.fn();
  render(Setup, { status: { ...failedStep, problem: null }, onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(screen.getByRole('alert').textContent).toBe('Butterfly Atlas answered with status 500.'));
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
  expect(screen.queryByRole('alert')).toBeNull();
});

test('a second Try again failing in the same minute is said again (the status is emptied, then filled)', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 9, 16, 14, 2));
  retrySetup.mockResolvedValue(failedStep);
  render(Setup, { status: failedStep, onDone: vi.fn() });
  const said = screen.getByRole('status');
  const seen = [];
  new MutationObserver(() => seen.push(said.textContent)).observe(said, { childList: true, characterData: true, subtree: true });
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(said.textContent).toBe("Tried again at 14:02 — still couldn't open."));
  seen.length = 0;
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await vi.waitFor(() => expect(seen.at(-1)).toBe("Tried again at 14:02 — still couldn't open."));
  expect(seen).toContain('');
  vi.useRealTimers();
});

test('under a failure: where the photos still are, and that another folder starts a new, empty library (contract §10)', () => {
  render(Setup, { status: failedStep, onDone: vi.fn() });
  const line = screen.getByText(/Your photos are still in/);
  expect(line.textContent).toBe('Your photos are still in /Volumes/Photos/Atlas; choosing another folder starts a new, empty library.');
  expect(screen.getByRole('alert').compareDocumentPosition(line) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(line.classList.contains('muted')).toBe(false);
});

test('a library from a newer version (retry false): the server\'s words and the folder line, no Try again (contract §10)', () => {
  const problem = 'This library uses place data from a newer version of Butterfly Atlas (2027-02-01). Install that version or a newer one to open it.';
  render(Setup, { status: { ...failedStep, retry: false, problem }, onDone: vi.fn() });
  expect(screen.getByRole('alert').textContent).toBe(problem);
  expect(screen.queryByRole('button', { name: 'Try again' })).toBeNull();
  expect(screen.getByText(/Your photos are still in/).textContent).toContain('/Volumes/Photos/Atlas');
});

test('no folder line on a fresh setup, even with a problem (no dataFolder: no library was open)', () => {
  const { unmount } = render(Setup, { status, onDone: vi.fn() });
  expect(screen.queryByText(/Your photos are still in/)).toBeNull();
  unmount();
  render(Setup, { status: { ...status, problem: 'Butterfly Atlas cannot save files in that folder. Choose another place.' }, onDone: vi.fn() });
  expect(screen.getByRole('alert')).toBeTruthy();
  expect(screen.queryByText(/Your photos are still in/)).toBeNull();
});

test('the folder line follows dataFolder: a failed open names it whatever its problem says', () => {
  render(Setup, { status: { ...failedStep, problem: null }, onDone: vi.fn() });
  expect(screen.getByText(/Your photos are still in/).textContent).toContain('/Volumes/Photos/Atlas');
});

const IN_USE = 'Another copy of Butterfly Atlas is using this library.';
const TOO_NEW = 'This library was made by a newer Butterfly Atlas (database version 13; this one knows 12). Install the newer version.';

test('a library another copy is using, or one made by a newer version, is refused in the server\'s words (Phase 6), and the user can choose another folder', async () => {
  for (const [code, message] of [['library_in_use', IN_USE], ['library_too_new', TOO_NEW]]) {
    setUpLibrary.mockRejectedValue(Object.assign(new Error(message), { code, status: 409 }));
    const onDone = vi.fn();
    const { unmount } = render(Setup, { status, onDone });
    await fireEvent.click(screen.getByRole('button', { name: 'Use this folder' }));
    expect((await screen.findByRole('alert')).textContent).toBe(message);
    expect(onDone).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Choose a different folder' }).hasAttribute('disabled')).toBe(false);
    unmount();
  }
});

test('the same refusals are shown in the folder browser, and when Try again meets them', async () => {
  browserAt();
  setUpLibrary.mockRejectedValue(Object.assign(new Error(IN_USE), { code: 'library_in_use', status: 409 }));
  const { unmount } = render(Setup, { status, onDone: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'Choose a different folder' }));
  await screen.findByText('Your library will be created in /Users/me/Documents/Butterfly_Atlas.');
  await fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Use this folder' }));
  expect((await screen.findByRole('alert')).textContent).toBe(IN_USE);
  unmount();

  retrySetup.mockRejectedValue(Object.assign(new Error(TOO_NEW), { code: 'library_too_new', status: 409 }));
  render(Setup, { status: { ...status, dataFolder: '/data/Atlas', problem: 'The library could not be opened.', retry: true }, onDone: vi.fn() });
  await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect((await screen.findAllByRole('alert')).map((a) => a.textContent)).toContain(TOO_NEW);
});

test('the butterfly on its daisy stands in a drawn meadow with soft light behind (Meadow §4), hidden from screen readers', () => {
  const { container } = render(Setup, { status, onDone: vi.fn() });

  const scene = container.querySelector('.setup-scene');
  expect(scene.getAttribute('aria-hidden')).toBe('true');
  expect(scene.querySelector('.oak')).not.toBeNull();
  expect(scene.querySelector('svg.meadow')).not.toBeNull();
  expect(screen.getByRole('heading', { level: 1, name: 'Where should Butterfly Atlas keep your photos?' })).toBeTruthy();
});

// ---- after a change of library folder, the new library won't open (change-library-folder design §6) ----

const notOpening = {
  ...status, dataFolder: '/Volumes/B/Butterfly_Atlas', retry: true, problem: "The drive with your library can't be found.",
  previousFolder: '/Users/me/Atlas', movedCopy: '/Volumes/B/Butterfly_Atlas',
};

test('the new library that won\'t open offers the old one beside Try again, and says the moved copy stays', () => {
  render(Setup, { status: notOpening, onDone: vi.fn() });
  const again = screen.getByRole('button', { name: 'Try again' });
  const old = screen.getByRole('button', { name: 'Use the old library at /Users/me/Atlas instead' });
  expect(again.parentElement).toBe(old.parentElement);
  expect(screen.getByText('The moved copy at /Volumes/B/Butterfly_Atlas was not deleted.')).toBeTruthy();
});

test('after a switch (no moved copy) the old library is offered without the moved-copy line; with no offer there is no button', () => {
  const { unmount } = render(Setup, { status: { ...notOpening, movedCopy: null }, onDone: vi.fn() });
  expect(screen.getByRole('button', { name: 'Use the old library at /Users/me/Atlas instead' })).toBeTruthy();
  expect(screen.queryByText(/The moved copy/)).toBeNull();
  unmount();
  render(Setup, { status: { ...notOpening, previousFolder: null, movedCopy: null }, onDone: vi.fn() });
  expect(screen.queryByRole('button', { name: /Use the old library/ })).toBeNull();
});

test('choosing the old library opens it, the app starts, and where the moved copy is is said once more', async () => {
  usePreviousLibrary.mockResolvedValue({ configured: true, suggestedFolder: status.suggestedFolder, dataFolder: '/Users/me/Atlas', problem: null });
  const onDone = vi.fn();
  render(Setup, { status: notOpening, onDone });
  await fireEvent.click(screen.getByRole('button', { name: 'Use the old library at /Users/me/Atlas instead' }));
  expect(usePreviousLibrary).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(onDone).toHaveBeenCalledOnce());
  expect(toast.message).toBe('The moved copy at /Volumes/B/Butterfly_Atlas was not deleted.');
});

test('the old library refused or failing to open says why; the screen stays', async () => {
  usePreviousLibrary.mockRejectedValueOnce(Object.assign(new Error('The old library is no longer offered.'), { code: 'no_previous' }));
  const onDone = vi.fn();
  render(Setup, { status: notOpening, onDone });
  const old = screen.getByRole('button', { name: 'Use the old library at /Users/me/Atlas instead' });
  await fireEvent.click(old);
  expect((await screen.findByRole('alert')).textContent).toBe('The old library is no longer offered.');

  usePreviousLibrary.mockResolvedValue({ ...notOpening, dataFolder: '/Users/me/Atlas', problem: "Butterfly Atlas can't open the library at /Users/me/Atlas.", previousFolder: null });
  await fireEvent.click(old);
  expect((await screen.findByRole('alert')).textContent).toBe("Butterfly Atlas can't open the library at /Users/me/Atlas.");
  expect(onDone).not.toHaveBeenCalled();
  expect(screen.queryByRole('button', { name: /Use the old library/ })).toBeNull();
});
