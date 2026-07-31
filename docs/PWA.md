# PWA support (installable app + offline reading, offline scratchpad ink)

Pit Pass is an installable Progressive Web App. On iPad/desktop it can be added
to the home screen and runs standalone, and it keeps working **for reading**
when the network drops — the trackside/travel case. General offline writes are
out of scope, with one deliberate exception: the sheet page's drawing
scratchpad. Its ink is mirrored to IndexedDB on every pen-up and replayed to
the server when connectivity returns (see "Scratchpad offline ink" below), so
drawing keeps working with the network down and survives iOS killing the tab.

The manifest and service worker are emitted into `frontend/dist/` by the build
and Spring Boot serves them as static assets like the rest of the bundle. One
backend piece exists expressly for the PWA: `web/ApiEtagConfig` stamps weak
content-hash ETags on `/api` JSON GETs, which is what lets the service worker
detect "the payload actually changed" (see Freshness below). No Dockerfile
changes.

## Pieces

| Concern | Where |
|---|---|
| Plugin / SW / manifest config | `frontend/vite.config.ts` (`VitePWA({...})`) |
| SW registration + update banner | `frontend/src/components/UpdatePrompt.tsx` (mounted in `App.tsx`) |
| "Newer data" nudge banner | `frontend/src/components/DataNudge.tsx` (mounted in `App.tsx`) |
| Connectivity pill + freshness store | `frontend/src/components/ConnectivityPill.tsx` (in `Layout.tsx`) + `frontend/src/lib/connectivity.ts` |
| API ETag fingerprints | `backend/.../web/ApiEtagConfig.java` (+ `ApiEtagTest`) |
| Scratchpad offline ink | `frontend/src/lib/scratchpadStore.ts` (IndexedDB mirror) + `frontend/src/lib/scratchpadSync.ts` (replay) |
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
| `api-data` | StaleWhileRevalidate | all other `GET /api/*` | cache answers **instantly**, a background refetch updates the cache; a changed payload triggers the DataNudge banner |

Current tuning (in `vite.config.ts`): `api-data`/`api-images` are **3000 entries,
30-day TTL**, `purgeOnQuotaError: true`. The 38 GB quota on a real iPad makes
entry caps a non-issue; the 30-day age keeps a prepped weekend available offline
for weeks. `api-data` was NetworkFirst until 2026-07-31 — on real paddock
internet the 4s-per-request wait before cache fallback stalled every page, so it
now serves stale-first and revalidates behind the paint. `/api/me` deliberately
stays NetworkFirst (`networkTimeoutSeconds: 4`): identity/role freshness is
worth one short wait at launch.

## Freshness signals (who tells the user what)

Stale-while-revalidate made loads instant but silent — these three pieces make
cache-vs-live visible. They are coupled; change one, re-check the others:

- **Change detection**: the SW's `broadcastUpdate` on `api-data` posts
  `CACHE_UPDATED` to open pages when the revalidated response differs from the
  cached one. Workbox only compares **headers** (`etag`, `content-length`,
  `last-modified`) — and if *none* of them exist on both copies it silently
  assumes "unchanged". Spring's chunked JSON has none of them naturally, which
  is exactly why `ApiEtagConfig` exists (weak content-hash ETag on `/api` JSON
  GETs; weak because gzipping proxies strip strong ETags; it also overrides
  the stock filter's refusal to tag Spring Security's `no-store` responses).
- **The nudge** (`DataNudge.tsx`): on `CACHE_UPDATED` for `api-data`, shows
  "Newer data is available — Refresh / Dismiss" (5-minute snooze on dismiss).
  Deliberately a nudge, **never a silent re-render** — a standings table must
  not shuffle under the broadcaster mid-sentence. Refresh reloads, which
  repaints instantly from the already-updated cache.
- **The pill** (`ConnectivityPill.tsx` + `lib/connectivity.ts`): topbar
  Live/Slow/Offline dot from a heartbeat — `HEAD /api/me` every 30s (HEAD
  bypasses every SW route, they match GET only; 502/503/504 counts as offline
  since that's what a proxy answers for a dead backend; `navigator.onLine` is
  not trusted because it reports "online" on unusably bad connections). Plus
  "cached Xm": `getJson` reports each response's preserved `Date` header, and
  anything already >90s old when served must have come from cache. Resets per
  route. iOS foregrounding pings immediately (`visibilitychange`), so the pill
  is honest within a beat of the app reappearing.

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

## Scratchpad offline ink (the one offline write)

The pad was chosen as the first offline write because it is structurally safe:
per-user (conflicts are only ever you-on-two-devices), whole-document PUT (no
operation queue — replay is "send the latest state once"), and already guarded
by an optimistic `revision`.

- **Durability**: every completed mutation (pen-up, erase, undo/redo, extend)
  mirrors the document into IndexedDB (`lib/scratchpadStore.ts`, DB
  `pit-pass`, keyed event + owner email). iOS killing the tab costs at most
  the stroke in progress. The debounced PUT is now merely sync, not the only
  persistence. Store failures degrade silently to the old online-only
  behavior — the pad must never break mid-broadcast because storage did.
- **Replay**: no Background Sync API on iOS, so `lib/scratchpadSync.ts` is
  foreground-driven — on app start and on every heartbeat flip back to
  `live`, dirty pads are PUT even if the pad is never reopened. The open
  modal registers itself so the background syncer skips it (a competing PUT
  would bump the revision under the modal and manufacture a phantom 409).
- **Conflicts**: a 409 (the pad moved on another device while this one was
  dirty) is never auto-resolved. The modal's banner offers *keep this
  device's ink* (rebase onto the server revision and overwrite) or *use other
  version*; either way the losing copy goes into a one-slot local `backup` on
  the IndexedDB record — recoverable via Web Inspector until the next
  conflict overwrites the slot. Strokes carry client-generated `id`s (opaque
  to the backend) so a future "keep both" can union-merge instead.
- **Surfacing**: the modal badge says "Offline — saved on this device"; the
  sheet page's FAB shows an amber dot while unsynced ink exists and a red one
  for a conflict (`useScratchpadAttention` in `SheetPage.tsx`, live via
  `subscribeLocalPads`).
- **Caveats**: iOS may evict IndexedDB after ~7 days of app non-use —
  unsynced ink older than that is at risk (Request persistent storage in
  Diagnostics is the lever). A PUT that 401s (session expired) just stays
  dirty locally and syncs after the next sign-in.

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
  invalidated by content. StaleWhileRevalidate makes this **stricter than the old
  NetworkFirst days**: the first paint after a deploy comes from the old cache
  even online. If you ship a **breaking API response-shape change**, bump the
  runtime `cacheName`s (e.g. `api-data` → `api-data-v2`) so stale-shape JSON
  can't feed the new UI at all.
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
