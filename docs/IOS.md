# Pit Pass for iPad (native app)

The iPad app replaces the installed PWA as the trackside surface. Decision
(2026-09-12): the service worker was flaky in the field, and a planned live
timing page needs the *website* to run cache-free in Safari beside the app.
So the app owns everything that used to live in the service worker — offline
storage, freshness, the Pencil scratchpad — and the website goes back to being
a plain web app. Usage model unchanged: **the iPad reads (plus the
scratchpad); all editing happens on the website.**

Status: sign-in, read-through offline store, the series directory and the
**season pages** (overview strip + recap, schedule, standings, stats, results,
entries, photos). The sheet page, "Download this event" and the PencilKit pad
follow, one slice each (PLAN.md). Driver/team info modals (the website's ⌘K
and name links) are not in the app yet — names are plain text. The PWA service worker stays on the
website until the app's sheet page and event download exist; retiring it needs
one deploy with `selfDestroying: true` in `vite.config.ts` so installed iPads
let go of the old worker.

## Layout

```
ios/
  project.yml            XcodeGen spec (the .xcodeproj is generated, gitignored)
  PitPass/
    App/        PitPassApp (entry), AppSession (phase + plumbing), ServerConfig
    Auth/       Keychain (the device token), WebSignIn (ASWebAuthenticationSession)
    Net/        APIClient (bearer + conditional GET), Connectivity (heartbeat)
    Store/      OfflineStore (SQLite), DataLoader (read-through), Resource (view-facing)
    Model/      Codable wire shapes — mirror frontend/src/lib/api.ts, same field names
    Season/     SeasonModel (hub, classes, champ selection, recap cache), SeasonLogic
                (pure ports of names.ts / raceForm.ts / venue.ts / ChampionshipGrid derivations)
    Views/      SwiftUI screens; Views/Season/ = SeasonView shell + one file per tab,
                GridTable (pinned identity columns + scrolling data columns), SeasonWidgets
                (segmented controls, class chips/tags/bands, race chips, legends)
    Resources/  Assets.xcassets (AppIcon from frontend/public/pwa-512x512.png, AccentColor = #f0b84a)
  PitPassTests/ Swift Testing: OfflineStore round-trips, DataLoader with a scripted transport
```

Swift 6, strict concurrency, iOS 18+, iPad only (`TARGETED_DEVICE_FAMILY = 2`).

## Build & run

```bash
brew install xcodegen
cd ios && xcodegen generate && open PitPass.xcodeproj
```

Then pick your team under Signing & Capabilities (a free Apple ID works; the
install expires after 7 days until a paid account is used). From the CLI, if
`xcode-select` still points at the command-line tools:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild -project ios/PitPass.xcodeproj -scheme PitPass \
  -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M5)' \
  -derivedDataPath ios/build build CODE_SIGNING_ALLOWED=NO      # or: test
```

The app defaults to `https://pitpass.arjunakankipati.com`. Settings can point
it at the local dev server (`http://localhost:8731`, auth off, so no sign-in).
To preset the simulator: `xcrun simctl spawn booted defaults write
com.arjunakankipati.pitpass pitpass.serverURL http://localhost:8731`.

## Sign-in (device tokens)

The website uses a Google session cookie. Google refuses OAuth inside embedded
web views, and the system sign-in sheet's cookies never reach the app, so the
app gets a **bearer device token** instead — same Google login, same roster,
same request-time rules (`LiveAuthorization`), different transport for "who".

1. App opens `GET /api/auth/device/start?name=<iPad name>` in
   `ASWebAuthenticationSession` (ephemeral). The endpoint flags the session
   (`pitPass.deviceLogin`) and redirects into the normal Google flow.
2. On success `DeviceLoginSuccessHandler` sees the flag, mints a one-time code
   (in memory, 2-minute TTL, single use — `DeviceTokens`), invalidates that
   browser session, and redirects to **`pitpass://auth?code=…`**. The sheet
   closes on the custom scheme (registered in `Info.plist`).
3. App calls `POST /api/auth/device/exchange {code}` → `{token, email,
   deviceName}`; the token goes into the Keychain and every request carries
   `Authorization: Bearer …` (`DeviceTokenFilter` → `DeviceAuthentication` →
   `DeviceUser` principal). Only its SHA-256 is stored (`device_token`, V45).
4. `/api/me` with the bearer reports the email. Because `/api/me` is public,
   a **revoked token shows up as `email: null`, never a 401** — the app treats
   "token stored, email null, answer came from the network" as signed out.
5. Sign out = `DELETE /api/auth/device` (revokes only the calling token) +
   Keychain wipe + offline store wipe. Admins can also unlink a device from
   **Manage → Sessions → Linked devices** (`/api/users/devices`).

Backend pieces: `auth/DeviceTokens`, `DeviceTokenFilter`,
`DeviceLoginSuccessHandler`, `DeviceAuthController`, `UserDeviceController`,
`Principals` (the one place that knows both principal kinds — use it instead
of matching on `OidcUser`). Tests: `DeviceTokensTest`,
`DeviceAuthControllerTest`, `DeviceLoginSuccessHandlerTest`, and the bearer
cases in `SecuredChainTest`.

Google Cloud console: no change — the redirect URI is still Spring's
`/login/oauth2/code/google`; the `pitpass://` hop happens after that.

## Offline data (the service worker's replacement)

`OfflineStore` is one SQLite table in Application Support (never purged by the
OS, unlike Caches or WebKit storage): `path → etag, fetched_at, body`.
`DataLoader.refresh` is a conditional GET (`If-None-Match` with the stored
ETag — `ApiEtagConfig` already stamps them) and `Resource` gives views the
rules the web app settled on:

- cached copy paints instantly, revalidation runs behind it;
- a **changed** payload waits as `pendingUpdate` behind a "Newer data is
  available — Refresh" nudge — never a silent re-render mid-sentence;
- the footer says "Updated Xm ago" or "Cached Xm ago" (data age, not clock).

`networkFirst` is for documents that must be fresh when online (`/api/me`
now; the scratchpad later). `Connectivity` is the heartbeat port of
`lib/connectivity.ts` (HEAD `/api/me` every 30s, slow > 2.5s, 502/503/504 =
offline, immediate ping on foregrounding).

Where the app differs from the SW on purpose: nothing is cached by *visiting*
alone in future slices — "Download this event" will prefetch a known manifest
so pages never opened still work offline, which the SW could not do.

## Design (the website's system, natively)

`ios/PitPass/Design/Theme.swift` is DESIGN.md as Swift — the `PP` namespace
holds every token the site publishes (colors as light/dark dynamic pairs from
the DESIGN.md sRGB approximations, the fixed ~1.2 type scale, the 4pt spacing
scale, radii, and the booth-fast 70/140ms ease-out-quart motion). The site's
own faces ship in the bundle: **Inter** (UI) and **JetBrains Mono** (every
number in a column), decompressed from the frontend's @fontsource woff2
subsets into variable TTFs (`Resources/Fonts`), driven through the `wght`
axis by `PP.sans(size, weight:)` / `PP.mono(size, weight:)`. The brand mark
is drawn as a `Shape` from the SVG paths (`BrandMark`), dark plate on light,
plate-less on dark, exactly like `--brand-mark`.

Component parity with the web CSS, so a screen reads the same on both:

| Web | App |
|---|---|
| `.topbar` + `.wordmark` + theme toggle | `TopBar`, `Wordmark`, `ThemeToggle` (Auto/Light/Dark, `session.theme`) |
| `.conn-pill` (+ "· cached Xm") | `ConnectivityPill` fed by `Connectivity` + `Freshness` |
| `.dir-grid` / `.dir-card` / `.dir-monogram` / `.dir-class` / `.dir-season-chip` / `.qualifier-badge` | `SeriesDirectoryView`, `SeriesCard`, `Monogram`, `ClassChips`, `SeasonChip`, `QualifierBadge` |
| `.login-screen` / `.login-button` | `SignInView`, `PPPrimaryButtonStyle` |
| `.update-banner.data-nudge` | `UpdateNudge` |
| `.error-panel` / `.empty-state` / `.skeleton` | `ErrorPanel`, `EmptyState`, `SkeletonBlock` |
| `flex-wrap: wrap` chip rows | `FlowLayout` (the web clips the earlier-seasons row; the app wraps it) |

Rules carried over, not just colors: amber is the only voiced accent (≤10% of
a screen, primary action + selection only); class colour is always paired
with its code and always carries a `--border-strong` hairline; tabular mono
numerals; no eyebrows, no gradients on the accent, no decorative shadows
(the nudge toast and the pressed segment are the two state-response shadows);
every animation collapses under Reduce Motion (`SkeletonBlock` checks it).
When DESIGN.md changes a token, change `Theme.swift` in the same slice.

### The grid natively

`GridTable` is `.grid-table`: identity columns pinned on the left, data columns
in a horizontal scroller, class bands across both. SwiftUI can't measure one
half against the other, so row heights are budgeted from a per-row `lines`
count (stacked race chips, crew members) — keep `lineHeight` honest when a
cell's font changes. Auto-width cells (lineup crews) compute their width from
the longest line; chip columns are fixed. Headers don't pin to the viewport
yet (the web's do).

## Parity checklist

Every feature that touches an iPad surface (season pages, sheet, pad) ships
twice. Before closing a web slice that changes one of those payloads, tick:
`Model/` updated → view updated → PLAN.md parity row.
