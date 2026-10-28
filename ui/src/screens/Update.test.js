import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Update from './Update.svelte';
import { applyUpdate, declineCustomMatch, getCustomMatches, getStatus, getUpdate, mergeCustomMatch, retryUpdate, updateLater } from '../lib/api.js';
import { LIBRARY_CHANGED, resetStatus, status } from '../lib/status.svelte.js';

vi.mock('../lib/api.js', () => ({
  getUpdate: vi.fn(), applyUpdate: vi.fn(), updateLater: vi.fn(), retryUpdate: vi.fn(), getStatus: vi.fn(),
  mergeCustomMatch: vi.fn(), declineCustomMatch: vi.fn(), getCustomMatches: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  getStatus.mockImplementation(() => new Promise(() => {}));
  getCustomMatches.mockResolvedValue([]);
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});
afterEach(() => vi.restoreAllMocks());

const ref = (id, name, common = null) => ({ id, name, common });
const item = (rank, detail, photos, from = [ref('A1', 'x')], to = [ref('A2', 'y')]) => ({ rank, from, to, photos, detail });
const pending = {
  state: 'pending', kind: null, later: false, from: '2026-10-08', to: '2026-12-01', steps: ['2026-11-01', '2026-12-01'],
  summary: {
    photos: 340,
    species: { before: { got: 342, total: 1139 }, after: { got: 342, total: 1141 } },
    kinds: [
      { kind: 'rename', count: 3, photos: 140, items: [item('species', 'Euploea core → Euploea coreta', 120), item('genus', 'Euploia → Euploea', 20), item('species', 'Tirumala limniace → Tirumala limniaceae', 0)] },
      { kind: 'split', count: 1, photos: 200, items: [item('species', 'Danaus chrysippus → Danaus chrysippus, Danaus alcippus', 200)] },
      { kind: 'add', count: 2, photos: 0, items: [item('species', 'Neptis sankara', 0), item('species', 'Neptis soma', 0)] },
    ],
  },
  progress: null, notice: null, result: null,
};
const progress = (over = {}) => ({ step: 1, steps: 2, stage: 'moves', done: 120, total: 340, continuing: false, waitingFor: null, ...over });
const running = { ...pending, state: 'running', kind: 'bundle', summary: null, progress: progress() };
const brief = (over = {}) => ({ state: 'running', kind: 'bundle', later: false, done: 120, total: 340, step: 1, steps: 2, continuing: false, waitingFor: null, notice: null, ...over });

test('pending: the headline, the species line, each kind with its photos, and what happens to folders', async () => {
  getUpdate.mockResolvedValue(pending);
  render(Update);

  expect(await screen.findByRole('heading', { level: 1, name: '3 names renamed, 1 split, 2 added — affects 340 of your photos.' })).toBeTruthy();
  expect(screen.getByText('Taxonomy update · 2026-10-08 → 2026-12-01')).toBeTruthy();
  expect(screen.getByText('Your atlas: 342 of 1,139 species → 342 of 1,141')).toBeTruthy();
  expect(screen.getByText('Folders on disk are renamed to match. Nothing is deleted.')).toBeTruthy();

  const renamed = screen.getByRole('button', { name: /Renamed/ });
  expect(renamed.textContent.replace(/\s+/g, ' ').trim()).toBe('Renamed 3 · 140 photos');
  expect(renamed.getAttribute('aria-expanded')).toBe('true');
  const list = document.getElementById(renamed.getAttribute('aria-controls'));
  expect([...list.querySelectorAll('li')].map((li) => li.textContent.replace(/\s+/g, ' ').trim()))
    .toEqual(['Euploea core → Euploea coreta 120 photos', 'Genus Euploia → Euploea 20 photos']);
  const more = within(list).getByRole('button', { name: 'and 1 more' });
  await fireEvent.click(more);
  expect(list.querySelectorAll('li')).toHaveLength(3);
  expect(more.textContent.trim()).toBe('Show fewer');
  expect(more.getAttribute('aria-expanded')).toBe('true');
  await fireEvent.click(more);
  expect(list.querySelectorAll('li')).toHaveLength(2);

  const added = screen.getByRole('button', { name: /Added/ });
  expect(added.getAttribute('aria-expanded')).toBe('false');
  expect(document.getElementById(added.getAttribute('aria-controls')).hidden).toBe(true);
  await fireEvent.click(added);
  const addList = document.getElementById(added.getAttribute('aria-controls'));
  expect(addList.hidden).toBe(false);
  await fireEvent.click(within(addList).getByRole('button', { name: 'Show all 2' }));
  expect(addList.querySelectorAll('li')).toHaveLength(2);
  await fireEvent.click(renamed);
  expect(list.hidden).toBe(true);
});

test('Later is remembered for this run and goes Home', async () => {
  getUpdate.mockResolvedValue(pending);
  updateLater.mockResolvedValue(null);
  render(Update);
  await fireEvent.click(await screen.findByRole('button', { name: 'Later' }));
  expect(updateLater).toHaveBeenCalledTimes(1);
  await vi.waitFor(() => expect(window.location.hash).toBe('#/'));
});

test('Apply now starts the update: both buttons inactive while it is asked, then the progress, focus on the heading', async () => {
  getUpdate.mockResolvedValueOnce(pending).mockResolvedValue(running);
  let answer;
  applyUpdate.mockImplementation(() => new Promise((resolve) => { answer = resolve; }));
  render(Update);
  const apply = await screen.findByRole('button', { name: 'Apply now' });
  await fireEvent.click(apply);
  expect(apply.getAttribute('aria-disabled')).toBe('true');
  expect(apply.textContent).toBe('Applying…');
  expect(screen.getByRole('button', { name: 'Later' }).getAttribute('aria-disabled')).toBe('true');
  await fireEvent.click(apply);
  await fireEvent.click(screen.getByRole('button', { name: 'Later' }));
  expect(applyUpdate).toHaveBeenCalledTimes(1);
  expect(updateLater).not.toHaveBeenCalled();

  answer(running);
  const heading = await screen.findByRole('heading', { level: 1, name: 'Updating the taxonomy' });
  await vi.waitFor(() => expect(document.activeElement).toBe(heading));
  expect(screen.getByText('Step 1 of 2 · 120 of 340 photos')).toBeTruthy();
  const bar = screen.getByRole('progressbar', { name: 'Photos moved' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['340', '120']);
  expect(status.taxonomyUpdate).toMatchObject({ state: 'running', done: 120, total: 340 });
  expect(screen.getByText(/You can leave this page/)).toBeTruthy();
});

test('a refused Apply says why above the buttons and reads the update again', async () => {
  getUpdate.mockResolvedValueOnce(pending).mockResolvedValue({ ...pending, state: 'none', summary: null });
  applyUpdate.mockRejectedValue(Object.assign(new Error('There is no update to apply.'), { code: 'no_update', status: 409 }));
  render(Update);
  await fireEvent.click(await screen.findByRole('button', { name: 'Apply now' }));
  expect(await screen.findByRole('heading', { level: 1, name: 'Your taxonomy is up to date.' })).toBeTruthy();
  expect(getUpdate).toHaveBeenCalledTimes(2);

  getUpdate.mockResolvedValue(pending);
  applyUpdate.mockRejectedValue(new Error('Butterfly Atlas could not reach its service.'));
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  await fireEvent.click(await screen.findByRole('button', { name: 'Apply now' }));
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
});

test('running: the numbers follow the status as the footer polls; waiting says what for; a new state reads the update again', async () => {
  getUpdate.mockResolvedValueOnce({ ...running, state: 'waiting', progress: progress({ done: 0, total: 0, waitingFor: 'import' }) });
  status.taxonomyUpdate = brief({ state: 'waiting', waitingFor: 'import', done: 0, total: 0 });
  render(Update);
  expect(await screen.findByText('Waiting for the import to finish filing…')).toBeTruthy();
  expect(screen.getByRole('progressbar', { name: 'Photos moved' }).hasAttribute('value')).toBe(false);

  getUpdate.mockResolvedValueOnce(running);
  status.taxonomyUpdate = brief({ done: 150 });
  expect(await screen.findByText('Step 1 of 2 · 150 of 340 photos')).toBeTruthy();
  status.taxonomyUpdate = brief({ done: 200, step: 2 });
  expect(await screen.findByText('Step 2 of 2 · 200 of 340 photos')).toBeTruthy();
  expect(getUpdate).toHaveBeenCalledTimes(2);

  getUpdate.mockResolvedValue({ ...running, progress: progress({ stage: 'plan', done: 0, total: 0 }) });
  window.dispatchEvent(new CustomEvent(LIBRARY_CHANGED));
  status.taxonomyUpdate = null;
  expect(await screen.findByText('Step 1 of 2 · Working out what moves…')).toBeTruthy();
});

test('paused: the notice and Retry', async () => {
  getUpdate.mockResolvedValueOnce({ ...running, state: 'paused', notice: { code: 'drive_full', message: 'The drive with your library is full. Free some space, then choose Retry.' } })
    .mockResolvedValue(running);
  retryUpdate.mockResolvedValue(running);
  render(Update);
  expect(await screen.findByRole('heading', { level: 1, name: 'The update is paused' })).toBeTruthy();
  expect(screen.getByText('Step 1 of 2 · 120 of 340 photos so far.')).toBeTruthy();
  const card = screen.getByRole('region', { name: 'Paused taxonomy update' });
  expect(card.textContent).toContain('The drive with your library is full. Free some space, then choose Retry.');
  await fireEvent.click(within(card).getByRole('button', { name: 'Retry' }));
  expect(retryUpdate).toHaveBeenCalledTimes(1);
  expect(await screen.findByRole('heading', { level: 1, name: 'Updating the taxonomy' })).toBeTruthy();
});

const result = {
  from: '2026-10-08', to: '2026-12-01', photos: 340, keptFolders: ['images/Nymphalidae/Danainae/Euploea/'],
  splits: [{ id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', photos: 28, version: '2026-12-01', targets: [] }],
  matches: [
    { custom: { id: 'U3', name: 'Neptis sankara', common: null, photos: 4 }, official: { id: 'A90', name: 'Neptis sankara', common: 'Broad-banded Sailer', family: 'Nymphalidae' }, offeredVersion: '2026-12-01' },
    { custom: { id: 'U4', name: 'Neptis soma', common: null, photos: 1 }, official: { id: 'A91', name: 'Neptis soma', common: null, family: 'Nymphalidae' }, offeredVersion: '2026-12-01' },
  ],
};

test('done: the photos moved, the folders kept, a link to sort each split and the matches', async () => {
  getUpdate.mockResolvedValue({ ...running, state: 'done', progress: null, result });
  declineCustomMatch.mockResolvedValue(null);
  getCustomMatches.mockResolvedValue(result.matches);
  render(Update);
  expect(await screen.findByRole('heading', { level: 1, name: 'Taxonomy updated' })).toBeTruthy();
  expect(screen.getByText('340 photos moved to their new folders.')).toBeTruthy();
  expect(screen.getByText('images/Nymphalidae/Danainae/Euploea/')).toBeTruthy();
  const sort = screen.getByRole('link', { name: 'Sort photos of Danaus chrysippus' });
  expect(sort.getAttribute('href')).toBe('#/split/A5');
  expect(sort.closest('li').textContent).toContain('28 photos to sort');

  const prompts = screen.getAllByRole('group', { name: /matches an official one/ });
  expect(prompts).toHaveLength(2);
  expect(prompts[0].textContent.replace(/\s+/g, ' ')).toContain('Your custom Neptis sankara (4 photos) now matches the official Neptis sankara (Broad-banded Sailer).');
  await fireEvent.click(within(prompts[1]).getByRole('button', { name: 'Not now' }));
  expect(declineCustomMatch).toHaveBeenCalledWith('U4', 'A91');
  await vi.waitFor(() => expect(screen.getAllByRole('group', { name: /matches an official one/ })).toHaveLength(1));
});

test('a merge done: its own words; none: up to date with the library\'s version; unavailable: its notice', async () => {
  getUpdate.mockResolvedValueOnce({ ...running, kind: 'match', state: 'done', progress: null, result: { ...result, photos: 1, keptFolders: [], splits: [], matches: [] } });
  const { unmount } = render(Update);
  expect(await screen.findByRole('heading', { level: 1, name: 'Custom species merged' })).toBeTruthy();
  expect(screen.getByText('1 photo moved to its new folder.')).toBeTruthy();
  unmount();

  getUpdate.mockResolvedValueOnce({ state: 'none', kind: null, later: false, from: '2026-12-01', to: '2026-12-01', steps: [], summary: null, progress: null, notice: null, result: null });
  const second = render(Update);
  expect(await screen.findByRole('heading', { level: 1, name: 'Your taxonomy is up to date.' })).toBeTruthy();
  expect(screen.getByText('It follows the 2026-12-01 list.')).toBeTruthy();
  second.unmount();

  const message = "This library's taxonomy (2026-10-08) is not in this copy of Butterfly Atlas, so updates can't be applied.";
  getUpdate.mockResolvedValueOnce({ state: 'unavailable', from: '2026-10-08', steps: [], summary: null, progress: null, notice: { code: 'unavailable', message }, result: null });
  render(Update);
  expect(await screen.findByText(message)).toBeTruthy();
});

test('the update that cannot be read says why', async () => {
  getUpdate.mockRejectedValue(new Error('Butterfly Atlas could not reach its service.'));
  render(Update);
  expect((await screen.findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service.');
});

test('Merge asks first; merging starts the job and the prompt goes', async () => {
  getUpdate.mockResolvedValue({ ...running, state: 'done', progress: null, result: { ...result, matches: [result.matches[0]] } });
  getCustomMatches.mockResolvedValue([result.matches[0]]);
  mergeCustomMatch.mockResolvedValue({ ...running, kind: 'match', progress: progress({ steps: 1, done: 0, total: 4 }) });
  render(Update);
  const merge = await screen.findByRole('button', { name: 'Merge' });
  await fireEvent.click(merge);
  const dialog = screen.getByRole('dialog', { name: 'Merge Neptis sankara?' });
  expect(dialog.textContent.replace(/\s+/g, ' ')).toContain('Its 4 photos move to the official Neptis sankara, with their tags and places, and the custom species is removed.');
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Merge' }));
  expect(mergeCustomMatch).toHaveBeenCalledWith('U3', 'A90');
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(status.taxonomyUpdate).toMatchObject({ state: 'running', kind: 'match', total: 4 });
});

test('a pending update without its summary still offers Later and Apply now, with a plain headline', async () => {
  getUpdate.mockResolvedValue({ ...pending, summary: null });
  render(Update);
  expect(await screen.findByRole('heading', { level: 1, name: 'A taxonomy update is ready.' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Apply now' })).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Later' })).toBeTruthy();
  expect(screen.queryByText('Your taxonomy is up to date.')).toBeNull();
  expect(screen.queryByText(/Your atlas:/)).toBeNull();
});

test('an update waiting for the startup\'s photo checks says so', async () => {
  getUpdate.mockResolvedValue({ ...running, state: 'waiting', progress: progress({ done: 0, total: 0, waitingFor: 'startup' }) });
  render(Update);
  expect(await screen.findByText('Waiting for the startup checks to finish…')).toBeTruthy();
  expect(screen.getByRole('progressbar', { name: 'Photos moved' }).hasAttribute('value')).toBe(false);
});

test('a pause for any code, no_access or one this page does not know, shows its message as sent with Retry', async () => {
  for (const notice of [
    { code: 'no_access', message: "Butterfly Atlas can't write to the drive with your library. Allow it, then choose Retry." },
    { code: 'something_new', message: 'Something new happened. Choose Retry to try again.' },
  ]) {
    getUpdate.mockResolvedValueOnce({ ...running, state: 'paused', notice });
    const { unmount } = render(Update);
    const card = await screen.findByRole('region', { name: 'Paused taxonomy update' });
    expect(card.textContent).toContain(notice.message);
    expect(within(card).getByRole('button', { name: 'Retry' })).toBeTruthy();
    unmount();
  }
});

test('done: folders that couldn\'t be removed and photos left behind are listed too', async () => {
  getUpdate.mockResolvedValue({ ...running, state: 'done', progress: null, result: {
    ...result, keptFolders: [], splits: [], matches: [],
    notRemoved: ['images/Nymphalidae/Danainae/Danaini/Danaus/'],
    leftBehind: [{ id: 7, fileName: 'Danaus_chrysippus_03.jpg', oldPath: 'images/x/Danaus_chrysippus_03.jpg' }],
  } });
  render(Update);
  expect(await screen.findByRole('heading', { name: "Folders that couldn't be removed" })).toBeTruthy();
  expect(screen.getByText('images/Nymphalidae/Danainae/Danaini/Danaus/')).toBeTruthy();
  const left = screen.getByRole('region', { name: 'Photos left behind' });
  expect(left.textContent.replace(/\s+/g, ' ')).toContain("This photo couldn't be found in its old or new folder, so the update went on without it:");
  expect(left.textContent.replace(/\s+/g, ' ')).toContain('Danaus_chrysippus_03.jpg — was in images/x/Danaus_chrysippus_03.jpg');
});

test('up to date after a restart: what the last update did, under "Your taxonomy is up to date."', async () => {
  getUpdate.mockResolvedValue({ state: 'none', kind: null, later: false, from: '2026-12-01', to: '2026-12-01', steps: [], summary: null, progress: null,
    notice: null, result: null, last: { ...result, matches: [], finishedAt: new Date().toISOString() } });
  render(Update);
  const heading = await screen.findByRole('heading', { level: 1, name: 'Your taxonomy is up to date.' });
  const last = screen.getByRole('heading', { level: 2, name: 'What the last update (2026-10-08 → 2026-12-01) did' });
  expect(heading.compareDocumentPosition(last) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getByText('Finished today. 340 photos moved to their new folders.')).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Sort photos of Danaus chrysippus' })).toBeTruthy();
  expect(screen.getByText('images/Nymphalidae/Danainae/Euploea/')).toBeTruthy();
});

test('done without this run\'s result shows the last one kept', async () => {
  getUpdate.mockResolvedValue({ ...running, state: 'done', progress: null, result: null, last: { ...result, photos: 2, matches: [], finishedAt: '2026-10-15T10:00:00Z' } });
  render(Update);
  expect(await screen.findByText('2 photos moved to their new folders.')).toBeTruthy();
});

test('a summary that couldn\'t be worked out says why under the plain headline', async () => {
  const summaryProblem = "The list of changes couldn't be worked out (a species is missing from the new list). You can still apply the update, or choose Later.";
  getUpdate.mockResolvedValue({ ...pending, summary: null, summaryProblem });
  render(Update);
  const heading = await screen.findByRole('heading', { level: 1, name: 'A taxonomy update is ready.' });
  const why = screen.getByText(summaryProblem);
  expect(heading.compareDocumentPosition(why) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Apply now' })).toBeTruthy();
});

test('the result\'s notes show each on its own line, as sent; a result without notes shows none', async () => {
  const notes = ['Photos still to sort on Danaus chrysippus moved to Danaus petilia with the merge.', 'Another note.'];
  getUpdate.mockResolvedValueOnce({ ...running, state: 'done', progress: null, result: { ...result, matches: [], notes } });
  const { unmount } = render(Update);
  const list = await screen.findByRole('list', { name: 'Notes' });
  expect([...list.querySelectorAll('li')].map((li) => li.textContent)).toEqual(notes);
  unmount();

  getUpdate.mockResolvedValueOnce({ state: 'none', from: '2026-12-01', to: '2026-12-01', steps: [], summary: null, progress: null, notice: null, result: null,
    last: { ...result, matches: [], finishedAt: new Date().toISOString(), notes: [notes[0]] } });
  const second = render(Update);
  expect((await screen.findByRole('list', { name: 'Notes' })).textContent).toBe(notes[0]);
  second.unmount();

  getUpdate.mockResolvedValueOnce({ ...running, state: 'done', progress: null, result: { ...result, matches: [] } });
  render(Update);
  await screen.findByRole('heading', { level: 1, name: 'Taxonomy updated' });
  expect(screen.queryByRole('list', { name: 'Notes' })).toBeNull();
});

test('only matches still open are offered: one decided since (or after a restart) is not; a list that can\'t be read is said', async () => {
  getUpdate.mockResolvedValue({ state: 'none', from: '2026-12-01', to: '2026-12-01', steps: [], summary: null, progress: null, notice: null, result: null,
    last: { ...result, finishedAt: new Date().toISOString() } });
  getCustomMatches.mockResolvedValueOnce([result.matches[1]]);
  const { unmount } = render(Update);
  await vi.waitFor(() => expect(screen.getAllByRole('group', { name: /matches an official one/ })).toHaveLength(1));
  expect(screen.getByRole('group', { name: /matches an official one/ }).getAttribute('aria-label')).toContain('Neptis soma');
  unmount();

  getCustomMatches.mockRejectedValueOnce(new Error('offline'));
  render(Update);
  expect(await screen.findByText("The custom-species matches couldn't be checked. Home shows those still open.")).toBeTruthy();
  expect(screen.queryByRole('button', { name: 'Merge' })).toBeNull();
});

test('pending, with a finished merge kept as last: what it did shows under the offer', async () => {
  getUpdate.mockResolvedValue({ ...pending, last: { ...result, photos: 4, keptFolders: [], splits: [], matches: [], finishedAt: new Date().toISOString() } });
  render(Update);
  await screen.findByRole('button', { name: 'Apply now' });
  const last = screen.getByRole('heading', { level: 2, name: 'What the last update (2026-10-08 → 2026-12-01) did' });
  expect(screen.getByRole('button', { name: 'Apply now' }).compareDocumentPosition(last) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getByText('Finished today. 4 photos moved to their new folders.')).toBeTruthy();
});

test('a result or a last update that couldn\'t be read is said where it would be, as sent', async () => {
  getUpdate.mockResolvedValueOnce({ ...running, state: 'done', progress: null, result: null, last: null,
    resultProblem: "What this update did couldn't be read (the log is damaged). Your photos and folders are as the update left them." });
  const { unmount } = render(Update);
  expect((await screen.findByRole('alert')).textContent).toBe("What this update did couldn't be read (the log is damaged). Your photos and folders are as the update left them.");
  unmount();

  getUpdate.mockResolvedValueOnce({ state: 'none', from: '2026-12-01', to: '2026-12-01', steps: [], summary: null, progress: null, notice: null, result: null,
    last: null, lastProblem: "What the last update did couldn't be read." });
  const second = render(Update);
  await screen.findByRole('heading', { level: 1, name: 'Your taxonomy is up to date.' });
  expect(screen.getByText("What the last update did couldn't be read.")).toBeTruthy();
  second.unmount();

  getUpdate.mockResolvedValueOnce({ state: 'none', from: '2026-12-01', to: '2026-12-01', steps: [], summary: null, progress: null, notice: null, result: null, last: null });
  const { container } = render(Update);
  await screen.findByRole('heading', { level: 1, name: 'Your taxonomy is up to date.' });
  expect(container.querySelector('.problem')).toBeNull();
});

test('the photos moved are the files moved (`moved`), not the photos the update touched; none moved says so', async () => {
  getUpdate.mockResolvedValueOnce({ ...running, state: 'done', progress: null, result: { ...result, photos: 340, moved: 338, matches: [] } });
  const { unmount } = render(Update);
  expect(await screen.findByText('338 photos moved to their new folders.')).toBeTruthy();
  unmount();
  getUpdate.mockResolvedValueOnce({ ...running, state: 'done', progress: null, result: { ...result, photos: 4, moved: 0, matches: [] } });
  render(Update);
  expect(await screen.findByText('No photo had to move.')).toBeTruthy();
});
