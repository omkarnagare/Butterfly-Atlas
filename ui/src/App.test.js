import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, expect, test, vi } from 'vitest';
import App from './App.svelte';
import { resetStatus } from './lib/status.svelte.js';
import { resetSessionLost } from './lib/api.js';
import { LOCK_NOTE, TAB_NOTE, resetSession, session } from './lib/session.svelte.js';

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  resetStatus();
  resetSessionLost();
  resetSession();
});

/** Answers ./api/<key>… with routes[key]; the longest matching key wins. */
function stubApi(routes) {
  const keys = Object.keys(routes).sort((a, b) => b.length - a.length);
  const fetchMock = vi.fn(async (url) => {
    const key = keys.find((k) => url.startsWith(`./api/${k}`));
    if (!key) return { ok: false, status: 404, json: async () => ({ error: { code: 'not_found', message: `No stub for ${url}` } }) };
    return { ok: true, status: 200, json: async () => routes[key] };
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

const unconfigured = { configured: false, suggestedFolder: '/home/me/Documents/Butterfly_Atlas', dataFolder: null, problem: null };
const configured = { configured: true, suggestedFolder: '/home/me/Documents/Butterfly_Atlas', dataFolder: '/home/me/Atlas', problem: null };
const summary = {
  group: { id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null, family: null, active: true, path: [],
    species: { got: 0, total: 1139 }, ranks: [], synonyms: [], searchWords: [], folder: null },
  families: [],
};

test('asks where to keep the library on first run', async () => {
  stubApi({ setup: unconfigured });
  render(App);

  expect(await screen.findByRole('heading', { name: 'Where should Butterfly Atlas keep your photos?' })).toBeTruthy();
});

test('shows the home screen once a library is set up', async () => {
  stubApi({ setup: configured, summary });
  render(App);

  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Butterfly Atlas home' })).toBeTruthy();
});

test('the page\'s main region can take focus, for an overlay that goes away with what opened it (a discarded export)', async () => {
  stubApi({ setup: configured, summary });
  render(App);
  await screen.findByRole('heading', { name: 'Families' });
  const main = screen.getByRole('main');
  expect(main.getAttribute('tabindex')).toBe('-1');
});

test('explains the problem when the service cannot be reached', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch'); }));
  render(App);

  expect(await screen.findByText('Butterfly Atlas could not reach its service. Close the app and open it again.')).toBeTruthy();
});

test('opens the page named in the address', async () => {
  window.location.hash = '#/explore/A1';
  const fetchMock = stubApi({
    setup: configured,
    'taxa/A1/children': { children: [] },
    'taxa/A1': { id: 'A1', rank: 'family', name: 'Nymphalidae', common: 'Brush-footed Butterflies', family: 'Nymphalidae',
      active: true, path: [{ id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null }],
      species: { got: 0, total: 400 }, ranks: [], synonyms: [], searchWords: [], folder: null },
  });
  render(App);

  expect(await screen.findByRole('heading', { level: 1, name: 'Nymphalidae' })).toBeTruthy();
  expect(fetchMock.mock.calls.map((call) => call[0])).toContain('./api/taxa/A1');
});

test('Ctrl+K opens search', async () => {
  stubApi({ setup: configured, summary });
  render(App);
  await screen.findByRole('heading', { name: 'Families' });

  await fireEvent.keyDown(window, { key: 'k', ctrlKey: true });

  expect(await screen.findByRole('dialog', { name: 'Search' })).toBeTruthy();
});

test('the search palette gives scroll, inertness and focus back when it closes', async () => {
  stubApi({ setup: configured, summary });
  const { container } = render(App);
  await screen.findByRole('heading', { name: 'Families' });
  const opener = document.createElement('button');
  document.body.append(opener);
  opener.focus();

  await fireEvent.keyDown(opener, { key: 'k', ctrlKey: true, bubbles: true });
  await screen.findByRole('dialog', { name: 'Search' });
  expect(document.documentElement.style.overflow).toBe('hidden');

  await fireEvent.keyDown(window, { key: 'Escape' });
  await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Search' })).toBeNull());
  await Promise.resolve();
  expect(document.documentElement.style.overflow).toBe('');
  expect(container.querySelector('[inert]')).toBeNull();
  expect(document.activeElement).toBe(opener);
  opener.remove();
});

test('opening a photo keeps the species page where it was', async () => {
  window.location.hash = '#/species/A5';
  const scrollTo = vi.fn();
  vi.stubGlobal('scrollTo', scrollTo);
  stubApi({
    setup: configured,
    'taxa/A5/photos': { photos: [] },
    'taxa/A5': { id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true,
      path: [], species: { got: 0, total: 1 }, ranks: [], synonyms: [], searchWords: [], folder: null,
      photoCount: 0, stages: { egg: false, larva: false, pupa: false, adult: false }, cover: null },
    tags: { tags: [] },
  });
  render(App);
  await screen.findByRole('heading', { level: 1, name: 'Danaus chrysippus' });

  // The species has no photo 12, so the page puts its own address back: two moves within one page.
  window.location.hash = '#/species/A5/photo/12';
  await vi.waitFor(() => expect(window.location.hash).toBe('#/species/A5'));
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(scrollTo).not.toHaveBeenCalled();

  window.location.hash = '#/explore';
  await vi.waitFor(() => expect(scrollTo).toHaveBeenCalledOnce());
});

test('Recently deleted opens from its address and the footer links to it', async () => {
  window.location.hash = '#/deleted';
  stubApi({ setup: configured, deleted: { photos: [] }, status: { rescanning: false, rescan: null, previews: { pending: 0, done: 0 }, deletedCount: 2 } });
  render(App);

  expect(await screen.findByRole('heading', { level: 1, name: 'Recently deleted' })).toBeTruthy();
  expect(await screen.findByRole('link', { name: 'Recently deleted (2)' })).toBeTruthy();
});

test('Import folder opens from the top bar; with no imports the folder browser opens straight away', async () => {
  window.location.hash = '#/import';
  stubApi({ setup: configured, imports: { imports: [] }, 'fs/roots': { roots: [] } });
  render(App);

  expect(await screen.findByRole('dialog', { name: 'Choose a folder to import' })).toBeTruthy();
  expect(screen.getByRole('heading', { level: 1, name: 'Import folder' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Import folder' }).getAttribute('aria-current')).toBe('page');
});

test('the top bar marks Import folder, not Home, on the imports and on one import', async () => {
  const imp = { id: 3, rootPath: '/p/B', rootName: 'B', state: 'open', createdAt: '2026-10-12T08:00:00Z', updatedAt: '2026-10-12T08:00:00Z',
    folderMissing: false, paused: false, phase: 'idle', progress: null, stopped: null, notice: null,
    counts: { total: 0, photos: 0, reading: 0, unassigned: 0, suggested: 0, assigned: 0, skipped: 0, problems: 0, filed: 0, missing: 0 } };
  stubApi({ setup: configured, 'imports/3/items': { version: 1, items: [], taxa: {} }, 'imports/3': imp, imports: { imports: [imp] }, tags: [], status: {} });
  for (const hash of ['#/import', '#/import/3']) {
    window.location.hash = hash;
    const { unmount } = render(App);
    const bar = () => document.querySelector('header.bar');
    // from the first paint (while starting up) to the page itself
    expect(bar().querySelector('a.imp').getAttribute('aria-current')).toBe('page');
    expect(bar().querySelector('a[href="#/"].navb').getAttribute('aria-current')).toBeNull();
    await screen.findByRole('heading', { level: 1 });
    expect(bar().querySelector('a.imp').getAttribute('aria-current')).toBe('page');
    expect(bar().querySelector('a[href="#/"].navb').getAttribute('aria-current')).toBeNull();
    unmount();
  }
});

test('Export opens from its address with its filter, and the top bar marks it', async () => {
  window.location.hash = '#/export?status=not';
  const fetchMock = stubApi({
    setup: configured, tags: { tags: [] }, status: {},
    'filter/count': { photos: 0, species: 212, photographedSpecies: 0, bytes: 0, manualSpecies: false,
      filter: { query: 'status=not', taxa: [], tags: [], tagMode: 'any', places: [], status: 'not', source: 'all', stages: null } },
    'filter/species': { species: [] },
  });
  render(App);

  expect(await screen.findByRole('heading', { level: 1, name: 'Export' })).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Export' }).getAttribute('aria-current')).toBe('page');
  expect(await screen.findByText('212 species not yet photographed', { selector: '.sr-only' })).toBeTruthy();
  expect(fetchMock.mock.calls.some(([url]) => url === './api/filter/count?status=not')).toBe(true);
});

test('opening a photo of the Export results neither reloads the results nor moves the page', async () => {
  window.location.hash = '#/export?status=photographed&view=photos';
  const scrollTo = vi.fn();
  vi.stubGlobal('scrollTo', scrollTo);
  const photo = { id: 7, taxonId: 'A5', fileName: 'Danaus_chrysippus_07.jpg', relPath: 'images/x/Danaus_chrysippus_07.jpg', tags: [], width: 4, height: 3,
    isCover: false, place: null, species: { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger' } };
  const fetchMock = stubApi({
    setup: configured, tags: { tags: [] }, status: {},
    'filter/count': { photos: 1, species: 1, photographedSpecies: 1, bytes: 10, manualSpecies: false,
      filter: { query: 'status=photographed', taxa: [], tags: [], tagMode: 'any', places: [], status: 'photographed', source: 'all', stages: null } },
    'filter/photos': { photos: [photo], next: null },
  });
  render(App);
  await screen.findByRole('link', { name: 'Open Plain Tiger, Danaus_chrysippus_07.jpg' });
  const asked = () => fetchMock.mock.calls.filter(([url]) => url.startsWith('./api/filter/')).length;
  const before = asked();

  window.location.hash = '#/export/photo/7?status=photographed';
  expect(await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' })).toBeTruthy();
  window.location.hash = '#/export?status=photographed&view=photos';
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await new Promise((resolve) => setTimeout(resolve, 250));
  expect(asked()).toBe(before);
  expect(scrollTo).not.toHaveBeenCalled();
});

const idleStatus = { rescanning: false, rescan: null, previews: { pending: 0, done: 0 }, deletedCount: 0 };
const update = (over = {}) => ({
  ...idleStatus,
  taxonomyUpdate: { state: 'pending', kind: 'bundle', later: false, done: 0, total: 0, step: 0, steps: 0, continuing: false, waitingFor: null, notice: null, ...over },
  splitsToSort: 0,
  customMatches: 0,
});

test('a pending taxonomy update opens first instead of Home, once per page load', async () => {
  vi.stubGlobal('scrollTo', vi.fn());
  const fetchMock = stubApi({ setup: configured, summary, status: update() });
  const statusReads = () => fetchMock.mock.calls.filter(([url]) => url === './api/status').length;
  render(App);

  await vi.waitFor(() => expect(window.location.hash).toBe('#/update'));
  const before = statusReads();
  window.location.hash = '#/';
  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  // Home's address reads the status again, still pending: it is not offered a second time.
  await vi.waitFor(() => expect(statusReads()).toBeGreaterThan(before));
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(window.location.hash).toBe('#/');
});

test('a first status that cannot be read does not use up the offer: the next one read decides', async () => {
  vi.stubGlobal('scrollTo', vi.fn());
  const fetchMock = stubApi({ setup: configured, summary, status: update() });
  // The status cannot be read until the page has opened.
  let readable = false;
  const answer = fetchMock.getMockImplementation();
  fetchMock.mockImplementation(async (url, init) => (url === './api/status' && !readable
    ? { ok: false, status: 500, json: async () => ({ error: { code: 'failed', message: 'Not now.' } }) }
    : answer(url, init)));
  render(App);
  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  expect(window.location.hash).toBe('');

  readable = true;
  window.location.hash = '#/';
  await vi.waitFor(() => expect(window.location.hash).toBe('#/update'));
});

test('a pending update put off with Later, or an address other than Home, opens as asked', async () => {
  vi.stubGlobal('scrollTo', vi.fn());
  stubApi({ setup: configured, summary, status: update({ later: true }) });
  const { unmount } = render(App);
  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  expect(window.location.hash).toBe('');
  unmount();

  window.location.hash = '#/explore/A1';
  stubApi({
    setup: configured, status: update(),
    'taxa/A1/children': { children: [] },
    'taxa/A1': { id: 'A1', rank: 'family', name: 'Nymphalidae', common: null, family: 'Nymphalidae', active: true,
      path: [{ id: 'G:butterflies', rank: 'group', name: 'Butterflies', common: null }], species: { got: 0, total: 400 }, ranks: [], synonyms: [], searchWords: [], folder: null },
  });
  render(App);
  expect(await screen.findByRole('heading', { level: 1, name: 'Nymphalidae' })).toBeTruthy();
  expect(window.location.hash).toBe('#/explore/A1');
});

test('an update continuing from the last run shows the launch line with its bar, asking again, then the page asked for', async () => {
  vi.stubGlobal('scrollTo', vi.fn());
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
  const routes = { setup: configured, summary, status: update({ state: 'running', continuing: true, done: 120, total: 340, step: 1, steps: 1 }) };
  stubApi(routes);
  render(App);

  const line = await screen.findByText('Finishing the taxonomy update… 120 of 340');
  expect(line.closest('main')).toBeTruthy();
  // the status region holds a fixed sentence; the numbers beside it are hidden from screen readers
  expect(line.getAttribute('aria-hidden')).toBe('true');
  expect(document.querySelector('main [role="status"]').textContent).toBe('Finishing the taxonomy update.');
  const bar = screen.getByRole('progressbar', { name: 'Photos moved' });
  expect([bar.getAttribute('max'), bar.getAttribute('value')]).toEqual(['340', '120']);
  expect(screen.queryByRole('heading', { name: 'Families' })).toBeNull();
  expect(document.querySelector('footer')).toBeNull();

  routes.status = update({ state: 'running', continuing: true, done: 300, total: 340, step: 1, steps: 1 });
  await vi.advanceTimersByTimeAsync(2000);
  expect(await screen.findByText('Finishing the taxonomy update… 300 of 340')).toBeTruthy();
  expect(document.querySelector('main [role="status"]').textContent).toBe('Finishing the taxonomy update.');

  routes.status = { ...idleStatus, taxonomyUpdate: null };
  await vi.advanceTimersByTimeAsync(2000);
  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  expect(window.location.hash).toBe('');
  vi.restoreAllMocks();
});

test('the app opens even when the status cannot be read', async () => {
  stubApi({ setup: configured, summary });
  render(App);
  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
});

test('a split\'s photo opens over its grid and closes back to its tile, keeping the page where it was', async () => {
  window.location.hash = '#/split/A5';
  const scrollTo = vi.fn();
  vi.stubGlobal('scrollTo', scrollTo);
  const photo = (id) => ({ id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.jpg`, relPath: 'images/x', tags: [], width: 4, height: 3, isCover: false, place: null, v: 'x' });
  stubApi({
    setup: configured, status: {}, tags: { tags: [] },
    'splits/A5': {
      taxon: { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', photos: 2, version: '2026-12-01', targets: [] },
      targets: [{ id: 'A90', name: 'Danaus alcippus', common: null, family: 'Nymphalidae', species: { got: 0, total: 1 } }],
      left: 2, total: 2, photos: [photo(1), photo(2)],
    },
  });
  render(App);
  await screen.findByRole('grid', { name: 'Photos of Danaus chrysippus to sort' });
  const tile = document.querySelector('.sg [data-key="2"]');
  tile.focus();
  await fireEvent.keyDown(tile, { key: ' ' });
  expect(await screen.findByRole('dialog', { name: 'Photo viewer: Plain Tiger' })).toBeTruthy();
  await fireEvent.keyDown(window, { key: 'Escape' });
  await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  await vi.waitFor(() => expect(document.activeElement).toBe(document.querySelector('.sg [data-key="2"]')));
  expect(scrollTo).not.toHaveBeenCalled();
  expect(screen.getByRole('link', { name: 'Explore' }).getAttribute('aria-current')).toBe('page');
});

test('the search keys do nothing while the launch line shows, and nothing opens once it has gone', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.stubGlobal('scrollTo', vi.fn());
  const routes = { setup: configured, summary, status: update({ state: 'running', continuing: true, done: 1, total: 2, step: 1, steps: 1 }) };
  stubApi(routes);
  render(App);
  await screen.findByRole('progressbar', { name: 'Photos moved' });
  await fireEvent.keyDown(window, { key: '/' });
  await fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
  expect(screen.queryByRole('dialog', { name: 'Search' })).toBeNull();

  routes.status = { ...idleStatus, taxonomyUpdate: null };
  await vi.advanceTimersByTimeAsync(2000);
  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  expect(screen.queryByRole('dialog', { name: 'Search' })).toBeNull();
});

test('an update continuing at launch that pauses opens the update screen', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.stubGlobal('scrollTo', vi.fn());
  const routes = {
    setup: configured, summary, status: update({ state: 'running', continuing: true, done: 1, total: 2, step: 1, steps: 1 }),
    update: { state: 'paused', kind: 'bundle', later: false, from: 'a', to: 'b', steps: ['b'], summary: null, progress: null,
      notice: { code: 'drive_gone', message: "The drive with your library can't be found." }, result: null },
  };
  stubApi(routes);
  render(App);
  await screen.findByRole('progressbar', { name: 'Photos moved' });
  routes.status = update({ state: 'paused', continuing: false, notice: { code: 'drive_gone', message: "The drive with your library can't be found." } });
  await vi.advanceTimersByTimeAsync(2000);
  await vi.waitFor(() => expect(window.location.hash).toBe('#/update'));
  expect(await screen.findByRole('heading', { level: 1, name: 'The update is paused' })).toBeTruthy();
});

test('the launch line says when Butterfly Atlas isn\'t answering, and goes back to normal when it answers', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.stubGlobal('scrollTo', vi.fn());
  const routes = { setup: configured, summary, status: update({ state: 'running', continuing: true, done: 1, total: 2, step: 1, steps: 1 }) };
  const fetchMock = stubApi(routes);
  render(App);
  await screen.findByRole('progressbar', { name: 'Photos moved' });
  const answer = fetchMock.getMockImplementation();
  fetchMock.mockImplementation(async (url, init) => (url === './api/status' ? Promise.reject(new TypeError('Failed to fetch')) : answer(url, init)));
  await vi.advanceTimersByTimeAsync(6000);
  expect((await screen.findByRole('alert')).textContent).toContain("Butterfly Atlas isn't answering.");
  fetchMock.mockImplementation(answer);
  await vi.advanceTimersByTimeAsync(2000);
  await vi.waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
});

test('an update already paused when the page loads opens the update screen, once', async () => {
  vi.stubGlobal('scrollTo', vi.fn());
  stubApi({
    setup: configured, summary, status: update({ state: 'paused', notice: { code: 'drive_gone', message: "The drive with your library can't be found." } }),
    update: { state: 'paused', kind: 'bundle', later: false, from: 'a', to: 'b', steps: ['b'], summary: null, progress: null,
      notice: { code: 'drive_gone', message: "The drive with your library can't be found." }, result: null, last: null },
  });
  render(App);
  await vi.waitFor(() => expect(window.location.hash).toBe('#/update'));
  expect(await screen.findByRole('heading', { level: 1, name: 'The update is paused' })).toBeTruthy();
  window.location.hash = '#/';
  expect(await screen.findByRole('heading', { name: 'Families' })).toBeTruthy();
  await new Promise((resolve) => setTimeout(resolve, 20));
  expect(window.location.hash).toBe('#/');
});

test('while the library\'s drive is full or gone, every screen says so at the top; it goes when the status clears it', async () => {
  vi.stubGlobal('scrollTo', vi.fn());
  const message = 'The drive with your library is full. Free some space, then try again.';
  const routes = { setup: configured, summary, status: { ...idleStatus, library: { code: 'drive_full', message } } };
  stubApi(routes);
  render(App);
  await screen.findByRole('heading', { name: 'Families' });
  const banner = screen.getByRole('alert');
  expect(banner.textContent.trim()).toBe(message);
  expect(banner.closest('main')).toBeTruthy();
  expect(banner.classList.contains('stop-card')).toBe(true);

  routes.status = idleStatus;
  window.location.hash = '#/';
  await vi.waitFor(() => expect(document.querySelector('.lib-banner')).toBeNull());
});

test('a page that couldn\'t load while the drive was full says so briefly with Try again, and reads again by itself once the drive is back', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.stubGlobal('scrollTo', vi.fn());
  const message = 'The drive with your library is full. Free some space, then try again.';
  const group = { ...summary.group, ranks: [] };
  const plainTiger = { id: 'A5', rank: 'species', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', active: true,
    path: [], species: { got: 0, total: 1 }, ranks: [], synonyms: [], searchWords: [], folder: null,
    photoCount: 0, stages: { egg: false, larva: false, pupa: false, adult: false }, cover: null };
  const routes = {
    setup: configured, summary, tags: { tags: [] }, 'fs/roots': { roots: [] },
    'taxa/G%3Abutterflies/children': { children: [] }, 'taxa/G%3Abutterflies': group,
    'taxa/A5/photos': { photos: [] }, 'taxa/A5': plainTiger,
    deleted: { photos: [] }, imports: { imports: [] },
    update: { state: 'none', from: '2026-12-01', to: '2026-12-01', steps: [], summary: null, progress: null, notice: null, result: null, last: null },
    'splits/A5': { taxon: { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger', family: 'Nymphalidae', photos: 0, version: 'v', targets: [] },
      targets: [], left: 0, total: 1, photos: [] },
  };
  const keys = Object.keys(routes).sort((a, b) => b.length - a.length);
  let full = true;
  vi.stubGlobal('fetch', vi.fn(async (url) => {
    if (url.startsWith('./api/status')) return { ok: true, status: 200, json: async () => ({ ...idleStatus, library: full ? { code: 'drive_full', message } : null }) };
    const key = keys.find((k) => url.startsWith(`./api/${k}`));
    if (full && key !== 'setup') return { ok: false, status: 507, json: async () => ({ error: { code: 'drive_full', message } }) };
    if (!key) return { ok: false, status: 404, json: async () => ({ error: { code: 'not_found', message: `No stub for ${url}` } }) };
    return { ok: true, status: 200, json: async () => routes[key] };
  }));
  const pages = [
    ['#/', { name: 'Families' }], ['#/explore', { level: 1, name: 'Butterflies' }], ['#/species/A5', { level: 1, name: 'Danaus chrysippus' }],
    ['#/deleted', { level: 1, name: 'Recently deleted' }], ['#/import', { level: 1, name: 'Import folder' }],
    ['#/update', { level: 1, name: 'Your taxonomy is up to date.' }], ['#/split/A5', { name: 'All sorted.' }],
  ];
  for (const [hash, heading] of pages) {
    full = true;
    resetStatus();
    window.location.hash = hash;
    const { unmount } = render(App);
    const problem = await screen.findByText("Couldn't load this page.");
    expect(problem.closest('main')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeTruthy();
    // the banner says why, once
    expect(await screen.findAllByText(message)).toHaveLength(1);

    full = false;
    await vi.advanceTimersByTimeAsync(5000);
    expect(await screen.findByRole('heading', heading)).toBeTruthy();
    expect(screen.queryByText("Couldn't load this page.")).toBeNull();
    expect(screen.queryByText(message)).toBeNull();
    unmount();
  }
});

test('an answer 401 no_session replaces the page with the full-screen message and nothing is asked again', async () => {
  const words = 'To open Butterfly Atlas, use its icon, or New window in a Butterfly Atlas window.';
  const fetchMock = vi.fn(async () => ({ ok: false, status: 401, json: async () => ({ error: { code: 'no_session', message: words } }) }));
  vi.stubGlobal('fetch', fetchMock);
  render(App);

  const message = await screen.findByRole('alert');
  expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(words);
  // the tab-mode line stays, muted
  expect(message.querySelector('p.muted').textContent).toBe('If you started it in a browser tab, reload from the address the app printed.');
  expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull();
  expect(screen.queryByText(/could not reach its service/)).toBeNull();
  const asked = fetchMock.mock.calls.length;
  await new Promise((resolve) => setTimeout(resolve, 50));
  expect(fetchMock.mock.calls.length).toBe(asked);
});

test('no already-running notice: a second launch opens one more window, and nothing is shown for it', async () => {
  window.history.replaceState(null, '', '/?notice=already-running');
  stubApi({ setup: configured, summary, session: { mode: 'window', version: '0.1.0', dataFolder: '/d', configFolder: '/c', logFolder: '/c/logs', lockWarning: false } });
  render(App);
  await screen.findByRole('navigation', { name: 'Main' });
  expect(screen.queryByRole('status', { name: 'Already running' })).toBeNull();
  expect(screen.queryByText(/already running/)).toBeNull();
  window.history.replaceState(null, '', '/');
});

test('the tab and drive notes are shown on the launch line, where there is no footer', async () => {
  session.tabNote = TAB_NOTE;
  session.lockNote = LOCK_NOTE;
  stubApi({ setup: configured, summary, status: update({ state: 'running', continuing: true, done: 1, total: 2, step: 1, steps: 1 }), session: { mode: 'tab', lockWarning: true } });
  render(App);
  await screen.findByText('Finishing the taxonomy update… 1 of 2');
  expect(screen.getByText(TAB_NOTE)).toBeTruthy();
  expect(screen.getByText(LOCK_NOTE)).toBeTruthy();
});

test('reads the session once the library is open, and the footer carries its standing notes', async () => {
  const fetchMock = stubApi({
    setup: configured, summary, status: { rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0 },
    session: { mode: 'tab', version: '0.1.0', dataFolder: '/d', configFolder: '/c', logFolder: '/c/logs', lockWarning: true },
  });
  render(App);

  expect(await screen.findByText('Running in a browser tab. Closing it quits the app.')).toBeTruthy();
  expect(screen.getByText('This drive cannot prevent a second copy from opening this library. Open only one.')).toBeTruthy();
  expect(fetchMock.mock.calls.filter((call) => call[0] === './api/session')).toHaveLength(1);
});

test('#/settings opens the Settings screen', async () => {
  window.location.hash = '#/settings';
  stubApi({
    setup: configured,
    backup: { asked: true, preference: 'remind', lastAt: null, lastWhat: null, lastUnread: 0, daysSince: null, due: true, current: null, past: [] },
    session: { mode: 'window', version: '0.1.0', dataFolder: '/d', configFolder: '/c', logFolder: '/c/logs', lockWarning: false },
  });
  render(App);

  expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeTruthy();
  expect(await screen.findByText('Never backed up')).toBeTruthy();
  expect(screen.getByRole('link', { name: 'Settings' }).getAttribute('aria-current')).toBe('page');
});
