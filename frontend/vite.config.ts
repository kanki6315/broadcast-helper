import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
  ],
  server: {
    // This project owns 6731 (dev UI), 6732 (preview) and 8731 (API) so it never
    // collides with another local checkout. strictPort makes a clash fail loudly
    // instead of silently drifting to 5174 — a moved port is how you end up
    // measuring the wrong app.
    port: 6731,
    strictPort: true,
    // host:true binds all interfaces so a phone/iPad on the same LAN can reach
    // the dev server at http://<mac-lan-ip>:6731 (default localhost is loopback
    // only).
    host: true,
    // Forward API calls to the Spring Boot backend so the app is CORS-free
    // in development and the frontend only ever talks to relative /api URLs.
    proxy: {
      '/api': process.env.VITE_API_ORIGIN ?? 'http://localhost:8731',
    },
  },
  // `npm run preview` serves the built dist/. It does NOT inherit
  // server.proxy, so declare host + the same /api proxy here too — otherwise
  // data calls from the built app 502.
  preview: {
    // 6732, so a preview build and `npm run dev` can run side by side.
    port: 6732,
    strictPort: true,
    host: true,
    proxy: {
      '/api': process.env.VITE_API_ORIGIN ?? 'http://localhost:8731',
    },
  },
})
