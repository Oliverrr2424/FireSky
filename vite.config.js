import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Cloudflare Pages Functions run in production (and via `wrangler pages dev`),
// not inside Vite. Proxy API calls during normal web development so the browser
// always receives the same JSON contract instead of Vite's SPA HTML fallback.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'https://fireskychase.pages.dev',
        changeOrigin: true,
        secure: true
      }
    }
  }
});
