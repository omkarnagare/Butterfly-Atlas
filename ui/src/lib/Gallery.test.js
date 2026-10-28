import { fireEvent, render, screen } from '@testing-library/svelte';
import { expect, test, vi } from 'vitest';
import Gallery from './Gallery.svelte';

const tags = [
  { id: 1, key: 'egg', label: 'egg', source: 'builtin', photoCount: 0 },
  { id: 2, key: 'larva', label: 'larva', source: 'builtin', photoCount: 2 },
  { id: 6, key: 'male', label: 'male', source: 'builtin', photoCount: 1 },
  { id: 10, key: 'mud_puddling', label: 'mud-puddling', source: 'user', photoCount: 1 },
  { id: 11, key: 'sinhagad', label: 'Sinhagad trip', source: 'user', photoCount: 3 },
];
const photo = (id, tagKeys) => ({
  id, taxonId: 'A5', fileName: `Danaus_chrysippus_0${id}.jpg`, relPath: '', tags: tagKeys, addedAt: 'v', width: 3, height: 2, isCover: false,
  place: null,
});
const photos = [photo(3, ['larva', 'mud_puddling']), photo(2, ['larva']), photo(1, ['male'])];

test('shows a chip per tag with its count; unused built-in tags are dimmed, unused user tags hidden', () => {
  render(Gallery, { photos, tags });

  const chip = (name) => screen.getByRole('button', { name });
  expect(chip('All 3').getAttribute('aria-pressed')).toBe('true');
  expect(chip('larva, 2 photos').disabled).toBe(false);
  expect(chip('egg, 0 photos').getAttribute('aria-disabled')).toBe('true');
  expect(chip('egg, 0 photos').classList.contains('dim')).toBe(true);
  expect(chip('mud-puddling, 1 photo')).toBeTruthy();
  expect(screen.queryByRole('button', { name: /Sinhagad/ })).toBeNull();
  expect(screen.getAllByRole('link')).toHaveLength(3);
});

test('a chip filters the gallery and All brings everything back', async () => {
  render(Gallery, { photos, tags });

  await fireEvent.click(screen.getByRole('button', { name: 'larva, 2 photos' }));
  expect(screen.getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(['#/species/A5/photo/3?tag=larva', '#/species/A5/photo/2?tag=larva']);
  expect(screen.getByRole('button', { name: 'larva, 2 photos' }).getAttribute('aria-pressed')).toBe('true');

  await fireEvent.click(screen.getByRole('button', { name: 'All 3' }));
  expect(screen.getAllByRole('link')).toHaveLength(3);
});

const at = (id, path, source = 'gps') => ({ id, path, source });
const pune = at('P414', ['Pune', 'Maharashtra', 'India']);
const satara = at('P420', ['Satara', 'Maharashtra', 'India'], 'user');
const kenya = at('P900', ['Kenya']);
const placed = [
  { ...photo(5, ['larva']), place: satara }, { ...photo(4, []), place: pune }, { ...photo(3, ['larva']), place: pune },
  { ...photo(2, ['larva']), place: null }, { ...photo(1, []), place: kenya },
];

test('Where photographed: a chip per place by count then name, with the full path as its title, then the unknown ones', () => {
  render(Gallery, { photos: placed, tags });

  const where = screen.getByRole('group', { name: 'Where photographed' });
  const chips = [...where.querySelectorAll('button')];
  expect(chips.map((b) => b.textContent.replace(/\s+/g, ' ').trim())).toEqual(['Pune 2', 'Kenya 1', 'Satara 1', '1 unknown']);
  expect(chips.map((b) => b.getAttribute('aria-label'))).toEqual([
    'Pune, 2 photos', 'Kenya, 1 photo', 'Satara, 1 photo', 'Unknown place, 1 photo',
  ]);
  expect(chips.map((b) => b.getAttribute('title'))).toEqual(['Pune, Maharashtra, India', 'Kenya', 'Satara, Maharashtra, India', null]);
  expect(chips.every((b) => b.getAttribute('aria-pressed') === 'false')).toBe(true);
});

test('no unknown chip when every photo has a place', () => {
  render(Gallery, { photos: placed.filter((p) => p.place), tags });
  expect(screen.queryByRole('button', { name: /^Unknown place/ })).toBeNull();
});

test('a place chip filters the gallery together with the tag chips; pressing it again clears it', async () => {
  render(Gallery, { photos: placed, tags });
  const hrefs = () => screen.getAllByRole('link').map((a) => a.getAttribute('href'));

  await fireEvent.click(screen.getByRole('button', { name: 'Pune, 2 photos' }));
  expect(hrefs()).toEqual(['#/species/A5/photo/4?place=P414', '#/species/A5/photo/3?place=P414']);
  expect(screen.getByRole('button', { name: 'Pune, 2 photos' }).getAttribute('aria-pressed')).toBe('true');

  await fireEvent.click(screen.getByRole('button', { name: 'larva, 3 photos' }));
  expect(hrefs()).toEqual(['#/species/A5/photo/3?tag=larva&place=P414']);

  await fireEvent.click(screen.getByRole('button', { name: 'Unknown place, 1 photo' }));
  expect(hrefs()).toEqual(['#/species/A5/photo/2?tag=larva&place=unknown']);

  await fireEvent.click(screen.getByRole('button', { name: 'Unknown place, 1 photo' }));
  expect(hrefs()).toEqual(['#/species/A5/photo/5?tag=larva', '#/species/A5/photo/3?tag=larva', '#/species/A5/photo/2?tag=larva']);
});

test('a place filter whose last photo moved away falls back to every place', async () => {
  const { rerender } = render(Gallery, { photos: placed, tags });
  await fireEvent.click(screen.getByRole('button', { name: 'Kenya, 1 photo' }));
  expect(screen.getAllByRole('link')).toHaveLength(1);

  await rerender({ photos: placed.filter((p) => p.place !== kenya), tags });
  await vi.waitFor(() => expect(screen.getAllByRole('link')).toHaveLength(4));
});

const ids = (place, ...above) => ({ ...place, ids: [place.id, ...above] });
const wider = [
  { ...photo(5, ['larva']), place: ids(satara, 'P20', 'P1') }, { ...photo(4, []), place: ids(pune, 'P20', 'P1') },
  { ...photo(3, ['larva']), place: ids(pune, 'P20', 'P1') }, { ...photo(2, []), place: null }, { ...photo(1, []), place: ids(kenya) },
];

test('a place above the photos\' own places shows as an extra chip "in Maharashtra · 3" that filters by the places\' ids and clears like the others', async () => {
  render(Gallery, { photos: wider, tags, place: 'P20' });
  const hrefs = () => screen.getAllByRole('link').map((a) => a.getAttribute('href'));
  const chip = screen.getByRole('button', { name: 'in Maharashtra, 3 photos' });
  expect(chip.textContent.replace(/\s+/g, ' ').trim()).toBe('in Maharashtra · 3');
  expect(chip.getAttribute('aria-pressed')).toBe('true');
  expect(chip.getAttribute('title')).toBe('Maharashtra, India');
  expect(chip.parentElement.querySelectorAll('.chip')[0]).toBe(chip);
  expect(hrefs()).toEqual(['#/species/A5/photo/5?place=P20', '#/species/A5/photo/4?place=P20', '#/species/A5/photo/3?place=P20']);

  await fireEvent.click(chip);
  expect(screen.getAllByRole('link')).toHaveLength(5);
  expect(screen.getByRole('button', { name: 'in Maharashtra, 3 photos' }).getAttribute('aria-pressed')).toBe('false');
  await fireEvent.click(screen.getByRole('button', { name: 'Pune, 2 photos' }));
  expect(hrefs()).toEqual(['#/species/A5/photo/4?place=P414', '#/species/A5/photo/3?place=P414']);
});

test('no extra chip for a place that is one of the photos\' own, for unknown, or for none', () => {
  render(Gallery, { photos: wider, tags, place: 'P414' });
  expect(screen.queryByRole('button', { name: /^in / })).toBeNull();
  expect(screen.getByRole('button', { name: 'Pune, 2 photos' }).getAttribute('aria-pressed')).toBe('true');
});

test('a place and a tag that no photo has together say so', async () => {
  render(Gallery, { photos: placed, tags });
  await fireEvent.click(screen.getByRole('button', { name: 'Kenya, 1 photo' }));
  expect(screen.queryByText('No photos match both filters.')).toBeNull();
  await fireEvent.click(screen.getByRole('button', { name: 'larva, 3 photos' }));
  expect(screen.queryAllByRole('link')).toHaveLength(0);
  expect(screen.getByText('No photos match both filters.')).toBeTruthy();
});

test('a former place\'s chip, when chosen, says what to do with its photos (contract §10)', async () => {
  const chittoor = { id: 'P300', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], level: 'district', source: 'gps', former: true };
  const list = [{ ...photo(1, ['male']), place: chittoor }, { ...photo(2, ['male']), place: pune }];
  const { container } = render(Gallery, { photos: list, tags, place: 'P300' });
  const hint = container.querySelector('.place-hint');
  expect(hint.textContent).toBe('These photos are in a former district. Select them and choose Place… to give them a current one.');
  expect(hint.classList.contains('muted')).toBe(false);
  await fireEvent.click(screen.getByRole('button', { name: 'Pune, 1 photo' }));
  expect(container.querySelector('.place-hint')).toBeNull();
});

test('a former first-level place outside India is "a former region"', () => {
  const old = { id: 'P900', path: ['Gorenjska (former region)', 'Slovenia'], level: 'state', source: 'user', former: true };
  const { container } = render(Gallery, { photos: [{ ...photo(1, ['male']), place: old }], tags, place: 'P900' });
  expect(container.querySelector('.place-hint').textContent).toContain('These photos are in a former region.');
});

test('a former place chip without a level says "a former place" (the level is never read from the name)', () => {
  const old = { id: 'P300', path: ['Chittoor (former district)', 'Andhra Pradesh', 'India'], source: 'gps', former: true };
  const { container } = render(Gallery, { photos: [{ ...photo(1, ['male']), place: old }], tags, place: 'P300' });
  expect(container.querySelector('.place-hint').textContent).toContain('These photos are in a former place.');
});

test('the File type line has a HEIC chip with its count when HEIC photos are among others (Phase 7b)', async () => {
  const heic = { ...photo(4, []), fileName: 'Danaus_chrysippus_04.heic', fileType: 'heic' };
  render(Gallery, { photos: [{ ...photo(1, []), fileType: 'jpeg' }, heic], tags });
  const chip = screen.getByRole('button', { name: /^HEIC/ });
  expect(chip.getAttribute('aria-label')).toBe('HEIC, 1 photo');
  await fireEvent.click(chip);
  expect(screen.getAllByRole('link')).toHaveLength(1);
});
