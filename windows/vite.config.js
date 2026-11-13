import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Inject a strict CSP into the production build only; in dev mode Vite's HMR
// needs inline scripts and websockets, which this policy would block.
const cspPlugin = () => ({
  name: 'inject-csp',
  apply: 'build',
  transformIndexHtml(html) {
    return html.replace(
      '<head>',
      `<head>\n    <meta http-equiv="Content-Security-Policy" content="default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:" />`
    );
  },
});

export default defineConfig({
  plugins: [react(), tailwindcss(), cspPlugin()],
  // Relative base so the built index.html works when loaded via file:// in Electron
  base: './',
  build: {
    outDir: 'dist',
  },
  server: {
    port: 5173,
    strictPort: true,
  },
});
