import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// /api calls are proxied to the Spring Boot backend on :8080
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' }
  }
})
