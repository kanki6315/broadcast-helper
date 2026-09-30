# Live timing for every series on a weekend, and a shareable timing link: plan

Status: **2026-09-30: slices 0, 1 and 2 done** on the branch below, not yet
merged to main. Slices 3–6 not started.
Branch: `claude/alchemel-connection-switchover-686f5e`.
This document is the handoff. It builds on `docs/LIVE_TIMING.md` (the feed
pipeline) and `docs/LIVE_TIMING_PAGE_PLAN.md` (the timing page, slices 0–5).

## Goal
- **Timing for every series on track, mapped or not.** One connection stays
  up all weekend. Every session the feed carries is recorded and viewable,
  including series that have no event, entries or aliases in Pit Pass.
- **Pit Pass data is added on top, never required.** Entry teams, class
  colours, driver links, drive-time rules and the championship calculator
  apply only to a session that is *filed* under a Pit Pass event.
- **A shareable timing link.** A link gated by a secret token that opens the
  timing page, with no sign-in and nothing else of Pit Pass.

## Decisions (Arjuna, 2026-09-30)
- The target is "all series on a weekend", including unmapped ones.
- **Sharing stays private through the token.** The link is the gate; timing
  is not public. Anyone without a valid token sees nothing.
- **A shared link shows everything the timing page shows**: tower, gaps,
  sectors, pits, car detail, drive time (with rules), class colours, IMSA
  energy. It does not include anything outside the timing page
  (championships, sheets, profiles, scratchpads, Manage).
- **One link, valid until revoked.** A single current token, no expiry and
  no per-session scope. An admin can revoke it and generate a new one; the
  old link stops working at once.

## What prompted this (prod dump, 2026-09-30)
Production was connected for IMPC practice (event 340, Fox Factory 120) and
was **not** disconnected before a VP Racing practice. A dump of prod
(`ios/pit_pass_seed.sql`, restored locally as `pit_pass_prod_0930`) shows:

| session_db_id | Feed | feed_event_db_id | event_id | Laps / stints / drivers | Driver rows linked |
|---|---|---|---|---|---|
| 3183 · Practice 1 | IMPC | 611 | 340 | 1126 / 478 / 90 | 90 of 90, all to event 340 |
| 3189 · Practice 2 | VP Racing | 612 | null | 260 / 70 / 22 | 0 of 22 |

- The new session was recorded under its own `sessionDbId` and **not filed**.
  Match-based filing (`LiveEventMatch`) rejected it, so nothing was linked
  to IMPC. The IMPC session has no VP laps in it (IMPC ends 17:17 UTC, VP
  starts 17:50).
- **The class check was not what saved it.** 7 of 17 VP numbers (2, 5, 18,
  26, 77, 95, 98) are also on the IMPC entry list. 41% is under the 50%
  filing bar, so number-only matching would also have refused. A field with
  more than half its numbers shared (WeatherTech vs Pilot Challenge) has not
  been seen on real data yet.
- **The screen was wrong even though storage was right.** The tower and the
  calculator follow the *bound* event, so during the VP session, VP cars with
  shared numbers were shown with IMPC teams.
- **Session 3189 is unreachable.** The timing page lists sessions by
  `event_id` only, and nothing stored says it was VP Racing.
- Open: keep 3189 once slice 4 can show unfiled sessions, or delete it
  (`DELETE FROM live_session WHERE session_db_id = 3189;` cascades).

## Slice 0 findings: what the feed says about itself (done 2026-09-30)
From the nine recording segments
`~/Downloads/aks-v2_2026-09-30_20260930T164827Z-000{1..9}.aks.gz`. Full
`timing.session.info` snapshots appear at the start of each session (segment
0001 at 16:48:28 UTC, segment 0005 at 17:35:00 UTC).

| Field | IMPC session | VP Racing session |
|---|---|---|
| `champName` | `IMSA Michelin Pilot Challenge` | `IMSA VP Racing SportsCar Challenge` |
| `champShortName` | `Michelin Challenge` | `VP Challenge` |
| `champDbId` | 612 | 613 |
| `champMongoId` | `6820777a6739e96fa2ce4b52` | `b52595eb8035d3d1dd2c9ace` |
| `eventDbId` | 611 | 612 |
| `eventName` | `Fox Factory 120` | `29th Annual Motul Petit Le Mans` |
| `eventShortName` | `Road Atlanta` | `Road Atlanta` |
| `sessionDbId` / `name` | 3183 / Practice 1 | 3189 / Practice 2 |
| `date` (epoch ms) | 1790785500000 (16:25 UTC) | 1790790600000 (17:50 UTC) |
| `utcOffset` | -4 | -4 |

What this means:
- **`champName` equals the Pit Pass `series.name` exactly** for both series
  in production (series 2 and 5). Filing can match on series name first and
  fall back to `series_alias`, with no setup for series already named the
  way Al Kamel names them.
- **`champDbId` is a stable numeric ID per championship.** Store it. Once a
  championship is linked to a series, the ID stays the key even if the
  display name changes.
- **The feed event is per series, not per weekend.** Same track and
  weekend, but IMPC is event 611 ("Fox Factory 120") and VP is 612 (named
  after the headline race). So `eventDbId` groups one series' sessions for
  the weekend. Grouping a whole weekend needs `eventShortName` plus the date.
- **`eventDbId` carries over between a series' sessions.** Prod
  `live_session` on 2026-09-30 evening: VP Practice 2 (3189) and VP
  Qualifying (3185) are both feed event 612. The spec only calls the field
  "Event Database Id (INTERNAL)" and promises nothing, so this is observed,
  not guaranteed. Seen for VP within one day; not yet seen for IMPC, or
  across days (Wednesday practice vs Saturday race). Re-check with the
  Petit Le Mans weekend's later sessions.
- **`sessionDbId` is not in time order.** VP Qualifying (3185, 16:44 EDT)
  has a lower ID than VP Practice 2 (3189, 13:35 EDT); Al Kamel creates a
  weekend's sessions in advance. Sort sessions by `session_date_ms` /
  `first_seen_at`, never by ID. (The current `sessions()` query uses the ID
  only as a tie-breaker, which is fine.)
- **The spec shows an IMSA ID we don't get.** Its example carries
  `event_IMSA_RaceEventSeriesId`; our feed sent no `event_*` fields.
- **Session changeover is visible.** Segment 0004 carries
  `timing.session.info` `{"closed": true}` at 17:24:13 UTC, then nothing
  until the VP snapshot at 17:35. Useful for the session browser.
- **Pit Pass stores one date per event** (`event.event_date`, the race day:
  event 340 is 2026-10-03), while practice ran 2026-09-30. Filing by date
  needs a window, not equality (see slice 3).
- **VP Racing has no Pit Pass event this weekend** (series 5 exists; there
  is no event in 2026-09-20 → 2026-10-15). Under the plan it would stay
  unfiled and still be fully viewable, which is the intended behaviour.

## Ground rules (carried over)
- **One concurrent Al Kamel login.** Only production connects. Never set
  `ALKAMELV2_HOST` locally. Develop against recordings.
- **Recordings are licensed feed data. Don't commit them.** Test fixtures
  are synthetic, in the real shapes (the table above gives the shapes).
- Car numbers match **exactly first** (#04 ≠ #4).
- Every new env var goes into `application.yml` as a placeholder.
- JdbcClient and raw SQL, no JPA, no Mockito. The latest migration is V59,
  so the next is **V60**.
- The raw feed tree (`/api/live/state`) stays admin-only, shared link or not.

## Slices

### Slice 1: tower and calculator from the filed event only (backend + web) — DONE 2026-09-30
The fix for the wrong screen seen on 2026-09-30. Small; do it first.
- `LiveTimingService`: expose `filedEventId()` (package-private today).
- `LiveClassificationService.current()`: build with the **filed** event's
  entries, number aliases and class aliases. When nothing is filed, build
  with an empty entry list rather than returning `Result.EMPTY`.
  `LiveClassification.build` already falls back to the feed's
  team/vehicle/manufacturer for unmatched cars, so the tower fills in.
- `LiveClassificationService.championship(id)`: score only when the
  championship's event is the filed event. Otherwise return a "session not
  filed under this event" state, not wrong positions.
- `LiveTimingPageService.tower()`: class colours from the filed event.
  `resolvedDrivers` is already per session.
- Response shape: keep `eventId` (the bound event) for client
  compatibility; add `filedEventId` and `filedEventName`. The web
  `TimingPage` and the iPad decode them as optional.
- Web: when the session on track is not filed, say so on the tower
  ("IMSA VP Racing SportsCar Challenge · Practice 2 — not in Pit Pass") and
  hide calculator links.
- Tests: bind event A, feed a session whose cars are series B with half the
  numbers shared. Expect feed teams, no `entryId`s, and a refused
  championship.

### Slice 2: record the feed's own labels (backend) — DONE 2026-09-30
- V60: `live_session` gains `champ_name TEXT`, `champ_db_id BIGINT`,
  `feed_event_name TEXT`, `feed_event_short_name TEXT`, `closed BOOLEAN`
  (`feed_event_db_id` exists already).
- `AnalysisRouter` / `AnalysisWriter`: fill them from `timing.session.info`
  in the existing upsert (`AnalysisWriter.java:257`); set `closed` when the
  `{"closed": true}` patch arrives.
- Still owed in production: backfill 3183, 3185 and 3189 by hand from the table above (3185 shares 3189's feed event and championship; one `UPDATE` per session in
  production), so the browser can label them.

**As built (slices 1–2):**
- `LiveStatus` gained `filedEventId` / `filedEventName` (from
  `LiveTimingService.overlayEventId`, which reads `live_session.event_id` for
  the session on track; the binding when analysis is off). The
  classification, tower (`Tower.filedEventId` / `filedEventName`) and
  championship responses use it; `eventId` stays the binding.
- Web: the Timing page follows the filed event; bound here but filed nowhere
  it shows the tower with one "not filed under this event" line and no
  per-row "not entered". The calculator projects only against the filed
  event and says when the session is filed nowhere.
- V60 adds `champ_db_id`, `champ_name`, `feed_event_name`,
  `feed_event_short_name`, `closed` to `live_session` (plus an index on
  `feed_event_db_id`), written by the session upsert.
- **Not done, for later slices:** the iPad still treats the binding as the
  tower's event (its calculator gets empty rows for an unfiled session from
  the server, so nothing wrong is projected); gaps/sectors/pits for a
  *finished* unfiled session group every car as "Not entered", because
  their classes come from entries (slice 4 should fall back to the feed
  class stored per car).

### Slice 3: feed events, binding and filing by championship (backend + web)
**The unit that gets bound is the feed event** (one championship at one
weekend, `eventDbId`), not each session and not the connection. One feed
event maps to one Pit Pass event: prod has no series with two Pit Pass
events in one week (the "(Oct 2)" suffixes are date labels, not
doubleheaders). Binding it once covers every session of that series'
weekend, including ones not yet run.

- V61 (or folded into V60): `live_feed_event` — `feed_event_db_id` (PK),
  `champ_db_id`, `champ_name`, `event_name`, `event_short_name`,
  `first_session_date_ms`, `event_id` (nullable, FK `event` ON DELETE SET
  NULL), `bound_by` (`AUTO` | `ADMIN` | `ADMIN_NONE`), `bound_at`,
  `bound_by_email`. Upserted from `timing.session.info` alongside
  `live_session`.
- `live_session.event_id` stays as the **resolved** value, so every
  existing query keeps working. It is written from the feed event's
  binding unless the session has its own override (below).
- **Resolving a feed event**, in order:
  1. **An admin binding wins.** `ADMIN` → that event; `ADMIN_NONE` → "not
     in Pit Pass", never auto-filed. An admin binding skips the entry-list
     check (the admin is vouching); the tower still reports class
     mismatches as it does today.
  2. **Series by championship:** `champ_name` → `series.name`
     (case-insensitive), else `series_alias`. Then that series' event whose
     `event_date` is within **−1 to +6 days** of the first session date
     (practice runs up to ~4 days before the race-day date stored in Pit
     Pass). Exactly one candidate, or don't bind.
  3. **The connection's bound event**, if an admin set one (today's
     behaviour, kept as a hint).
  4. For 2 and 3, `LiveEventMatch` must pass on a session of that feed
     event before binding `AUTO`. The championship decides; the entry list
     confirms.
  5. Otherwise unbound: sessions still recorded and viewable.
- **Retry unbound feed events when something changes**, not only while
  connected: a Pit Pass event is created or its date changes, entries are
  imported, a series alias is added. (Wednesday practice usually runs
  before the entry list is imported; today's "file once" rule would leave
  it unfiled for good.) Never replace an `AUTO` binding automatically, and
  never touch an `ADMIN` one.
- **Guard against the `eventDbId` assumption failing.** If a new feed
  event appears with the same `champ_db_id`, the same `event_short_name`
  and a first session within the window of an already-bound feed event,
  inherit that binding (`AUTO`) and log it. Then a changed ID between days
  costs nothing.
- **Per-session override** for the odd case (a combined session filed
  under another event): `live_session.event_override` — nullable, wins over
  the feed event. Kept out of the main UI.
- **(Re)binding re-runs driver resolution** for every session of the feed
  event; unbinding clears their `live_driver.entry_id` / `driver_id`.
  `EntriesChanged` only runs on the live connection today; it needs a path
  that works for finished sessions.
- IMSA telemetry: decide coverage from the session's mapped series, not the
  bound event's (`ensureTelemetry`, `LiveTimingService.java:512`).
- `/api/live/connect`: `eventId` becomes optional
  (`LiveTimingController.java:77`); the connect bar's one action is
  "Connect". `live_timing.event_id` is already nullable; check the store
  and status paths treat null as "no hint".
- Manage: "Championships seen on the feed" — distinct `champ_name` /
  `champ_db_id`, with the series each resolved to, or a "map to series"
  action that writes a `series_alias` row and retries that championship's
  unbound feed events.
- Tests: two series in one replay with shared numbers, one with a Pit Pass
  event and one without. Expect one bound, one unbound, no cross-linking.
  A practice four days before the event date binds; one eight days before
  does not. An `ADMIN_NONE` feed event stays unbound after entries are
  imported. A second feed event ID for the same championship and weekend
  inherits the first one's binding.

### Slice 4: browse sessions by weekend; bind by hand (backend + web)
- `GET /api/live/sessions` with no `eventId`: every recorded session,
  grouped by weekend (`event_short_name` + date), then by feed event
  (championship), newest first **by date, never by `sessionDbId`**. Each
  feed event shows its Pit Pass event and how it got there (auto / admin).
  `?eventId=` keeps working.
- `PUT /api/live/feed-events/{feedEventDbId}/event` `{eventId}` or
  `{none: true}` or `{auto: true}` (admin): bind, mark "not in Pit Pass", or
  hand back to automatic filing.
- `PUT /api/live/sessions/{id}/event` `{eventId | null}` (admin): the
  per-session override; `null` clears it.
- In the browser, the control sits on each championship row: "IMSA VP
  Racing SportsCar Challenge · Road Atlanta → not in Pit Pass · change".
- Web routes:
  - `#/timing`: what's live now, plus recent weekends and their sessions.
  - `#/timing/session/:sessionDbId`: the timing views for one session,
    filed or not.
  - `#/timing/:eventId`: unchanged, the sessions filed under one event.
- The timing page must work from a session alone: event name, drive-time
  rules and class colours come from the session's filed event when there is
  one, and are simply absent otherwise.
- iPad: the Timing tab keeps working from an event. It needs to tolerate
  `filedEventId` being null; no browse UI in this slice.

### Slice 5: the share token (backend)
Modelled on `DeviceTokens` / `DeviceTokenFilter`, simplified by the
decisions above.
- V62 (the next free number after slice 3): `live_share_token` — `id`, `token_hash` (SHA-256 hex; the secret is
  shown once), `created_by`, `created_at`, `revoked_at`, `last_used_at`
  (touched at most every 5 minutes). At most one row with `revoked_at IS
  NULL` (partial unique index). Revoked rows stay as history.
- Admin API: `GET /api/live/share` (whether a link exists, created by and
  when, last used; never the secret), `POST /api/live/share` (revoke the
  current one if any, issue a new one, return the secret once),
  `DELETE /api/live/share` (revoke, no replacement).
- `ShareTokenFilter`: reads `X-Pit-Pass-Share`, checks the hash against the
  active row, and authenticates a `ShareAuthentication`. Cache the active
  hash in memory and drop it on issue/revoke (one container in production),
  so polling doesn't hit the database per request.
- `SecurityConfig`: a share identity may make **GET** requests to the
  timing page's endpoints only, listed explicitly before the general rules:
  - `/api/live/timing`, `/api/live/status`, `/api/live/sessions`,
    `/api/live/gaps`, `/api/live/sectors`, `/api/live/pits`,
    `/api/live/cars/*`, `/api/live/drive-time`
  - the event name and drive-time rules the page reads
    (`/api/events/*` summary, `/api/events/*/drive-time-rules`). Prefer
    folding these into the live responses in slice 4 so the share identity
    needs no `/api/events/*` access at all.
  - Everything else, including `/api/live/state`, `/api/live/championships/*`,
    `/api/live/classification`, `/api/me` data and any write, refuses it.
    It is not a member, so the general `/api/**` rules already do.
- **Status must not leak admin detail.** `/api/live/status` includes
  `requestedBy` (an email) and the lease holder; for a share identity,
  return the same record with those fields null.
- Per-token rate limit in memory; 429 past it. The existing ETag behaviour
  keeps polling cheap.
- Tests: no header, a wrong token and a revoked token each get 401;
  regenerating kills the old token immediately; a share identity gets
  401/403 on `/api/live/state`, `/api/live/championships/1`,
  `/api/events/1/sheet` (or current equivalent) and every POST/PUT/DELETE;
  `status` hides `requestedBy`.

### Slice 6: the shared page and link management (web)
- Route `#/live/:token`, outside `Layout`. It must not call `/api/me` or
  redirect to sign-in. The page keeps the token in memory and sends it as
  `X-Pit-Pass-Share` on each request. Because it sits after `#`, it never
  reaches server access logs or `Referer`.
- It opens on `#/timing` (slice 4): whatever is live, plus recent weekends.
  Same views as members, in a `shared` mode: no connect bar, no rule
  editing, no links into the rest of Pit Pass.
- A "Timing data © Al Kamel Systems" credit line.
- Manage → Share link: shows whether a link exists, when it was made and
  last used; **Generate new link** (confirms that the old one will stop
  working, then shows the new URL once with a copy button); **Revoke**.
- Verification in the browser pane: a signed-out tab with the link sees
  timing; after regenerating, the same tab gets 401 and shows a "this link
  no longer works" message rather than a blank page.

## Order and dependencies
1 → 2 → 3 → 4 is the all-series work. 5 → 6 is sharing. Slice 6 reuses
slice 4's session-based routes, and the shared view is only meaningful once
slice 1 makes the tower correct for unfiled sessions, so build 1–4 first.
Slices 1 and 2 are independent of each other and can land together.

## Rollout
- Slices 1–2 are safe to deploy at once: no flags, one additive migration.
  Run the backfill `UPDATE`s for 3183/3185/3189 after V60 applies.
- Slice 3: no alias work needed for WeatherTech, Pilot Challenge and VP
  Racing (names already match). Check Manage → Championships after the first
  all-series weekend for anything unmapped.
- Slices 5–6: ship with no link generated. Generate the first one when it is
  needed.

## Verification
- Unit tests per slice, as listed.
- Replay: build a synthetic two-series recording in the shapes above
  (Pilot Challenge-shaped then VP-shaped, shared numbers, a
  `{"closed": true}` between) and run it through `AksReplayServer` locally.
  Check filing, tower contents and the session browser.
- Against the restored prod dump `pit_pass_prod_0930`: after slice 2's
  backfill, slice 4's browser lists 3189 as an unfiled VP Racing session at
  Road Atlanta, next to 3183 filed under event 340.
