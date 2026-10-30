import { expect, test } from 'vitest';
import { changeCanChoose, changeHere, changeLabel } from './changeView.js';

const GB = 1024 ** 3;
const check = (kind, extra = {}) => ({
  path: '/Volumes/B', absolute: true, libraryFolder: '/Volumes/B/Butterfly_Atlas', kind, unfinished: null, refusal: null, ...extra,
});

// Move library (design §3's table): a place for a new library, with its size and the drive's free space.
test('a move to a new folder says where, how big and how much is free; Move here', () => {
  const c = check('create', { free: 80.1 * GB, needed: 13.2 * GB });
  expect(changeHere(c, 'move', 12.4 * GB)).toBe('Your library will be moved to /Volumes/B/Butterfly_Atlas. It is 12.4 GB; that drive has 80.1 GB free.');
  expect(changeLabel(c, 'move')).toBe('Move here');
  expect(changeCanChoose(c, 'move')).toBe(true);
});

test('a move is refused, with the table\'s words, for too little space, a library there, or a folder inside the library', () => {
  const cases = [
    [check('create', { refusal: 'not_enough_space', free: 3.2 * GB, needed: 13.2 * GB }), 'That drive has 3.2 GB free; moving your library needs about 13.2 GB.'],
    [check('existing', { refusal: 'existing_library' }), 'That folder already holds a library. To open it, use Use a different library.'],
    [check('existing'), 'That folder already holds a library. To open it, use Use a different library.'],
    [check('create', { refusal: 'inside_library' }), 'Choose a folder outside your library.'],
    [check('existing', { refusal: 'current_library' }), 'Choose a folder outside your library.'],
    [check('create', { refusal: 'library_in_use' }), 'Another copy of Butterfly Atlas is using this library.'],
    [check('taken'), "/Volumes/B/Butterfly_Atlas already exists and isn't a Butterfly Atlas library. Choose another folder."],
    [check('not_writable'), "Butterfly Atlas can't save files in this folder."],
    [check('not_found'), "That folder can't be found."],
    [check('not_a_folder'), 'That is a file, not a folder.'],
  ];
  for (const [c, words] of cases) {
    expect(changeHere(c, 'move', 12.4 * GB)).toBe(words);
    expect(changeCanChoose(c, 'move')).toBe(false);
    expect(changeLabel(c, 'move')).toBe('Move here');
  }
  expect(changeHere(null, 'move', 1)).toBe('');
  expect(changeCanChoose(null, 'move')).toBe(false);
});

// Use a different library: the setup screen's lines and buttons, and its own refusals.
test('a switch says what setup says: an existing library opens (with its unfinished work), a new one is created', () => {
  const existing = check('existing', { libraryFolder: '/Volumes/B', path: '/Volumes/B', unfinished: { imports: 1, exports: 0, backups: 0 } });
  expect(changeHere(existing, 'switch', 1)).toBe('This folder already has a Butterfly Atlas library. It will open as it is, except for work left'
    + ' unfinished where it was used before: 1 import. It will be cleared; photos already added stay in the library.');
  expect(changeLabel(existing, 'switch')).toBe('Open this existing library');
  expect(changeCanChoose(existing, 'switch')).toBe(true);
  const create = check('create');
  expect(changeHere(create, 'switch', 1)).toBe('Your library will be created in /Volumes/B/Butterfly_Atlas.');
  expect(changeLabel(create, 'switch')).toBe('Use this folder');
  expect(changeCanChoose(create, 'switch')).toBe(true);
});

test('a switch to the library in use, inside it, or to one another copy holds is refused with its words', () => {
  const cases = [
    [check('existing', { refusal: 'current_library' }), 'That is the library you are using.'],
    [check('create', { refusal: 'inside_library' }), 'Choose a folder outside your library.'],
    [check('existing', { refusal: 'library_in_use' }), 'Another copy of Butterfly Atlas is using this library.'],
    [check('taken'), "/Volumes/B/Butterfly_Atlas already exists and isn't a Butterfly Atlas library. Choose another folder."],
  ];
  for (const [c, words] of cases) {
    expect(changeHere(c, 'switch', 1)).toBe(words);
    expect(changeCanChoose(c, 'switch')).toBe(false);
  }
});
