# PWA support (installable app + read-only offline)

Pit Pass is an installable Progressive Web App. On iPad/desktop it can be added
to the home screen and runs standalone, and it keeps working **for reading**
when the network drops — the trackside/travel case. It does **not** support
offline writes; those are explicitly out of scope. The sheet page's drawing
scratchpad is an online feature — its saves need connectivity (a dropped save
retries, but ink drawn while offline is lost if the tab closes first).

Everything is frontend-only. There are **no backend or Dockerfile changes** for
the PWA — the manifest and service worker are emitted into `frontend/dist/` by
the build and Spring Boot serves them as static assets like the rest of the
bundle.

## Pieces

| Concern | Where |
|---|---|
| Plugin / SW / manifest config | `frontend/vite.config.ts` (`VitePWA({...})`) |
| SW registration + update banner | `frontend/src/components/UpdatePrompt.tsx` (mounted in `App.tsx`) |
| "Add to Home Screen" hint | `frontend/src/components/InstallHint.tsx` (mounted in `Layout.tsx`) |
| On-device diagnostics | `frontend/src/pages/StoragePage.tsx` → **Manage → Diagnostics** (`/manage/storage`, admin-only) |
| Home-screen icons | `frontend/public/icon.svg` (full-bleed square source) + generated `pwa-*.png`, `apple-touch-icon-180x180.png`, `maskable-*.png` |
| Virtual-module types | `frontend/src/vite-env.d.ts` (`vite-plugin-pwa/client` + `/react`) |

Built with [`vite-plugin-pwa`](https://vite-pwa-org.netlify.app/) (Workbox under
the hood), `registerType: 'prompt'`.

## Caching strategy

The app is same-origin (`/api/*` served by the same Spring Boot instance), which
keeps the SW simple. Routes are matched **in order, first match wins**:

| Cache | Handler | What | Notes |
|---|---|---|---|
| precache | — | app shell (hashed JS/CSS, `index.html`, fonts) | excludes the ~1.25 MB `pdfjs` worker (lazy-loaded, not an offline feature) |
| `api-images` | CacheFirst | driver photos, car images, series + manufacturer logos | all display URLs carry a `?v=` buster → immutable per URL → never revalidate |
| `pdfjs` | CacheFirst | the pdfjs worker chunk | cached on first use, not precached |
| `api-me` | NetworkFirst | `/api/me` only | **1-day** TTL — offline restores the real identity (see auth note below) |
| `api-data` | NetworkFirst | all other `GET /api/*` | the read-only-offline story |

Current tuning (in `vite.config.ts`): `api-data`/`api-images` are **3000 entries,
30-day TTL**, `purgeOnQuotaError: true`. The 38 GB quota on a real iPad makes
entry caps a non-issue; the 30-day age keeps a prepped weekend available offline
for weeks. `networkTimeoutSeconds: 4` on the NetworkFirst caches is the
trackside-feel knob (how long to wait on a flaky-but-alive connection before
falling back to cache) — tune against real paddock connectivity.

**Deliberately never cached** (excluded in the `api-data` matcher → straight to
network): `/api/search` and `/api/drivers/search` (typeaheads fire a new URL per
keystroke and would thrash the LRU) and `/api/users/sessions` (live admin
security data that must never be served stale).

### ⚠️ Matchers must be self-contained

Workbox serializes each `urlPattern` function via `.toString()`, which captures
**only the arrow body** — not any module-scope helpers it references. A matcher
that calls an outer function compiles fine but throws `ReferenceError` at runtime
in the SW, silently breaking that cache. Keep every matcher's logic inline (see
the image / no-cache regexes in `vite.config.ts`). This bit us once; don't
refactor the matchers into named helpers.

### Image matcher precision

`/api/seasons/{id}/data` is JSON but also ends in `/data`, so the `api-images`
matcher is path-specific (`/photo`, `/car-images/*/data`, `/logo/data`,
`/manufacturer-logos/*/data`) rather than a blanket `/data$`. Don't loosen it.

## Updates

`registerType: 'prompt'` + `useRegisterSW` in `UpdatePrompt.tsx`:

- While the app is **foregrounded**, it polls for a new build every **30 minutes**
  (`onRegisteredSW` → `setInterval(r.update, ...)`). iOS freezes background timers,
  so this only ticks in the foreground — which is the case we care about.
- When a new deploy is found, the SW installs it and **waits**; a bottom banner
  ("A new version is available — Reload / Later") lets the user reload when it's
  safe. It never reloads itself, so a live broadcast is never yanked mid-session.
- A cold launch (iOS killed the app) checks for updates automatically as part of
  registration, so relaunching always picks up the latest.

**Transition cost when changing `registerType` or SW behavior:** the *old*
installed worker governs how the *next* update is applied. So the first update
after such a change still requires a full quit-and-reopen (sometimes twice) to
adopt the new worker; subsequent updates use the new behavior. A guaranteed reset
if a device is ever stuck on an old build: delete the home-screen icon and re-add.

## Diagnostics

**Manage → Diagnostics** (`/manage/storage`, admin-gated) reports, live on the
device:

- **Viewport** — visual viewport vs layout viewport (with the delta highlighted),
  `window.inner`, `screen`, `devicePixelRatio`. Built to diagnose "content
  overflows a resized Stage Manager window" questions.
- **Offline storage** — real `navigator.storage` quota/usage, whether storage is
  persistent, secure-context yes/no, and per-cache entry counts. Actions: refresh,
  **Request persistent storage**, and **Clear API caches** (runtime buckets only —
  never the precached shell).

Use this on the actual iPad; the numbers are device/browser-specific.

## Gotchas & notes for future development

- **HTTPS is mandatory.** Service workers *and* the storage APIs
  (`navigator.storage`, `caches`) are secure-context-only (localhost excepted).
  Over a plain-http LAN address (`http://<mac-ip>:6731`) the SW won't register and
  Diagnostics reads all blank. Production is HTTPS (Railway), so this only matters
  for local device testing — use a trusted-HTTPS tunnel (e.g. `cloudflared tunnel
  --url http://localhost:6732`) or test on the deployment.
- **Offline auth fallback.** `lib/auth.tsx` synthesizes an *open/admin* `me` when
  `/api/me` is unreachable (mimics local dev so the login screen isn't shown
  behind a dead backend). Offline this would render edit controls that can't save.
  Caching `/api/me` NetworkFirst (the `api-me` bucket) restores the **real**
  identity/admin state offline instead. If you change the auth fallback, re-check
  this interaction. The `authEnabled` flag is deployment config (`AUTH_ENABLED`
  env → `application.yml`), not per-user — `email` is what says "signed in".
- **Cache versioning across deploys.** The precache auto-versions by content hash,
  but the runtime caches (`api-data`, etc.) persist across deploys and are *not*
  invalidated by content. NetworkFirst hides this online (it refetches), but if
  you ship a **breaking API response-shape change**, bump the runtime `cacheName`s
  (e.g. `api-data` → `api-data-v2`) so stale-shape JSON can't feed the new UI
  offline.
- **iOS eviction.** WebKit can clear a PWA's storage after ~7 days of non-use, and
  quota is a fraction of free disk (measure via Diagnostics, don't hardcode).
  Home-screen install plus **Request persistent storage** (Diagnostics) is the
  lever against time-based eviction.
- **`.sr-only` must stay pinned to the origin.** Visually-hidden
  `position: absolute` elements with no offset sit at their *static* position —
  which, inside a wide horizontally-scrolling table, lands far to the right and
  leaks past the viewport, adding phantom document horizontal scroll ("dead space"
  that appears only when the window is narrower than that fixed x). `.sr-only` in
  `App.css` pins `top: 0; left: 0` to prevent this. Keep it.
- **Wide tables scroll in their own container**, not the page. Recap/standings/
  results/entries grids are wrapped in `.grid-scroll` (`overflow-x: auto`) so a
  narrow/windowed view never slides the whole page sideways. Sticky-left ident
  columns pin to that scroller. Tradeoff: the sticky header pins to the table
  region, not the page viewport.
- **Layout viewport tracks the window** on modern iPadOS Stage Manager (confirmed
  via Diagnostics: `layout − window = 0`). Content overflow is genuine wide
  content, not a viewport mismatch — don't chase a viewport-clamp fix.
- **Icons.** iOS rounds home-screen icons itself and fills transparency with
  black, so the PNG source (`public/icon.svg`) is a **full-bleed square**, not the
  rounded-rect `favicon.svg`. Regenerate with
  `npx @vite-pwa/assets-generator --preset minimal-2023 public/icon.svg`.
- **Testing the SW in `vite preview`** requires the cache-clear dance
  (unregister + `caches.delete` + reload with a cache-buster query) to force a
  fresh build, because `registerType: 'prompt'` holds new workers as *waiting*.
  `devOptions` is off, so `npm run dev` runs without a SW (keeps HMR + the `/api`
  proxy). `server`/`preview` have `host: true` for LAN access.
