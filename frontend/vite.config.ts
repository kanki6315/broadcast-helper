import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    // RETIRING (2026-09-12, step 1 of 2). The trackside surface is the iPad
    // app now (docs/IOS.md); the website goes back to being a plain, uncached
    // web app so it can run beside the app for live timing. A service worker
    // an iPad already installed stays in charge of the origin forever unless
    // a NEW worker replaces it, so this deploy ships Workbox's self-destroying
    // worker: it installs over the old one, unregisters itself and deletes
    // every cache. Step 2 — once every installed iPad has opened the site
    // once — removes vite-plugin-pwa, this block, the registration in
    // main.tsx and the /sw.js allowlist entry in SecurityConfig.
    VitePWA({
      selfDestroying: true,
      registerType: 'autoUpdate',
      includeAssets: ['pit-pass-access-lane.svg', 'apple-touch-icon-180x180.png'],
      // The manifest still ships so home-screen installs keep their icon and
      // name while the worker retires; it goes with the plugin in step 2.
      manifest: {
        name: 'Pit Pass',
        short_name: 'Pit Pass',
        description: 'Motorsport broadcast prep: series, standings, and sheets.',
        display: 'standalone',
        start_url: '/',
        scope: '/',
        background_color: '#ffffff',
        theme_color: '#f0b84a',
        icons: [
          { src: 'pit-pass-access-lane.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          { src: 'maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      devOptions: { enabled: false },
    }),
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
    // only). NOTE: a service worker will NOT register over a plain-http LAN URL
    // — SWs need a secure context (localhost excepted), so this exposes the app
    // for UI checks, not for PWA/offline testing. Use a trusted-HTTPS tunnel or
    // the deployed site to exercise the service worker on-device.
    host: true,
    // Forward API calls to the Spring Boot backend so the app is CORS-free
    // in development and the frontend only ever talks to relative /api URLs.
    proxy: {
      '/api': process.env.VITE_API_ORIGIN ?? 'http://localhost:8731',
    },
  },
  // `npm run preview` serves the built dist/ (the only build with the service
  // worker). It does NOT inherit server.proxy, so declare host + the same /api
  // proxy here too — otherwise data calls from the built app 502.
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
