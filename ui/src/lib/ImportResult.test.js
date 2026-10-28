import { render, screen } from '@testing-library/svelte';
import { beforeEach, expect, test, vi } from 'vitest';
import ImportResult from './ImportResult.svelte';
import { getTaxon } from './api.js';

vi.mock('./api.js', () => ({ getTaxon: vi.fn() }));
beforeEach(() => {
  getTaxon.mockReset();
});

beforeEach(() => {
  vi.restoreAllMocks();
  vi.spyOn(Number.prototype, 'toLocaleString').mockImplementation(function () { return new Intl.NumberFormat('en-US').format(this); });
});

const result = {
  filed: 2140, newSpecies: 27, speciesCount: 312, milestone: 250,
  bySpecies: [
    { taxonId: 'A5', name: 'Danaus chrysippus', common: 'Plain Tiger Butterfly', count: 14, isNew: true },
    { taxonId: 'A9', name: 'Junonia almana', common: null, count: 1200, isNew: false },
  ],
  duplicates: 12, alsoElsewhere: 3, copies: 9, skipped: 88, failed: 2,
  problems: [{ reason: "AVIF photos aren't supported yet.", count: 212 }], left: 1730,
};

test('says what was filed per species, and the rest in plain words', () => {
  const { container } = render(ImportResult, { result });

  expect(container.querySelector('.ir-big').textContent).toBe('2,140 photos filed, 27 new species');
  const rows = screen.getByRole('list', { name: 'Filed by species' }).querySelectorAll('li');
  expect([...rows].map((li) => li.textContent.replace(/\s+/g, ' ').trim())).toEqual([
    'Plain Tiger Butterfly Danaus chrysippus 14 New', 'Junonia almana 1,200',
  ]);
  expect([...container.querySelectorAll('.ir-o li')].map((li) => li.textContent)).toEqual([
    '12 photos already in your atlas, not filed again',
    '3 photos filed, also under another species',
    '9 copies of other photos in this folder, not filed',
    '88 photos skipped, left in the folder',
    "2 photos couldn't be filed",
    "212 photos: AVIF photos aren't supported yet.",
  ]);
  expect(screen.getByText('1,730 photos are still waiting to be filed.')).toBeTruthy();
});

test('leaves out what did not happen', () => {
  const { container } = render(ImportResult, {
    result: { ...result, newSpecies: 0, bySpecies: [], duplicates: 0, alsoElsewhere: 0, copies: 1, skipped: 0, failed: 0, problems: [], left: 0 },
  });

  expect(container.querySelector('.ir-big').textContent).toBe('2,140 photos filed');
  expect(screen.queryByRole('list', { name: 'Filed by species' })).toBeNull();
  expect([...container.querySelectorAll('.ir-o li')].map((li) => li.textContent)).toEqual(['1 copy of other photos in this folder, not filed']);
  expect(screen.queryByText(/still waiting/)).toBeNull();
});

test('two problems with the same words are both listed', () => {
  const { container } = render(ImportResult, {
    result: { ...result, duplicates: 0, alsoElsewhere: 0, copies: 0, skipped: 0, failed: 0,
      problems: [{ reason: 'Video', count: 1 }, { reason: 'Video', count: 2 }] },
  });
  expect([...container.querySelectorAll('.ir-o li')].map((li) => li.textContent)).toEqual(['1 photo: Video', '2 photos: Video']);
});

test('says how many files the RAW + JPEG choice left out (Phase 7a)', () => {
  const { container, rerender } = render(ImportResult, { result: { filed: 212, bySpecies: [], pairLeft: 212, pairChoice: 'jpeg', left: 0 } });
  expect([...container.querySelectorAll('.ir-o li')].map((li) => li.textContent)).toEqual(['212 files left out by your RAW + JPEG choice (JPEG only)']);
  rerender({ result: { filed: 1, bySpecies: [], pairLeft: 1, pairChoice: 'raw', left: 0 } });
  return Promise.resolve().then(() => {
    expect([...container.querySelectorAll('.ir-o li')].map((li) => li.textContent)).toEqual(['1 file left out by your RAW + JPEG choice (RAW only)']);
  });
});

test('files a RAW + HEIC choice left out are said with HEIC words (Phase 7b)', () => {
  render(ImportResult, { result: { filed: 1, newSpecies: 0, bySpecies: [], pairLeft: 3, pairChoice: 'jpeg', left: 0 }, kinds: ['heic'] });
  expect(screen.getByText('3 files left out by your RAW + HEIC choice (HEIC only)')).toBeTruthy();
});

test("filing finished: a meadow sprig and a butterfly in the colour of the most filed species' family (Meadow §5)", async () => {
  getTaxon.mockResolvedValue({ id: 'A9', name: 'Junonia almana', family: 'Nymphalidae' });
  const { container } = render(ImportResult, { result: { ...result, left: 0 } });

  const sprig = container.querySelector('.ir-sprig');
  expect(sprig.getAttribute('aria-hidden')).toBe('true');
  expect(sprig.querySelector('svg.meadow')).not.toBeNull();
  await vi.waitFor(() => expect(getTaxon).toHaveBeenCalledWith('A9'));
  await vi.waitFor(() => expect(sprig.querySelector('.ir-fly').style.getPropertyValue('--fam')).toBe('var(--f-nym)'));
  expect(container.querySelector('.ir-big').textContent).toBe('2,140 photos filed, 27 new species');
});

test('while photos still wait to be filed, or none was filed, there is no sprig', () => {
  const { container, unmount } = render(ImportResult, { result });
  expect(container.querySelector('.ir-sprig')).toBeNull();
  unmount();

  const none = render(ImportResult, { result: { filed: 0, bySpecies: [], left: 0 } });
  expect(none.container.querySelector('.ir-sprig')).toBeNull();
  expect(getTaxon).not.toHaveBeenCalled();
});

test('a family that cannot be read leaves the butterfly in the accent', async () => {
  getTaxon.mockRejectedValue(new Error('offline'));
  const { container } = render(ImportResult, { result: { ...result, left: 0 } });

  await vi.waitFor(() => expect(getTaxon).toHaveBeenCalledWith('A9'));
  await new Promise((resolve) => setTimeout(resolve));
  expect(container.querySelector('.ir-fly').style.getPropertyValue('--fam')).toBe('var(--accent)');
});
