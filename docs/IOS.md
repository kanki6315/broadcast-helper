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
| 2. Season pages | done | Season recap overview, Races (schedule + session results), Standings, Stats, Entries |
| 3. Event sheet | done | Sheet, team-sheets and storylines PDFs, Recap overlay, Pit lane with GPS guidance, Print / Save PDF |
| 4. Download this event / season | done | Prefetch manifests with progress, "Downloaded · Xm" per screen, Settings list |
| 5. PencilKit scratchpad | done | Same stroke wire format as the web pad, local mirror, offline replay, conflict banner, FAB badge |
| 6. Retire the service worker | done | Self-destroying worker deployed first, then `vite-plugin-pwa`, the manifest and the `/sw.js` allowlist removed |

Confirmed on a real iPad (2026-09-12): the Print / Save PDF export matches
the website's export for a real weekend; the scratchpad with a real Apple
Pencil (latency, and the system "Only Draw with Apple Pencil" setting); and
the production Google sign-in end to end (device token minted, stored,
`/api/me` answering with the email).

| 7. Driver and team profiles | done | Names open the website's info modals: photo, bio, career stats, championship matrices, roster, lineage, notes (read-only) |

Known gaps: the website's ⌘K search has no counterpart; grid headers don't
pin to the viewport while scrolling.

The Overview opens directly on the season recap. Race details and entries remain
available in their dedicated views; the duplicate summary strip and its extra
requests have been removed so more season rows are visible immediately.

## Workspace navigation and resume

The Series library uses compact rows with expandable seasons. Selecting a
season establishes the root workspace; All series switches back to the library
without stacking another season in navigation history. Event broadcast workspaces
remain pushed destinations with six tabs (Sheet, Recap, Pit lane, Scratchpad,
Conversations, Calculator) and a Back to series action. The event workspace uses
the native top tab bar on iPad, adapting to bottom tabs in compact windows. It
does not force compact sizing or enable a persistent sidebar.

Navigation preferences are stored locally in UserDefaults under a versioned key
scoped to the server URL and signed-in owner. They do not expire: the last season,
last season per series, each season's tab/class/race selection, and event tab and
viewing positions survive restarts. Scroll positions include the season's vertical
tab views, championship horizontal grids, event reference tables, recap, pit lane,
and the Pencil canvas (stored in logical document coordinates). Loading placeholders
do not overwrite positions; content growth retries restoration until user interaction.
Missing season/filter/tab selections fall back to the library or a valid default.

These are navigation preferences, not data snapshots. Resource loading, offline
caches, freshness handling, and scratchpad document storage retain their existing
behavior. Resume preferences are local to this installation, not synced to other devices.

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
                (Models, SeasonModels, SheetModels, ProfileModels)
    Scratchpad/ PadDocument (the web's Stroke wire format + the LocalPad mirror),
                PadBridge (PencilKit ↔ Stroke with identity), PadModel (one open pad:
                load, mirror, debounced PUT, conflicts), PadSync (offline replay,
                FAB badges, BGAppRefresh)
    Season/     SeasonModel (hub, classes, championship selection, recap cache),
                SeasonLogic (pure ports of names.ts / raceForm.ts / venue.ts /
                ChampionshipGrid derivations), PitLaneGeo (port of pitLaneGeo.ts),
                ProfileLogic (CareerStats.tsx lines, bio facts, name→driver rule)
    Design/     Theme (DESIGN.md tokens as `PP.*`), BrandMark (the SVG mark as a Shape),
                FlowLayout (flex-wrap)
    Views/      RootView, HomeView, SignInView, SettingsView, TopBar, StatusViews,
                SeriesDirectoryView, DownloadButton
      Season/   SeasonView shell + one file per tab, GridTable, SeasonWidgets
      Sheet/    SheetView, PdfViewerSheet, PitLaneSheet, RecapSheet, SheetPrint, ScratchpadSheet
      Info/     InfoModal (targets, presenter, host, NameLink), DriverProfileView,
                TeamProfileView, ProfileWidgets (career stats, matrices, notes)
    Resources/  Assets.xcassets (AppIcon, AccentColor #f0b84a), Fonts (Inter, JetBrains Mono),
                PrivacyInfo.xcprivacy (required-reason APIs + data types, for App Review)
  release.sh    TestFlight release (below); asc_profile.py keeps its App Store profile current
  PitPassTests/ Swift Testing — OfflineStore, DataLoader (scripted transport), Downloads
                (plan + job over a path-routed transport), Pad (wire format, mirror,
                PencilKit bridge, syncer), PitLaneGeo, ProfileLogic
```

Swift 6 with strict concurrency, iPadOS 26+, iPad only (`TARGETED_DEVICE_FAMILY
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

Signing is automatic against the team in `project.yml` (`DEVELOPMENT_TEAM`,
the Apple Developer Program team ID — see "Releasing to TestFlight"). A free
Apple ID also works for a personal install, expiring after 7 days. From the
CLI — `DEVELOPER_DIR` sidesteps an `xcode-select` that still points at the
command-line tools:

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xcodebuild -project ios/PitPass.xcodeproj -scheme PitPass \
  -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M5)' \
  -derivedDataPath ios/build build CODE_SIGNING_ALLOWED=NO      # or: test
```

Release builds (including archives) always use `https://pitpass.arjunakankipati.com`.
Debug simulator builds default to `http://localhost:8731`; start the local backend
as described in README.md before launching. Debug builds on physical devices
still default to production. There is no server URL setting in the app.

For a worktree running its backend on a different port, bake the origin into the
build. For example, start that worktree's backend with `PORT=8732 ./gradlew bootRun`
from `backend/`, then build from the repository root:

```bash
xcodegen generate --spec ios/project.yml
xcodebuild -project ios/PitPass.xcodeproj -scheme PitPass -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPad Pro 11-inch (M5)' \
  -derivedDataPath ios/build build CODE_SIGNING_ALLOWED=NO \
  PITPASS_SERVER_URL=http://localhost:8732
xcrun simctl install booted ios/build/Build/Products/Debug-iphonesimulator/PitPass.app
xcrun simctl launch --terminate-running-process booted com.arjunakankipati.pitpass
```

The build setting is stored in the app's Info.plist, so it also applies to launches
from the simulator home screen or a simulator panel. Pass it on every rebuild;
without it, a new build returns to the default. Each worktree should use a distinct
backend port and its own derived-data directory. Apps share a bundle identifier,
so use separate simulator devices for simultaneous worktrees (replace `booted`
with each device's UDID).

For a temporary Debug override, set `PITPASS_SERVER_URL` in **Product → Scheme →
Edit Scheme → Run → Arguments → Environment Variables**, or use the simctl recipe
below. Resolution order is launch environment → build setting → platform default.
To test production in a Debug simulator, explicitly set
`PITPASS_SERVER_URL=https://pitpass.arjunakankipati.com`.
For a physical iPad's local backend, use `http://<your-mac-hostname>.local:8731`
on the same network; the backend must listen on a network-accessible interface.
`localhost` on an iPad refers to the iPad itself. Allow local network access when prompted.

Overrides must be full HTTP(S) origins without paths, credentials, queries, or
fragments; invalid Debug overrides stop launch with a configuration error.
Release ignores both overrides and any old saved URL. There is no automatic
fallback to production if the local backend is unavailable. Switching endpoints
on relaunch signs out and clears offline data, including unsynced scratchpad ink,
before contacting the new server. The old URL preference is removed during
migration; existing production data is retained when the endpoint is unchanged.

Simulator recipes that proved necessary:

- Launch a Debug build against local dev (terminate the app first):
  `SIMCTL_CHILD_PITPASS_SERVER_URL=http://localhost:8731 xcrun simctl launch
  booted com.arjunakankipati.pitpass`. Omit the variable to use the built-in endpoint.
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

## Releasing to TestFlight

One command per release, once the account is set up:

```bash
ios/release.sh            # unit tests → Release archive → upload → git tag
```

It reads the version from `project.yml`, stamps a build number, archives,
signs the export with the keychain's Apple Distribution certificate and an
App Store profile that `ios/asc_profile.py` keeps current through the App
Store Connect API, uploads, and tags the commit `ios/v<version>-<build>`. Flags: `--skip-tests`, `--no-upload` (export an
`.ipa` instead), `--build N`, `--allow-dirty`. Output lands in
`ios/build/release/<version>-<build>/` (git-ignored) — the `.xcarchive` there
holds the dSYMs for that build, so keep it until the next release.

### One-time setup (after Apple Developer Program approval)

1. **Xcode → Settings → Accounts**: sign in with the enrolled Apple ID (or
   press *Download Manual Profiles* / re-add it so Xcode drops the cached
   "Personal Team"). The paid team's ID is under *Membership details* at
   [developer.apple.com/account](https://developer.apple.com/account).
2. **`ios/project.yml`**: set `DEVELOPMENT_TEAM` to that team ID and commit.
   Everything signs against it — Xcode runs, the release script, and the
   certificates + profiles Xcode creates on demand (`-allowProvisioningUpdates`).
3. **App Store Connect → Apps → New App**: platform iOS, bundle ID
   `com.arjunakankipati.pitpass` (Xcode registers the identifier on the first
   signed build; if the menu doesn't offer it yet, add it under
   *Certificates, Identifiers & Profiles → Identifiers* first), SKU
   `pitpass`, primary language English. The **name** must be unique across the
   App Store — if "Pit Pass" is taken, pick a variant; it's only the store
   listing, not `CFBundleDisplayName`.
4. **Distribution certificate**: Xcode → Settings → Accounts → your Apple ID
   → *Manage Certificates…* → **+** → *Apple Distribution*. It lives in this
   Mac's keychain for a year (Xcode warns before it expires; renewing is the
   same dialog). Export it once from Keychain Access as a `.p12` and keep it
   with your backups in case you change Macs.
5. **API key** so signing and uploads never prompt for a password or 2FA:
   *App Store Connect → Users and Access → Integrations → App Store Connect
   API → Team Keys → Generate*, role **App Manager** (enough to create
   profiles and upload builds — an Admin key is not needed). Download the
   `.p8` once (Apple won't offer it again), then:

   ```bash
   mkdir -p ~/.appstoreconnect/private_keys && mv ~/Downloads/AuthKey_*.p8 ~/.appstoreconnect/private_keys/
   cat > ios/.release.env <<'EOF'
   ASC_KEY_ID=<Key ID from the keys table>
   ASC_ISSUER_ID=<Issuer ID shown above the table>
   EOF
   ```

   `ios/.release.env` is git-ignored. Without it the script falls back to the
   Apple ID signed into Xcode with automatic (cloud) signing, which works but
   may stop to ask for 2FA.
6. **TestFlight → Internal Testing → +**: create a group (e.g. "Booth"),
   tick *Enable automatic distribution*, add testers by Apple ID email. They
   must first be added under *Users and Access* (role Customer Support is
   enough) — internal testers are team members; there can be up to 100, and
   their builds skip review. Testers install the TestFlight app and accept the
   email invite once.
7. Run `ios/release.sh`. The first build for a version also needs the
   **export-compliance** answer, which `ITSAppUsesNonExemptEncryption = false`
   in `Info.plist` already gives (HTTPS only, no custom crypto).

### Every release

1. Merge to `main`, work from a clean tree.
2. Bump `MARKETING_VERSION` in `ios/project.yml` **only when testers should
   see a new version** (a new sheet feature, not a hotfix). The build number
   is a UTC minute stamp (`202609122145`) so every upload is unique and
   increasing without a counter to maintain; TestFlight rejects a repeated
   version + build, and the stamp makes that impossible.
3. `ios/release.sh`, then `git push origin main --tags`.
4. App Store Connect processes the build in 5–15 minutes; internal groups
   with automatic distribution get a push from TestFlight. Add **What to Test**
   notes on the build when there is something specific to look at.
5. Settings → About in the app shows "0.1.0 (202609122145)" and the server
   host, so bug reports can name the exact build.

TestFlight builds expire after 90 days; the sustainable rhythm is a release
after each merged batch of iPad work, and at least one every couple of months
so the booth iPads never hold an expired build.

### External testers and the App Store

Anyone outside the team (a co-commentator with their own Apple ID) goes in an
**External Testing** group. The first build in an external group goes through
Beta App Review (typically a day), later builds of the same version usually
don't. External groups need a public or invite link plus a Beta App
Description and feedback email; the app's privacy manifest
(`PitPass/Resources/PrivacyInfo.xcprivacy`) already declares the
required-reason APIs (UserDefaults) and the data the app handles (account
email, on-device location), which review checks.

Shipping to the App Store proper adds a listing (screenshots for the 13" and
11" iPad sizes, description, privacy nutrition labels matching the manifest,
a support URL) and App Review; the same archive is submitted from the
TestFlight build, no rebuild.

### Why the export is signed manually

With an API key, xcodebuild's automatic signing considers only
*cloud-managed* distribution certificates, and creating those needs an Admin
key ("Cloud signing permission error" from an App Manager key, even when your
own user has the cloud-certificate permission). Rather than hold an Admin key
on disk, the script signs the export manually: `asc_profile.py` finds the
portal certificate that matches the keychain's Apple Distribution identity,
reuses an active App Store profile containing it or creates one named
"PitPass App Store", installs it under `~/Library/Developer/Xcode/UserData/
Provisioning Profiles`, and the export options name it. Nothing needs
touching in the developer portal; a renewed certificate simply gets a new
profile on the next run. The archive step stays automatic (development
signing).

### Troubleshooting

- `no Apple Distribution certificate with a private key in the keychain`:
  one-time setup step 4. `security find-identity -v -p codesigning` lists what
  the keychain holds.
- `Cloud signing permission error` / `No profiles for
  'com.arjunakankipati.pitpass' were found`: automatic signing ran with the
  API key — `ios/.release.env` and the certificate are the fix (above). With
  no key configured, the team in `project.yml` is wrong or still the free one.
- `Unable to authenticate with App Store Connect`: the `.p8` path or IDs in
  `ios/.release.env` — the default key path is
  `~/.appstoreconnect/private_keys/AuthKey_<ASC_KEY_ID>.p8`.
- `The bundle version must be higher than the previously uploaded version`:
  a `--build` was passed that is lower than an earlier upload; drop the flag.
- The upload succeeds but the build never appears: check the App Store
  Connect email — a missing privacy-manifest reason or a bad icon (must be
  1024×1024 without alpha; ours is) shows up there, not in `xcodebuild`.
- Tests fail to find the simulator: the destination is `iPad Pro 11-inch
  (M5)`; `xcrun simctl list devices available | grep iPad` shows what this
  Xcode has, edit `release.sh` if the name changed.

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
real Google round trip was exercised end to end against production on
2026-09-12 (local dev runs with auth off).

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

`networkFirst` is for documents that must be fresh when online (`/api/me`;
the scratchpad does its own network-first load, see below). `DataLoader.bytes` is cache-first for immutable
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

### The scratchpad

The pen toolbar uses a 0.5–12 pt slider in 0.5 pt steps, with a live width
preview and numeric label on both iPad and web. On iPad, a completed Pencil
Pro squeeze toggles Pen/Eraser without resetting the pen color or width;
cancelled squeezes and system-disabled interactions do nothing. Double-tap
also toggles when the system preference is Switch Eraser or Switch Previous.
Pencil hardware gestures need validation on a physical iPad.

`ScratchpadSheet` is ScratchpadModal.tsx over a `PKCanvasView`: PencilKit
draws, erases and undoes; the web's `[Stroke]` stays the document and the
wire format is untouched (`{id, tool, color, size, points}` in the 800-wide
logical column, tenths of a px), so the desktop pad reads iPad ink and vice
versa. The pieces:

- **`PadBridge`** converts both ways with identity. A `PKStroke` built from
  a web stroke carries a private creation date as its key, so when PencilKit
  hands the drawing back every stroke it didn't touch maps to the *original*
  `Stroke` object — desktop ink never gets re-sampled by a trip through the
  iPad. Only pen-drawn strokes are exported: control points, thinned at
  1.5px and rounded to tenths like the web's pointer samples. Two PencilKit
  facts the bridge encodes: it fits a B-spline *through* its control points,
  so web strokes are fed the web's own quadratic-through-midpoints curve
  sampled every ~3px (a square stays a square); and monoline points store
  the tool width plus 2, so sizes shift by 2 in each direction. Ink is
  `.monoline` (uniform width, like the web's lineWidth), the eraser is the
  vector one (whole strokes, like the web's), paper is white in both themes
  because ink colours are persisted literals.
- **`PadModel`** is the modal's state: network-first load against the
  `LocalPad` mirror (dirty local ink wins and syncs; a moved-on server is the
  conflict case), every completed change mirrored to SQLite first, a 2.5s
  debounced whole-document PUT, 409 → conflict banner with the one-slot
  backup for the loser, 413 → "pad full", transport failure → "Offline —
  saved on this iPad" and a retry on the next 'live' flip.
- **`PadSyncer`** on `AppSession` is scratchpadSync.ts: replays dirty
  mirrors on start and on every offline→live flip, skipping the pad whose
  sheet is open, flagging (never resolving) 409s; it also feeds the sheet
  FAB's badge (amber = unsynced ink, red = conflict). A `BGAppRefreshTask`
  (`com.arjunakankipati.pitpass.padsync`) gives ink one more chance to sync
  after the app is backgrounded. Mirrors survive "Clear offline data"; only
  sign-out or a server change wipes them.

Canvas mechanics worth knowing: the 800-wide column is fitted to the window
by a fixed zoom (min = max = width/800) with content size 800×pageHeight
scaled, the way Apple's PencilKit sample does it; the fit resets the content
offset (re-zooming otherwise leaves the page scrolled mid-way), and a drawing
is only installed once the view has a real width — installed at the
placeholder zoom it is rasterised blurry and stays that way. On the
simulator the drawing policy is `.anyInput` because it reports "Only Draw
with Apple Pencil" on; devices use `.default`.

One rule fell out of the downloads work: `Loaded.digest` identifies the stored bytes. A
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
| `.sp-*` (scratchpad chrome, swatches, conflict bar, save status) | `ScratchpadSheet` |
| `.drv-link`, `.dm-*` (DriverModal / TeamModal / CareerStats) | `NameLink`, `DriverProfileView`, `TeamProfileView`, `CareerStatsView`, `ChampMatrixTable` |

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
first. The standalone Photos page is omitted from the app; upload and number
matching stay on the website.

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

### Driver and team profiles

`Views/Info/` is InfoModalProvider + DriverModal.tsx + TeamModal.tsx. Every
name the website links is a `NameLink` here — the sheet's crews, the entries
matrix (team and crew), both standings grids (the recap row and the points
view's name cell with its team sub-line), the stats rows and the results
classification (team, crew, the attributed qualifying or fastest-lap
driver). `InfoTarget` is the website's `Open` union: a driver by id where
the payload carries one (stats, a team roster), by name elsewhere — resolved
like `openDriverByName`, through `/api/drivers/search` (network first, so a
stored answer serves offline) with the exact match preferred; a team by name
(the backend normalises spelling) or by entity id (stats, lineage). TBD
seats and "Privateer" stay plain text.

`.infoModalHost()` installs the presenter on a **navigation stack** (the
home stack, the recap sheet's) — pushed screens inherit the stack's
environment, not the root view's modifiers, which is where the first cut
went wrong. Inside the sheet a second presenter *pushes* instead: driver →
team → driver reads as drill-through with a Back button, where the website
replaces one modal with the next. Every level carries Done. Where no host
is installed the name renders as plain text rather than a dead button.

Profiles and stats load through `Resource` (`/api/drivers/{id}/profile`,
`/stats`, `/api/teams/profile?id=|name=`, `/api/teams/{id}/stats`), so a
visited profile paints from the store offline and a changed one waits behind
the usual nudge; the photo and roster liveries are `?v=`-stamped bytes
(`CachedImage`). Profiles are not part of a Download bundle — only what has
been opened is stored. The championship matrices are the `GridTable` with
the row heads pinned (`ChampMatrixTable`: a Result row per driver or per
car, and a Pts row when any points exist). Read-only by the usage model:
bio, photo and the broadcast notes are edited on the website; the notes
show here as text.

## Parity checklist

Every feature that touches an iPad surface (directory, season pages, sheet,
pit lane, later the pad) ships twice. Before closing a web slice that changes
one of those payloads, tick: `Model/` updated → view updated → PLAN.md
parity row. Where the web's derivation lives in `lib/*.ts`, its port is in
`Season/SeasonLogic.swift` or `Season/PitLaneGeo.swift` — change both.

## Native season navigation

Season screens use five native tabs: Overview, Races, Standings, Stats and
Entries. The tab container uses compact navigation to keep
the bar at the bottom on iPad, while content retains the actual size class.
iPadOS 26 is the minimum supported version and supplies Liquid Glass.
The title menu switches year/stage, and class filtering uses an compact segmented control with configured class-colour swatches.
Races uses a horizontally scrolling round selector, initially selecting the
next dated event or the latest event, with results and event-sheet access below.
UI text uses the system font; numeric data retains JetBrains Mono.

## App icon

`ios/PitPass/Resources/AppIcon.icon` is the editable Icon Composer source for
the Liquid Glass app icon. Open it in Icon Composer (included with Xcode 26+).
Its four SVG layers preserve the existing Access Lane mark: the rail and two
P forms share a glass group, with the gold access marker in a group above it.
The charcoal background and system-generated Default, Dark, and Mono appearances
were visually checked in Icon Composer. Edit the SVGs inside the package's
`Assets` directory to change the artwork; let Icon Composer provide the glass
effects and system corner mask.

XcodeGen automatically includes the `.icon` package from the app sources. Keep
its basename `AppIcon` aligned with `ASSETCATALOG_COMPILER_APPICON_NAME` in
`ios/project.yml`. The existing `AppIcon.appiconset` remains for compatibility;
the in-app `AppIconPreview` and web icons are separate static assets.

Validation: generated the Xcode project and built the unsigned app for a generic
iOS device with Xcode 26.6 (deployment target remains iOS 18).


### Event conversations (2026-09-16)

The event workspace now has five native tabs: Sheet, Recap, Pit lane,
Scratchpad, Conversations. The Conversations list contains only drivers with
saved conversations or “Want to speak to” entries. The full event roster is
available in the add/record chooser, with search by name, car and team.
Unassigned recordings have their own section and can be assigned later.

Session is a menu picker in both the working context and conversation details.
Choices include standard pre-import sessions, imported event session names and
existing journal labels. Matching ignores case and whitespace, preserving old
notes without making the session filter depend on exact free-text spelling.
Swipe-to-delete is available on driver, conversation, planned-contact and
unassigned-recording rows. Driver deletion confirms that it removes the entire
event history (all sessions) in one journal update, including associated audio.
Active recording or transcription prevents deletion of the affected rows.

Each entry carries the driver/car/team snapshot, session label, broadcast
context (Before going live / On air), time, topic/questions, takeaway and
optional PencilKit ink. These are event-specific notes, separate from profile
biographies. Notes save as edited; planned contacts can be marked as spoken to
or recorded directly. Existing entries are reachable from the driver's sheet
indicator; the driver name's context menu opens conversations even with no
prior entry. The main scratchpad is unchanged.

`Conversations/ConversationBook.swift` atomically persists JSON under Application
Support/PitPass/Conversations/<SHA256(server|owner)>/<eventId>. Leading zeros in
car numbers are retained. This is authored data, not cached reference material:
clearing downloads or signing out does not delete it. It becomes accessible
again when the same account signs in to the same server. There is no server
sync or team sharing in this version. Delete removes the selected entry and
its audio. Export audio through the system share sheet before uninstalling if
it needs to be retained elsewhere.

`ConversationAudio` records mono 24 kHz 16-bit PCM CAF files locally. CAF is
chosen to tolerate interrupted recording better than a finalized MP4 container.
The journal is written before capture starts; interruption/backgrounding stops
capture, and reopening flags unfinished capture for review. Bookmarks preserve
audio offsets. Saved files remain available if transcription fails. Back-to-back
recordings queue for transcription; retry never overwrites newer typed notes.

SpeechAnalyzer + SpeechTranscriber require iPadOS 26; English (en-US equivalent
locale) is the only requested language. “Prepare English” installs system
speech assets in advance. Transcription runs **after Stop & save**, on device,
without uploading audio. Unsupported hardware or missing assets leave recording
and notes usable with an explicit retry message. Transcripts have timestamped
passages, playback seeking and “Add to takeaway”; they do not assign speakers or
generate summaries. Microphone permission is requested only when recording.

Validation: ConversationTests covers round trips (including ink/transcripts),
sparse driver membership, namespace isolation, interrupted-recording recovery,
and refusal to overwrite an unreadable journal. ConversationRenderTests provides
synthetic native event-tab captures for light/dark review. Actual microphone
quality and English asset/transcription behavior still require a supported
physical iPad; simulator renders do not establish speech accuracy.
