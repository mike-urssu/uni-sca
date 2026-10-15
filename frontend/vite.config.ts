import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Dev: proxy /api to the Spring Boot server. The browser's own Origin (localhost:5173) passes HostGuard; never rewrite it,
// or any website could drive scans through the dev proxy.
// Build: the bundle goes into the backend jar's static resources.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true },
    },
  },
  build: { outDir: '../backend/src/main/resources/static', emptyOutDir: true },
})
