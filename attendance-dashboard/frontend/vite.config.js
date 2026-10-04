import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: { chunkSizeWarningLimit: 800 },
  server: {
    port: 5173,
    // Forward API calls to the Spring Boot backend
    proxy: { '/api': 'http://localhost:8080' },
  },
});
