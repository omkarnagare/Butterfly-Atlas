import { fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import HeicBar from './HeicBar.svelte';
import Gallery from './Gallery.svelte';
import PhotoResults from './PhotoResults.svelte';
import { checkHeic, getStatus } from './api.js';
import { refreshStatus, resetStatus, status } from './status.svelte.js';

vi.mock('./api.js', () => ({
  checkHeic: vi.fn(), getStatus: vi.fn(), getBackup: vi.fn(), photoThumb: (photo) => `./api/photos/${photo.id}/thumb?v=1`,
}));

beforeEach(() => {
  vi.resetAllMocks();
  resetStatus();
});
afterEach(() => resetStatus());

const linux = { state: 'missing', decoder: null, system: 'linux', reason: 'absent' };
const LINUX_NOTE = "This computer can't show HEIC photos yet. Install heif-convert from your distribution's packages, then choose Check again in Settings → About. Your photo is safely in the library.";

test('the bar says its line, How shows the install note, and Check again asks (Phase 7b audit D1)', async () => {
  status.heic = linux;
  checkHeic.mockResolvedValue({ state: 'available', decoder: 'heif-convert', system: 'linux', reason: null });
  render(HeicBar, { line: "This computer can't show HEIC photos yet." });

  expect(screen.getByText("This computer can't show HEIC photos yet.")).toBeTruthy();
  const how = screen.getByRole('button', { name: 'How' });
  expect(how.getAttribute('aria-expanded')).toBe('false');
  expect(screen.queryByText(LINUX_NOTE)).toBeNull();
  await fireEvent.click(how);
  expect(how.getAttribute('aria-expanded')).toBe('true');
  expect(screen.getByText(LINUX_NOTE)).toBeTruthy();

  await fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
  expect(await screen.findByText('HEIC photos can be shown now. Making their thumbnails…')).toBeTruthy();
  expect(checkHeic).toHaveBeenCalledOnce();
});

const tags = [{ id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 0 }];
const photo = (id, fileType) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.${fileType}`, relPath: '', tags: [], addedAt: 'v', width: 3, height: 2, isCover: false,
  place: null, fileType, unreadable: fileType === 'heic', species: { id: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger' },
});

test('a species gallery showing HEIC photos says when this computer can\'t show them, and only then', async () => {
  status.heic = linux;
  const { rerender } = render(Gallery, { photos: [photo(1, 'jpeg'), photo(2, 'heic')], tags });
  expect(screen.getByText("This computer can't show HEIC photos yet.")).toBeTruthy();
  expect(screen.getByRole('button', { name: 'Check again' })).toBeTruthy();

  await rerender({ photos: [photo(1, 'jpeg')], tags });
  expect(screen.queryByText("This computer can't show HEIC photos yet.")).toBeNull();
  await rerender({ photos: [photo(1, 'jpeg'), photo(2, 'heic')], tags });
  status.heic = { state: 'available', decoder: 'heif-convert', system: 'linux', reason: null };
  await vi.waitFor(() => expect(screen.queryByText("This computer can't show HEIC photos yet.")).toBeNull());
});

test('the Export results\' photos say it too', () => {
  status.heic = linux;
  render(PhotoResults, { photos: [photo(2, 'heic')], query: 'types=heic', onMore: vi.fn() });
  expect(screen.getByText("This computer can't show HEIC photos yet.")).toBeTruthy();
  expect(screen.getByRole('button', { name: 'How' })).toBeTruthy();
});

/* ---------- review fix U3: Check again's result outlives a poll that says checking ---------- */

const idle = { rescanning: false, previews: { pending: 0, done: 0 }, deletedCount: 0 };

test('a gallery bar stays, with its Check again, while the check it started runs; its result stays until the state moves on (U3)', async () => {
  getStatus.mockResolvedValue({ ...idle, heic: linux });
  await refreshStatus();
  let answer;
  checkHeic.mockReturnValue(new Promise((resolve) => { answer = resolve; }));
  render(Gallery, { photos: [photo(2, 'heic')], tags });

  await fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
  // A poll meanwhile says the server is checking: the bar does not go.
  getStatus.mockResolvedValue({ ...idle, heic: { ...linux, state: 'checking', reason: null } });
  await refreshStatus();
  expect(screen.getByText("This computer can't show HEIC photos yet.")).toBeTruthy();
  expect(screen.getByText('Checking for a HEIC decoder…')).toBeTruthy();

  // The check answers that it is still running: said, and kept while polls say the same.
  answer({ ...linux, state: 'checking', reason: null });
  expect(await screen.findByText('HEIC photos: checking for a decoder…')).toBeTruthy();
  await refreshStatus();
  expect(screen.getByText('HEIC photos: checking for a decoder…')).toBeTruthy();
  expect(status.heicCheck.result).toBe('HEIC photos: checking for a decoder…');

  // It ends missing: the old result is stale and goes; the bar is back as it was.
  getStatus.mockResolvedValue({ ...idle, heic: linux });
  await refreshStatus();
  await vi.waitFor(() => expect(screen.queryByText('HEIC photos: checking for a decoder…')).toBeNull());
  expect(status.heicCheck.result).toBe('');
  expect(screen.getByText("This computer can't show HEIC photos yet.")).toBeTruthy();
});

test('without a check of its own, a bar is not shown while the decoder is being checked', () => {
  status.heic = { ...linux, state: 'checking', reason: null };
  render(Gallery, { photos: [photo(2, 'heic')], tags });
  expect(screen.queryByText("This computer can't show HEIC photos yet.")).toBeNull();
});

/* ---------- review fix U11: a system with no decoder Butterfly Atlas can use ---------- */

test('on another system the bars say it has no decoder Butterfly Atlas can use, without "yet" (U11)', () => {
  status.heic = { state: 'missing', decoder: null, system: 'other', reason: 'absent' };
  render(Gallery, { photos: [photo(2, 'heic')], tags });
  expect(screen.getByText('This computer has no HEIC decoder Butterfly Atlas can use.')).toBeTruthy();
  expect(screen.queryByText(/can't show HEIC photos yet/)).toBeNull();
});
