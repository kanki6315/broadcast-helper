# Live timing (Al Kamel AKS V2)

The backend can hold a connection to Al Kamel's live timing feed and keep the
current state of the session in memory. This is the pipeline only: live
championship points, and later a live timing page, are built on top of it.

Code: `backend/src/main/java/com/pitpass/live/`. Schema: `V54__live_timing.sql`,
`V57__live_analysis.sql`.
Protocol reference: *Timing AKS V2 Protocol (JSON)* 1.0.36.

## The one rule: a single login

The account allows **one concurrent login**. Everything below follows from it.

- **Production is the only thing that ever connects.** Never put
  `ALKAMELV2_HOST` in a local environment. Local development uses a recording
  (see *Developing without the feed*).
- **Don't use the same credentials in another tool while Pit Pass is
  connected** (Al Kamel's own timing app, a second deployment). What the server
  does with a second login — refuse it, or drop the first — is not documented
  and **has not been tested yet**. Find out on a practice session, not a race.
- **Only one process dials.** The connecting process holds a lease in the
  `live_timing` row and renews it every 3 s (30 s lease). On a redeploy the old
  and new processes overlap: the new one reports `STANDBY` until the old one
  receives SIGTERM, closes the socket and releases the lease — a few seconds.
  A process that crashes simply stops renewing and is replaced after 30 s.
- **A refused login waits at least 60 s** before the next attempt, so a login
  that is in use elsewhere is not hammered. A dropped link retries on a
  2 → 60 s ladder, reset once a connection has held for a minute.

What the admin asked for (connected or not, and for which event) lives in the
same row, not in memory, so a redeploy mid-race resumes on its own.

Live data lives in the memory of the process holding the lease. That is fine
on a single Railway replica; with more than one, requests landing on the other
replica would see `STANDBY` and no data.

## Control

| Endpoint | Who | |
|---|---|---|
| `GET /api/live/status` | member | State, bound event, the event the session on track is filed under (`filedEventId`), what the feed says is running, message counters, last error. Poll it. |
| `GET /api/live/classification` | member | The running order per class, matched to the filed event's entries — see below. Poll it. |
| `GET /api/live/championships/{id}` | member | One class championship's rows against the running order — see *Championship positions*. Poll it. |
| `POST /api/live/connect` `{ "eventId": n }` | admin | Ask for the connection and bind it to the Pit Pass event it is scored against. The iPad's live timing bar and, for admins, the web Timing page's top bar both call it. |
| `POST /api/live/disconnect` | admin | Close the socket and free the login. |
| `GET /api/live/timing` | member | The timing page's tower — see *Timing page API*. Poll it. |
| `GET /api/live/cars/{car}?session=` | member | One car's laps, stints and drivers. |
| `GET /api/live/drive-time?session=` | member | Drive time per driver against the event's rules. |
| `GET /api/live/sessions?eventId=` | member | The sessions recorded for an event, newest first. |
| `GET` / `PUT /api/events/{id}/drive-time-rules` | member / admin | The event's drive-time rules; PUT replaces the whole set. |
| `GET /api/live/state?path=timing.session.info` | admin | The merged feed at a dotted path (blank = everything). The licensed feed verbatim, hence admin-only. |

States: `NOT_CONFIGURED` (no host), `OFF`, `CONNECTING`, `LIVE`, `BACKING_OFF`
(link lost or login refused — see `lastError` / `nextAttemptAt`; the last-known
data is kept), `STANDBY` (another process holds the lease). `OFF` and
`STANDBY` hold no data.

The feed's own session (`status.session`) is shown next to the bound event so
a mismatch — connected during the wrong series' session — is visible. The feed
is never trusted to pick the event.

**Bound vs filed.** `eventId` is the event an admin bound the connection to:
where the connect control stands, and which event filing tries (see
*Which event a session belongs to*). `filedEventId` / `filedEventName` is
the event the session on track is actually filed under, read from
`live_session`. **Every Pit Pass overlay — entries, teams, class colours,
driver links, championship positions — comes from the filed event, never
the binding.** A binding can outlive its series' session: on 2026-09-30 an
IMPC binding stayed up while VP Racing ran, and matching VP cars against the
IMPC entry list put IMPC teams on VP cars that share their numbers. Filed
nowhere, the classification and tower show the feed's own teams and cars,
and no championship is projected. With `timing.analysis` off there are no
session rows and nothing is filed, so `filedEventId` falls back to the
binding, as before.

## Classification

`GET /api/live/classification` is what the championship calculators score. No
points are computed on the server — the scales live in the web and iPad
calculators — it supplies the positions a person would otherwise type in.

```json
{ "state": "LIVE", "eventId": 8, "eventName": "Rolex 24 at Daytona",
  "filedEventId": 8, "filedEventName": "Rolex 24 at Daytona",
  "session": { "name": "Race", "type": "RACE", "flag": "GREEN", "running": true, "finished": false, … },
  "classification": {
    "classes": [ { "className": "GTP", "feedClass": "GTP", "cars": [
        { "position": 1, "carNumber": "93", "competitorKey": "93", "entryId": 412,
          "teamName": "…", "vehicle": "…", "manufacturer": "…", "guest": false,
          "status": "CLASSIFIED", "laps": 50, "gapToLeaderMs": null, "gapToLeaderLaps": null } ] } ],
    "matched": 56, "total": 57,
    "unmatched": [ … ], "missing": [ … ], "classMismatches": [ … ] } }
```

- **Matching is by car number within the filed event**, exactly as written
  first: a grid really does hold #04 and #4, #23 and #023, as different cars.
  Only a number with no exact match falls back to ignoring leading zeros, and
  only when that points at a single entry; otherwise it is left unmatched.
- **`className` is ours, `feedClass` is Al Kamel's.** A feed class is named
  after the class most of its matched cars are entered in ("GTDPRO" vs
  "GTD PRO" needs no configuration); with no car matched, the series'
  class alias is used.
- **`competitorKey`** is the TEAMS standings key: the entry's number through
  the season's `car_number_alias`, leading zeros dropped. Compare it to a
  standings key normalized the same way, within the class.
- **Nothing is dropped.** `unmatched` = in the feed but not the event (a late
  entry — it keeps its place in the order, so cars behind it are not promoted);
  `missing` = entered but not in the feed; `classMismatches` = running in a
  feed class other than the one entered. `matched` of `total` near zero means
  the feed is showing another series' session: check `session` against the
  bound event. In that case `missing` is left empty rather than listing the
  whole entry list. (With filing, that session is normally filed nowhere, and
  then every car is unmatched by design.)
- **Built for polling.** The body carries no timestamps or counters, so it
  changes only when the order (or a gap) does and `If-None-Match` earns a 304.
  Staleness is `state`: `BACKING_OFF` means last-known order, `OFF`/`STANDBY`
  mean none. `LIVE` is declared at login, a moment before the first snapshot
  lands, so an empty `classes` right after connecting is normal.
- A session's `type` (`RACE`, `QUALIFYING_*`, `FREE_PRACTICE`) says which
  calculator column the positions belong to; that choice is the client's.

Not modelled here (flag as provisional in the UI): points eligibility of guest
cars (`guest` is passed through), drive-time minimums, post-race penalties.

## Championship positions

`GET /api/live/championships/{id}` answers, for one class championship, where
each standings row is scoring right now. It is what the calculators' Live mode
polls, on the iPad and the web (one request per class shown). Still no points on the server: a
position here is scored by the client exactly as one set by hand.

```json
{ "state": "LIVE", "eventId": 8, "championshipId": 322, "kind": "MANUFACTURERS", "className": "GTP",
  "livePhase": "RACE", "qualifyingImported": true,
  "rows": [ { "competitorKey": "Porsche",
              "live": { "position": 1, "carNumber": "7", "teamName": "…", "status": "CLASSIFIED",
                        "laps": 72, "gapToLeaderMs": null, "gapToLeaderLaps": null },
              "qualifyingPosition": 3 } ],
  "newcomers": [ { "name": "…", "carNumber": "93", "position": 5 } ] }
```

`live.position` is already by the rule of the championship's kind:

- **TEAMS** — the car's position in class. Rows match by car number through
  the season's `car_number_alias`, leading zeros ignored on both sides, with
  the recap's team-name fallback for championships keyed by name.
- **DRIVERS** — the position in class of the car the driver is entered in
  (`driver_assignment` of the bound event); every member of a crew takes the
  car's position. Names match the standings key or name, case-insensitively.
- **MANUFACTURERS** — only the best-placed car of each make counts, and the
  makes are ranked among themselves: a Porsche 1-2 ahead of a Cadillac makes
  Cadillac second. Only makes with a standings row take part — an unregistered
  make neither scores nor pushes anyone down.

`livePhase` says which column the live positions fill: `RACE`, `QUALIFYING`,
or null for a session that pays nothing (practice). `qualifyingPosition` is the
filed event's **imported** qualifying result, ranked by the same rules:
official standings leave a weekend's qualifying points out until after the
race, so a live race projection adds them itself — and says so when the
qualifying result has not been imported (`qualifyingImported: false`).

`newcomers` are scoring now without a standings row (a late entry, an
endurance-only driver): named, with a baseline of zero. Overall championships
answer 422 (positions are per class); a championship of another season than
the filed event answers 409. With the session filed nowhere the response has
`eventId: null` and no rows.

Assumed, not yet checked against the regulations: that manufacturers score
qualifying points on the same collapse-and-rerank rule as race points, and
that every driver of a car takes the car's qualifying points.

Known gap: IMSA qualifies class groups in separate sessions. When the timing
system loads the next session the previous group's live order is gone, and its
qualifying column is empty until that result is imported.

## Configuration

All `ALKAMELV2_*`. Set the first three on Railway; the rest have working defaults.

| Variable | Default | |
|---|---|---|
| `ALKAMELV2_HOST` | — | Timing server host. **Blank = feature off, no background thread.** |
| `ALKAMELV2_USERNAME` | — | |
| `ALKAMELV2_PASSWORD` | — | Never logged, never recorded (only inbound lines are recorded). |
| `ALKAMELV2_PORT` | `11001` | |
| `ALKAMELV2_TLS_ENABLED` | `true` | The feed is TLS. Off only for the local replay server. |
| `ALKAMELV2_TLS_VERIFY_CERTIFICATE` | `false` | The spec says to ignore certificate errors. The trust-all context is scoped to this one socket. Set `true` if the server's certificate proves valid. |
| `ALKAMELV2_CLIENT_APP_NAME` | `Pit Pass` | Sent in LOGIN. |
| `ALKAMELV2_CHANNELS` | info, entry, classes, status, standings.byClass.active, startingGrid (all under `timing.session.`) | Comma-separated. Excludes `timing.analysis`, which `ALKAMELV2_ANALYSIS_ENABLED` adds and streams separately. |
| `ALKAMELV2_MAX_LINE_BYTES` | `33554432` | A longer line is a protocol fault, not buffered. |
| `ALKAMELV2_CONNECT_TIMEOUT_SECONDS` | `10` | |
| `ALKAMELV2_LOGIN_TIMEOUT_SECONDS` | `15` | How long the TLS handshake, and then the LOGIN reply, may each take. |
| `ALKAMELV2_RECORDING_ENABLED` | `true` | |
| `ALKAMELV2_RECORDING_BUCKET` | — | A **private** R2 bucket on the same account/keys as images. Never the public images bucket (the code refuses it). Blank = segments stay on local disk, which on Railway does not survive a redeploy. |
| `ALKAMELV2_RECORDING_DIRECTORY` | system temp `/pit-pass-aks` | Where segments are written before upload. |
| `ALKAMELV2_RECORDING_SEGMENT_MINUTES` | `10` | |
| `ALKAMELV2_RECORDING_MAX_LOCAL_MEGABYTES` | `512` | Oldest local segments pruned past this. |
| `ALKAMELV2_REPLAY_FILE` | — | Local dev: replay this recording instead of connecting. |
| `ALKAMELV2_REPLAY_SPEED` | `1.0` | `10` = ten times faster; `0` = no pauses. |
| `ALKAMELV2_ANALYSIS_ENABLED` | `false` | Also join `timing.analysis.laps` and `.stints` and stream them into Postgres — see *timing.analysis*. Off until a practice session has run clean with it. |
| `ALKAMELV2_ANALYSIS_MAX_LINE_BYTES` | `536870912` | Sanity cap on one **streamed** line (counted, never buffered). `ALKAMELV2_MAX_LINE_BYTES` then guards only buffered lines. |

### IMSA telemetry settings

`IMSA_TELEMETRY_*`, separate from the Al Kamel variables because it is a
separate, unofficial source. See *IMSA telemetry (energy)*.

| Variable | Default | |
|---|---|---|
| `IMSA_TELEMETRY_ENABLED` | `false` | Connect to IMSA's telemetry websocket whenever the Al Kamel feed is connected. |
| `IMSA_TELEMETRY_SERIES` | `IMSA WeatherTech SportsCar Championship,IWSC` | The series that send energy, by name or abbreviation. The connection runs only while the bound event belongs to one. |
| `IMSA_TELEMETRY_APP_URL` | `https://d3aqeo5txo0gzi.cloudfront.net/` | The telemetry app that imsa.com/telemetry frames. The endpoint and API key are read from its bundle at every connect. |
| `IMSA_TELEMETRY_CHANNELS` | `/telemetry/message,/telemetry/session` | AppSync Events channels. |
| `IMSA_TELEMETRY_STALE_SECONDS` | `15` | A reading older than this shows no energy. |
| `IMSA_TELEMETRY_RECORDING_ENABLED` | `true` | Raw frames go to `ALKAMELV2_RECORDING_BUCKET` under `imsa-telemetry/`. |
| `IMSA_TELEMETRY_REPLAY_FILE` / `_SPEED` | — / `1.0` | Local dev: replay a telemetry recording instead of connecting. Runs while the (replayed) Al Kamel feed is connected. |

## Railway settings

Beyond the `ALKAMELV2_*` variables, on the backend service:

| Setting | Value | Why |
|---|---|---|
| `RAILWAY_DEPLOYMENT_DRAINING_SECONDS` | `30` | **Railway's default is 0: SIGKILL straight after SIGTERM.** With 0 a redeploy never runs the clean shutdown — the lease is left to lapse (a ~30 s gap in the feed) and the recording segment in progress is lost with the container's disk. With 30 the old process closes the socket, releases the lease so the new one dials within seconds, and uploads the last segment. |
| `RAILWAY_DEPLOYMENT_OVERLAP_SECONDS` | leave at `0` | The lease already makes the overlap safe; a longer overlap only delays the handover. |
| Replicas | `1` | Live data is in the memory of the process holding the lease. |
| `R2_IMAGES_ENABLED` | `true` (already, in production) | Recordings upload through the same R2 client; with it off they stay on local disk. |

Nothing else: outbound TCP to port 11001 needs no Railway configuration. If
Al Kamel turns out to allow-list client addresses, the service would need
Railway's static outbound IP — `lastError` would show a connect timeout or
refusal rather than a login error.

The container runs with `-Xmx256m` (Dockerfile). The default channels need a
few MB. `timing.analysis` is streamed rather than held, so it fits in the same
heap — measured under *timing.analysis*.

## timing.analysis

Laps and stints for the timing page and drive time. **Off by default**
(`ALKAMELV2_ANALYSIS_ENABLED`). When on, `timing.analysis.laps` and
`timing.analysis.stints` are joined too, and every JSON frame takes the
streaming path below instead of being parsed whole.

Why streaming: a reconnect snapshot of a 24-hour race's laps is one line of
45–78 MB (over `max-line-bytes`, so the old reader would refuse it and
reconnect forever), and as a Jackson tree it would take 300–510 MB.

**The path.** `AksLineReader` reads a frame's `CMD:id:channel:` header, and
for a JSON frame hands the rest of the line to `AnalysisRouter` as a stream
that ends at `\n` (the CR before it dropped). Every byte is teed to the
recorder as it passes, so **recordings stay byte-identical** — the replay
server cannot tell the difference. `AnalysisRouter` walks the JSON with
Jackson's `JsonParser`:

- `timing.analysis.laps.<car>.laps.<lap>` → one small patch per lap.
  `loopSectors` and `sections` (most of a lap's bytes) are skipped unread;
  `sectors.<n>` keeps time and flag.
- `timing.analysis.stints.<car>.stints.<startTime>` → one patch per stint,
  with the four driver accumulators.
- Other analysis sub-channels (`pitIn`, `pitOut`…) are skipped: laps and
  stints carry what we keep.
- Everything under `timing.session` is read as a tree and merged into
  `AksStateTree` as before. **Analysis never enters the tree.**

Patches are partial: a field the diff names is written (a JSON `null` writes
NULL), a field it does not name keeps its stored value, and a diff that names
one sector patches just that slot of the arrays (`live_patch_int/_text` in
V57). A `null` lap or stint deletes the row. A `null` car or channel deletes
**nothing** — the history is permanent, and a new session has its own key.

`AnalysisWriter` drains a bounded queue (100,000 patches) on one virtual
thread and upserts in JDBC batches every second or 500 rows. The socket
thread never waits on the database: a full queue drops the patch and counts
it (`analysis.dropped` in status); the next reconnect's snapshot rewrites it,
since every write is an idempotent upsert.

**Keys.** Rows are keyed by Al Kamel's `timing.session.info.sessionDbId`
(`live_session`), not by our event. Car numbers are stored exactly as the
feed writes them (#04 ≠ #4).

**Which event a session belongs to.** Every session the feed shows is
recorded, whichever series it is. A session is **filed** under an event
(`live_session.event_id`) only when that event's entry list matches the cars
on track, by car number **and** class (`LiveEventMatch`, at least half the
field). Numbers alone would not do: a weekend's series share them (#7 in
WeatherTech and in Pilot Challenge), but never their classes. The binding
only says which event to try:
- Binding Pilot Challenge while WeatherTech is on track files and moves
  nothing.
- A session that loads before you rebind stays unfiled, then is filed the
  moment the rebind matches it (checked every supervisor tick).
- A binding that doesn't match is re-checked every 10 s as the running order
  fills in.

Drivers are matched against the **filed** event's crews, so they follow the
filing, not the binding. An unfiled session appears on no event's Timing page
until it is filed.

**The feed's own labels** (V60): each `live_session` row also keeps
`champ_name`, `champ_db_id`, `feed_event_name`, `feed_event_short_name` and
`closed` from `timing.session.info`, so a session filed nowhere can still be
told apart later. On 2026-09-30 `champName` matched our `series.name`
exactly (IMSA Michelin Pilot Challenge, IMSA VP Racing SportsCar Challenge),
and the feed event id carried over between one series' sessions — see
`docs/LIVE_TIMING_ALL_SERIES_PLAN.md`. The feed closes a session with a bare
`info` patch `{"closed": true}`. (V57's comment still describes the old "bound when first
seen" rule; a merged migration's text can't change without breaking Flyway's
checksum.) Laps or stints that arrive before any
`info.sessionDbId` has been seen are skipped and counted
(`analysis.withoutSession`); the JOIN order puts `info` first.

**Drivers.** Whenever `timing.session.entry` changes, `LiveDriverResolver`
writes `live_driver` for the session: driver order N of each car
(`drivers."1"`, `"2"`…, what laps and stints call `driver`), matched to the
bound event's entry by number (exact first, then leading zeros only if
unambiguous) and to the crew by surname (full name when a crew shares a
surname; case and accents ignored). `rating` is ours from
`driver_assignment`, or the first letter of the feed's `license` when
unmatched. Unmatched drivers are written with `driver_id` null — never
dropped.

**In memory** is only `LiveCarSummaries`: per car, last lap, best lap, open
stint and laps in it — reset on a new session or connection. A lap
invalidated after the fact may have been the best; that car's `bestStale`
says to read the best lap from `live_lap` instead.

`/api/live/status` carries an `analysis` block: `enabled`, `laps` and
`stints` (patches read on this connection), `dropped`, `withoutSession`,
`queued`, `written`, `failed`.

### Heap, measured

`DaytonaSnapshotTest` generates a Rolex-24-size snapshot as it is read — 60
cars × 650 laps = 39,000 laps, 20 loop sectors each, **one 88 MB line** — and
streams it through the real reader, router and recorder in a forked JVM with
production's flags (`-Xmx256m -XX:+UseSerialGC`), into an **undrained** queue
of the writer's capacity (the worst case: the database has stalled).

| | 2026-09-29 |
|---|---|
| Retained after GC, whole snapshot queued | ~28 MB (≈ 760 B per queued lap patch) |
| Old-gen peak | ~31 MB |
| Time to stream the line | 0.7 s |
| Dropped | 0 |

So the 256 MB cap stays; there is no case for 384 MB. With the database
keeping up (500-row batches) the queue holds far less than this.

### Not yet verified against real bytes

No recording holds analysis data yet; every fixture is synthetic, built from
the spec (1.0.36, pp. 52–58) against a server that runs 1.0.33. See the plan
(`docs/LIVE_TIMING_PAGE_PLAN.md`, *Rollout*) for the practice session that
settles these.

## Timing page API

Everything the web timing page (`#/timing/:eventId`, linked from the event
page as "Timing →") and the iPad's Timing tab read. All member GETs,
all gzipped over 1 kB (`server.compression`), and the ETag filter answers
`If-None-Match` with 304 when nothing changed. The tower carries no timestamps
for that reason; a stint's running time is sent as `stintStartMs` for the
client to count up from.

- **`/api/live/timing`** — the classification (above) per class, each car
  with: `intervalMs`/`intervalLaps` (the feed's `gapPreviousTime`/`Laps`,
  nothing computed), the current driver (feed `entry.currentDriver`; our name
  and rating from `live_driver`, else the feed's), `lastLap`/`lastLapMs`,
  `bestLap`/`bestLapMs` (from memory; re-read from `live_lap` when a best
  was invalidated), `inPit` (the open stint is a PIT stint), `stintStartMs`,
  `stintLaps`, and `energyPct` (always null until the IMSA telemetry
  adapter). Each class carries its series' `class_style` `color`, and the
  tower carries `feedClockMs` (the newest time the feed reported) for the
  page's stint clock. `sessionDbId` names the feed session. Needs
  `ALKAMELV2_ANALYSIS_ENABLED` for the lap and stint fields; without it they
  are null and the rest still works.
- **`/api/live/cars/{car}?session=`** — laps (with `sectorMs` /
  `sectorFlags`, 1-based by sector), stints (with the four accumulators) and
  drivers. `session` defaults to the session being fed, else the bound
  event's latest. The car number is exact: `/cars/04` is not `/cars/4`.
- **`/api/live/drive-time?session=`** — per driver: `driveMs`, `inCar`,
  the applicable `minMs`/`maxMs`, and `status` `OK` / `UNDER_MIN` (with
  `owedMs`) / `OVER_MAX` (with `overMs`) / `NO_RULE`, plus `remainingMs`.
- **`/api/live/sessions?eventId=`** — `sessionDbId`, name, type, date, lap and
  car counts, and whether it is the one being fed.
- **`/api/live/gaps?session=`**, **`/api/live/sectors?session=`**,
  **`/api/live/pits?session=`** — the analysis views (below), class by class.

### Analysis: gaps, sectors, pits

Ported from Gantry (`iracing-broadcast-graphics`: gap visualizer, best
sectors, pit cycles). `LiveAnalysis` is pure; `LiveAnalysisService` loads the
rows. Nothing is stored: every poll recomputes from `live_lap` / `live_stint`,
so a lap the feed corrects later corrects every gap and best built on it, and
a fix to the formula fixes old sessions too. A 24-hour race is ~50k laps,
keyed by `session_db_id` first, so the reads are cheap.

A car's class is the one it runs in on the tower while its session is the one
being fed, else the class of the entry its drivers matched. Cars matched to
no entry are grouped under "Not entered", not dropped.

- **Gaps.** A lap's crossing time is `start_time_ms + lap_time_ms`. The class
  leader at lap L is the first car in the class to finish lap L; a car's gap
  is how much later it finished the same lap. A car that finished lap L after
  the leader finished lap L + n is n laps down and gets `lapsDown` instead of
  a gap — the chart breaks its line there. `pitLaps` are laps with a
  `pit_in_time_ms`. **Rests on `start_time_ms` being the lap's start, epoch
  ms**: check it against the feed's own `gapFirstTime` at the practice session.
- **Sectors.** Best time per sector over valid laps only (a lap marked
  invalid keeps none of its sectors; validity unknown counts), the lap it came
  on, and the theoretical best when the car has a best in every sector. The
  car panel marks its own best sectors and the class's.
- **Pits.** One stop per Al Kamel PIT stint: pit-lane time is the stint's
  length, the lap is its `open_lap_number`, and the driver in and out are the
  TRACK stints either side. Penalty / safety-car stops keep `pit_type`.

### Drive time

Rules are typed in per event (`drive_time_rule`, V58): per class, an
optional minimum and maximum, either for every rating (`rating` blank) or for
one (B/S/G/P). A driver gets their rating's rule, with any bound it leaves
blank taken from the class-wide rule. Our rating comes from
`driver_assignment`; an unmatched driver uses the feed license's letter.

A driver's time is the latest `driverAccumSessionTrackTime` on their stints:
track time only, pit lane excluded, as the IMSA rule counts. **Unverified**:
how that accumulator behaves on an open stint. `DriveTime.driveMs` assumes an
open TRACK stint with no accumulator of its own adds its elapsed time, and
that one carrying an accumulator is being updated live. That single method is
where to change it once real bytes show otherwise.

"Now", for an open stint, is the wall clock only while the session is being
fed and the feed's newest time is within 10 minutes of it. Otherwise it is
the newest time the feed reported (the last lap's end or stint's start), so
a replay or a finished session does not keep counting.

## IMSA telemetry (energy)

Energy remaining per car comes from IMSA, not Al Kamel: imsa.com/telemetry
frames an app that listens to an **AWS AppSync Events** websocket. It is
unofficial and undocumented, so it is off by default
(`IMSA_TELEMETRY_ENABLED`) and **fails soft**: nothing it does can touch the
Al Kamel connection, and a broken or vanished endpoint costs a log line and a
retry (5 s → 5 min ladder).

- **When it runs:** only in the process holding the Al Kamel lease, only
  while the feed is asked for, and **only while the bound event belongs to a
  series that sends energy** (`IMSA_TELEMETRY_SERIES`, by default
  WeatherTech: name or `IWSC`). Bound to any other series it stays OFF, with
  the reason in the status's `telemetry.idleReason`. The supervisor starts
  and stops it every tick, so a rebind switches it within seconds.
- **Class guard:** a reading counts only when IMSA's class for the car agrees
  with the car's class in the Al Kamel feed ("GTD PRO" = "GTDPRO"). So another
  series' car sharing the number never shows or stores its energy. Refused
  lap samples are counted in `telemetry.classRejected`.
- **Endpoint and key:** read from the app's JavaScript bundle at every
  connect (`AppSyncEndpoint`), never configured. AppSync keys expire, so a
  copied key would silently stop working. The key is never logged whole.
- **Protocol:** `AppSyncSession`, a pure state machine: `connection_init` →
  `connection_ack` (its `connectionTimeoutMs` bounds the silence the watchdog
  allows) → one `subscribe` per channel → `data`, with `ka` keep-alives. The
  websocket offers the subprotocols `aws-appsync-event-ws` and
  `header-<base64url {host, x-api-key}>`.
- **Payload:** every plausible wrapping is decoded (base64 JSON, a `data`
  field, plain JSON), because none has been seen live. A car is
  `scoring.number` (exact, #04 ≠ #4), `energy_remaining`, `scoring.lapNumber`
  and `pit_lane`.
- **Kept:** only the latest reading per car, plus each car's last 30 lap
  samples. **Stored:** one row per car per lap in `live_energy_lap` (V59).
  The first reading after `lapNumber` goes up is the energy at the line for
  the lap just completed. Missed crossings are not invented. Rows are keyed
  to the Al Kamel session being fed, so with analysis off energy is shown but
  not stored.
- **Shown:** the tower's `energyPct` (null when older than
  `IMSA_TELEMETRY_STALE_SECONDS`) and `energyLapsLeft` (energy over the
  stint's average use per lap; refills are left out). The car panel shows
  energy and energy used per lap, and each stint's average.
  `/api/live/status` has a `telemetry` block: state, source, last error,
  message and car counts, laps stored.
- **Recorded:** raw websocket frames, gzip segments under `imsa-telemetry/`
  in the private recording bucket. `IMSA_TELEMETRY_REPLAY_FILE` plays one
  back through the same protocol code.

**Unverified until a live weekend:** the endpoint and handshake themselves,
the payload wrapping, whether IMSA's `lapNumber` matches Al Kamel's or runs
one off, which classes carry energy, and the real message rate and size.
Nothing has connected to IMSA's endpoint yet.

## When it will not connect

The app shows `lastError` and the server logs the same text; each stage names
itself, and a successful attempt logs three INFO lines on the way (`TCP
connected…`, `TLS handshake done (protocol, cipher)`, `LOGIN sent…`).

| `lastError` begins | Meaning | Try |
|---|---|---|
| `Could not reach host:port` | DNS, firewall, wrong host/port, or the address is allow-listed and Railway's is not. | Check `ALKAMELV2_HOST`/`PORT`; ask Al Kamel whether client IPs are allow-listed (Railway static outbound IP). |
| `TLS handshake … got no answer` | The port took the TCP connection and then ignored the TLS hello: it is not a TLS port, or a TLS stack too old to answer a modern hello. | `openssl s_client -connect HOST:PORT </dev/null` from a laptop (sends no credentials, does not use the login). If that hangs too, confirm the port with Al Kamel. |
| `TLS handshake … failed (…)` | TLS answered and refused — protocol or cipher mismatch. The reason is in the brackets. | The JVM disables TLS 1.0/1.1; a server that old needs them re-enabled for this socket. |
| `Connected over TLS and sent LOGIN, but no reply` | The transport is fine; the server is not answering LOGIN as sent. The message lists what did arrive, including bytes with no line ending. | Compare those bytes with the spec's framing; check the account is enabled for this server. |
| `Login refused: …` | The server's own reason (bad credentials, user limit reached). Retried no sooner than every 60 s. | If it says the user limit is reached, something else holds the one login. |

`openssl s_client` first, always: it separates "network/TLS" from "protocol"
without touching the account.

## Recordings

Al Kamel offers no test or replay server, so every session production connects
to is recorded: gzip segments named `aks-v2/<date>/<connection start>-<n>.aks.gz`,
one line per inbound message as `<receive epoch ms> TAB <raw line>`. With the
narrow default channels a race is a few MB.

## Developing without the feed

Point a local backend at a recording (`.aks` or `.aks.gz`):

```yaml
# backend/src/main/resources/application-local.yml (gitignored)
pit-pass:
  alkamel-v2:
    replay:
      file: /path/to/recording.aks.gz
      speed: 10
```

The backend starts a stand-in AKS server on a loopback port and the real client
code connects to it — login, joins, pings, diffs — then `POST /api/live/connect`
as usual. Each connect replays from the start. Pauses longer than 10 s are
shortened. The same stand-in drives `LiveTimingServiceTest`.

## First real connection — checklist

1. Create the private R2 bucket; set `ALKAMELV2_RECORDING_BUCKET`.
2. Set `ALKAMELV2_HOST`, `ALKAMELV2_USERNAME`, `ALKAMELV2_PASSWORD` on Railway.
3. During a **practice** session, connect from the app and watch
   `/api/live/status`: `LIVE`, `session` naming the right session, `messages`
   climbing, `lastWarning` empty (a refused JOIN shows up there).
4. Check `GET /api/live/state?path=timing.session.standings.byClass.active`.
5. Deliberately test the second login once (another client, same credentials)
   and write down what happens here.
6. Disconnect; confirm segments arrived in the bucket. That recording is the
   fixture everything after this is built against.

## Verification

`cd backend && ./gradlew test --tests 'com.pitpass.live.*' --tests com.pitpass.auth.SecuredChainTest`
