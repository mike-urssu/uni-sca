import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Dev: proxy /api to the Spring Boot server, presenting a loopback Host/Origin so its HostGuard accepts it.
// Build: the bundle goes into the backend jar's static resources.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': { target: 'http://127.0.0.1:8080', changeOrigin: true, headers: { origin: 'http://127.0.0.1:8080' } },
    },
  },
  build: { outDir: '../backend/src/main/resources/static', emptyOutDir: true },
})
