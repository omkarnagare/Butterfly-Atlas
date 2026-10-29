import { expect, test } from 'vitest';
import { EMPTY } from './filter.js';
import { ADDITIONS_ID, GROUP_ID } from './route.js';
import { exploreAddress, pageFilter, startsFiltered } from './exploreFilter.js';

test('the page\'s own part joins the filter from the address: a group as the first taxon, Custom additions as its source', () => {
  expect(pageFilter(GROUP_ID, 'tags=female')).toEqual({ ...EMPTY, tags: ['female'] });
  expect(pageFilter('A1', 'tags=female')).toEqual({ ...EMPTY, taxa: ['A1'], tags: ['female'] });
  expect(pageFilter('A1', 'taxa=A9,A1')).toEqual({ ...EMPTY, taxa: ['A1', 'A9'] });
  expect(pageFilter('A1', '')).toEqual({ ...EMPTY, taxa: ['A1'] });
  expect(pageFilter(ADDITIONS_ID, 'tags=female')).toEqual({ ...EMPTY, tags: ['female'], source: 'manual' });
});

test('a filter that still has the page\'s part stays on that page, printed without it', () => {
  expect(exploreAddress('A1', { ...EMPTY, taxa: ['A1', 'A9'], tags: ['female'] })).toEqual({ id: 'A1', query: 'taxa=A9&tags=female' });
  expect(exploreAddress('A1', { ...EMPTY, taxa: ['A1'] })).toEqual({ id: 'A1', query: '' });
  expect(exploreAddress(ADDITIONS_ID, { ...EMPTY, source: 'manual', tags: ['male'] })).toEqual({ id: ADDITIONS_ID, query: 'tags=male' });
  expect(exploreAddress(GROUP_ID, { ...EMPTY, tags: ['male'] })).toEqual({ id: GROUP_ID, query: 'tags=male' });
});

test('a filter without the page\'s part widens to the top page', () => {
  expect(exploreAddress('A1', { ...EMPTY, taxa: ['A9'], tags: ['female'] })).toEqual({ id: GROUP_ID, query: 'taxa=A9&tags=female' });
  expect(exploreAddress('A1', { ...EMPTY, tags: ['female'] })).toEqual({ id: GROUP_ID, query: 'tags=female' });
  expect(exploreAddress('A1', { ...EMPTY })).toEqual({ id: GROUP_ID, query: '' });
  expect(exploreAddress(ADDITIONS_ID, { ...EMPTY, source: 'official' })).toEqual({ id: GROUP_ID, query: 'source=official' });
});

test('a page is filtered when its address has a filter beyond its own part, or a view, or a photo', () => {
  expect(startsFiltered({ screen: 'explore', id: 'A1' })).toBe(false);
  expect(startsFiltered({ screen: 'explore', id: 'A1', query: '', view: 'species' })).toBe(false);
  expect(startsFiltered({ screen: 'explore', id: 'A1', query: '', view: 'photos' })).toBe(true);
  expect(startsFiltered({ screen: 'explore', id: 'A1', query: 'tags=female', view: 'species' })).toBe(true);
});
