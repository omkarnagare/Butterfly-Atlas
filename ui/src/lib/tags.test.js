import { expect, test } from 'vitest';
import { checkTagName, formTagName, labeller, tagWords } from './tags.js';
import { TAG_NAME_EMPTY, TAG_NAME_TOO_LONG, TAG_NAME_TOO_MANY_WORDS, tagNameTaken } from './messages.js';

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
