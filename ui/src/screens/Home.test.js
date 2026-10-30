import { fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import Home from './Home.svelte';
import { cancelBackup, cancelExport, checkBackup, dismissMovedNote, dismissPlaceUpdate, dismissRescanLeft, getBackups, getFsRoots, listFolder, resumeBackup, setBackupPreference, getPlaceUpdate, getSplits, getStatus, getSummary, getTagNames, getTags, resumeExport } from '../lib/api.js';
import { resetStatus, status } from '../lib/status.svelte.js';
import { modal } from '../lib/modal.js';
import { resetAsk } from '../lib/backups.js';
import { forgetViewerOpen, viewerOpenedFrom } from '../lib/viewer.js';

vi.mock('../lib/api.js', () => ({
  getSummary: vi.fn(), getTags: vi.fn(), getStatus: vi.fn(), resumeExport: vi.fn(), cancelExport: vi.fn(),
  getSplits: vi.fn(), getCustomMatches: vi.fn(), retryUpdate: vi.fn(), mergeCustomMatch: vi.fn(), declineCustomMatch: vi.fn(),
  getPlaceUpdate: vi.fn(), dismissPlaceUpdate: vi.fn(), getBackups: vi.fn(), getBackup: vi.fn(), resumeBackup: vi.fn(), cancelBackup: vi.fn(), pauseBackup: vi.fn(),
  setBackupPreference: vi.fn(), checkBackup: vi.fn(), startBackup: vi.fn(), getFsRoots: vi.fn(), listFolder: vi.fn(),
  dismissRescanLeft: vi.fn(), checkHeic: vi.fn(), getTagNames: vi.fn(), dismissTagNames: vi.fn(), dismissMovedNote: vi.fn(),
  photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
  thumbUrl: (id, v) => `./api/photos/${id}/thumb?v=${v}`,
}));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
  resetAsk();
  getTags.mockResolvedValue([{ id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 1 }]);
  getTagNames.mockResolvedValue({ merged: [], numbered: [], long: [], dismissed: false });
});

const summary = {
  group: {
    id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null, family: null, active: true, path: [],
    species: { got: 0, total: 1139 },
    ranks: [{ rank: 'family', got: 0, total: 6 }, { rank: 'species', got: 0, total: 1139 }],
    synonyms: [], searchWords: [], folder: null,
  },
  families: [
    { id: 'A1', rank: 'family', name: 'Nymphalidae', common: 'Brush-footed Butterflies', family: 'Nymphalidae', active: true, species: { got: 0, total: 400 } },
  ],
  photoCount: 0, latest: null, recent: [], nextOnList: [], fullLifeCycles: [],
};

const card = (id, name, common, got = 1, cover = null) => ({
  id, rank: 'species', name, common, family: 'Nymphalidae', active: true, species: { got, total: 1 }, cover,
});
const photo = (id, tags = []) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.jpg`, relPath: '', tags, addedAt: new Date().toISOString(),
  width: 4, height: 3, isCover: false, place: null,
});
const plainTiger = card('A5', 'Danaus chrysippus', 'Plain Tiger', 1, { id: 2, v: 'x' });
const withPhotos = {
  ...summary,
  group: { ...summary.group, species: { got: 2, total: 1139 } },
  photoCount: 7,
  latest: { photo: photo(2, ['larva']), taxon: plainTiger },
  recent: [{ photo: photo(2, ['larva']), taxon: plainTiger }, { photo: photo(1), taxon: plainTiger }],
  nextOnList: [card('A6', 'Danaus genutia', 'Striped Tiger', 0)],
  fullLifeCycles: [plainTiger],
};

test('shows how many species are photographed, by rank', async () => {
  getSummary.mockResolvedValue(summary);
  render(Home);

  expect(await screen.findByRole('heading', { name: '0 species photographed' })).toBeTruthy();
  expect(screen.getByRole('img', { name: '0 of 1139 species photographed' })).toBeTruthy();
  expect(screen.getByText('0/6')).toBeTruthy();
  expect(screen.getByText(/10-species milestone/)).toBeTruthy();
  expect(screen.getByText(/Across 0 photos/)).toBeTruthy();
});

test('the ring has a segment per family, and its butterfly is in the colour of the most photographed family', async () => {
  getSummary.mockResolvedValue({
    ...summary,
    group: { ...summary.group, species: { got: 3, total: 1139 } },
    families: [
      { id: 'A1', rank: 'family', name: 'Nymphalidae', common: null, family: 'Nymphalidae', active: true, species: { got: 1, total: 400 } },
      { id: 'A2', rank: 'family', name: 'Pieridae', common: null, family: 'Pieridae', active: true, species: { got: 2, total: 90 } },
      { id: 'X', rank: 'additions', name: 'Custom additions', common: null, family: null, active: true, species: { got: 0, total: 1 } },
    ],
  });
  const { container } = render(Home);

  await screen.findByRole('img', { name: '3 of 1139 species photographed' });
  expect([...container.querySelectorAll('.ring > svg g')].map((g) => g.style.getPropertyValue('--fam'))).toEqual(['var(--f-nym)', 'var(--f-pie)']);
  expect(container.querySelector('.ring .perch').style.getPropertyValue('--fam')).toBe('var(--f-pie)');
});

test('the tally: the number of species in the accent, and a meadow along its foot', async () => {
  getSummary.mockResolvedValue(summary);
  const { container } = render(Home);

  const heading = await screen.findByRole('heading', { level: 1, name: '0 species photographed' });
  expect(heading.querySelector('.key').textContent).toBe('0 species');
  const strip = container.querySelector('.tally > svg.meadow');
  expect(strip.getAttribute('aria-hidden')).toBe('true');
});

test("the family cards are laid out as Home's family grid (three across when wide)", async () => {
  getSummary.mockResolvedValue(summary);
  const { container } = render(Home);

  await screen.findByRole('heading', { name: 'Families' });
  expect(container.querySelector('.grid.fam-grid').querySelectorAll('.node')).toHaveLength(1);
});

test('links each family card to its page', async () => {
  getSummary.mockResolvedValue(summary);
  render(Home);

  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  expect(screen.getByRole('link', { name: /Nymphalidae/ }).getAttribute('href')).toBe('#/explore/A1');
});

test('shows the problem when the summary cannot be loaded', async () => {
  getSummary.mockRejectedValue(new Error('Choose where to keep your library first.'));
  render(Home);

  expect((await screen.findByRole('alert')).textContent).toBe('Choose where to keep your library first.');
});

test('before the first photo: a drawn latest addition, no strips and no life cycles yet', async () => {
  getSummary.mockResolvedValue(summary);
  const { container } = render(Home);

  expect(await screen.findByText('Your newest photo shows here. Add photos from any species page.')).toBeTruthy();
  expect(container.querySelector('.latest .hero-fly')).not.toBeNull();
  expect(screen.queryByRole('heading', { name: 'Recently added' })).toBeNull();
  expect(screen.queryByRole('heading', { name: 'Next on your list' })).toBeNull();
  expect(screen.getByText('None yet.')).toBeTruthy();
});

test('with no photos yet it offers to import a folder of photos; once there are photos it does not', async () => {
  getSummary.mockResolvedValue(summary);
  const { unmount } = render(Home);

  const cta = await screen.findByRole('link', { name: 'Import a folder of photos' });
  expect(cta.getAttribute('href')).toBe('#/import');
  unmount();

  getSummary.mockResolvedValue(withPhotos);
  render(Home);
  await screen.findByRole('link', { name: /Latest addition/ });
  expect(screen.queryByRole('link', { name: 'Import a folder of photos' })).toBeNull();
});

test('shows the latest photo, opening the viewer, and the photos added recently, each opening its species', async () => {
  forgetViewerOpen();
  getSummary.mockResolvedValue(withPhotos);
  const { container } = render(Home);

  const latest = await screen.findByRole('link', { name: 'Latest addition: Plain Tiger, today' });
  expect(latest.getAttribute('href')).toBe('#/species/A5/photo/2');
  expect(latest.querySelector('img').getAttribute('src')).toBe('./api/photos/2/thumb?v=1');
  expect(latest.textContent).toContain('Plain Tiger · larva · today');
  expect(screen.getByText(/Across 7 photos/)).toBeTruthy();
  const recent = [...container.querySelectorAll('.thumb')];
  expect(recent.map((a) => a.getAttribute('href'))).toEqual(['#/species/A5', '#/species/A5']);
  expect(recent[0].querySelector('img').getAttribute('src')).toBe('./api/photos/2/thumb?v=1');
  recent[0].addEventListener('click', (event) => event.preventDefault());
  await fireEvent.click(recent[0]);
  expect(viewerOpenedFrom()).toBeNull();
});

test('suggests species to find next and lists full life cycles', async () => {
  getSummary.mockResolvedValue(withPhotos);
  const { container } = render(Home);

  expect(await screen.findByRole('heading', { name: 'Next on your list' })).toBeTruthy();
  const target = container.querySelector('.strip .spc.target');
  expect(target.getAttribute('href')).toBe('#/species/A6');
  expect(target.textContent).toContain('Striped Tiger');
  const full = container.querySelector('a.full');
  expect(full.getAttribute('href')).toBe('#/species/A5');
  expect(full.textContent).toBe('Plain Tiger');
});

test('reloads in place when the photo check finds new photos', async () => {
  getSummary.mockResolvedValue(summary);
  render(Home);
  expect(await screen.findByRole('heading', { name: '0 species photographed' })).toBeTruthy();

  getSummary.mockResolvedValue({ ...summary, group: { ...summary.group, species: { got: 1, total: 1139 } } });
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));

  expect(await screen.findByRole('heading', { name: '1 species photographed' })).toBeTruthy();
});

test('an import stopped by the app closing shows first, with a way to continue it', async () => {
  getSummary.mockResolvedValue(withPhotos);
  status.stoppedImports = [{ id: 3, rootName: 'Butterflies 2024', phase: 'reading', done: 812, total: 9870 }];
  render(Home);

  const card = await screen.findByRole('region', { name: 'Imports that stopped' });
  expect(card.textContent).toContain('Your import of Butterflies 2024 stopped when Butterfly Atlas closed');
  expect(screen.getByRole('link', { name: 'Continue import of Butterflies 2024' }).getAttribute('href')).toBe('#/import/3');
});

test('the stopped imports card is named once', async () => {
  getSummary.mockResolvedValue(withPhotos);
  status.stoppedImports = [{ id: 3, rootName: 'Butterflies 2024', phase: 'reading', done: 812, total: 9870 }];
  const { container } = render(Home);
  await screen.findByText(/Your import of Butterflies 2024 stopped/);
  expect(container.querySelectorAll('[aria-label="Imports that stopped"]').length).toBe(1);
});

test('no stopped imports, no card', async () => {
  getSummary.mockResolvedValue(withPhotos);
  status.stoppedImports = [];
  render(Home);
  await screen.findByRole('heading', { level: 1 });
  expect(screen.queryByText(/stopped when Butterfly Atlas closed/)).toBeNull();
  expect(document.querySelector('.stop-card')).toBeNull();
});

const pausedExport = (over = {}) => ({
  id: 4, state: 'paused', done: 3200, total: 50000, pausedBy: 'you', notice: null, path: '/Volumes/Backup/Butterfly_Atlas_export_2026-10-13', ...over,
});

afterEach(() => {
  vi.restoreAllMocks();
  // a test's own <main> (focus goes there when the paused card goes) does not outlive it
  for (const main of document.querySelectorAll('main')) main.remove();
});

test('a paused export shows first, like a stopped import: why it stopped, how far it got, Continue export and Discard…', async () => {
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  getSummary.mockResolvedValue(withPhotos);
  status.export = pausedExport({ pausedBy: 'closing', notice: 'This export stopped when Butterfly Atlas closed.' });
  const { container } = render(Home);

  const card = await screen.findByRole('region', { name: 'Paused export' });
  expect(card.textContent).toContain('This export stopped when Butterfly Atlas closed.');
  expect(card.textContent).toContain('3,200 of 50,000 photos copied to /Volumes/Backup/Butterfly_Atlas_export_2026-10-13.');
  expect([...card.querySelectorAll('button')].map((b) => b.textContent)).toEqual(['Continue export', 'Discard…']);
  expect(container.querySelectorAll('[aria-label="Paused export"]').length).toBe(1);
  expect(card.compareDocumentPosition(screen.getByRole('heading', { level: 1 })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
});

test('paused by the user it says so; Continue export resumes it and the card goes', async () => {
  getSummary.mockResolvedValue(withPhotos);
  getStatus.mockImplementation(() => new Promise(() => {}));
  status.export = pausedExport();
  resumeExport.mockResolvedValue({ ...pausedExport(), state: 'running' });
  render(Home);
  const card = await screen.findByRole('region', { name: 'Paused export' });
  expect(card.textContent).toContain('You paused an export.');
  await fireEvent.click(within(card).getByRole('button', { name: 'Continue export' }));
  expect(resumeExport).toHaveBeenCalledWith(4);
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Paused export' })).toBeNull());
});

test('Discard… opens the stop dialog over Home', async () => {
  getSummary.mockResolvedValue(withPhotos);
  getStatus.mockImplementation(() => new Promise(() => {}));
  status.export = pausedExport();
  cancelExport.mockResolvedValue({ ...pausedExport(), state: 'cancelled', cancel: 'keep' });
  render(Home);
  await fireEvent.click(await screen.findByRole('button', { name: 'Discard…' }));
  await fireEvent.click(within(screen.getByRole('dialog', { name: 'Stop this export?' })).getByRole('button', { name: 'Stop export' }));
  expect(cancelExport).toHaveBeenCalledWith(4, false);
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Paused export' })).toBeNull());
});

test('no card for a running export, nor for none', async () => {
  getSummary.mockResolvedValue(withPhotos);
  status.export = { ...pausedExport(), state: 'running' };
  render(Home);
  await screen.findByRole('heading', { level: 1 });
  expect(screen.queryByRole('region', { name: 'Paused export' })).toBeNull();
});

test('after Continue export or Discard… the card goes and focus goes to the page\'s main, not to nothing', async () => {
  getSummary.mockResolvedValue(withPhotos);
  getStatus.mockImplementation(() => new Promise(() => {}));
  const main = document.createElement('main');
  main.tabIndex = -1;
  document.body.append(main);
  status.export = pausedExport();
  resumeExport.mockResolvedValue({ ...pausedExport(), state: 'running' });
  const { unmount } = render(Home);
  const resume = await screen.findByRole('button', { name: 'Continue export' });
  resume.focus();
  await fireEvent.click(resume);
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Paused export' })).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(main));
  unmount();

  status.export = pausedExport();
  cancelExport.mockResolvedValue({ ...pausedExport(), state: 'cancelled', cancel: 'keep' });
  render(Home);
  await fireEvent.click(await screen.findByRole('button', { name: 'Discard…' }));
  await fireEvent.click(within(screen.getByRole('dialog', { name: 'Stop this export?' })).getByRole('button', { name: 'Stop export' }));
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Paused export' })).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(main));
});

test('Custom additions, once it has a species, is the dashed card after the families', async () => {
  getSummary.mockResolvedValue({
    ...summary,
    families: [...summary.families, {
      id: 'U:butterflies', rank: 'additions', name: 'Custom additions', common: null, family: null, source: 'system', active: true,
      species: { got: 1, total: 2 },
    }],
  });
  const { container } = render(Home);

  const link = await screen.findByRole('link', { name: /Custom additions/ });
  expect(link.classList.contains('add-card')).toBe(true);
  expect(link.getAttribute('href')).toBe('#/explore/U%3Abutterflies');
  expect(link.textContent).toContain('2 species · 1 photographed');
  expect(container.querySelector('.grid').lastElementChild).toBe(link);
});

test('the taxonomy cards come after a paused export and before the tally', async () => {
  getSummary.mockResolvedValue(summary);
  getSplits.mockResolvedValue([{ id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', photos: 3, version: '2026-12-01', targets: [] }]);
  status.export = { id: 4, state: 'paused', done: 1, total: 2, pausedBy: 'you', notice: null, path: '/x' };
  status.taxonomyUpdate = { state: 'pending', kind: 'bundle', later: true, done: 0, total: 0, step: 0, steps: 0, continuing: false, waitingFor: null, notice: null };
  status.splitsToSort = 1;
  const { container } = render(Home);
  await screen.findByRole('region', { name: 'Split species to sort' });
  expect([...container.querySelectorAll('section.stop-card')].map((s) => s.getAttribute('aria-label')))
    .toEqual(['Paused export', 'Taxonomy update', 'Split species to sort']);
  const order = screen.getByRole('region', { name: 'Split species to sort' }).compareDocumentPosition(container.querySelector('.tally'));
  expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
});

test('the place note comes after the taxonomy cards and before the tally; its Dismiss sends focus to the page\'s heading', async () => {
  getSummary.mockResolvedValue(summary);
  getStatus.mockImplementation(() => new Promise(() => {}));
  getPlaceUpdate.mockResolvedValue({
    dismissed: false, versions: ['2026-10-20'], appliedAt: '2026-10-20T09:00:00Z',
    counts: { merged: 1, split: 0, retired: 0, renamed: 0, moved: 0, added: 0, elsewhere: 0 }, items: [], mine: [],
    moved: { total: 0, groups: [] }, former: { photos: 0, species: [] }, notChecked: [], rechecking: false,
  });
  dismissPlaceUpdate.mockResolvedValue(null);
  status.taxonomyUpdate = { state: 'pending', kind: 'bundle', later: true, done: 0, total: 0, step: 0, steps: 0, continuing: false, waitingFor: null, notice: null };
  status.placeUpdate = { note: true, formerPhotos: 0, notChecked: 0, pending: 0, moved: 0, rechecking: false, failed: false, lost: 0 };
  const { container } = render(Home);
  const card = await screen.findByRole('region', { name: 'Place names updated' });
  await vi.waitFor(() => expect(card.textContent).toContain('1 place merged.'));
  expect([...container.querySelectorAll('section.stop-card')].map((s) => s.getAttribute('aria-label')))
    .toEqual(['Taxonomy update', 'Place names updated']);
  expect(card.compareDocumentPosition(container.querySelector('.tally')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

  await fireEvent.click(within(card).getByRole('button', { name: 'Dismiss' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(screen.getByRole('heading', { level: 1, name: '0 species photographed' })));
  expect(screen.queryByRole('region', { name: 'Place names updated' })).toBeNull();
});

const backupRun = (over = {}) => ({ id: 2, state: 'paused', done: 3210, total: 12400, pausedBy: 'closed', notice: null, path: '/Volumes/B/Butterfly_Atlas_backup_2026-10-21', ...over });

test('a backup the app\'s closing stopped shows its card on Home with Continue and Discard…, and nothing restarts by itself', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue({ current: null, past: [] });
  status.backup = backupRun();
  render(Home);
  const card = await screen.findByRole('region', { name: /Backup stopped — the app was closed/ });
  expect(within(card).getByRole('button', { name: 'Continue' })).toBeTruthy();
  expect(within(card).getByRole('button', { name: 'Discard…' })).toBeTruthy();
  expect(resumeBackup).not.toHaveBeenCalled();
  await fireEvent.click(within(card).getByRole('button', { name: 'Discard…' }));
  expect(screen.getByRole('dialog', { name: 'Stop this backup?' })).toBeTruthy();
  expect(cancelBackup).not.toHaveBeenCalled();
});

test('a running backup shows its progress card on Home', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue({ current: null, past: [] });
  status.backup = backupRun({ state: 'running', pausedBy: null });
  render(Home);
  expect(await screen.findByText('Backing up your library — 3,210 of 12,400 photos')).toBeTruthy();
});

test('no backup, no card', async () => {
  getSummary.mockResolvedValue(summary);
  render(Home);
  await screen.findByRole('heading', { name: '0 species photographed' });
  expect(screen.queryByText(/Backing up|Backup stopped|Backup paused/)).toBeNull();
});

const backupsOf = (over = {}) => ({
  asked: true, preference: 'remind', lastAt: '2026-10-09T10:00:00Z', lastWhat: 'everything', lastUnread: 0, daysSince: 12, due: true, current: null, past: [], ...over,
});

test('when a backup is due, one quiet line says how long ago the last was, with Back up now', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf());
  render(Home);
  expect(await screen.findByText('Last backup: 12 days ago.')).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Back up now' })).toBeTruthy();
});

test('"Never backed up." when there is none; what the last backup left out is said in the line', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ lastAt: null, lastWhat: null, daysSince: null }));
  const { unmount } = render(Home);
  expect(await screen.findByText('Never backed up.')).toBeTruthy();
  unmount();
  getBackups.mockResolvedValue(backupsOf({ lastUnread: 3 }));
  render(Home);
  expect(await screen.findByText('Last backup: 12 days ago. 3 photos could not be copied.')).toBeTruthy();
});

test('no line when a backup is not due (including No backup needed), and none while a backup has its card', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ preference: 'none', due: false }));
  const { unmount } = render(Home);
  await screen.findByRole('heading', { name: '0 species photographed' });
  await vi.waitFor(() => expect(getBackups).toHaveBeenCalled());
  expect(screen.queryByRole('button', { name: 'Back up now' })).toBeNull();
  unmount();

  getBackups.mockResolvedValue(backupsOf());
  status.backup = backupRun();
  render(Home);
  await screen.findByRole('region', { name: /Backup stopped/ });
  expect(screen.queryByRole('button', { name: 'Back up now' })).toBeNull();
});

test('Back up now opens the sheet; closing it gives focus back to the link', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf());
  getFsRoots.mockResolvedValue([]);
  render(Home);
  const link = await screen.findByRole('button', { name: 'Back up now' });
  await fireEvent.click(link);
  const sheet = screen.getByRole('dialog', { name: 'Back up your library' });
  await fireEvent.click(within(sheet).getByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  await vi.waitFor(() => expect(document.activeElement).toBe(link));
});

test('when the sheet closes because a backup started and the link has gone, focus goes to the page, not to nothing', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf());
  getFsRoots.mockResolvedValue([]);
  render(Home);
  await fireEvent.click(await screen.findByRole('button', { name: 'Back up now' }));
  const sheet = screen.getByRole('dialog', { name: 'Back up your library' });
  const main = document.createElement('main');
  main.tabIndex = -1;
  document.body.append(main);
  status.backup = backupRun({ state: 'running', pausedBy: null });
  await vi.waitFor(() => expect(screen.queryByRole('button', { name: 'Back up now' })).toBeNull());
  await fireEvent.click(within(sheet).getByRole('button', { name: 'Cancel' }));
  await vi.waitFor(() => expect(document.activeElement).toBe(main));
  main.remove();
});

test('a library that was never asked gets the preference dialog once: Remind me chosen, Save saves it, and the line follows', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ asked: false, lastAt: null, lastWhat: null, daysSince: null }));
  setBackupPreference.mockResolvedValue(backupsOf({ asked: true, preference: 'less', lastAt: null, lastWhat: null, daysSince: null, due: true }));
  render(Home);
  const dialog = await screen.findByRole('dialog', { name: 'Do you want reminders to back up your library?' });
  expect(within(dialog).getAllByRole('radio').map((r) => [r.labels[0].textContent.trim(), r.checked])).toEqual([
    ['Remind me', true], ['Remind me less often', false], ['No backup needed', false],
  ]);
  expect(dialog.textContent).toContain('You can change this later in Settings');
  await fireEvent.click(within(dialog).getByRole('radio', { name: 'Remind me less often' }));
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
  expect(setBackupPreference).toHaveBeenCalledWith('less');
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(await screen.findByText('Never backed up.')).toBeTruthy();
});

test('closing the preference dialog without saving saves nothing and does not ask again on this page', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ asked: false }));
  render(Home);
  await screen.findByRole('dialog', { name: 'Do you want reminders to back up your library?' });
  await fireEvent.keyDown(window, { key: 'Escape' });
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(setBackupPreference).not.toHaveBeenCalled();
  window.dispatchEvent(new CustomEvent('atlas:library-changed'));
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('closing the preference dialog without saving is not asked again when Home is opened again, only on the next page load', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ asked: false }));
  const first = render(Home);
  const dialog = await screen.findByRole('dialog', { name: 'Do you want reminders to back up your library?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Not now' }));
  expect(screen.queryByRole('dialog')).toBeNull();
  first.unmount();
  render(Home);
  await screen.findByRole('heading', { name: '0 species photographed' });
  await vi.waitFor(() => expect(getBackups).toHaveBeenCalledTimes(2));
  await new Promise((resolve) => setTimeout(resolve, 30));
  expect(screen.queryByRole('dialog', { name: 'Do you want reminders to back up your library?' })).toBeNull();
});

test('a save that fails says why in the dialog and keeps it open', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ asked: false }));
  setBackupPreference.mockRejectedValue(new Error('Butterfly Atlas could not save that. Try again.'));
  render(Home);
  const dialog = await screen.findByRole('dialog', { name: 'Do you want reminders to back up your library?' });
  await fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));
  expect((await within(dialog).findByRole('alert')).textContent).toBe('Butterfly Atlas could not save that. Try again.');
  expect(screen.getByRole('dialog', { name: 'Do you want reminders to back up your library?' })).toBeTruthy();
});

test('the preference dialog waits until no other dialog is open, and while a taxonomy update needs attention', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ asked: false }));
  const other = document.createElement('div');
  other.setAttribute('role', 'dialog');
  document.body.append(other);
  const action = modal(other);
  render(Home);
  await screen.findByRole('heading', { name: '0 species photographed' });
  await vi.waitFor(() => expect(getBackups).toHaveBeenCalled());
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(screen.queryByRole('dialog', { name: 'Do you want reminders to back up your library?' })).toBeNull();
  action.destroy();
  other.remove();
  expect(await screen.findByRole('dialog', { name: 'Do you want reminders to back up your library?' })).toBeTruthy();
});

test('a taxonomy update that is ready holds the preference dialog back', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockResolvedValue(backupsOf({ asked: false }));
  status.taxonomyUpdate = { state: 'pending', later: false };
  render(Home);
  await screen.findByRole('heading', { name: '0 species photographed' });
  await new Promise((resolve) => setTimeout(resolve, 30));
  expect(screen.queryByRole('dialog', { name: 'Do you want reminders to back up your library?' })).toBeNull();
  status.taxonomyUpdate = null;
  expect(await screen.findByRole('dialog', { name: 'Do you want reminders to back up your library?' })).toBeTruthy();
});

test('a backup list that cannot be read leaves Home as it is, with no line and no dialog', async () => {
  getSummary.mockResolvedValue(summary);
  getBackups.mockRejectedValue(new Error('down'));
  render(Home);
  expect(await screen.findByRole('heading', { name: '0 species photographed' })).toBeTruthy();
  await vi.waitFor(() => expect(getBackups).toHaveBeenCalled());
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.queryByRole('button', { name: 'Back up now' })).toBeNull();
});

/* ---------- Phase 7b: what the rescan left out (audit D2) ---------- */

const SEQUENCE = "HEIC image sequences (several frames in one file) aren't supported";
const left = (over = {}) => ({ reason: SEQUENCE, next: 'Save the frame you want as a photo and add that.', count: 1, examples: ['Danaus_chrysippus_[A5]/IMG_1.HEICS'], ...over });

test('Home says which files in the library folders the last rescan did not add: reason, count, examples and what to do, until dismissed (7b review fix U12)', async () => {
  getSummary.mockResolvedValue(summary);
  getStatus.mockResolvedValue({ rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0, rescanLeft: [] });
  status.rescanLeft = [left(), left({
    reason: "Files Butterfly Atlas can't read as photos", next: 'Move them out of the library folder, or replace them with JPEG copies.', count: 3,
    examples: ['Danaus_chrysippus_[A5]/notes.txt', 'Danaus_chrysippus_[A5]/x.bmp'],
  })];
  dismissRescanLeft.mockResolvedValue(null);
  render(Home);

  const note = await screen.findByRole('region', { name: 'Files not added' });
  const lines = [...note.querySelectorAll('p')].map((p) => p.textContent.trim());
  expect(lines.slice(0, 6)).toEqual([
    `1 file in your library folders wasn't added: ${SEQUENCE}.`,
    'e.g. Danaus_chrysippus_[A5]/IMG_1.HEICS',
    'Save the frame you want as a photo and add that.',
    "3 files in your library folders weren't added: Files Butterfly Atlas can't read as photos.",
    'e.g. Danaus_chrysippus_[A5]/notes.txt, Danaus_chrysippus_[A5]/x.bmp',
    'Move them out of the library folder, or replace them with JPEG copies.',
  ]);
  await fireEvent.click(within(note).getByRole('button', { name: 'Dismiss' }));
  expect(dismissRescanLeft).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Files not added' })).toBeNull());
});

test('a Dismiss that fails says why and keeps the note', async () => {
  getSummary.mockResolvedValue(summary);
  getStatus.mockResolvedValue({ rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0, rescanLeft: [left({ count: 2 })] });
  status.rescanLeft = [left({ count: 2 })];
  dismissRescanLeft.mockRejectedValue(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  render(Home);
  const note = await screen.findByRole('region', { name: 'Files not added' });
  await fireEvent.click(within(note).getByRole('button', { name: 'Dismiss' }));
  expect((await within(note).findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.');
  expect(screen.getByRole('region', { name: 'Files not added' })).toBeTruthy();
});

/* ---------- change the library folder: the one-time note after a move (design §6) ---------- */

test('after a move Home says once where the library now is, until Dismiss', async () => {
  getSummary.mockResolvedValue(summary);
  getStatus.mockResolvedValue({ rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0, libraryMoved: null });
  status.libraryMoved = { to: '/Volumes/B/Butterfly_Atlas' };
  dismissMovedNote.mockResolvedValue(null);
  render(Home);

  const note = await screen.findByRole('region', { name: 'Library moved' });
  expect(within(note).getByText('Your library was moved to /Volumes/B/Butterfly_Atlas.')).toBeTruthy();
  await fireEvent.click(within(note).getByRole('button', { name: 'Dismiss' }));
  expect(dismissMovedNote).toHaveBeenCalledOnce();
  await vi.waitFor(() => expect(screen.queryByRole('region', { name: 'Library moved' })).toBeNull());
});

test('a Dismiss of the moved note that fails says why and keeps it; no note without a move', async () => {
  getSummary.mockResolvedValue(summary);
  getStatus.mockResolvedValue({ rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0, libraryMoved: { to: '/V/A' } });
  status.libraryMoved = { to: '/V/A' };
  dismissMovedNote.mockRejectedValue(new Error('Butterfly Atlas could not reach its service. Close the app and open it again.'));
  const { unmount } = render(Home);
  const note = await screen.findByRole('region', { name: 'Library moved' });
  await fireEvent.click(within(note).getByRole('button', { name: 'Dismiss' }));
  expect((await within(note).findByRole('alert')).textContent).toBe('Butterfly Atlas could not reach its service. Close the app and open it again.');
  expect(screen.getByRole('region', { name: 'Library moved' })).toBeTruthy();
  unmount();
  status.libraryMoved = null;
  render(Home);
  await screen.findByRole('heading', { name: 'Families' });
  expect(screen.queryByRole('region', { name: 'Library moved' })).toBeNull();
});

test('Home\'s recent photos show the HEIC badge and "Can\'t show this photo", with one line under them (7b review fix U8)', async () => {
  status.heic = { state: 'missing', decoder: null, system: 'mac', reason: 'failed' };
  const heic = { ...photo(3), fileName: 'Danaus_chrysippus_03.heic', fileType: 'heic', unreadable: true };
  getSummary.mockResolvedValue({ ...withPhotos, recent: [{ photo: heic, taxon: plainTiger }, { photo: photo(1), taxon: plainTiger }] });
  getStatus.mockResolvedValue({ rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0, heic: status.heic });
  render(Home);
  const section = (await screen.findByRole('heading', { name: 'Recently added' })).closest('section');
  const [first, second] = within(section).getAllByRole('link');
  expect(first.querySelector('img')).toBeNull();
  expect(first.querySelector('.cant').textContent).toBe("Can't show this photo");
  expect(first.querySelector('.tb').textContent).toBe('HEIC');
  expect(second.querySelector('img')).toBeTruthy();
  expect(within(section).getByText('Some photos are HEIC, which this device can\'t show yet. See Settings → About.')).toBeTruthy();
});

test('on another system Home\'s line says there is no decoder Butterfly Atlas can use (7b review fix U11)', async () => {
  status.heic = { state: 'missing', decoder: null, system: 'other', reason: 'absent' };
  const heic = { ...photo(3), fileType: 'heic', unreadable: true };
  getSummary.mockResolvedValue({ ...withPhotos, recent: [{ photo: heic, taxon: plainTiger }] });
  getStatus.mockResolvedValue({ rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0, heic: status.heic });
  render(Home);
  expect(await screen.findByText('Some photos are HEIC, and this device has no HEIC decoder Butterfly Atlas can use. See Settings → About.')).toBeTruthy();
});
