import { readFileSync } from 'node:fs';
import { join } from 'node:path';

// The app window's title-bar and taskbar icon is the page's favicon (an app-mode browser window takes it from there,
// not from the .exe). The icon files live only in packaging/icons; the build copies them into the bundle.
const ICONS = [
  { from: 'butterfly-atlas.ico', fileName: 'favicon.ico', type: 'image/x-icon' },
  { from: 'butterfly-atlas.png', fileName: 'icon.png', type: 'image/png' },
];

/** Reads each icon from `dir`; a missing or empty file fails the build, naming its path. */
function read(dir) {
  return ICONS.map((icon) => {
    const path = join(dir, icon.from);
    let source;
    try {
      source = readFileSync(path);
    } catch (e) {
      throw new Error(`App icon ${path} could not be read: ${e.message}`);
    }
    if (source.length === 0) {
      throw new Error(`App icon ${path} is empty.`);
    }
    return { ...icon, source };
  });
}

export function atlasIcons(dir) {
  let icons;
  return {
    name: 'atlas-icons',
    buildStart() {
      icons = read(dir);
    },
    generateBundle() {
      for (const { fileName, source } of icons) {
        this.emitFile({ type: 'asset', fileName, source });
      }
    },
    transformIndexHtml() {
      return [
        { tag: 'link', attrs: { rel: 'icon', href: './favicon.ico', sizes: 'any' }, injectTo: 'head' },
        { tag: 'link', attrs: { rel: 'icon', type: 'image/png', sizes: '512x512', href: './icon.png' }, injectTo: 'head' },
      ];
    },
    configureServer(server) {
      const served = read(dir);
      server.middlewares.use((req, res, next) => {
        const path = req.url.split('?')[0];
        const icon = served.find((i) => path === '/' + i.fileName);
        if (!icon) {
          next();
          return;
        }
        res.setHeader('Content-Type', icon.type);
        res.end(icon.source);
      });
    },
  };
}
