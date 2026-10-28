import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { svelteTesting } from '@testing-library/svelte/vite';

// The bundle is written into target/classes/ui so Maven packages it into the ui jar,
// and the Java server serves it from the classpath.
export default defineConfig({
  plugins: [svelte(), svelteTesting()],
  base: './',
  build: {
    outDir: 'target/classes/ui',
    emptyOutDir: true,
    // One baseline for all three OS web views (see docs/PLAN.md §8a).
    target: ['safari16.4', 'chrome120'],
  },
  server: {
    // While this dev proxy is in use, Vite's own allowedHosts check is what stops DNS rebinding of the dev server.
    port: 5173,
    strictPort: true,
    // `npm run dev` talks to a server started with -Datlas.port=7878. The proxy presents
    // requests as coming from that server's own page so its Host/Origin checks pass.
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:7878',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (request) => {
            // Only the dev page's own changes are re-labelled; a foreign Origin passes through and is refused.
            const origin = request.getHeader('origin');
            if (origin === 'http://localhost:5173' || origin === 'http://127.0.0.1:5173') {
              request.setHeader('origin', 'http://127.0.0.1:7878');
            }
          });
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.js'],
    setupFiles: ['src/test-setup.js'],
  },
});
