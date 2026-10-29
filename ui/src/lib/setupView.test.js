import { expect, test } from 'vitest';
import { setupCanChoose, setupHere, setupLabel, unfinishedNote } from './setupView.js';

const check = (kind, extra = {}) => ({
  path: '/Users/you/Documents', absolute: true, libraryFolder: '/Users/you/Documents/Butterfly_Atlas', kind, unfinished: null, ...extra,
});

test('names the unfinished work by kind, with "and" before the last', () => {
  expect(unfinishedNote(null)).toBe('');
  expect(unfinishedNote({ imports: 0, exports: 0, backups: 0 })).toBe('');
  expect(unfinishedNote({ imports: 1, exports: 0, backups: 0 })).toBe('1 import');
  expect(unfinishedNote({ imports: 1, exports: 1, backups: 0 })).toBe('1 import and 1 paused export');
  expect(unfinishedNote({ imports: 2, exports: 3, backups: 1 })).toBe('2 imports, 3 paused exports and 1 paused backup');
  expect(unfinishedNote({ imports: 0, exports: 0, backups: 2 })).toBe('2 paused backups');
});

test('says what happens to each kind of folder', () => {
  expect(setupHere(check('create'))).toBe('Your library will be created in /Users/you/Documents/Butterfly_Atlas.');
  expect(setupHere(check('existing'))).toBe('Your library in /Users/you/Documents/Butterfly_Atlas will open as it is.');
  expect(setupHere(check('existing', { libraryFolder: '/Users/you/Documents' })))
    .toBe('This folder already has a Butterfly Atlas library. It will open as it is.');
  expect(setupHere(check('taken')))
    .toBe("/Users/you/Documents/Butterfly_Atlas already exists and isn't a Butterfly Atlas library. Choose another folder.");
  expect(setupHere(check('not_writable'))).toBe("Butterfly Atlas can't save files in this folder.");
  expect(setupHere(check('not_found', { libraryFolder: null }))).toBe("That folder can't be found.");
  expect(setupHere(check('not_a_folder', { libraryFolder: null }))).toBe('That is a file, not a folder.');
  expect(setupHere(check('relative', { absolute: false, libraryFolder: null }))).toBe('Enter the full path of a folder.');
  expect(setupHere(null)).toBe('');
});

test('an existing library with unfinished work says it will be cleared, and that photos stay', () => {
  expect(setupHere(check('existing', { libraryFolder: '/Users/you/Documents', unfinished: { imports: 1, exports: 1, backups: 0 } })))
    .toBe('This folder already has a Butterfly Atlas library. It will open as it is, except for work left unfinished where it was'
      + ' used before: 1 import and 1 paused export. They will be cleared; photos already added stay in the library.');
  expect(setupHere(check('existing', { unfinished: { imports: 0, exports: 0, backups: 1 } })))
    .toBe('Your library in /Users/you/Documents/Butterfly_Atlas will open as it is, except for work left unfinished where it'
      + ' was used before: 1 paused backup. It will be cleared; photos already added stay in the library.');
});

test('only a library to open or create can be chosen, with the button saying which', () => {
  expect(setupLabel(check('existing'))).toBe('Open this existing library');
  expect(setupLabel(check('create'))).toBe('Use this folder');
  expect(setupLabel(null)).toBe('Use this folder');
  expect(['existing', 'create', 'taken', 'not_writable', 'not_found', 'not_a_folder', 'relative'].filter((kind) => setupCanChoose(check(kind))))
    .toEqual(['existing', 'create']);
  expect(setupCanChoose(null)).toBe(false);
});
