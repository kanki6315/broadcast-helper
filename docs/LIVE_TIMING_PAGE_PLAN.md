# Live timing page, drive time and IMSA energy: plan

Status: **plan approved 2026-09-29. Slices 0, 1 and 2 are done. Slice 3 (web page) is next.**
Branch: `claude/custom-live-timing-page-882b20`.
This document is the handoff: everything a fresh session needs is here or in
`docs/LIVE_TIMING.md` (the existing feed pipeline).

## Goal
- A custom live timing page, on the web first and the iPad later.
- Lap and stint history, collected server-side from Al Kamel `timing.analysis`.
- Drive time per driver, checked against the drive time rule. The IMSA rule
  **excludes pit lane time**, so use `driverAccumSessionTrackTime`.
- IMSA `energy_remaining` from imsa.com/telemetry, synced to Al Kamel cars
  and laps.

Decisions made by Arjuna:
- Web first, then iPad.
- Drive-time limits are entered by an admin, per event and per class.
- Laps and stints are kept permanently, at about 100 bytes per lap.

## Ground rules (carried over from the feed work)
- **One concurrent Al Kamel login.** Only production connects. Never set
  `ALKAMELV2_HOST` locally. Develop against recordings through
  `pit-pass.alkamel-v2.replay.file`.
- The deployed server is protocol 1.0.33, older than the spec PDF (1.0.36).
  Treat the spec as a guide, test against real bytes, and keep parsers
  tolerant. For example, LOGIN replies arrive as `1++`.
- Car numbers must match **exactly first**: real grids have #04 and #4, and
  #23 and #023, as different cars.
- Every new env var goes into `application.yml` as a placeholder. A `@Value`
  default alone silently ignores the env var. Al Kamel vars use the
  `ALKAMELV2_` prefix.
- **Recordings are licensed feed data. Don't commit them.** Committed test
  fixtures are synthetic, built in the real shapes. The real recordings live
  on Arjuna's machine (`~/Downloads/aks-v2_2026-09-20_*.aks.gz`) and in the
  private R2 bucket (`ALKAMELV2_RECORDING_BUCKET`, `aks-v2/<date>/`).
- The backend uses JdbcClient with raw SQL, not JPA. Flyway migrations: the
  latest is V56, so the next is **V57**.

## Cost and memory reasoning
- Railway bills outbound traffic, not inbound, so both feeds are free to
  receive. Viewer polling of a gzipped tower costs pennies per race.
- **Memory is the risk.** The container runs with `-Xmx256m` (see the Dockerfile).
  - Holding Daytona's laps as a Jackson tree would take 300–510 MB. That is
    6.4–12.8 KB retained per lap, about 6.5× the raw JSON.
  - A reconnect snapshot is a single 45–78 MB line, which exceeds
    `max-line-bytes` (32 MB). A reconnect would therefore loop forever.
- Fix: analysis **never enters memory as a tree**. Stream-parse it into
  compact Postgres rows and keep only small per-car summaries in RAM.

## Slice 0 findings (real Indianapolis race, 2026-09-20)
All from one post-race snapshot recording. There is no analysis data in it:
the recordings to date only hold the narrow `timing.session.*` channels.
- **Session key:** `timing.session.info.sessionDbId` (e.g. 3150), plus
  `sessionMongoId`, `eventDbId`, `name` ("Race") and `date` (epoch ms).
  `info.closed` marks a finished session.
- `info.stintCalcType: "IMSA"`: Al Kamel computes stints under the IMSA rules.
- **Entry drivers:** `timing.session.entry.<car>.drivers` is keyed `"1"`,
  `"2"`, … and matches each driver's `number`. That is the driver *order*,
  which is what stints and laps call `driver`.
  - Each driver has `firstName`, `lastName`, a three-letter `shortName`
    (e.g. `"Kur"`), `license` (Bronze/Silver/Gold/Platinum), `country` and
    `hometown`.
  - The entry itself carries `currentDriver` and `firstDriver`.
- **Matching against local event 224** (the same race, imported):
  - Cars: 44 of 44 matched exactly by number.
  - Drivers: 88 of 88 matched by surname within the car.
  - Rating: the feed's `license` agreed with `driver_assignment.rating`
    (`B`/`S`/`G`/`P`) 100% of the time.
  - Classes (GTP/LMP2/GTDPRO/GTD) were identical.
  - So rules can key on our rating, with the feed `license` as a fallback
    when a driver is unmatched.
- **Interval needs no computing.** `timing.session.standings.byClass.active`
  already carries `gapPreviousTime` / `gapPreviousLaps`, as well as
  `gapFirst*`.
- `timing.session.status` gives `sessionStartTime`, `finalType` (`BY_TIME`),
  `finalTime` (seconds), `currentFlag`, `isFinished` and `isClosed`.

## Spec shapes for analysis (spec 1.0.36, pp. 52–58)
- `timing.analysis.laps.<car>.laps.<lapNum>`: `driver` (order),
  `driverLapNum`, `isValid`, `isLongLap`, `isShortLap`, `lapNum`, `position`,
  `startTime` (epoch ms), `time` (ms), `topSpeed`, `trackLimits`, `pitIn` /
  `pitOut` `{driver, time}`, `sectors.<n>{flag, isValid, number, time,
  trackLimits}`.
  - The heavy parts are `loopSectors` and `sections`. **Skip them.**
- `timing.analysis.stints.<car>.stints.<startTime>`: `type` (TRACK/PIT),
  `pitType` (PENALTY/SC), `driver` (order), `openLapNumber`, `closeLapNumber`,
  `startTime`, `finishTime`, `driverAccumSessionTrackTime`,
  `driverAccumSessionTime`, `driverAccumTrackTime`, `driverAccumTime`.
  - The spec's own example is internally inconsistent about the accumulators.
    Verify against real bytes.
- `pitIn` / `pitOut` sub-channels: skipped. Laps and stints already carry
  that information.
- Each sub-channel can be JOINed on its own. A JOIN gets a full snapshot, then
  diffs, where null means delete. Diffs are rooted at `{"timing":{...}}`, and
  the frame's channel header is empty on pushes.

## IMSA telemetry source (found 2026-09-29, never seen live)
- imsa.com/telemetry is an iframe of `https://d3aqeo5txo0gzi.cloudfront.net/`,
  an Angular app from IMSA Labs.
- The app uses **AWS AppSync Events**. The endpoint is
  `https://wzidxebhlbgqpm7kt22wkx2pri.appsync-api.us-east-1.amazonaws.com/event`;
  the realtime host is the same id with `appsync-realtime-api`.
  - Auth is by API key, using a public `da2-…` key embedded in `main-*.js`.
  - The key rotates, so scrape it from the bundle each time you connect.
    Never hard-code it.
- Channels: `telemetry/message` and `telemetry/session`. `event.data` is
  **base64 JSON**.
  - A `message` is an array of per-car objects: `car_id`, `speed` (km/h),
    `throttle_percentage`, `brake_percentage_front` (raw; the app scales it
    by /35×100), `gear`, `energy_remaining` (%), `regen`, `is_recharging`,
    `pit_lane`, `is_jacked_up`, `time`, `sIndex`, and
    `scoring{position, class, number, team, manufacturer, activeDriver,
    lapNumber}`.
  - A `session` carries `session_start_time` (epoch s), `session_duration`
    and `seconds_remaining`.
- The app animates over 900 ms, so the stream is probably about 1 Hz.
  Unverified: the lap-number offset against Al Kamel, which classes carry
  energy, and the real rate and message size.
- This endpoint is unofficial, so the adapter must fail soft.

## Slices

### Slice 1: streaming analysis ingest (backend, the hard part) — DONE 2026-09-29

Built as planned; `docs/LIVE_TIMING.md` *timing.analysis* describes it as it
is. Where the build settled something the plan left open:
- `AksLineReader` owns line framing for both paths. Streamed JSON only when
  analysis is on, so production with the flag off runs the old buffered path.
  An unparseable streamed frame is read to its end, recorded, and reported as
  a warning; it no longer drops the login.
- `live_session` is keyed by `sessionDbId` itself. Its `event_id` is the event
  bound when the session was last seen.
- `live_driver` also stores `entry_id` and `rating` (ours, or the feed
  license's first letter when unmatched).
- The writer's queue holds 100,000 patches. A full queue drops and counts;
  the next reconnect snapshot rewrites the rows.
- `LiveCarSummaries.bestStale` flags a best lap invalidated after the fact.
  Slice 2 should read that car's best lap from `live_lap`.
- Heap measured: a 39,000-lap, 88 MB single-line snapshot retains ~28 MB with
  the whole snapshot queued. **The 256 MB cap stays.**

Still unverified until a practice session is recorded with the flag on:
- JOIN snapshot framing. Rooted at `{"timing":…}` like pushes, or at the
  channel? Both are handled.
- Field types. For example, whether `trackLimits` is a count or a flag,
  whether `pitIn.time` is epoch or duration, and whether `topSpeed` is km/h.
  The parsers are tolerant, but the column meanings are guesses.
- When the stint accumulators update (live or at stint close), and which one
  matches the IMSA rule. This decides slice 2's `DriveTime`.
- Whether `lapNum` keys are 1-based, and whether a lap in progress appears
  before it has a time.
- Real snapshot size and diff rate, and heap and batch timing on Railway.
- Whether `info` really arrives before analysis on a fresh JOIN. If not,
  `analysis.withoutSession` counts the loss.

Original plan:
- **`live/AksConnection.java`:** `readLine` buffers whole lines, and
  `handle` calls `mapper.readTree` and `tree.merge`.
  - For `JSON` frames, read the `CMD:id:channel:` prefix, then hand a
    line-bounded `InputStream` to a streaming consumer. The stream stops at
    `\n` and strips `\r`.
  - Tee the raw bytes to `LiveRecorder` so recordings stay byte-identical
    (`<epochMs>\t<line>`).
  - Non-JSON frames keep the buffered path. `max-line-bytes` then guards only
    buffered frames. Streamed frames get a counted sanity cap, around 512 MB,
    and are never held in memory.
- **New `AnalysisRouter`** (Jackson `JsonParser`):
  - Read each lap object on its own, calling `skipChildren()` on
    `loopSectors` and `sections`, to produce a `LapRow`. Stints produce
    `StintRow`s.
  - Anything under `timing.session` is read as a tree and merged into
    `AksStateTree` as it is today. The tree never holds analysis.
  - A null value deletes the row. A partial diff becomes a partial upsert.
- **New `AnalysisWriter`:** a bounded queue plus one virtual thread doing
  batched JdbcClient upserts every 1 s or 500 rows.
  - The socket thread never waits on the database. The Hikari pool has 5
    connections.
  - A reconnect snapshot re-upserts idempotently.
- **`LiveCarSummary` map:** per car, last lap, best lap, open stint and laps
  in stint. This is the only analysis state kept in RAM.
- **Config:** add `ALKAMELV2_ANALYSIS_ENABLED`, default false. When set, also
  JOIN `timing.analysis.laps` and `timing.analysis.stints`.
- **V57:**
  - `live_session`: event_id, `sessionDbId`, name, type, date.
  - `live_lap`: PK (session, car, lap). Also driver_order, times, validity,
    pit in/out, `sector_ms int[]`, `sector_flags text[]`.
  - `live_stint`: PK (session, car, start_time_ms). Type, pit_type,
    driver_order, open/close lap, finish, and the four accumulators.
  - `live_driver`: (session, car, driver_order) → names, license, resolved
    `driver_id`.
- **Matching:** lift `entries` / `numberAliases` / `classAliases` / `crews`
  out of `LiveClassificationService` into a reusable `LiveEntryMatcher`.
  Crews gain `driver_id` and `rating`. Unmatched drivers are listed, never
  dropped.
- **Heap guard:** a test that streams a synthetic Daytona-size snapshot line
  (about 39k laps, 20 loop sectors). Also measure retained heap with
  `-Xmx256m` and record the result in `docs/LIVE_TIMING.md`. Go to 384 MB
  only with the measurement as justification.

### Slice 2: drive time and the timing API (backend) — DONE 2026-09-29

Built as planned; `docs/LIVE_TIMING.md` *Timing page API* and *Drive time*
describe it as it is. Where the build settled something:
- A rating's rule takes any bound it leaves blank from the class-wide rule,
  so a Bronze minimum still sits under everyone's maximum.
- `DriveTime.driveMs` is the one method that holds the open-stint
  assumption: an open TRACK stint with no accumulator adds its elapsed time,
  and one carrying an accumulator is taken as live.
- "Now" is the wall clock only while the session is being fed and its newest
  feed time is within 10 minutes. Otherwise it is that newest feed time, so
  replays and finished sessions stop counting.
- The tower sends `stintStartMs` rather than an elapsed time, so the body
  stays ETag-stable. The client counts up. In a replay the client's clock is
  not the feed's; slice 3 should decide whether to show elapsed time there.
- `server.compression` was already on for JSON. Checked on a spare-port
  backend over a synthetic replay: gzip, 304 on `If-None-Match`, 2/2 cars and
  4/4 drivers matched to event 224.
- `/api/live/timing` has a current-driver fallback: the feed's
  `entry.currentDriver`, else the open stint's driver.

Still unverified on real bytes: the accumulator question above; whether
`entry.currentDriver` is present and uses the driver order; whether PIT
stints are how the feed marks a car in the pit lane (the standings may carry
a better flag).

Original plan:
- **V58 `drive_time_rule`:** (event_id, class_name, rating nullable,
  min_ms, max_ms, note). `GET/PUT /api/events/{id}/drive-time-rules`, where
  PUT is admin-only.
- **Pure `DriveTime` class:** take the latest
  `driver_accum_session_track_ms` per driver. If the accumulator only updates
  at stint close, add `now − start` for an open TRACK stint; that choice sits
  behind one method.
  - Status: `OK`, `UNDER_MIN` (with time owed), or `OVER_MAX`.
- **Member GETs:** these use the existing ETag filter, so bodies carry no
  timestamps.
  - `/api/live/timing`: the tower. Classification, gaps and intervals come
    from standings. It also carries current driver, last/best lap, pit
    status, stint laps and time, and `energyPct`.
  - `/api/live/cars/{car}?session=`
  - `/api/live/drive-time?session=`
  - `/api/live/sessions?eventId=`
- Enable or confirm `server.compression` for JSON.

### Slice 3: web page
- Chrome-less route `/timing/:eventId` beside `/sheet/:eventId` in
  `frontend/src/App.tsx`, linked from `EventDetailPage.tsx`.
- Tower: a `.grid-table` with class bands, tabular mono numbers, dark
  first. Polls every 2 s with `useLivePoll`.
- Car panel: laps and stints, polling every 10 s while open.
- Drive time tab, with an admin rules editor.
- No connection controls on the web.
- Playwright test `frontend/tests/liveTiming.browser.cjs`, run as
  `npm run test:timing:live`.

### Slice 4: IMSA telemetry adapter (backend)
- A `java.net.http.WebSocket` client using subprotocols
  `aws-appsync-event-ws` + `header-<base64url({host,x-api-key})>`. It
  subscribes to both channels and handles keep-alive.
  - Put it behind a `TelemetrySource` interface with websocket and replay
    implementations.
- Start and stop it from `LiveTimingService.tick()`: same lease holder, own
  backoff. Gate it on `IMSA_TELEMETRY_ENABLED`.
- Keep only the latest message per car in memory. On a `scoring.lapNumber`
  increment, write **V59** `live_energy_lap` (session, car, lap,
  energy_pct).
- Record raw frames to R2 under `imsa-telemetry/` for replay.
- Expose energy % in the tower (null when stale, over 15 s), energy per lap,
  and per-stint average plus projected laps left.

### Slice 5: iPad
- A `timing` case in the `SheetView` `Page` enum, using `LiveFeed<T>`.
- Connect and disconnect stay in `LiveTimingBar`.

## Rollout
1. Merge slices 1–2 with `ALKAMELV2_ANALYSIS_ENABLED=false`.
2. At the next **practice** session, set it to true. The recording captures
   the first real analysis bytes, which become the local fixture.
3. Verify against those bytes: stint accumulators, diff shapes, snapshot
   size, and heap on Railway.
4. Tune slice 3 on that replay. Capture telemetry for the first time at the
   same session.
5. Nothing is enabled for a race until a practice session has run clean.

## Verification
- `./gradlew test --tests 'com.pitpass.live.*' --tests com.pitpass.auth.SecuredChainTest`
  - Router, large-snapshot, `DriveTime` and telemetry-sampling tests.
  - `LiveTimingServiceTest` over `AksReplayServer` checks database rows and
    byte-identical recordings.
- `npm run build` (runs `tsc -b`), then `npm run test:timing:live`.
- Local end to end with `replay.file`. Check that `/api/live/timing` returns
  gzipped responses and 304 when nothing changed.
