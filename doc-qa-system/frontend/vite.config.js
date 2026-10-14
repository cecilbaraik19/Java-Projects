import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // During development, send /api calls to the Spring Boot backend
    proxy: { '/api': 'http://localhost:8090' },
  },
  build: {
    // "npm run build" puts the finished site inside the backend,
    // so the Spring Boot app serves everything on http://localhost:8090
    outDir: '../backend/src/main/resources/static',
    emptyOutDir: true,
  },
})
