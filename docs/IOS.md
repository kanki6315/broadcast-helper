# Pit Pass for iPad (native app)

The iPad app replaces the installed PWA as the trackside surface. Decision
(2026-09-12): the service worker was flaky in the field, and a planned live
timing page needs the *website* to run cache-free in Safari beside the app.
So the app owns everything that used to live in the service worker — offline
storage, freshness, the Pencil scratchpad — and the website goes back to being
a plain web app. Usage model unchanged: **the iPad reads (plus the scratchpad
and pit-lane anchors); all editing happens on the website.** A WKWebView
hybrid was considered and rejected: it would still carry a bundle-version
staleness problem, which is the class of bug being escaped.

## Status (2026-09-12)

| Slice | State | What it covers |
|---|---|---|
| 1. Sign-in, offline store, series directory | done | Device-token login, SQLite read-through store, the card grid |
| 2. Season pages | done | Overview strip + recap, Schedule, Standings, Stats, Results, Entries, Photos |
| 3. Event sheet | done | Sheet, team-sheets and storylines PDFs, Recap overlay, Pit lane with GPS guidance, Print / Save PDF |
| 4. Download this event / season | done | Prefetch manifests with progress, "Downloaded · Xm" per screen, Settings list |
| 5. PencilKit scratchpad | next | Same stroke wire format as the web pad, offline replay, conflict handling |
| 6. Retire the service worker | planned | One deploy with `selfDestroying: true`, then remove `vite-plugin-pwa` |

Known gaps: driver/team info modals (the website's ⌘K and name links) — names
are plain text; grid headers don't pin to the viewport while scrolling; the
website's sheet page stays until the app's PDF export has been compared with
one real weekend's export. The PWA service worker stays on the website until
slice 5 lands, so race weekends in between still have the web scratchpad.

## Layout

```
ios/
  project.yml            XcodeGen spec — the .xcodeproj is generated and gitignored
  PitPass/
    App/        PitPassApp (entry), AppSession (phase, client, loader, theme), ServerConfig
    Auth/       Keychain (the device token), WebSignIn (ASWebAuthenticationSession)
    Net/        APIClient (bearer, conditional GET, JSON), Connectivity (heartbeat pill),
                Freshness ("cached Xm" per screen), LocationWatcher (Core Location stream)
    Store/      OfflineStore (SQLite), DataLoader (read-through), Resource (view-facing
                document), Downloads (the prefetch manifests, job and manager),
                ImageDecoding (UIImage, SVG via SwiftDraw)
    Model/      Codable wire shapes — mirror frontend/src/lib/api.ts, same field names
                (Models, SeasonModels, SheetModels)
    Season/     SeasonModel (hub, classes, championship selection, recap cache),
                SeasonLogic (pure ports of names.ts / raceForm.ts / venue.ts /
                ChampionshipGrid derivations), PitLaneGeo (port of pitLaneGeo.ts)
    Design/     Theme (DESIGN.md tokens as `PP.*`), BrandMark (the SVG mark as a Shape),
                FlowLayout (flex-wrap)
    Views/      RootView, HomeView, SignInView, SettingsView, TopBar, StatusViews,
                SeriesDirectoryView, DownloadButton
      Season/   SeasonView shell + one file per tab, GridTable, SeasonWidgets
      Sheet/    SheetView, PdfViewerSheet, PitLaneSheet, RecapSheet, SheetPrint
    Resources/  Assets.xcassets (AppIcon, AccentColor #f0b84a), Fonts (Inter, JetBrains Mono)
  PitPassTests/ Swift Testing — OfflineStore, DataLoader (scripted transport), Downloads
                (plan + job over a path-routed transport), PitLaneGeo
```

Swift 6 with strict concurrency, iOS 18+, iPad only (`TARGETED_DEVICE_FAMILY
= 2`). One package: **SwiftDraw** (zlib licence, pinned in `project.yml`) for
SVG rasterising. Everything user-facing is SwiftUI; UIKit appears only where
SwiftUI has no primitive — decoding image bytes (UIImage), the light/dark
dynamic colours and the variable-font axis behind `PP` (UIColor,
UIFontDescriptor), PDFKit's viewer (UIViewRepresentable), and the PDF file
writer (UIGraphicsPDFRenderer + ImageRenderer).

## Build, run, test

```bash
brew install xcodegen
cd ios && xcodegen generate && open PitPass.xcodeproj
```

Pick your team under Signing & Capabilities (a free Apple ID works; the
install expires after 7 days until a paid account is used). From the CLI —
`DEVELOPER_DIR` sidesteps an `xcode-select` that still points at the
command-line tools:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild -project ios/PitPass.xcodeproj -scheme PitPass \
  -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M5)' \
  -derivedDataPath ios/build build CODE_SIGNING_ALLOWED=NO      # or: test
```

The app defaults to `https://pitpass.arjunakankipati.com`. Settings can point
it at the local dev server (`http://localhost:8731`, auth off, so no sign-in);
changing servers signs the iPad out and clears its offline data.

Simulator recipes that proved necessary:

- Preset the server: `xcrun simctl spawn booted defaults write
  com.arjunakankipati.pitpass pitpass.serverURL http://localhost:8731` —
  **after** terminating the app, or its exit flush wins.
- `xcrun simctl launch` reuses the *installed* build; reinstall after every
  `xcodebuild` (the desktop app's simulator panel does this on launch).
- A background `xcodebuild test` reinstalls the app mid-session and kicks it
  to the home screen; run tests against a separate `-derivedDataPath`.
- GPS: `xcrun simctl location <device> set <lat>,<lng>` and pre-grant with
  `xcrun simctl privacy <device> grant location com.arjunakankipati.pitpass`.
  The system location alert survives app relaunches; a simulator reboot
  clears it (and comes back in portrait).
- Screenshots: `xcrun simctl io <device> screenshot out.png` works right
  after a boot when the panel's capture doesn't.

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
`/login/oauth2/code/google`; the `pitpass://` hop happens after that. The
real Google round trip has not yet been exercised end to end (local dev runs
with auth off); the first production sign-in is the proof.

## Offline data (the service worker's replacement)

`OfflineStore` is one SQLite table in Application Support (never purged by the
OS, unlike Caches or WebKit storage): `path → etag, fetched_at, body`.
`DataLoader.refresh` is a conditional GET (`If-None-Match` with the stored
ETag — `ApiEtagConfig` already stamps them) and `Resource` gives views the
rules the web app settled on:

- cached copy paints instantly, revalidation runs behind it;
- a **changed** payload waits as `pendingUpdate` behind a "Newer data is
  available — Refresh" nudge — never a silent re-render mid-sentence;
- the topbar pill says "· cached Xm" while any document on the screen came
  from the store (`Freshness`, reset per screen).

`networkFirst` is for documents that must be fresh when online (`/api/me`
now; the scratchpad later). `DataLoader.bytes` is cache-first for immutable
binaries (`?v=`-stamped logos, photos, PDFs). `Connectivity` is the heartbeat
port of `lib/connectivity.ts` (HEAD `/api/me` every 30s, slow > 2.5s,
502/503/504 = offline, immediate ping on foregrounding). Verified: a cold
launch with the backend down paints the whole directory from the store with
the Offline pill.

### Download this event / season

Where the app differs from the SW on purpose: the SW only ever cached what
had been visited. `Store/Downloads.swift` knows which documents each screen
reads and fetches the whole manifest up front, so a sheet's PDFs, its pit
lane, the recap behind its FAB, or a season page nobody opened yet still
work offline.

- **Event** (the sheet's toolbar): the sheet, pit-lane assignments, the
  team-sheets and storylines PDFs, every car photo and manufacturer mark,
  the event's results and race control, and the recap's prerequisites (hub,
  class styles, the seasons list, every championship grid with rows).
- **Season** (the season's toolbar): hub, reference + lineups, the four stats
  tables, every round's results and race control, every recap, the photo
  index and the sheet-size photos.

`DownloadPlan` derives the paths from the payloads (pure, unit-tested — the
same strings the views build, so a download and a visit store the same
rows). `PrefetchJob` runs four fetches at a time through `DataLoader`:
JSON is a conditional GET (`prefetchDocument`, 304s cost nothing), binaries
are fetched only when absent (`ensureBytes`), so re-running a download is
cheap. A 404 or an undecodable body counts as *missing* and the bundle
carries on; a dropped connection or a revoked sign-in ends it (what's stored
stays — it is all valid). `DownloadManager` (on `AppSession`, so a job
outlives its screen) holds the in-flight progress and the completed records;
`download_record` in SQLite remembers title, time, document count, bytes and
missing count so "Downloaded · 2h" survives a relaunch. `DownloadButton` is
the toolbar control: Download → ring + "12 of 40" (tap stops) →
"Downloaded · Xm" (tap refreshes) → "Download failed" (tap retries); the
schedule marks downloaded rounds and Settings lists every bundle. A
background task keeps the job alive for a while if the person switches to
Safari mid-download.

One rule fell out of this: `Loaded.digest` identifies the stored bytes. A
`Resource` that adopted v1 while a download stored v2 gets a **304** on its
own revalidation (the store's ETag is v2's), so it compares digests and
surfaces v2 as the usual "Newer data" nudge instead of concluding
"unchanged".

## Design (the website's system, natively)

`Design/Theme.swift` is DESIGN.md as Swift — the `PP` namespace holds every
token the site publishes (colors as light/dark dynamic pairs from the
DESIGN.md sRGB approximations, the fixed ~1.2 type scale, the 4pt spacing
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
| `.seg` / `.seg-btn`, `.class-chip`, `.class-tag`, `.class-band` | `Segmented`, `SegmentedToggles`, `ClassChipRow`, `ClassTag`, `ClassBand` (SeasonWidgets) |
| `.race-line` / `RaceCell`, `.pts-line`, `.legend` | `RaceLineView`, `RaceCellView`, `PtsLine`, `Legend` |
| `.grid-table` | `GridTable` (below) |
| `.round-chip`, `.session-notes`, `.race-control`, starting-grid modal | ResultsView's pieces |
| `.login-screen` / `.login-button` / `.btn` | `SignInView`, `PPPrimaryButtonStyle`, `PPSecondaryButtonStyle`, `PPQuietButtonStyle` |
| `.update-banner.data-nudge` | `UpdateNudge` |
| — (the SW had no equivalent) | `DownloadButton` (toolbar), download marks on Schedule rows, the Settings list |
| `.error-panel` / `.empty-state` / `.skeleton` | `ErrorPanel`, `EmptyState`, `SkeletonBlock`, `SkeletonLines` |
| `flex-wrap: wrap` chip rows | `FlowLayout` (the web clips the earlier-seasons row; the app wraps it) |
| `.sheet-*` / `.form-strip` / `.sheet-fabs` | `SheetView`, `StripRace`, `FabButtonStyle` |
| `.pl-*` (pit lane, `.pl-guide`) | `PitLaneSheet` |

Rules carried over, not just colors: amber is the only voiced accent (≤10% of
a screen, primary action + selection only); class colour is always paired
with its code and always carries a `--border-strong` hairline; tabular mono
numerals; no eyebrows, no gradients on the accent, no decorative shadows
(the nudge toast, the FAB stack and the pressed segment are the
state-response shadows); every animation collapses under Reduce Motion
(`SkeletonBlock` checks it). When DESIGN.md changes a token, change
`Theme.swift` in the same slice.

### The grid natively

`GridTable` is `.grid-table`: identity columns pinned on the left, data columns
in a horizontal scroller that fills the leftover width (the web's `grid-soak`),
class bands across both. SwiftUI can't measure one half against the other, so
row heights are budgeted from a per-row `lines` count (stacked race chips,
crew members) — keep `lineHeight` honest when a cell's font changes.
Auto-width cells (lineup crews) compute their width from the longest line;
chip columns are fixed; breakdown points columns widen for the marks gutter.
Headers don't pin to the viewport yet (the web's do).

### The season pages

`SeasonView` is SeasonLayout: series title with the year strip and
qualifying-stage strip, class chips, and the sub-page segmented nav; switching
years swaps the `SeasonModel` in place so tab and filters survive. Each tab is
a port of its web page; the pure derivations (short names, result tiers,
session tags, venue codes, championship families and kinds, gap arithmetic)
live in `Season/SeasonLogic.swift` so a fact reads the same on both surfaces.
Every document loads through `Resource`, so each tab paints from the store
first. Photos is read-only (upload and number matching stay on the website).

### The sheet and its PDF

`Views/Sheet/SheetView.swift` is SheetPage.tsx: the eight percentage columns
(`SheetColumn`, laid out by the `PercentColumns` Layout so a row is as tall
as its tallest cell), one entry = main row + form strip, zebra as the class
colour at 8% into the ground, linked rows open the team-sheets PDF at the
car's page (`PdfViewerSheet`, PDFKit, bytes from the offline store). Prior-year
notes are read-only here. Manufacturer marks are SVG, which UIKit can't
decode: `Store/ImageDecoding.swift` rasterises them with SwiftDraw, capped at
a sane height; monochrome marks flagged `invert_on_dark` render white on the
dark ground, like the site. Schedule rows push the sheet (`SheetRoute`).

`SheetPrint` is the `@media print` block: a light US-Letter PDF laid out at
the browser's 96/in CSS px (so the columns match the web's print density),
then scaled onto 72dpi points. Pagination is by **measured** entry heights
(estimates overflowed the page) — an entry never splits, a class band stays
with its first rows, continuation pages repeat band + header. The export
lands in a ShareLink sheet whose share menu offers Print and Save to Files.

**Pit lane** (`PitLaneSheet`) is the lane in physical order with landmarks
(the S/F stripe, the amber penalty box), gaps for other series, class tints
and tags. Tap a car for walk-to-box guidance: `Season/PitLaneGeo.swift`
projects the live fix onto the polyline through the GPS anchors and says
"~8 boxes (200 ft) toward pit in · you're near box 12", "You're at box N" on
arrival, and flags weak GPS past 25 m. Positions come from
`Net/LocationWatcher.swift` (`CLLocationUpdate.liveUpdates`, when-in-use; the
watch stops when the target is cleared or the sheet closes). Admins can also
**mark anchors** here — ten seconds of fixes, inverse-variance averaged with
outlier rejection, PUT to the anchors endpoint — because the iPad in the
lane is the device that knows where box 12 is. Upload and review stay on the
website. Verified against the real Road America anchors with a simulated
position.

## Parity checklist

Every feature that touches an iPad surface (directory, season pages, sheet,
pit lane, later the pad) ships twice. Before closing a web slice that changes
one of those payloads, tick: `Model/` updated → view updated → PLAN.md
parity row. Where the web's derivation lives in `lib/*.ts`, its port is in
`Season/SeasonLogic.swift` or `Season/PitLaneGeo.swift` — change both.
