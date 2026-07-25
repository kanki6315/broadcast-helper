import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    // Installable PWA + read-only offline resilience (trackside connectivity is
    // flaky). The app shell is precached; API reads are NetworkFirst so a page
    // you've opened online reopens offline from cache. No offline writes.
    VitePWA({
      // 'prompt' (not autoUpdate): a new deploy installs but WAITS — the app
      // shows a "reload to update" banner instead of refreshing itself, so a
      // live broadcast is never yanked out from under the user mid-session.
      registerType: 'prompt',
      includeAssets: ['pit-pass-access-lane.svg', 'apple-touch-icon-180x180.png'],
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
          // Vector first: browsers that support SVG app icons (desktop install,
          // and a growing set of others) render the crisp source at any size.
          // The raster fallbacks below cover everyone else; iOS ignores these
          // and uses the apple-touch-icon PNG, Android the maskable PNG.
          {
            src: 'pit-pass-access-lane.svg',
            sizes: 'any',
            type: 'image/svg+xml',
            purpose: 'any',
          },
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          {
            src: 'maskable-icon-512x512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        // The pdfjs worker (~1.25MB) is lazy-loaded only when the team-sheets
        // modal opens; keep it out of the install-time precache (it's runtime-
        // cached below instead). Fonts + hashed JS/CSS + index.html precache.
        globPatterns: ['**/*.{js,css,html,svg,png,ico,woff,woff2}'],
        globIgnores: ['**/pdf.worker*.js', '**/pdf.worker*.mjs'],
        // The precache installs a NavigationRoute that serves index.html for
        // every in-scope navigation — great for SPA client routes, fatal for
        // the server-owned ones. The OAuth login flow is all full-page
        // navigations Spring must handle (/oauth2/authorization/google kicks
        // off, /login/oauth2/code/google exchanges the code); if the worker
        // answers those with the cached shell instead, the handshake silently
        // dies ("clicking Sign in does nothing"). Deny-list them (plus /logout,
        // /error, and /api navigations) so they always hit the network.
        navigateFallbackDenylist: [
          /^\/oauth2\//,
          /^\/login\//,
          /^\/logout/,
          /^\/error/,
          /^\/api\//,
        ],
        // Routes are matched in order (first match wins), so the specific image
        // and /api/me buckets sit before the general api-data catch-all.
        runtimeCaching: [
          {
            // Binary images (photos, car images, series + manufacturer logos).
            // All display URLs carry a ?v= buster, so CacheFirst is safe and
            // keeps large blobs out of the small JSON-doc cache below. Matched
            // precisely so JSON docs that also end in "/data" (notably
            // /api/seasons/{id}/data) are NOT swept in here. NOTE: this matcher
            // must be self-contained — Workbox serializes it via .toString(),
            // so it cannot reference module-scope helpers.
            urlPattern: ({ url }) =>
              /\/(drivers\/[^/]+\/photo|car-images\/[^/]+\/data|series\/[^/]+\/logo\/data|manufacturer-logos\/[^/]+\/data)$/.test(
                url.pathname,
              ),
            handler: 'CacheFirst',
            options: {
              cacheName: 'api-images',
              expiration: {
                maxEntries: 3000,
                maxAgeSeconds: 60 * 60 * 24 * 30,
                purgeOnQuotaError: true, // iOS evicts aggressively; fail gracefully
              },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
          {
            // pdfjs worker: available offline after first use, without bloating
            // the install-time precache.
            urlPattern: ({ url }) => /pdf\.worker/.test(url.pathname),
            handler: 'CacheFirst',
            options: {
              cacheName: 'pdfjs',
              expiration: { maxEntries: 4, maxAgeSeconds: 60 * 60 * 24 * 30 },
            },
          },
          {
            // /api/me on its own 1-day TTL: offline restores the real signed-in
            // identity (not the open-admin fallback in lib/auth.tsx), but the
            // short TTL bounds how long a stale identity can linger after a
            // logout the device never saw.
            urlPattern: ({ url, request }) =>
              request.method === 'GET' && url.pathname === '/api/me',
            handler: 'NetworkFirst',
            options: {
              cacheName: 'api-me',
              networkTimeoutSeconds: 4,
              expiration: { maxEntries: 1, maxAgeSeconds: 60 * 60 * 24 }, // 1 day
              cacheableResponse: { statuses: [200] },
            },
          },
          {
            // Read-only offline for prep content: GET /api/* served
            // network-first, falling back to the last-seen response offline.
            // Only 200s cached (never a 401, so the auth gate still works
            // online). Excluded (→ straight to network, uncached): search
            // typeaheads (a new URL per keystroke would thrash the LRU) and the
            // admin session list (live security data, never serve stale). Like
            // the image matcher, this must stay self-contained (.toString()).
            urlPattern: ({ url, request }) =>
              request.method === 'GET' &&
              url.pathname.startsWith('/api/') &&
              !/^\/api\/(search|drivers\/search|users\/sessions)/.test(url.pathname),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'api-data',
              networkTimeoutSeconds: 4,
              expiration: {
                maxEntries: 3000,
                maxAgeSeconds: 60 * 60 * 24 * 30, // 30d: keep prep available offline for weeks
                purgeOnQuotaError: true,
              },
              cacheableResponse: { statuses: [200] },
            },
          },
        ],
      },
      // Service workers don't run under `vite dev`; leave preview/build to
      // exercise them so local dev keeps hot-reload and the /api proxy.
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
