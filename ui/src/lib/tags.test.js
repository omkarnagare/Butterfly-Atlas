import { expect, test } from 'vitest';
import { ADULT_KEYS, checkTagName, formTagName, labeller, matchTags, tagNamed, tagWords } from './tags.js';
import {
  FIND_A_TAG, FIND_OR_ADD_A_TAG, TAG_NAME_EMPTY, TAG_NAME_TOO_LONG, TAG_NAME_TOO_MANY_WORDS, createTag, moreTags, noTagCalled, tagNameTaken,
} from './messages.js';

test('a tag name takes its one form, as the server does (TagNamesTest)', () => {
  expect(formTagName('upper wing')).toBe('upper_wing');
  expect(formTagName('Unconfirmed')).toBe('unconfirmed');
  expect(formTagName('Mud-puddling')).toBe('mud_puddling');
  expect(formTagName('  egg ')).toBe('egg');
  expect(formTagName('Unconfirmed (built-in)')).toBe('unconfirmed_built_in');
  expect(formTagName('__a..b__')).toBe('a_b');
  expect(formTagName('Mud puddling on DSF  wet sand')).toBe('mud_puddling_on_DSF_wet_sand');
  expect(formTagName('DSF')).toBe('DSF');
  expect(formTagName('WSF2')).toBe('WSF2');
  expect(formTagName('UV light')).toBe('UV_light');
  expect(formTagName('A')).toBe('a');
  expect(formTagName('iNat')).toBe('inat');
  expect(formTagName('2B')).toBe('2b');
  expect(formTagName('ÉTÉ')).toBe('ÉTÉ');
  expect(formTagName('Été')).toBe('été');
  expect(formTagName('Café 2')).toBe('café_2');
  expect(formTagName('!!!')).toBe('');
  expect(formTagName(null)).toBe('');
});

test('counts words', () => {
  expect(tagWords('egg')).toBe(1);
  expect(tagWords('mud_puddling_on_wet_sand')).toBe(5);
  expect(tagWords('')).toBe(0);
});

test('checks a new name or a rename against the rules and the other tags', () => {
  const tags = [{ id: 1, label: 'DSF' }, { id: 2, label: 'mud_puddling' }];
  expect(checkTagName(' Mud puddling site ', tags)).toEqual({ name: 'mud_puddling_site', problem: '' });
  expect(checkTagName('!!!', tags)).toEqual({ name: '', problem: TAG_NAME_EMPTY });
  expect(checkTagName('mud puddling on sand', tags).problem).toBe(TAG_NAME_TOO_MANY_WORDS);
  expect(checkTagName('a'.repeat(41), tags).problem).toBe(TAG_NAME_TOO_LONG);
  expect(checkTagName('dsf', tags).problem).toBe(tagNameTaken('DSF'));
  expect(checkTagName('Mud-Puddling', tags).problem).toBe(tagNameTaken('mud_puddling'));
  expect(checkTagName('Mud-Puddling', tags, 2)).toEqual({ name: 'mud_puddling', problem: '' });
});

test('an unknown key is shown as it is', () => {
  const label = labeller([{ key: 'dsf', label: 'DSF' }]);
  expect(label('dsf')).toBe('DSF');
  expect(label('upper_wing')).toBe('upper_wing');
});

test('the adult keys are the server\'s nine, in its order (PhotoRows.ADULT_KEYS, behaviour tags §3)', () => {
  expect(ADULT_KEYS).toEqual(['upper_wing', 'under_wing', 'male', 'female', 'dsf', 'wsf', 'mating', 'nectaring', 'puddling']);
  expect(ADULT_KEYS).not.toContain('predated');
});

const tag = (key, label = key, source = 'user') => ({ key, label, source });
const ALL = [
  tag('egg', 'egg', 'builtin'), tag('nectaring', 'nectaring', 'builtin'), tag('puddling', 'puddling', 'builtin'), tag('dsf', 'DSF', 'builtin'),
  tag('basking'), tag('nectaring_on_lantana'), tag('on_lantana'), tag('wet_sand'),
];
const keys = (list) => list.map((t) => t.key);

test('a tag matches when its name contains what is typed, without case, in tag order (behaviour tags §4.2)', () => {
  expect(keys(matchTags(ALL, 'nect'))).toEqual(['nectaring', 'nectaring_on_lantana']);
  expect(keys(matchTags(ALL, 'NECT'))).toEqual(['nectaring', 'nectaring_on_lantana']);
  expect(keys(matchTags(ALL, 'dsf'))).toEqual(['dsf']);
  expect(keys(matchTags(ALL, 'ing'))).toEqual(['nectaring', 'puddling', 'basking', 'nectaring_on_lantana']);
  expect(matchTags(ALL, 'zzz')).toEqual([]);
});

test('spaces and _ count as the same, and leading and trailing spaces are ignored', () => {
  expect(keys(matchTags(ALL, 'on lantana'))).toEqual(['nectaring_on_lantana', 'on_lantana']);
  expect(keys(matchTags(ALL, 'on_lantana'))).toEqual(['nectaring_on_lantana', 'on_lantana']);
  expect(keys(matchTags(ALL, '  wet  sand '))).toEqual(['wet_sand']);
  expect(keys(matchTags(ALL, 'wet-sand'))).toEqual(['wet_sand']);
  expect(keys(matchTags([tag('ete', 'été')], 'e\u0301te\u0301'))).toEqual(['ete']); // NFD typed, NFC stored
});

test('an empty query (or one of only spaces or marks) matches nothing', () => {
  expect(matchTags(ALL, '')).toEqual([]);
  expect(matchTags(ALL, '   ')).toEqual([]);
  expect(matchTags(ALL, '!!!')).toEqual([]);
  expect(matchTags(ALL, null)).toEqual([]);
  expect(matchTags(null, 'egg')).toEqual([]);
});

test('the picker\'s words', () => {
  expect(FIND_OR_ADD_A_TAG).toBe('Find or add a tag');
  expect(FIND_A_TAG).toBe('Find a tag');
  expect(noTagCalled('basking spot')).toBe('No tag called "basking spot".');
  expect(createTag('basking_spot')).toBe('Create "basking_spot"');
  expect(moreTags(4)).toBe('and 4 more: keep typing');
});

test('the tag named exactly what is typed, after its one form and without case', () => {
  expect(tagNamed(ALL, 'Nectaring')?.key).toBe('nectaring');
  expect(tagNamed(ALL, ' on Lantana ')?.key).toBe('on_lantana');
  expect(tagNamed(ALL, 'dsf')?.key).toBe('dsf');
  expect(tagNamed(ALL, 'lantana')).toBeNull();
  expect(tagNamed(ALL, '')).toBeNull();
});
