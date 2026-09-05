import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The SPA is built straight into the WAR's static resources so that
// `mvn package` yields a complete, self-contained fs-browser.war.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: './target-frontend',
    emptyOutDir: true,
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
