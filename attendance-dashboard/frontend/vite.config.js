import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import basicSsl from '@vitejs/plugin-basic-ssl';

// `npm run dev`       -> http://localhost:5173 (this PC only)
// `npm run dev:phone` -> https://<your-PC-IP>:5173 on your Wi-Fi, so phones can scan the QR code.
//                        Phones only allow camera + GPS on HTTPS pages, hence the self-signed certificate.
export default defineConfig(({ mode }) => {
  const phone = mode === 'phone';
  return {
    plugins: [react(), ...(phone ? [basicSsl()] : [])],
    build: { chunkSizeWarningLimit: 800 },
    server: {
      port: 5173,
      host: phone ? true : 'localhost',
      proxy: {
        // Forward API calls to Spring Boot and pass the real client IP in X-Forwarded-For
        '/api': { target: 'http://localhost:8080', xfwd: true },
      },
    },
  };
});
