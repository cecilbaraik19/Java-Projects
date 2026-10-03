import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// /api calls are proxied to the Spring Boot backend on :8080
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // allow public tunnel addresses (see README: "Open it on your phone")
    allowedHosts: ['.trycloudflare.com', '.ngrok-free.app'],
    proxy: { '/api': 'http://localhost:8080' }
  }
})
