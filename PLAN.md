# Broadcast Helper — Build Plan

A tool that automates event preparation for a motorsports commentator / pit-lane
reporter: import results and standings, manage entry lists and car photos, and
generate broadcast-ready reference documents — starting with an augmented
pit-lane entry list PDF.

---

## 1. Confirmed decisions

| Decision | Choice |
|---|---|
| First target series | IMSA WeatherTech SportsCar Championship (2026) |
| Data input | Import (CSV/JSON preferred, per-series PDF importers where needed) with a **staging + review step** before commit; manual entry/edit always available as fallback |
| Standings | A series can have **multiple championships** (team, driver, manufacturer, endurance cup). Standings are imported where files exist, manually entered/edited where they don't |
| Entry-list champ column | Configurable per generated sheet. Default follows the series' entry style: **teams points for team-based series** (IMSA), **drivers points for single-driver series**. When a drivers' championship is selected for a multi-driver car, the cell lists **every crew member's position** (e.g. "1st Aitken · 14th Vesti") — best-only would hide diverging title situations within one car |
| Guest / VIP entries | "GUEST" badge, blank championship column; best/last results still shown if they have starts |
| Car images | Bulk upload with auto-match by car number in filename, review grid to fix mismatches; images carry over between events until replaced |
| Season bootstrap | Import every completed round of the season; the tool computes best/last results and season tables from round data |
| Layout | Keep the format of the hand-made WGI sheet; **US Letter** paper in all cases |
| IMSA source files | Race/qualifying results as **JSON**; **entry list PDF** per event; teams-championship standings as **JSON** (per-session points for the whole season calendar — richer than expected). Not every series/season publishes the standings JSON (2024 Mustang Challenge doesn't), so those import from the **championship-points PDF** instead — lossier by nature, see §4. User provides real samples when each importer is built |
| Driver ratings | The **event entry list PDF is authoritative per event** — series can issue derogations overriding a driver's FIA rating for an event or season, so never assume the entry list matches the FIA list |
| Runs where | Locally first, but built as a proper client/server web app so hosting it later is a deploy, not a rewrite |
| Long-term ambition | Live timing integration during the race to surface storylines to the broadcast team |

## 2. Stack

Chosen to match the user's experience (Java strongest, then C#; basic Python/TS)
while fitting the problem:

- **Backend: Java 21 + Spring Boot 3** — REST API now, WebSocket support later
  for live timing. Apache **PDFBox** for per-series PDF importers, Jackson/
  Commons-CSV for JSON/CSV importers, **Flyway** for schema migrations,
  Spring Data JPA for persistence.
- **Frontend: React + TypeScript (Vite)** — forms, review tables, image grid,
  and later the live-timing dashboard.
- **Database: PostgreSQL from day one**, run locally via Docker Compose so the
  local and hosted environments are identical.
- **PDF export: print-styled web pages rendered by headless Chromium
  (Playwright)**. The entry list is a normal React page with print CSS; what
  you preview in the browser is exactly what exports. One layout engine, no
  separate template language, pixel-accurate output.

Repo layout: monorepo — `backend/` (Spring Boot), `frontend/` (React),
`docker-compose.yml` (Postgres), `docs/`.

## 3. Domain model

The model is the part that must absorb every series quirk up front. Key shapes:

- **Series** → has many **Seasons** → has many **Events** (rounds) and
  **Championships**.
- **Championship** (per season): type = drivers | teams | manufacturers | cup
  (e.g. IMSA Michelin Endurance Cup). A cup championship references the subset
  of events that count toward it. A season has many championships.
- **Event**: a race weekend at a venue. Has many **Sessions**.
- **Session**: practice | qualifying | race, with an ordinal (Race 1, Race 2,
  Qualifying 2). A race session has a **grid source** describing where its grid
  comes from: a qualifying session, *the Nth-fastest lap* of a qualifying
  session (fastest-two-laps format), a previous race's result, or
  championship order. This models multi-race / multi-quali weekends without
  special cases. A race session also carries a **RaceFormat**.
- **RaceFormat**: what *kind* of race a session is, per series — Sprint/Main, or
  Heat/Consolation/Feature on a rallycross-style round, or a plain Race where
  every race is the same. It exists so results can be counted per format: a
  sprint win and a six-car heat win are different broadcast facts. Each format
  has a stable machine `code` (the classifier's identity) and a separately
  renamable display `name`, so relabelling for air never disturbs
  classification. Assignment is by the **event's session shape, not session
  names** — the same "Heat 1" is a full-field sprint on one weekend and a
  six-car heat on another — and is marked AUTO or MANUAL so a hand-pinned
  session survives re-imports. Qualifying sessions carry no format; their stats
  are their own bucket.
- **GridPosition**: per race session per entry — the published starting slot
  (overall and in class) with the qualifying time where the source carries one.
  Always imported, never computed: a real grid reflects fastest-two-laps
  carry-over and penalties. It also records **who qualified the car and who
  takes the start**, which is what lets a pole count for one driver rather than
  a whole crew.
- **Entry**: a car entered in an event — car number (stored as a **string**:
  `04` ≠ `4`, `068` keeps its zero), class, team, manufacturer/model,
  `isGuest` flag, livery image. Car numbers are unique **across the event**;
  leading zeros are how series achieve that (IMSA's #23 GTP vs #023 GTD), which
  is exactly why numbers must never be stored numerically.
- **DriverAssignment**: driver ↔ entry for one event, with seat order and the
  driver's **rating (P/G/S/B) as published on that event's entry list** — the
  per-event value is the source of truth because series can issue derogations
  overriding the FIA rating for an event or season. Endurance rounds add extra
  drivers to the same entry — assignments are per-event, not per-season. The
  entry list and results own the lineup and replace it wholesale; a grid import
  only seeds it when an entry has no assignments at all.
- **Result**: per session per entry — position (overall and in class), status
  (finished/DNF/DNS/DSQ), best lap, plus per-lap times where available (needed
  for fastest-two-laps grid splitting).
- **StandingsRow**: position/points per competitor per championship *after a
  given round*, with a **per-round points breakdown split into qualifying
  points and race points** (IMSA awards both, and its standings PDF publishes
  the split — storing it lets the tool verify imported totals and recompute
  standings). Two provenances: `imported`/`computed` and `manual` — manual
  edits win and are flagged in the UI so you know what to re-verify.
- **ImportBatch**: every import lands in staging tables with a diff-style
  review screen; nothing touches real data until committed. Committed batches
  are kept for audit ("where did this number come from?").

### Edge cases baked in from day one

Collected from the user and from their hand-made 2026 WGI example PDF:

1. Guest/VIP entries classify in class but score no points (badge + blank champ).
2. Multiple championships per series; not all have machine-readable points files.
3. Multi-race weekends; grids set by second quali session, second-fastest lap,
   race-1 result, or points.
4. Single-driver vs multi-driver entries; endurance-only extra drivers.
5. Car numbers are strings, unique across the event; leading zeros significant.
6. "Best result of season" ties show all venues (`1st – DAY/SEB`).
7. Prior-year-at-this-track: **auto-pass implemented** for the safe cases —
   if last season has an event at the same venue (matched by venue, so sponsor
   renames don't break it) with the same car number and a team name that
   hasn't changed significantly (token-set match; sponsor suffixes pass,
   renames/takeovers deliberately fail), the sheet fills the result
   automatically, annotating a class switch ("1st (GTDPRO)"). A manual
   per-entry note always overrides. The richer change-context design
   (manufacturer, lineup) remains a Phase 3 item.
8. Blank qualifying column until quali results are imported (sheets are
   generated before quali happens).
11. Ratings derogations: the same driver can carry different ratings at
   different events; never backfill from a season-level list.
9. Part-season entries: best/last computed only from rounds actually started.
10. Class changes and car-number changes mid-season don't break season tables.

## 4. Import pipeline

Importer = a plugin implementing `parse(file) → staged rows` for a
(series, file-kind) pair. MVP importers, in order:

1. **IMSA results JSON** (race + qualifying classification) — DONE. Bootstraps
   past rounds and ingests each new weekend. Format quirks handled: two
   session_date formats (`dd-MM-yyyy` and `dd/MM/yyyy`), string car numbers,
   in-class positions derived from overall order (preserves penalty
   demotions), 2–4 driver crews.
2. **IMSA standings JSON** — DONE. Teams championships arrive as JSON (UTF-8
   BOM!) with the full season calendar and per-session points (quali/race
   split per round). Championship class/kind derived from the title.
   **Michelin Endurance Cup** files import through the same path: they carry
   checkpoint sessions ("Hour 6" / "Finish") over a 5-round subset, proving
   the generic championship_session model. Cup titles don't start with the
   series name, so series have **aliases** (e.g. "IMSA Michelin Endurance
   Cup" on IMSA) that the importer matches by longest prefix; missing aliases
   fail the commit with an actionable message.
3. **IMSA entry list PDF** — DONE, via the **Python parser sidecar**
   (`parser/parse_entry_list.py`, adopted from the user's prior
   imsa-broadcast-prep project; pdfplumber handles multi-line driver cells and
   the italic-shear team/sponsor split). The backend shells out to it on PDF
   upload; the two communicate via the entries.json contract (parser/SCHEMA.md).
   Creates the event pre-weekend, upserts entries (sponsor/tire/fuel included),
   supports TBD seats, and stores ratings with provenance: **ENTRY_LIST-sourced
   ratings are authoritative** — a later results import keeps them (derogation
   rule) and only fills RESULTS-sourced ratings for drivers the entry list
   didn't cover. Series detection comes from the filename code (IWSC/IMPC/...)
   matched against series abbreviation or alias. Also ready for the challenge
   series (hometowns, Bronze Cup / coach / rookie icon markers).
4. **Championship points PDF** — DONE (2026-07-15), via a second Python sidecar
   (`parser/parse_points.py`), for the series that publish no standings JSON —
   2024 Mustang Challenge among them. It emits the same standings JSON shape, so
   the existing STANDINGS review/commit path carries it: PDF → `points.json`
   (parser/POINTS_SCHEMA.md) → one batch per championship, because one sheet
   holds every championship of the series. Chosen explicitly as `IMSA_POINTS_PDF`
   — AUTO can't tell two IMSA PDFs apart without opening them, so it assumes an
   entry list and now says so instead of staging an empty one.
   Read as text these sheets are *quietly wrong*: points columns come in pairs
   ("Extra"+"Round N", or "Qualifying"+"Race") and adjacent cells render flush,
   so Noaker's 2024 row prints `350 10320 10320` where 10320 is Extra 10 + Round
   320 — read naively his season totals 43230 against a printed 3270. The parser
   is therefore geometry-driven: the column headers are **rotated 90°**, giving an
   exact x anchor per column; long names overprint the numbers in the same font
   with interleaved x, so fonts and baselines separate them and the name is read
   in draw order. **Every row is checked against its printed total** and the
   parser exits non-zero rather than emit points that merely look plausible.
   `bonus_points` (V21) records the sheet's "Extra" column whole: the JSON splits
   pole from fastest lap, the PDF prints them summed, and a 10 could be either —
   so where a JSON exists, import that. The season year is the one thing the sheet
   doesn't state (a points value can look like a year), so it falls back to the
   PDF's creation date and **the reviewer confirms it**. Validated against a season
   IMSA published in both formats: reproduces the JSON import row for row.
   **Still open on this seam:** the manual editor for championships with neither
   file.
5. **Image bulk upload** — DONE. Images are keyed by **(season, car number)**
   — numbers repeat across series, so the season scope disambiguates while one
   image carries over across a season's events until replaced. Filename
   matching is strict-by-string against the season's known numbers (digit runs
   of 1–3, so "2026" in a filename is never a candidate; `023` never matches
   `23`); ambiguous or unknown filenames are surfaced for one-click manual
   assignment, never guessed. Coverage view lists cars still missing an image.
   Stored as BYTEA; served per entry via `/api/entries/{id}/image`.

Since the MVP the pipeline gained **starting-grid importers** (JSON and, for
events without published grid JSON, a semicolon **grid CSV**), a
**championship-points PDF** importer for series that publish no standings JSON,
and an explicit **format** on upload — the `ImportFormat` enum (parser family =
provider × medium: `IMSA_JSON` / `IMSA_PDF` / `IMSA_CSV` / `IMSA_POINTS_PDF`),
which concretely realizes the `(series, file-kind)` plugin model above. `AUTO`
stays the default and resolves to a concrete family; `import_batch.format`
records which parser ran. Document-kind detection lives inside each family. One
upload can stage **several** batches — a points PDF holds every championship of
the series. A later family, `IRACING_JSON`, reads iRacing subsessions from an
exported file *or* the live Data API (same payload either way) and adds a
fetch-driven import UI alongside upload. Full detail in Phase 3.

### Starting-grid file contract

The grid formats are parsed in Java (`ImportParser.parseGrid` / `parseGridCsv`),
not by a Python sidecar, so they have no `parser/*_SCHEMA.md`; this is the
contract. Both name **who qualified the car and who takes the start**, in
different shapes — the reason the loader normalizes to a seat index:

- **Grid JSON** (`{session, grid[]}`, UTF-8 BOM): each slot carries `position`,
  `number`, `class`, `team`, `vehicle`, and — the attribution — an integer
  `starting_driver_number` and `qualifying_driver_number` plus a per-car
  `drivers[]` roster of `{number, firstname, surname, license, hometown,
  country}`. Those two numbers are **1-based seat indexes into that roster**,
  the same per-car numbering a results file uses for `drivers[].number`, which
  is what makes seats comparable across the two file kinds. `0`/absent means
  "no seat named", the same convention as `fastest_lap_driver_number`.
- **Grid CSV** (semicolon, BOM, CRLF, trailing `;`): header
  `POSITION;CLASS;NUMBER;STARTING_DRIVER;QUALIFYING_DRIVER;DRIVER_1..DRIVER_6;TEAM;CAR;TIME;`.
  Attribution is by **full name**, resolved to a seat by matching against that
  row's own `DRIVER_1..6` (DRIVER_N = seat N). The parser deliberately builds
  **no roster** from these columns: a single "Hannah Grisham" string cannot be
  split into first/surname without corrupting the `(first_name, surname)` key
  that identifies a driver, so the entry list stays the driver authority and
  commit resolves the seat through the stored lineup.
- **iRacing grids** carry no attribution at all (a solo league's entry *is* its
  driver), which is why the stats fall back to an entry's sole crew member
  rather than requiring a stored value.

Only `POSITION`/`CLASS`/`NUMBER`/`TEAM`/`CAR`/`TIME` are required of a CSV — a
file without the driver columns still imports, with null attribution.

Real sample files for each format to be provided by the user when the
importer is built — parsers are written against real files, not assumptions.
The 2026 samples live under `backend/src/test/resources/fixtures/` (per series:
`imsa/`, `mustang/`, `pilot/`) and the parser test suite runs against them.

Every importer output goes to the staging review screen (edit cells, drop rows,
then commit). Unrecognized formats fail loudly with the raw text shown, never
silently guess.

## 5. Outputs

1. **Pit-lane entry list PDF (MVP)** — ✅ DONE. Same layout as the hand-made
   example plus nationality flags, a manufacturer column (logo/name), and
   class-colored headers + zebra rows. Details in Phase 1 above.
2. **Season reference table** — ✅ DONE. Rows = cars, columns = rounds, each cell
   the car's **start → finish** in class (broadcaster's call over quali/finish);
   per class on the season hub. See Phase 4.
3. **Per-format stats** — ✅ DONE. Per-driver wins, podiums, top-5s, DNFs and
   poles, counted separately for each kind of race a weekend runs, for one
   season or all-time across a series. On the hub's Stats sub-page, a stat-leader
   widget, and the driver modal. See Phase 4.
4. **Grid rundown sheet** — generated from the imported starting grid, in grid
   order with storyline fields (champ position, best/last, notes). Grid *data*
   imports today — including which driver qualified the car and which starts it —
   so this is now purely a presentation sheet, **deferred to after hosting**
   (Phase 6). Exact contents to be specced with the user then.
5. Free-text **notes per entry** (pit-lane intel: sponsor pronunciation, driver
   storylines) — surfaced in a **dynamic dashboard** (click an entry to load its
   notes), *not* on the generated PDF. Deferred until after hosting (Phase 4a),
   which provides the auth/multi-user substrate this shared, living content needs.

## 6. Phases

**Phase 0 — Skeleton (small).** Monorepo, Spring Boot + React + Postgres via
Docker Compose, Flyway baseline schema, one vertical slice (create a series in
the UI, persisted). `git init` the repo.

**Phase 1 — MVP: IMSA entry list PDF. ✅ COMPLETE (2026-07-09).**
Delivered:
- Domain model: series (+ aliases) → season → events → sessions/entries/
  drivers/results, and championships → per-session points. Postgres + Flyway
  (migrations V1–V9).
- Importers, all auto-detected on upload and staged for review before commit:
  IMSA **results JSON**, **standings JSON** (team, driver, and Michelin
  Endurance Cup families), and **entry-list PDF** via the Python parser
  sidecar (`parser/`). Manual standings entry not needed — everything IMSA
  publishes arrived as JSON except the entry list, which the sidecar handles.
- Season bootstrap: all completed 2026 rounds + 2025 CTMP loaded from real
  files; standings grouped by championship family in the UI.
- Bulk **car images** keyed by (season, car number); **manufacturer logos**
  keyed by name (user-uploaded, own column on the sheet).
- **Pit-lane entry list sheet**: print-first US Letter page (`#/sheet/{event}`)
  → PDF via headless Chromium (the browser page *is* the export; no separate
  Playwright renderer needed). Columns: #, team, manufacturer (logo/name),
  drivers with nationality flags + ratings (TBD seats as "(?)"), qualifying
  (blank until imported), prior-year-at-venue (auto-passed when car+team carry
  over, else manual override), championship position + points (teams default,
  per series style), best result of season (+venue, ties listed), last race
  result, car photo. Class-colored headers + subtle class-tinted zebra rows,
  guest badges.
**Acceptance test met: the 2026 sheet regenerates from imported data;** the
only gap vs. the hand-made original is manufacturer logos (upload-pending, the
sheet shows names until then) and that standings reflect the latest imported
round rather than a pre-round snapshot.

**Phase 2 — Season tools. ✅ COMPLETE.** Delivered below: class-name
canonicalization, best/last handling, pre-round standings snapshot, season-hub
UI, championship_group remodel, image size variants. The **season reference
table**, **per-entry notes**, and the **championship-consolidation presentation
features** (hub per-class grouping + sheet Endurance Cup column) were **moved to
Phase 4** — the season hub needs more concrete design first before those are
built on it.
**Standings — pre-round snapshot ✅ DONE; recompute-from-results + manual
override DEFERRED.** Every IMSA championship publishes a JSON standings file with
the full per-round points breakdown, so recomputing totals from results is
redundant now (it belongs to Phase 3, when a series without standings files
appears) and there's nothing to hand-enter. What was needed: the sheet's champ
column showed the *latest* cumulative total, not standings as they stood *going
into* the round. Fixed with `event.round_ordinal` (V12, backfilled by calendar
order, kept current by `renumberSeasonRounds` on import). The sheet groups each
championship's sessions into rounds at query time (no name-matching, no event FK)
and sums only the rounds before this event's ordinal, re-ranking per class. Round
number now shows in the sheet header; the same per-round series will feed a
championship-gap trend graph later. Positions are re-derived from summed points
(no official tie-break countback — an accepted approximation).
**Best/last-result handling — ✅ DONE.** Root cause found: the results parser
derived `position_in_class` by counting rows, which fabricated a class position
for DNS cars (e.g. #79 LMP2 shown "12th" at Sebring though it never started).
Fixed at import (`ImportParser.didNotStart` — DNS rows are kept but carry a null
position) + migration V11 backfilled existing rows. With that, best = strongest
actual finish (DNS excluded, DNFs count at face value since they're classified),
last = the finishing position or "DNS", and venue ties stay listed inline
("2nd – SEB/LAG") — no footnotes, per the broadcaster's call. **Season hub
navigation — ✅ DONE.** The UI is restructured around the hierarchy. Top nav is
now **Seasons · Imports · Logos · Series** (the flat Events/Standings tabs are
gone). `/` lists series-seasons; `/seasons/{id}` is the hub gathering the
calendar (rounds in order, each → event detail + Sheet), championship families
(grouped by `group_title`), and per-season car images (the old Images page,
refactored into a `SeasonImages` component that takes the hub's seasonId).
Routing is **react-router (HashRouter)** — zero server-config and the existing
`#/sheet/{id}` links keep working; the sheet renders outside the nav layout so
the print page stays clean. Backend: new `SeasonController`
(`/api/seasons`, `/api/seasons/{id}`); `seasonId` added to the event/championship
summaries so detail pages link back to their hub.
**Championship model — `championship_group` ✅ DONE.** The Michelin Endurance Cup
is not a separate contest — it's a **sub-cup within** the WeatherTech
championship (an extra points award over 5 of the season's rounds; historically
there's also been a Sprint Cup over the other subset). The loose
`group_title`/`kind` strings on each per-class `championship` are lifted into a
first-class `championship_group` (one per season × family × kind) with an
explicit `ordinal` and an `is_cup` flag (V13; `championship` now hangs off a
`group_id`, `group_title`/`kind` dropped). The importer find-or-creates the group
(`family` that isn't the series' own name ⇒ cup). Frontend unchanged — the
`ChampionshipSummary` API still exposes `groupTitle`/`kind` (mapped from the
group). *Moved to Phase 4:* the presentation consolidation on top of this model —
the hub grouping each class's championship + cup(s) together, and a sheet
**Endurance Cup points column shown only at endurance rounds** (so one sheet, two
columns, instead of a separate cup PDF; cup rounds match the event by
`round_ordinal` — see the recap round-matching fix in Phase 4, which replaced an
earlier `venueAbbrev` match that collided on two rounds at similarly named
venues).
**Class-name normalization — ✅ DONE.**
IMEC standings spell classes long-form ("GT Daytona PRO") while entries/results
use short codes ("GTDPRO"). Rather than a per-series alias map, the entry list
is the per-season **class authority**: an import that references a class matching
no entry-list class (compared case/space-insensitively) is **flagged in the
Imports review screen and blocks commit** until the reviewer maps it to a known
class (`ImportService.classReview` / `canonicalizeClass`; commit takes an
optional `classMapping`). A space/case-only difference auto-resolves; a cold
season with no entry list yet accepts the file's classes as canon. Reviewer
mappings are not persisted (re-imports re-ask) — a *standing* spelling mapping
is a per-series **class alias** (V29, Phase 4's class-rename slice), which
canonicalizeClass consults after the reviewer's mapping and before everything
else. Migration V10 back-filled the existing IMEC championships to the
canonical short codes.
**Image size variants — ✅ DONE.** Full-res stays the source of truth in
`car_image`; on upload a ~400px-longest-side **WebP** "sheet" variant is
generated (scrimage-webp; bundled native cwebp binaries) into a new
`car_image_variant` table (V14), and lazily for images uploaded before variants
existed. Serving added a `?variant=sheet` param on both `/car-images/{id}/data`
and `/entries/{id}/image`, falling back to full-res if a variant is absent or
can't be produced (best-effort — never a hard dependency). The sheet, event-
detail thumbnails, and the images grid all point at the variant; the transparent
cutout is preserved. Measured on the 2026 set: **28 MB → 284 kB (1.0%)**, so the
exported PDF lands well under 1 MB (vs ~24 MB). Serving stays the single
indirection point, so this composes with the Phase 4 S3 move. (Root cause was
headless Chrome embedding each raster at ~source resolution regardless of
display size.)

**Phase 3 — Multi-series generalization.** First contrasting series:
**Mustang Challenge North America** (one-make, single-driver, two races/weekend).
- **Entry-list sheet — ✅ DONE.** Registered the series (abbrev `MC`); the sidecar
  now detects `MC` and parses its `Name NAT` driver line (no rating, no hometown)
  via `DRIVER_NAT_RE`; classes DH / DHL. VIP entries carry the same blue-V icon as
  IMSA "invitational" entries, so detection already worked — the commit now maps
  that marker to `entry.is_guest` (Jim Farley #17 → GUEST badge). Single-driver
  rendering confirmed end-to-end on the sheet. Sample: `parser/samples/
  2026_MC_MidOhio_EntryList.pdf`; parser tests cover it.
- **Multi-race results + event-key hardening — ✅ DONE.** Race 1 / Race 2 /
  Qualifying import through the existing results importer (same timing-provider
  shape; DH/DHL already canonical, single-driver). The sources name the weekend
  differently (entry list "Mid-Ohio SportsCar Weekend" vs results "O'Reilly Auto
  Parts 4 Hours of Mid-Ohio"); events are now matched by circuit + date window
  (see below), so results attach to the entry list's event (one event, three
  sessions) rather than duplicating it. The sheet's Q column fills from
  qualifying in-class positions.
- **Import target confirmation — ✅ DONE (replaces name auto-matching).** Rather
  than guessing series/event/championship from free-text names and accumulating
  aliases (which failed silently or hard-errored), the **review step now confirms
  the target**. `GET /api/imports/{id}/review` returns the tool's *guess* (series,
  event via circuit+date, championship class/kind/cup) plus the selectable
  options; the Imports page pre-fills them and the reviewer picks/overrides;
  `POST /commit` takes an explicit `ImportTarget` (series id or new-series name,
  event id or new, championship class/kind/is-cup/family, class map) and commits
  to it with no re-matching. Series aliases and the "family ≠ series name ⇒ cup"
  heuristic are retired as hard requirements (aliases stay only as guess hints).
  This is how the Mustang **drivers** standings (DH + DHL) imported — the reviewer
  just picked the series; no `Mustang Challenge` alias needed.
- **Drivers-championship champ column — ✅ DONE.** The sheet's champ column now
  picks the primary (non-cup) championship **per class, preferring TEAMS, else
  DRIVERS** (so IMSA stays team points, Mustang uses driver points). A TEAMS
  championship matches by car number; a DRIVERS one matches each crew member by
  **driver name** (single-driver → one position + points; a crew lists each
  member). Same pre-round snapshot: Mustang's imported events are a chronological
  prefix of the championship calendar, so `event.round_ordinal` aligns with the
  championship round position (Mid-Ohio round 3 → standings through round 4).
  Verified: Cole Loftsgard 1st (1350 pts) going into Mid-Ohio; IMSA unchanged.
- **Per-series class colours + order — ✅ DONE.** The hardcoded IMSA class
  colours (sheet.css) and `classRank` order (SheetController) are replaced by a
  `class_style(series_id, class_code, ordinal, color)` table (V15, seeded IMSA +
  Mustang DH/DHL). The sheet returns each class's colour and orders by the
  config; the frontend applies it via `--class-color`/`--class-tint` (zebra tint
  derived from the colour), so any new series renders correctly with no code
  change. A class without a row falls back to a neutral default sorted last.
  (DH navy / DHL bronze are seeded defaults, editable in the table.)
- **Per-series class-style config UI — ✅ DONE.** `class_style` (colour + order)
  is no longer SQL-only. `ClassStyleController` (`/api/series/{id}/class-styles`)
  exposes GET (configured styles + class codes seen in the series' entries that
  have no style yet, so codes carry the exact casing the sheet matches on),
  `PUT /{classCode}` (upsert, `#rrggbb` validated), `DELETE /{classCode}`. The
  Series page gains an expandable per-series editor (native colour picker,
  ordinal, delete; add via a suggestion dropdown or free-text code). No migration
  or code change to onboard a new series' classes now.
- **Starting-grid import — ✅ DONE.** The published **starting grids** (`grid[]`
  files) now import through the same stage→review→commit pipeline (detected as
  `GRID` = `session` + `grid`; `GridImport`/`ImportParser.parseGrid`). Grids are
  always imported, never computed — the published order already reflects the
  fastest-two-laps carry-over + penalties. A grid attaches to its race by the
  hardened `(event, session_type, ordinal)` key and stores a per-entry start
  position in `grid_position` (V17), with **in-class** start position derived from
  the overall grid order (blank grid slots skipped). Results and grid are separate
  files on the same session and may arrive in either order: `commitRaceResults`
  and `commitGrid` both find-or-create the session and replace only their own
  child rows (results / grid_position), so importing one never wipes the other.
  This gives **start → finish** per car — the true race story. Verified on the
  2026 WGI grid (54 cars, leading zeros like `033`/`911` preserved).
- **Importer-format foundation + grid CSV — ✅ DONE.** Uploads now carry an
  explicit **format** (parser family = source provider × medium: `IMSA_JSON`,
  `IMSA_PDF`, `IMSA_CSV`; `AUTO` default resolves to a concrete family and
  `import_batch.format` (V19) records it). Document-kind detection lives inside
  each family: the JSON family keeps its `looksLike*` chain; the CSV family
  sniffs headers. `IMSA_CSV` parses the published semicolon grid CSV
  (`ImportParser.parseGridCsv`: BOM/CRLF/trailing-`;` tolerant; the entry list
  stays the driver authority, but `STARTING_DRIVER`/`QUALIFYING_DRIVER` are read
  and resolved to seat indexes against the row's `DRIVER_1..6` — see the
  grid-driver attribution entry in Phase 4) including the **qualifying
  time** per slot (`grid_position.qualifying_time`, V18). Because the CSV carries
  no session/event metadata, review gains `needsSession`: the reviewer picks an
  **existing event** (all-events fallback list; class review recomputed via
  `?eventId=`) and the session type + race number; commit derives the season from
  the event and synthesizes the session name ("Race 2"). `upsertEntry` now
  COALESCEs manufacturer/class_group so a metadata-poor grid never erases
  entry-list richness. **Next slices on this seam:** manual entry (paste-a-table
  → editable grid → staged as a normal batch, `format=MANUAL`) and per-provider
  PDF parsers (e.g. `CCA_PDF`, config-mapped sidecar scripts).
- **Championship points PDF (`IMSA_POINTS_PDF`) — ✅ DONE (2026-07-15).** The
  second sidecar (`parser/parse_points.py`), for series/seasons with no standings
  JSON — 2024 Mustang Challenge. Emits the standings JSON shape, so the existing
  STANDINGS path commits it; one sheet holds every championship, so one upload
  stages **one batch per championship** (`stage()` returns a list). New
  `bonus_points` (V21) and a reviewer-confirmed **season year**
  (`ImportTarget.seasonYear`, review recomputes classes via `?seasonYear=`).
  Chosen explicitly: AUTO can't distinguish two IMSA PDFs without opening them.
  See §4 for why the parser reads geometry rather than text, and
  parser/POINTS_SCHEMA.md for the contract.
- **iRacing Data API (`IRACING_JSON`) — ✅ DONE (2026-07-17).** For sim-racing
  leagues (first user: the Porsche TAG Heuer Esports Supercup), where the source
  is iRacing's own API rather than a timing provider. One parser, two sources: an
  iRacing subsession export and the Data API's `/results/get` return the *same*
  result object — the exported file wraps it `{"type":"event_result","data":…}`,
  the API returns it unwrapped with `session_results` at the top level, and
  `IRacingParser` accepts both (`looksLikeEventResult`/`resultData`). Auto-detect
  routes the uploaded file with no format hint. One subsession is a whole meeting,
  so it stages **several** batches — qualifying, then each race in order, plus a
  grid per race; practice/warmup are dropped. Heat formats fall out for free
  (Sachsenring: five heats + consolation + feature). Times/gaps are
  ten-thousandths of a second (`-1` = none); a qualifier who set no lap is
  classified "No Time", not retired; the feature grid is imported verbatim
  (it's a reverse-of-heat grid, never derivable). Solo-league mapping: one seat
  per entry, `team` = driver, licence class only (the safety rating has no home).
  `sessionStart` is shifted into the JVM zone so the payload's UTC survives the
  timestamptz column; a track with no layout reports config_name `"N/A"`, dropped.

  Live fetch (`IRacingClient`, proven against subsession 74553295 — its batches
  are byte-identical to the exported file): the `password_limited` OAuth2 grant,
  which bypasses 2FA and needs no browser redirect (legacy read-only auth was
  retired 2025-12; the client id is scarce — issuance is paused). Both secrets
  are SHA-256+base64 masked before they leave the process. Data endpoints answer
  with a short-lived (~15 min) signed S3 link — `results/get` under `link`,
  `roster` under a wrapped `data_url` — fetched **as a URI** (a String is URI-
  template-expanded and corrupts the `%2F` in the AWS signature) and parsed **as
  bytes** (S3 serves the roster as octet-stream). Endpoints: subsession →
  results+grids; `league/season_sessions` → round enumeration; `season_standings`
  → StandingsImport (see the per-round assembly below); bulk-import a whole
  season, or a hand-picked list of subsessions — both resilient, one bad
  subsession reported not fatal. The **Import-from-iRacing modal** on the imports
  page is the UI for all of it (subsession list or league season → grouped result
  → the normal review + commit). Committing N rounds to one series lands them as
  N events under one season — a season assembled from individual races. Roster →
  entry list was evaluated and dropped as low-value here (a roster is season-wide
  with no team/class). Credentials live in a gitignored `application-local.yml`
  (the `local` Spring profile, bootRun only) locally and env vars in prod
  (`IRACING_CLIENT_ID` / `_CLIENT_SECRET` / `_USERNAME` / `_PASSWORD`; leave
  `IRACING_TOKEN_URL` / `_DATA_BASE` unset to hit the live service).
- **iRacing standings → a full recap (per-round points) — ✅ DONE (2026-07-18).**
  The recap builds its columns from `championship_session` and its per-round cells
  from `standings_session_points`, but the iRacing standings import left both empty
  (`season_standings` returns season totals only), so the recap degenerated to a
  flat Pos/Pts list. `stageStandingsFromIRacing` now also walks the season's
  completed rounds and reads the points **iRacing already scored** on each round's
  result — `league_points` per `cust_id`, summed across a round's race sim-sessions
  (a sprint-plus-feature round). **No scoring engine was built and none is needed**:
  the points are in the `/results/get` payload the round import already downloads;
  they were simply being dropped on the floor. `commitStandings`, the schema, and
  `SeasonViewController.recap` are unchanged — they already consumed this shape
  from the IMSA points-PDF path; only the parser was handing them empty lists.
  Round N of the calendar lines up with the season event of `round_ordinal` N
  (the recap matches **by ordinal, not venue** — see `625f73c`); the round's venue
  name supplies the column *label* only. `total_points` stays the endpoint's
  authoritative value: it already folds in the manual `positive/negative_adjustments`
  that carry post-race penalties, which per-race points cannot know about, so a row
  total and its per-round sum legitimately differ by those adjustments — that split
  *is* the points breakdown, now alongside a real per-round one. Drivers only
  (keyed `cust_id`); `team_standings` (car-number keyed) is the follow-up.
  **Unvalidated against a full season:** whether `league_points` or
  `league_agg_points` reconciles to `base_points` on a heat-format round (the
  Daytona fixture pays both a heat and a feature), and whether `base_points`
  already nets out the per-driver `drop_race` flag. Check `sum(per-round) ==
  base_points` for one driver on a real season before trusting the column totals.
  Also fixed alongside: the standings endpoint answers with a **bare list** of
  staged batches while the subsession/season endpoints answer with an
  `IRacingImport` — the import modal's shared `run()` cast every response to the
  latter, so `result.failures` was undefined and the modal threw *during render*,
  blanking the whole page (there is **no error boundary** in the app, so any render
  throw is a white screen — worth adding one).
- **Official iRacing series import (PESC 2019/2020) — ✅ DONE (2026-07-18).**
  The first two Porsche Esports Supercup seasons ran as *official* iRacing series
  (series 373 / season 2437 = 2019; series 409 / season 2812 = 2020), which the
  league flow cannot address. A parallel official flow now exists:
  `GET/POST /api/imports/iracing/series/{seriesId}/season/{seasonId}/{rounds,standings}`
  plus a third "By official series" tab in the import modal that shares the league
  tab's rounds preview and stages picked rounds through the existing
  `/iracing/subsessions` endpoint — `/results/get` payloads parse identically
  either way. **Only `official_session == true` races count anywhere**
  (`IRacingParser.parseOfficialSeasonRounds`): PESC 2020's June Le Mans was voided
  — it stays in `season_results` flagged unofficial with all-zero champ points and
  was re-run in week 10 — and `race_week_num` has gaps (week 4 raceless), so
  rounds are the official race subsessions sorted by `start_time`, never week
  numbers. Standings mirror the league walk with the official twins of every
  piece: season name / integer `season_year` / car classes from
  `/series/past_seasons` (the name is year-*suffixed*, so `leadingYear` would
  fail); totals from `/stats/season_driver_standings`, which answers in a third
  indirection shape — a `chunk_info` block naming pre-signed chunk objects, each
  a bare JSON row array (`IRacingClient.fetchChunkedRows`, same URI-verbatim /
  no-bearer rules as single links); per-round points read as
  `aggregate_champ_points` (the driver's round total, quali included, repeated on
  every sim-session row — taken as max per `cust_id` across *all* sim-sessions so
  a quali-only driver still scores). One STANDINGS batch per car class (name
  suffixed with the class only when multi-class). **Verified live on both
  seasons**: per-round sums reconcile to the published totals *exactly* for the
  whole 2020 top 10 (Job 659) and 2019 podium (Rogers 976) — unlike league
  standings there are no manual adjustments here, so column sums equal row totals.
  The recap's round columns fold quali points into the round figure by design
  (single-figure-per-round, same as the league path). Tests:
  `ImportServiceOfficialStandingsTest` (first service-level staging test — stubbed
  client, no DB; proves the voided round is never fetched and a failed round
  fetch leaves a calendar gap), chunk handling in `IRacingClientHttpTest`
  (%2F-preserving chunk URLs, no bearer to S3, non-array chunk → 502), and
  official fixtures under `fixtures/iracing/` trimmed from the real 2812 payloads
  (`season-results-2812.json` keeps the voided row and is deliberately shuffled).
- **iRacing rounds split into their scoring sessions (both paths) — ✅ DONE
  (2026-07-18).** Both iRacing standings paths staged one championship session
  per *round*, holding the round's whole points figure. Every other importer
  splits a round into its scoring sessions — a published IMSA standings file
  lists "Daytona / Qualifying" and "Daytona / Race" as separate sessions sharing
  an event name, which `SeasonViewController.recap` groups and sums back into one
  round column (`pole_points` is **not** where IMSA's qualifying points live; it
  is a bonus column, zero throughout the real file). The iRacing paths now follow
  that convention via one shared `IRacingParser.roundSessions`: every sim-session
  that paid somebody becomes its own session ("Qualifying", "Heat 1", "Feature"),
  all carrying the round's venue as their event name. Practice and warmup, and
  any session that scored nobody, contribute nothing — no columns of zeros.
  This fixed a real bug on **each** path:
  - **League** read only race sim-sessions, silently dropping league qualifying
    points. The 2025 PESC league pays them: Cooper Webster's Daytona was staged
    as 70 (heat 20 + feature 50) when iRacing scored him 78 (pole 8 + 20 + 50).
    Per-round sums now reconcile to `base_points` for **28 of 30** drivers,
    closing a question flagged as unvalidated when the league path was built.
    The two exceptions are a ±5 steward correction the league baked into the
    race payloads inconsistently with `base_points`; `total_points` stays the
    authoritative row total, as before.
  - **Official** read `aggregate_champ_points` (the cached round total). Across
    both PESC seasons that agreed with the per-session sum for every driver-round
    but one — Joshua W Anderson at Spa 2020, where the cache ran a point ahead of
    both the sum and the published standings, so his ten columns summed to 122
    against a correct row total of 121. Reading per-session makes all 40 rows of
    both seasons sum exactly to their totals.
  Verified live on both seasons and the 2025 league: 30 sessions across 10 rounds
  for PESC 2020, collapsing to 10 recap columns with round 1 reading 72 (6+16+50).
  **Backfill = re-import the standings** — commit replaces on (season, name), so
  an already-imported season keeps one championship and simply gains the split.
- **Standings adjustments stored as their own figures (V28) — ✅ DONE (2026-07-18).**
  A league's steward correction is a season-level ruling with no round to
  attribute it to, so it does not belong in `standings_session_points.penalty_points`
  (which exists because a published standings JSON attributes its extras to the
  session that earned them). V28 adds `base_points`, `positive_adjustments` and
  `negative_adjustments` to **`standings_row`**, carried through the importer as
  `StandingsImport.Adjustments` and surfaced on the recap row as `adjustments`.
  The invariant is `total_points = base_points + positive + negative`, with the
  per-round columns summing to `base_points` — so the difference between a row
  total and its columns now has a name instead of being an unexplained gap
  (Webster 2025: columns 389, adjustment −10, total 379). **Nullable, and null
  means "this source reports no such thing"** rather than zero: an IMSA standings
  JSON has no adjustment concept, and an official iRacing series carries none,
  which is exactly why official totals reconcile to the rounds exactly where a
  league's need not. Only the iRacing **league** path populates them today.
  Gotcha for anything reading these back: Postgres hands a NUMERIC over as
  BigDecimal, and `rs.getObject(col, Double.class)` throws "conversion to class
  java.lang.Double from numeric not supported" — read `getBigDecimal` and convert
  (this only shows up against a real database, not in the parser tests).
  **Next on this seam — manual adjustments UI (not built):** the fields are
  deliberately shaped so a human can set them without an import. A per-row editor
  on the standings/recap surface would write `positive_adjustments` /
  `negative_adjustments` and recompute `total_points` from `base_points`, letting
  a broadcaster record a penalty a source hasn't published yet, or correct one it
  got wrong. `standings_row.provenance` (already `imported` | `manual`) is the
  flag for marking a hand-edited row so a re-import can decide whether to
  preserve it — commit currently replaces the championship wholesale, so that
  decision needs making before the editor ships.
- **Per-series class aliases + class rename (V29) — ✅ DONE (2026-07-18).**
  One series imported from two providers can spell its single class two ways —
  iRacing's official-series payloads say `[L] Porsche 911` where hosted/league
  payloads say `Hosted All Cars` (`car_class_short_name` either way), which
  split every PESC driver's **all-time** stats row in two (season tables were
  unaffected: one spelling per season). `class_alias` (V29: series_id, alias,
  class_name, unique on `(series_id, lower(alias))`) is the durable fix,
  mirroring `series_alias` for titles. `canonicalizeClass` resolves an alias
  after the reviewer's explicit mapping but **before the bootstrap case**, so a
  cold season imports canonical from the first file; `classReviewForSeason` /
  `reviewStandings` consult it so an aliased spelling is never flagged
  unknown; the entry-list commit applies it too (the entry list is the class
  authority, but a standing rename outranks it — otherwise a re-imported entry
  list re-seeds the retired spelling). Managed on Manage → Series → **Class
  names** (`ClassAliasController`): alias CRUD at `/api/series/{id}/class-aliases`
  and — the main entry point — `POST /api/series/{id}/classes/rename`
  `{from,to}`, which renames the class across every season's entries and
  championships in one transaction, carries the `class_style` row over (target's
  style wins on a merge), retargets aliases pointing at the old name, and
  records the retired spelling as an alias. Renaming onto an existing class is
  the merge. Applied live: both PESC spellings → **Porsche 911 Cup** (2,056
  entries, 7 championships); the all-time stats table went 135 rows → 106 and
  the independent row-level recomputation still matches the API exactly
  (0 discrepancies, all seasons + all-time). Re-import durability proven
  end-to-end on a throwaway series: after a rename, re-committing the same
  results file lands rows under the new name with nothing flagged in review.
  The bare file input on the imports page is replaced by `UploadFilesModal`:
  drag-and-drop (plus browse/paste), a per-file staged queue, and a shared
  **`SeriesEventPicker`** typeahead that pins one series + event as the batch's
  target so the staged rows land pre-filled. The same picker is an optional
  "Pin to" section in the iRacing modal (there the batches carry their own
  metadata, so pinning is a convenience, not required). It owns the
  `/api/series` + `/api/events` fetch, filters events to the chosen series, and
  supports creating a new series inline.
- **Confirm-and-commit grouping (`commit-group`) — ✅ DONE (2026-07-17).** After
  staging, both import modals (the file-upload `UploadFilesModal` and the
  `IRacingImportModal`) hand off to a shared **`ConfirmImportStep`**: proposed
  event cards, each holding its sessions, that the user drags between events
  (with a keyboard-accessible **"Move to…"** `<select>` as the equivalent
  control), renames, or attaches to an existing event. A **round-ordinal
  preview** merges the season's existing events with the proposed ones by date,
  showing the Rd numbers the commit-time renumber will produce — so a mid-season
  backfill's numbering is visible before commit. Confirming commits the whole
  batch through **`POST /api/imports/commit-group`**: **one transaction per event
  group**, so a subsession's sibling batches (results + grid) resolve **one**
  event and converge on one `race_session` — which single-batch `commit` can't
  guarantee (it attaches to a chosen eventId or creates a new event per batch).
  A group failure rolls back that group alone (its batches stay STAGED); standings
  batches commit individually (they hang off a championship, not an event).
  `ImportTarget` gains an **`eventName` override** so two rounds at the same track
  — iRacing names both after the bare circuit — are de-collided (the UI suffixes
  the date) before they hit `UNIQUE(season_id, name)`; the backend pre-checks the
  name and fails the group with an actionable message otherwise. The orchestrator
  reuses the existing `commit*` methods and `renumberSeasonRounds` unchanged (a
  `TransactionTemplate`, not `@Transactional`, spans the per-group transactions;
  the self-call to `commit` joins the template's transaction). Items that still
  need per-batch review — unrecognized classes, a metadata-less grid needing a
  session, a standings row with no championship kind — are set aside and left
  STAGED, seeded into the table below with the committed series/event. The review
  table remains the fallback for fixing anything after the fact. Shared frontend
  bits were extracted for reuse: `lib/useSeriesEvents` (the series/events fetch),
  `lib/importGroups` (filename→round grouping + kind labels), and
  `ImportStatusIcon`.
- **Still ahead:** design the automated prior-year-at-this-track feature,
  including change context (manufacturer, lineup, team) alongside the raw result.
  The **grid rundown sheet** (grid-order sheet with storyline fields) is
  **deferred to after hosting** (see Phase 6) — the grid *data* already lands, and
  the sheet is most relevant to Carrera Cup Asia, which isn't running for a while.
- **Session-key hardening (tech debt from Phase 1) — ✅ DONE.** Sessions were
  keyed `(event_id, raw session_name string)`, re-imported as delete-then-insert
  on that key — brittle: a renamed session (`"Race"` vs `"Race 1"`) added a second
  RACE row instead of overwriting, and multi-race weekends leaned entirely on the
  name to tell Race 1 from Race 2. Replaced with the structured
  `(event_id, session_type, ordinal)` key (V16: `race_session.ordinal` added,
  backfilled from the trailing number in the name; old `(event_id, name)` unique
  constraint dropped). The parser derives `ordinal` from the session name's
  trailing integer (default 1); `commitRaceResults` replaces on the new key, so
  re-import is idempotent under name drift; `name` is a display label only.
  `normalizeSessionType` stays the single centralized RACE/QUALIFYING/PRACTICE
  derivation (both current series fit it; a per-series config table is deferred
  until one doesn't). Also fixed a latent bug: `BrowseController`'s event-detail
  query joined *any* RACE session, so multi-race events rendered every entry twice
  — it now joins the latest (highest-ordinal) race. **Deferred (with the grid
  sheet):** the grid-source descriptor from §3. Events already key on
  circuit+date (Phase 3), so the event string-key half of this debt is retired.

**Phase 4 — Hosted + deferred season tools.**
- **Season reference table — ✅ DONE.** On the season hub, a per-class grid: rows =
  cars, columns = rounds (`event.round_ordinal`, header = round + venue abbrev),
  each cell the car's **start → finish** in class for that round's race(s). Start
  comes from `grid_position`, finish from `result`; a multi-race weekend stacks one
  line per race (R1/R2), and a round with no grid imported falls back to finish
  only (with a hint to import grids). Row identity is `(class, car)` — the best/last
  key — so part-season cars blank the rounds they missed and a class-switcher shows
  in both tables; team label taken at the car's latest round. Class order + header
  colour reuse `class_style`. Read-only `GET /api/seasons/{id}/reference`
  (`SeasonReferenceController`) + `SeasonReferenceTable` component. The broadcaster
  chose **start → finish** over quali/finish: the grid (fastest-two-laps carry-over
  + penalties) is the true starting order, so the cell shows places gained/lost.
  *Superseded by the browse rebuild below:* the `SeasonReferenceTable` component
  is gone and its cell vocabulary now lives in the hub's **season recap**.
  `/reference` survives as the data source behind the hub's widgets.
- **Browse rebuild — ✅ DONE (2026-07-15).** The seasons grid and old hub are
  replaced by a **series directory** (`/`) → **season hub** (`/seasons/:id`) on a
  documented design system (`PRODUCT.md` / `DESIGN.md`; register = product, north
  star "The Timing Tower"). The hub sets the season context (year switcher; class
  filter in the URL, so filtered views are bookmarkable and survive sub-page
  navigation) and shows four live-extract widgets over the **season recap** —
  the reference-table cell vocabulary plus Pos/Pts/Back/#/name, result tints
  (win/top-3/top-5/DNF+`R`), pole marks, and a top-level **WeatherTech ⇄ Michelin
  Endurance Cup** switch (the cup re-derives its *own* round numbering from
  `championship_session`, so DAY/SEB/WGI/RDA/ATL read as Rd 1–5) with Teams ⇄
  Drivers under it. Sub-pages: **Schedule**, **Standings** (points per round;
  since 2026-07-18 each round cell prints the **earnings breakdown** instead of
  one sum — one line per scoring session (`Q`/`R1` tags) with every bonus
  value on the table: `25 +1P +1F`, `320 +10` (lumped bonus, no letter code),
  red `−n` penalties. A session the competitor **sat out prints no line**, so a
  PESC driver who ran one heat of a six-race weekend costs two lines, not
  seven; tags are abbreviated from the **real session names** (Q/H/F/R,
  numbered only where the word repeats in the round) because once blank lines
  are gone the tag is the only thing naming the session. A **Breakdown ⇄ Round
  total** toggle (`?pts=total`) collapses back to one number per round.
  The **season recap** carries the same tags on its start→finish lines for
  multi-race rounds (`RecapRace.name` + `RecapRound.races`; tags derived over
  the round's whole race list so a lone fourth-heat start reads `H4`), shared
  with the driver/team modals through `RaceLine`; `raceForm.sessionTagList` is
  the one abbreviator behind both surfaces.
  The recap endpoint grew `RecapRound.sessions` +
  `RecapRow.sessionPoints` (per-`session_index` race/pole/FL/penalty/bonus
  components, gated to scored+contested rounds exactly like `pointsByRound`);
  components verifiably sum to totals for every imported row. Single-session
  rounds print the bare number and the data-driven legend disappears, so a
  source that scores one session per round renders as before. iRacing seasons
  gain Q/R lines as they are **re-imported** under the session split landed
  the same day — imports predating it keep one session per round until then),
  **Stats** (added 2026-07-18, see below; every column header sorts as of the
  same day — the label is the button and spans its cell for a 24×24 target,
  with a 5×4px caret in slack the header already had, so the two-row header
  keeps its exact 30/27px metrics and no column changes width. Sorts within a
  class section, never across; never-contested `·` sinks last in both
  directions; third click restores the backend ranking), **Results** (round selector →
  quali/grid + race), **Entries** (lineup rotation per car per round),
  **Photos** (the old hub's `SeasonImages`).
  New read-only endpoints in `SeasonViewController`: `/championships/{id}/recap`,
  `/seasons/{id}/lineups`, `/events/{id}/results`; `/seasons/{id}` also returns
  `entryClasses` so the UI only offers classes that can answer (a `class_style`
  row with no data used to render a dead-end filter whose empty states read as
  "import failed"). `venueAbbrev` learned WRLS / CTMP / COTA / WeatherTech
  Raceway — and later SPA / LMS / MUG — because the cup and Mustang calendars
  name venues differently from the event rows they label.
  `StandingsDetailPage`, `SeasonsLandingPage`, `SeasonHubPage` and
  `SeasonReferenceTable` retired; the event page, the sheet, and the admin tabs
  are untouched.
  **Known gaps** (from three `/impeccable critique` passes, snapshots in
  `.impeccable/critique/`): the recap is four independent tables (scroll-synced,
  but "one table with class bands" is the open IA question); empty future rounds
  eat ~35% of horizontal scroll (narrowing them per-grid would break cross-grid
  column alignment, so it needs a parent-level pass). *(The third gap — no
  search — was closed by the ⌘K palette; `search/SearchController` +
  `SearchPalette` ship a car/driver/team jump.)*
- **Results page rebuild — ✅ DONE (2026-07-17).** The Results sub-page stacked
  qualifying, the starting grid, and race classification end to end (~10 screens
  for a full field). Now one **ARIA tablist per session** (`?session=` in the URL),
  the grid in a **side-by-side modal** (`StartingGridModal`: pole up front, cars
  staggered as they line up), and the classification's columns computed from the
  whole session rather than the class-filtered rows, so flipping a class chip
  never reshapes the table or renames a header. Two data fixes rode along:
  **(a) qualifying best lap** — an IMSA "Qualifying Practice by Best Lap" file
  spells an entry's lap as `time`/`lap`/`kph` where a race file uses
  `fastest_lap_time`/`_number`/`_kph`; `parseRaceResults` read only the race
  spelling, so every qualifying entry stored a null best lap. Both spellings are
  read now (`firstText`/`firstInt`/`firstDouble`, race wins), and a
  **"Fastest lap by"** column exposes `fastest_lap_driver_seat` (the driver
  credited with the car's best lap — on a both-drivers-run-it qualifying session
  not always who qualified it, so it's labeled precisely; the true qualifying
  driver lives in the grid file, imported later — see grid-driver attribution
  below, which promotes the header to "Qualified by" where it exists).
  **(b) class gap** — computed
  client-side, qualifying only (every qualifying gap is plain seconds off one
  leader, so exact subtraction; a race mixes those with lap-count gaps that carry
  no time). Existing committed sessions needed a re-import to backfill.
- **Session context on the Results page — ✅ DONE (2026-07-17).** Two facts the
  data already held but nothing read. **Stewards' notes:** `race_session.report_mark`
  / `report_message` (V2 columns, populated since Phase 1) now surface as a per-
  session notes panel (mark pill only when ≠ Official), with the cars each note
  names marked on their result rows (`browse/ReportNotes` extracts numbers from the
  head before the first ` - `, exact-then-zeros-stripped match). **Flags / race
  control:** a new import kind **FLAGS** (`FlagsAnalysisWithRCMessages` JSON =
  `session` + `flags`; `FlagsImport`/`parseFlags`; `session_flag` V24, verbatim
  TEXT times, `seq` = source order) mirrors the RACE_RESULTS stage→review→commit
  path and refreshes the session notes via the same COALESCE upsert (the flags
  report is generated later, so its notes are usually richer). A **"Race control"**
  disclosure on the session tab shows flag-period chips (GF/FCY/FF with lap +
  duration) and a scrollable RC-message log, lazy-fetched via
  `GET /api/sessions/{id}/flags` (`hasFlags` on the results payload gates it). RC
  messages link to cars (`browse/RcCars`: numbers only where they follow
  `Car`/`Cars`, never bare cross-references or turn/lap/article numbers), computed
  at read time — never stored, so the heuristic can change without a re-import —
  driving a car filter on the log. **Still dropped from the results JSON:** the
  session-level `fastest_lap` block (overall pole + driver). **Not parsed:** a
  qualifying-results CSV (`IMSA_CSV` recognizes only grid CSVs) — matters for the
  older VP Racing CSV-only events.
- **Recap round matching by ordinal — ✅ DONE (2026-07-17).** The recap matched
  each championship round to its season event by `venueAbbrev`, which is not
  unique within a season: Spa ("Circuit de Spa-Francorchamps") and Le Mans
  ("Circuit des 24 Heures du Mans") both abbreviate to `CIR`, so the venue-keyed
  map collapsed them, the later round overwrote the earlier one's event, and
  Round 3 rendered as a column of "·" for every driver while its results showed
  under Round 7. Now `SeasonViewController.recap` keys season events by
  `round_ordinal` — unique by construction — and `venueAbbrev` supplies only the
  column *label* (it gained SPA / LMS / MUG so the two columns also read
  differently). The **Back column** was reworked at the same time: a leading
  minus plus the gap to the row above in brackets ("-64 (-50)"), one `backText`
  helper serving both the recap and Standings so they can't drift.
- **Standings import year and kind for generic sources — ✅ DONE (2026-07-18).**
  An iRacing league season whose name carries no year ("League 6004 season
  99330") could not be imported at all: the guesser produced no year *and* a
  garbage kind ("99330", parsed from the name's tail), the confirm step offered
  no control for either, and the commit died on a 422. Two fixes. `needsYear` is
  now true for **every** standings batch, not just `IMSA_POINTS_PDF` — a season
  year is confirmed, never assumed — and is deliberately independent of the
  guessed year, because the review refetch that follows entering one would
  otherwise flip the condition false and yank the field mid-edit.
  `ConfirmImportStep.excludeReason` now validates the guessed kind against the
  real set (DRIVERS/TEAMS/MANUFACTURERS) and routes an unrecognized kind — or a
  yearless standings — to the review table, which has the pickers, instead of
  committing garbage from the modal.
- **Per-race-format stats — ✅ DONE (2026-07-18).** Every surface was a *matrix*
  (start→finish per round); nothing counted anything, and a naive tally would
  have been wrong anyway: a weekend's races aren't interchangeable, so a sprint
  win and a six-car heat win must not land in the same bucket. New per-series
  **`race_format`** (V25: stable machine `code`, renamable display `name`,
  ordinal) with `race_session.format_id` + `format_source` ('AUTO'|'MANUAL'),
  RACE sessions only — QUALIFYING deliberately carries no format, its stats are
  their own bucket. `formats/RaceFormatService.autoAssignEvent` classifies by
  the event's **session shape, not names**: the same "Heat 1" is a full-field
  sprint on a two-race weekend and a six-car heat on a rallycross one (PESC runs
  both), so ≥4 races or a consolation name selects the rallycross vocabulary
  (RX_HEAT/RX_CONSOLATION/RX_FEATURE), two differently named races give
  SPRINT/MAIN, and same-named races collapse to one repeated RACE (Mustang's
  "Race"/"Race 2" drift). It runs at the end of `commitRaceResults` and
  `commitGrid`, and never overwrites a MANUAL pin. `formats/RaceFormatController`
  is the manage surface (list/create/rename/merge/delete,
  `PATCH /api/sessions/{id}/format`, and a
  `POST /api/series/{id}/race-formats/auto-assign` backfill — the classifier is
  Java, so a migration can't seed it). Reads: `browse/SeasonStatsController`
  (`GET /api/seasons/{id}/stats` and `/api/series/{id}/stats` for all-time,
  one shared query, rows keyed driver × class) and `GET /api/drivers/{id}/stats`
  (career / per-series all-time / per-season grains). Surfaces: a season-hub
  **Stats** sub-page (season ⇄ all-time toggle, per-format column toggles
  limited to formats present in the data), a **Stat leaders** hub widget, and
  career chips + per-season lines in the driver modal. `raceForm.positionTier`
  became the single owner of the win/top-3/top-5 thresholds (`RaceLine`
  delegates). **Poles come only from qualifying results** — a reversed feature
  grid's front row is not a pole, so these counts deliberately disagree with the
  recap's `P` start chip. Backfill: press Auto-assign once per series.
- **Grid-driver attribution — ✅ DONE (2026-07-18).** The stats above exposed a
  correctness hole: quali claims fanned out over `driver_assignment`, so one
  qualifying lap became a pole for every member of an endurance crew. The grid
  files had the answer all along and the parsers threw it away. New
  `grid_position.qualifying_driver_id` / `starting_driver_id` (V27), FKs to
  `driver.id` — stable across re-imports, where `driver_assignment` rows are
  delete-and-reinserted and so must never be referenced. JSON grids carry 1-based
  seat indexes plus a per-car `drivers[]` roster (same shape as a results file);
  grid CSVs carry full names, resolved to seats against the row's own
  `DRIVER_1..6` and building **no** roster, because splitting a single
  full-name string would corrupt the `(first_name, surname)` driver identity;
  iRacing carries nothing. `commitGrid` seeds `driver_assignment` from the grid
  roster **only when the entry has none** (entry list and results still own the
  lineup and replace it wholesale later), then resolves each seat to a driver id.
  Quali stats now credit **only the qualifying driver of record**: the stored FK
  from the event's lowest-ordinal race grid, else a sole-crew-member fallback
  (which counts TBD seats, so a partly named crew never collapses to a false
  qualifier), else nobody. Race wins and podiums stay crew-wide. Surfaces: the
  starting-grid modal names each slot's starter (and the qualifier when they
  differ), the qualifying table's driver column becomes **"Qualified by"** where
  attribution exists (falling back to "Fastest lap by", then "Drivers"), and the
  sheet's Start column carries the short starter name. Backfill: re-upload grid
  files — committing a grid replaces that session's rows.
- **Hosting — the active next block (see Phase 4a below).** Deploy + auth +
  multi-user/sharing + the S3 image migration. Elevated to the priority because
  it's the substrate the deferred notes dashboard needs, and it unblocks sharing
  sheets/tables with the broadcast team.
- **Per-entry notes — DEFERRED, and reframed.** Not a field on the generated PDF
  (a print artifact read on air); instead a **dynamic dashboard** where the user
  clicks an entry to load its detailed notes (pit-lane intel: sponsor
  pronunciation, driver storylines). Living, searchable, shared content — so it is
  built *after* hosting provides the auth/multi-user substrate, not retrofitted.
- **Championship-consolidation presentation — ✅ HUB HALF DONE (2026-07-15), sheet
  half still deferred.** The hub half shipped with the browse rebuild: the recap's
  championship ⇄ cup switch consolidates each class's championship and its cup(s)
  behind one control, with cup rounds matched to events by `round_ordinal`
  (originally `venueAbbrev` — see the round-matching fix below). Still
  deferred: the **sheet** Endurance Cup points column shown only at endurance
  rounds — and the sheet is now legacy (PRODUCT.md scopes the PDF as a
  proof-of-concept), so this may never be worth building.

**Phase 4a — Hosting.** Make it a real, shared web app, not a local dev stack.
**Decisions locked (2026-07-10):**
- **Platform: Railway** — push-to-deploy from a Dockerfile + managed Postgres
  add-on. (Fly.io / DO VPS considered; Railway chosen for lowest-friction DX on a
  solo tool; cost ~$10–20/mo.)
- **Packaging: single container.** A multi-stage Docker image: build the React
  bundle, build the Spring Boot jar serving that bundle as static assets (HashRouter
  means no server-side SPA fallback needed — deep links are `#/...`), final image
  `temurin:21-jre` + `python3` + `pdfplumber` with the `parser/` dir bundled (the
  sidecar stays; a PDFBox rewrite to drop Python is an optional later cleanup, not
  worth the regression risk now). Point the parser python/script config at the
  in-image paths.
- **Auth: Google login (Spring Security `oauth2-client` / OIDC) + email allowlist.**
  No third-party auth SaaS. Team sharing was going to be unlisted share tokens, but
  the user chose **account-based view access** instead (allowlist accounts for
  viewing) — deferred, since nobody logs in soon and the sheet ships as a PDF. See
  build-order step 3.
- **Images: stay in Postgres BYTEA for v1** (already ~284kB WebP variants; small).
  S3/R2 deferred — the `/data` / `/entries/{id}/image` endpoints remain the single
  indirection point, so S3 drops in later with no API change.
- **Config via env:** datasource, Google client id/secret, and the allowlist come
  from environment (Railway vars); application.yml keeps local dev defaults.

**Build order:**
1. **Single-container packaging — ✅ DONE.** Root `Dockerfile` (multi-stage: React
   build → Spring Boot jar serving it as static assets → JRE + bundled Python
   venv/pdfplumber + `parser/`), env-driven datasource/PORT/parser paths, base
   pinned to `jammy` (Python 3.10; the rolling tag's 3.14 crashes pdfplumber),
   JVM heap sized to the container. `docs/DEPLOY.md` has the Railway steps. Verified
   in-container: serves the React app + `/api` from one origin, Flyway migrates, the
   parser runs on amd64 (the ARM-Mac SIGILL is a Docker-emulation artifact only).
   *(Actual Railway deploy + Postgres provisioning are the user's to do.)*
2. **Google OAuth login — ✅ DONE.** Spring Security `oauth2-client`: two filter
   chains gated by `AUTH_ENABLED` (off in dev → open; on in prod → Google OIDC).
   A custom `OidcUserService` rejects any signed-in email not in
   `AUTH_ALLOWED_EMAILS` (empty = nobody). SPA-driven: `/api/me` is public and
   reports `{authEnabled, email}`; the Layout redirects to Google when auth is on
   and unauthenticated, shows the signed-in email + logout otherwise. All other
   `/api/**` are gated; a global `fetch` interceptor (`lib/authRedirect`) redirects
   to Google on any `/api` 401, so a session that expires mid-use re-authenticates
   gracefully instead of surfacing errors. Google's provider details are built
   in, so prod only sets the client id/secret (+ `AUTH_ENABLED`, allowlist) via env
   — the registration is *not* in application.yml (an empty client-id fails startup).
   CSRF: SameSite=Lax session cookie, tokens off. Verified: dev open, auth-on gates
   `/api/**` (401), `/oauth2/authorization/google` → Google. *(User creates the
   Google OAuth client; see docs/DEPLOY.md.)*
3. **Team sharing — DEFERRED, and reframed (decided 2026-07-10).** No one else is
   expected to log in soon, and the main deliverable (the pit-lane sheet) is already
   shared as an exported **PDF**, so live sharing isn't needed yet. When it is, it
   will be **account-based view access** — allowlist specific Google accounts for
   viewing the live dashboards — *not* unlisted share tokens. Concretely that means
   splitting today's single allowlist into an **editor** allowlist and a **viewer**
   allowlist and gating mutating requests (POST/PUT/PATCH/DELETE) to editors; a
   small, clean add when someone actually needs live access. Share tokens or a
   richer permission model only if the project's scope grows.

   ⇒ **Phase 4a is code-complete pending the user's Railway deploy** (Steps 1–2 done;
   the app is hosted-ready, behind Google login, team gets PDFs).

**Ops caveat:** any reverse proxy needs its request-size limit raised to match the
multipart limits in application.yml (defaults like nginx's 1MB would reject
entry-list PDFs / image bulk uploads). Railway's proxy is generous, but note it.

**Team-sheets PDF deep links — ✅ DONE (2026-07-12).** The series' team-sheets PDF
(team/driver-bio pages, one multi-page section per car — e.g. IMSA's spotter-guide
style "Team Sheets") attaches to a specific **event**: `event_document` +
`event_document_page` (V20, one doc per `(event, kind)`; re-upload replaces bytes
and map in place). On upload, a second sidecar (`parser/
extract_team_sheet_pages.py`, same contract style as the entry-list parser;
`TEAM_SHEET_PARSER_SCRIPT` in the container) extracts the **car-number → first
page** map — the CTMP layout puts the car number as the first text line of every
page, so the map is the first page per number. `EventDocumentController` exposes
upload / metadata / raw-PDF / per-car `PATCH` (manual fix or clear) / delete; the
event page gains a "Team sheets PDF" section (upload, mapped-count, unmatched
entries with a manual page setter, full mapping editor, replace/remove). The
sheet API adds `teamSheetPage` per entry + `teamSheetsVersion` (car numbers
matched with leading zeros stripped: PDF "04" = entry "4"), and the sheet page
makes mapped rows clickable → a modal (pdf.js, lazily rasterised ~5 pages around
the viewport, placeholder heights exact so the jump is precise) scrolled straight
to that team's section; continuous scroll reaches the rest of the document.
pdf.js is a dynamic import (own chunk, ~427kB) so only sheet pages with team
sheets pay for it; the modal and row-hover affordances are screen-only, so the
printed/exported PDF is unchanged. Verified end-to-end on the 2026 CTMP round:
64 pages, 32/32 cars auto-mapped (one true absentee, #37, correctly left
unlinked), #04 CrowdStrike click lands on page 3, prior-year cell edits don't
trigger the modal. Lazy rendering is scroll-position-driven (not
IntersectionObserver — deterministic, and canvases far from the viewport are
released; 64 rendered canvases would hold hundreds of MB).

**Sheet season-form strip — ✅ DONE (2026-07-12).** Each sheet entry now carries
a two-line strip under its main row: a header line of prior rounds (`R1 DAY` …)
over raw **start→finish in class** values — the season reference table's cell
data (same query shape: result ⟕ grid_position per race session), scoped to
rounds strictly before the event and filtered per class (LMP2 doesn't show
IMSA's sprint rounds as "—"; a round the *car* missed within a contested round
set does show "—"). Multi-race weekends render `R1 5 R2 4` within the round
block; `raceText`/`statusAbbr` moved to `frontend/src/lib/raceForm.ts`, shared
with `SeasonReferenceTable`. The **Best/Last columns are dropped** (the strip
supersedes them; widths redistributed). Structural change: each entry is now
its own `<tbody>` (main row + strip) — zebra moved from
`tr:nth-child(even)` to `tbody:nth-of-type(even)` so the tint alternates per
entry and covers both rows, and `break-inside: avoid` on the tbody keeps the
pair together across printed pages (verified: 3-page CTMP export, every page
starts at the repeated column header). Entries with no prior data omit the
strip. Cars with data still deep-link to team sheets from either row. The
finish value is a **colour-coded chip** (gold P1 / silver P2 / bronze P3 / blue
4–5 / purple 6–10 / light-blue 11–20 / light-brown 21+ classified / black for
DNS·DQ·DNF) so the season form reads at a glance; brackets + non-result
detection live in `finishBucket`/`isNonResult` (raceForm.ts), the start stays
plain. The strip's position rows sit on a subtle neutral band with faint inner
dividers. The whole sheet's row height + content now scales from one knob —
`--row-scale` on `.sheet` (currently 1.25), applied as `base × var(--row-scale)`
to every size that drives row height (fonts, cell padding, images, flags),
still breaking cleanly at entry boundaries.

**Phase 5 — Live timing.** Ingest a timing feed (provider TBD per series),
WebSocket push to a dashboard, storyline surfacing (position changes vs champ
implications, guest running ahead of points leader, pit-cycle notes). Separate
design effort when Phase 4 is stable.

**Phase 6 — Grid rundown sheet (post-hosting).** The starting-grid *data* already
imports (Phase 3, `grid_position`); this is the print-first **grid-order sheet**
that presents it, with storyline fields (champ position, best/last, notes) —
reusing the sheet infrastructure. Deferred here per the user: it's most relevant
to Carrera Cup Asia, which isn't running for a while, so it waits until after
hosting lands. Spec the exact contents with the user then.

## 7. Open questions

- **Hosting choices (Phase 4a, active):** deploy target, auth approach, image
  storage (S3 provider), DB provider. Scope/ADR before building.
- Per-entry notes dashboard: exact UX for click-an-entry → notes (design after
  hosting).
- Prior-year-at-this-track change-context automation — **deferred, not MVP.**
  What to surface (manufacturer, driver lineup, team) and how, if built later.
- Grid rundown sheet contents (spec in Phase 6, after hosting).
- Live timing providers and feed access per series (Phase 5).
- **Race-format naming and merging policy** — formats are per-series with a
  stable machine `code` and a renamable display `name`, so a broadcaster can call
  the second race "Main" or "Feature" without disturbing classification. Open:
  whether merging two formats should be sticky (today a later auto-assign can
  re-split AUTO sessions, which is honest but can surprise), and whether a
  format should ever span series.
- **Quali attribution fallback order** — a pole credits the stored qualifying
  driver, else an entry's sole crew member, else nobody. The middle rung exists
  because iRacing files can never name a seat; the last rung means a
  multi-driver crew with no attribution contributes to no one's pole count until
  its grid is re-imported. Worth revisiting if the silent zero reads as a bug on
  air rather than as honesty.
- **Is the season recap one table or four?** Today it's one scroll-synced grid
  per class. One table with class bands as separators would kill four scroll
  positions and four header blocks, and would answer "what happened at CTMP
  across every class, at a glance" — the question four tables forecloses.
- **Is chronological left-to-right right for a lookup surface?** The recap
  auto-scrolls to the latest round because that's the live question; a
  latest-first calendar would make the newest round sit next to the team name
  and remove the auto-scroll entirely. Rejected once (2026-07-15) as inverting
  how a season reads; revisit if booth use says otherwise.
