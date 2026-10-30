import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test } from 'vitest';

/**
 * Every sentence the page says calls the machine it runs on "device", never "computer" (match-device design §6), so the
 * words still fit a phone. Reads the UI's sources (tests run from ui/), comments left out.
 */
function sources(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sources(path);
    return /\.(js|svelte)$/.test(entry.name) && !entry.name.includes('.test.') ? [path] : [];
  });
}

const withoutComments = (code) => code
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(^|\s)\/\/.*$/gm, '$1');

test('no sentence says computer', () => {
  const found = sources('src').flatMap((file) => withoutComments(readFileSync(file, 'utf8'))
    .split('\n').filter((line) => /computer/i.test(line)).map((line) => `${file}: ${line.trim()}`));
  expect(found).toEqual([]);
});
