import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { atlasIcons } from './atlasIcons.js';

describe('atlasIcons', () => {
  let dir;
  const ico = Buffer.from([0, 0, 1, 0, 7]);
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47]);

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), 'atlas-icons-'));
  });
  afterEach(() => rmSync(dir, { recursive: true, force: true }));

  function emitted(plugin) {
    const files = [];
    plugin.buildStart();
    plugin.generateBundle.call({ emitFile: (file) => files.push(file) });
    return files;
  }

  it('emits favicon.ico and icon.png with the bytes of the packaging icons', () => {
    writeFileSync(join(dir, 'butterfly-atlas.ico'), ico);
    writeFileSync(join(dir, 'butterfly-atlas.png'), png);

    const files = emitted(atlasIcons(dir));

    expect(files.map((f) => [f.type, f.fileName])).toEqual([
      ['asset', 'favicon.ico'],
      ['asset', 'icon.png'],
    ]);
    expect(Buffer.from(files[0].source)).toEqual(ico);
    expect(Buffer.from(files[1].source)).toEqual(png);
  });

  it('links both icons in the page head, relative like the rest of the bundle', () => {
    const tags = atlasIcons(dir).transformIndexHtml();

    expect(tags).toEqual([
      { tag: 'link', attrs: { rel: 'icon', href: './favicon.ico', sizes: 'any' }, injectTo: 'head' },
      { tag: 'link', attrs: { rel: 'icon', type: 'image/png', sizes: '512x512', href: './icon.png' }, injectTo: 'head' },
    ]);
  });

  it('fails the build, naming the path, when an icon is missing', () => {
    writeFileSync(join(dir, 'butterfly-atlas.ico'), ico);

    expect(() => atlasIcons(dir).buildStart()).toThrow(join(dir, 'butterfly-atlas.png'));
  });

  it('fails the build, naming the path, when an icon is empty', () => {
    writeFileSync(join(dir, 'butterfly-atlas.ico'), Buffer.alloc(0));
    writeFileSync(join(dir, 'butterfly-atlas.png'), png);

    expect(() => atlasIcons(dir).buildStart()).toThrow(join(dir, 'butterfly-atlas.ico'));
  });

  it('serves both icons on the dev server, and passes every other request on', () => {
    writeFileSync(join(dir, 'butterfly-atlas.ico'), ico);
    writeFileSync(join(dir, 'butterfly-atlas.png'), png);
    let middleware;
    atlasIcons(dir).configureServer({ middlewares: { use: (fn) => (middleware = fn) } });

    const sent = [];
    const res = { setHeader: (k, v) => sent.push([k, v]), end: (body) => sent.push(['body', Buffer.from(body)]) };
    let passed = 0;
    middleware({ url: '/favicon.ico' }, res, () => passed++);
    middleware({ url: '/icon.png?v=2' }, res, () => passed++);
    middleware({ url: '/src/main.js' }, res, () => passed++);

    expect(sent).toEqual([
      ['Content-Type', 'image/x-icon'],
      ['body', ico],
      ['Content-Type', 'image/png'],
      ['body', png],
    ]);
    expect(passed).toBe(1);
  });
});
