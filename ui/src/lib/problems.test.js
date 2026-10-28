import { expect, test } from 'vitest';
import { loadProblem } from './problems.js';

test('a load refused for the library\'s drive does not repeat the banner; any other says the server\'s words', () => {
  expect(loadProblem(Object.assign(new Error('The drive with your library is full.'), { code: 'drive_full' }))).toBe("Couldn't load this page.");
  expect(loadProblem(Object.assign(new Error("The drive with your library can't be found."), { code: 'drive_gone' }))).toBe("Couldn't load this page.");
  expect(loadProblem(Object.assign(new Error('No species has the id A5.'), { code: 'not_found' }))).toBe('No species has the id A5.');
});
