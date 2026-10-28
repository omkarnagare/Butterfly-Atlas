import { expect, test } from 'vitest';
import {
  GROUP_ID, exportHref, exportPhotoHref, hrefFor, importHref, importPhotoHref, parseRoute, photoHref, splitHref, splitPhotoHref, UPDATE_HREF,
} from './route.js';

test('empty and unknown hashes go home', () => {
  for (const hash of ['', '#', '#/', '#/nowhere', '#/species']) {
    expect(parseRoute(hash)).toEqual({ screen: 'home' });
  }
});

test('recognises the setup screen', () => {
  expect(parseRoute('#/setup')).toEqual({ screen: 'setup' });
});

test('explore without an id starts at the group', () => {
  expect(parseRoute('#/explore')).toEqual({ screen: 'explore', id: GROUP_ID });
});

test('explore decodes its id', () => {
  expect(parseRoute('#/explore/G%3Abutterflies')).toEqual({ screen: 'explore', id: 'G:butterflies' });
  expect(parseRoute('#/explore/A1')).toEqual({ screen: 'explore', id: 'A1' });
});

test('species pages take an id', () => {
  expect(parseRoute('#/species/A339')).toEqual({ screen: 'species', id: 'A339' });
});

test('survives a badly encoded id', () => {
  expect(parseRoute('#/explore/%E0%A4%A')).toEqual({ screen: 'explore', id: '%E0%A4%A' });
});

test('builds links for each rank', () => {
  expect(hrefFor({ id: 'A5', rank: 'species' })).toBe('#/species/A5');
  expect(hrefFor({ id: 'G:butterflies', rank: 'group' })).toBe('#/explore');
  expect(hrefFor({ id: 'A1', rank: 'family' })).toBe('#/explore/A1');
});

test('a photo opens over its species page', () => {
  expect(parseRoute('#/species/A5/photo/12')).toEqual({ screen: 'species', id: 'A5', photoId: 12 });
  expect(photoHref('A5', 12)).toBe('#/species/A5/photo/12');
  for (const hash of ['#/species/A5/photo/', '#/species/A5/photo/x1', '#/species/A5/photo/0', '#/species/A5/other/12']) {
    expect(parseRoute(hash)).toEqual({ screen: 'species', id: 'A5' });
  }
});

test('the viewer carries the gallery filter', () => {
  expect(parseRoute('#/species/A5/photo/12?tag=upper_wing')).toEqual({ screen: 'species', id: 'A5', photoId: 12, tag: 'upper_wing' });
  expect(parseRoute('#/species/A5/photo/12?tag=')).toEqual({ screen: 'species', id: 'A5', photoId: 12 });
  expect(parseRoute('#/species/A5?tag=larva')).toEqual({ screen: 'species', id: 'A5', tag: 'larva' });
  expect(photoHref('A5', 12, 'mud_puddling')).toBe('#/species/A5/photo/12?tag=mud_puddling');
  expect(photoHref('A5', 12, 'all')).toBe('#/species/A5/photo/12');
});

test('the viewer carries the place filter too, alone or with the tag', () => {
  expect(parseRoute('#/species/A5/photo/12?place=P414')).toEqual({ screen: 'species', id: 'A5', photoId: 12, place: 'P414' });
  expect(parseRoute('#/species/A5/photo/12?tag=larva&place=unknown'))
    .toEqual({ screen: 'species', id: 'A5', photoId: 12, tag: 'larva', place: 'unknown' });
  expect(parseRoute('#/species/A5/photo/12?place=')).toEqual({ screen: 'species', id: 'A5', photoId: 12 });
  expect(photoHref('A5', 12, null, 'P414')).toBe('#/species/A5/photo/12?place=P414');
  expect(photoHref('A5', 12, 'larva', 'unknown')).toBe('#/species/A5/photo/12?tag=larva&place=unknown');
  expect(photoHref('A5', 12, 'all', null)).toBe('#/species/A5/photo/12');
});

test('recognises Recently deleted', () => {
  expect(parseRoute('#/deleted')).toEqual({ screen: 'deleted' });
});

test('decodes an encoded id and filter, and encodes them again', () => {
  expect(parseRoute('#/species/A%205/photo/12?tag=mud%20puddling')).toEqual({ screen: 'species', id: 'A 5', photoId: 12, tag: 'mud puddling' });
  expect(photoHref('A 5', 12, 'mud puddling')).toBe('#/species/A%205/photo/12?tag=mud%20puddling');
});

test('recognises the imports, one import and a photo of it', () => {
  expect(parseRoute('#/import')).toEqual({ screen: 'imports' });
  expect(parseRoute('#/import/3')).toEqual({ screen: 'import', id: 3 });
  expect(parseRoute('#/import/3/photo/812')).toEqual({ screen: 'import', id: 3, itemId: 812 });
  for (const hash of ['#/import/x', '#/import/0', '#/import/-1']) expect(parseRoute(hash)).toEqual({ screen: 'imports' });
  for (const hash of ['#/import/3/photo/', '#/import/3/photo/x', '#/import/3/other/812']) {
    expect(parseRoute(hash)).toEqual({ screen: 'import', id: 3 });
  }
  expect(importHref(3)).toBe('#/import/3');
  expect(importPhotoHref(3, 812)).toBe('#/import/3/photo/812');
});

test('a photo id in the address is a positive whole number', () => {
  expect(parseRoute('#/species/A5/photo/0')).toEqual({ screen: 'species', id: 'A5' });
  expect(parseRoute('#/species/A5/photo/07')).toEqual({ screen: 'species', id: 'A5' });
  expect(parseRoute('#/species/A5/photo/12')).toEqual({ screen: 'species', id: 'A5', photoId: 12 });
});

test('the Export screen carries its filter, read back as printed, and its view', () => {
  expect(parseRoute('#/export')).toEqual({ screen: 'export', query: '', view: 'species' });
  expect(parseRoute('#/export?status=not&taxa=A12,A12&view=places&zoom=2')).toEqual({ screen: 'export', query: 'taxa=A12&status=not', view: 'places' });
  expect(parseRoute('#/export?view=photos')).toEqual({ screen: 'export', query: '', view: 'photos' });
  expect(parseRoute('#/export?view=map')).toEqual({ screen: 'export', query: '', view: 'species' });
  expect(exportHref('')).toBe('#/export');
  expect(exportHref('taxa=A12')).toBe('#/export?taxa=A12');
  expect(exportHref('taxa=A12', 'species')).toBe('#/export?taxa=A12');
  expect(exportHref('taxa=A12', 'photos')).toBe('#/export?taxa=A12&view=photos');
  expect(exportHref('', 'places')).toBe('#/export?view=places');
});

test('a photo of the results opens over the Photos view, with the filter', () => {
  expect(parseRoute('#/export/photo/12?places=unknown')).toEqual({ screen: 'export', query: 'places=unknown', view: 'photos', photoId: 12 });
  expect(parseRoute('#/export/photo/12')).toEqual({ screen: 'export', query: '', view: 'photos', photoId: 12 });
  expect(parseRoute('#/export/photo/x?places=unknown')).toEqual({ screen: 'export', query: 'places=unknown', view: 'species' });
  expect(exportPhotoHref(12, 'places=unknown')).toBe('#/export/photo/12?places=unknown');
  expect(exportPhotoHref(12, '')).toBe('#/export/photo/12');
});

test('a species page address may pre-set its gallery chips (from the Export results)', () => {
  expect(parseRoute('#/species/A5?tag=larva&place=P20')).toEqual({ screen: 'species', id: 'A5', tag: 'larva', place: 'P20' });
  expect(parseRoute('#/species/A5?place=unknown')).toEqual({ screen: 'species', id: 'A5', place: 'unknown' });
  expect(parseRoute('#/species/A5?tag=&place=')).toEqual({ screen: 'species', id: 'A5' });
});

test('the taxonomy update screen, and a split species\' sorting screen with a photo of it', () => {
  expect(parseRoute('#/update')).toEqual({ screen: 'update' });
  expect(UPDATE_HREF).toBe('#/update');
  expect(parseRoute('#/split/A5')).toEqual({ screen: 'split', id: 'A5' });
  expect(parseRoute('#/split/U%3A9/photo/12')).toEqual({ screen: 'split', id: 'U:9', photoId: 12 });
  expect(parseRoute('#/split/A5/photo/x')).toEqual({ screen: 'split', id: 'A5' });
  expect(parseRoute('#/split')).toEqual({ screen: 'home' });
  expect(splitHref('U:9')).toBe('#/split/U%3A9');
  expect(splitPhotoHref('A5', 12)).toBe('#/split/A5/photo/12');
});

test('Settings is its own screen', () => {
  expect(parseRoute('#/settings')).toEqual({ screen: 'settings' });
  expect(parseRoute('#/settings?x=1')).toEqual({ screen: 'settings' });
});

test('a species address carries one file type, as it carries a tag and a place (Phase 7a)', () => {
  expect(parseRoute('#/species/A5?type=raw')).toEqual({ screen: 'species', id: 'A5', type: 'raw' });
  expect(parseRoute('#/species/A5/photo/12?tag=larva&place=P20&type=jpeg')).toEqual({ screen: 'species', id: 'A5', photoId: 12, tag: 'larva', place: 'P20', type: 'jpeg' });
  expect(parseRoute('#/species/A5?type=avif')).toEqual({ screen: 'species', id: 'A5' });
  expect(parseRoute('#/species/A5?type=heic')).toEqual({ screen: 'species', id: 'A5', type: 'heic' });
  expect(photoHref('A5', 12, 'larva', null, 'raw')).toBe('#/species/A5/photo/12?tag=larva&type=raw');
  expect(photoHref('A5', 12, 'all', null, null)).toBe('#/species/A5/photo/12');
});
