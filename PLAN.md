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
| IMSA source files | Race/qualifying results as **JSON**; **entry list PDF** per event; teams-championship standings as **JSON** (per-session points for the whole season calendar — richer than expected); other championships may still need the **standings PDF** importer or manual entry. User provides real samples when each importer is built |
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
  special cases.
- **Entry**: a car entered in an event — car number (stored as a **string**:
  `04` ≠ `4`, `068` keeps its zero), class, team, manufacturer/model,
  `isGuest` flag, livery image. Car numbers are unique **across the event**;
  leading zeros are how series achieve that (IMSA's #23 GTP vs #023 GTD), which
  is exactly why numbers must never be stored numerically.
- **DriverAssignment**: driver ↔ entry for one event, with seat order and the
  driver's **rating (P/G/S/B) as published on that event's entry list** — the
  per-event value is the source of truth because series can issue derogations
  overriding the FIA rating for an event or season. Endurance rounds add extra
  drivers to the same entry — assignments are per-event, not per-season.
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
4. **Standings PDF / manual editor** — for championships without JSON files.
5. **Image bulk upload** — DONE. Images are keyed by **(season, car number)**
   — numbers repeat across series, so the season scope disambiguates while one
   image carries over across a season's events until replaced. Filename
   matching is strict-by-string against the season's known numbers (digit runs
   of 1–3, so "2026" in a filename is never a candidate; `023` never matches
   `23`); ambiguous or unknown filenames are surfaced for one-click manual
   assignment, never guessed. Coverage view lists cars still missing an image.
   Stored as BYTEA; served per entry via `/api/entries/{id}/image`.

Since the MVP the pipeline gained **starting-grid importers** (JSON and, for
events without published grid JSON, a semicolon **grid CSV**) and an explicit
**format** on upload — the `ImportFormat` enum (parser family = provider ×
medium: `IMSA_JSON` / `IMSA_PDF` / `IMSA_CSV`), which concretely realizes the
`(series, file-kind)` plugin model above. `AUTO` stays the default and resolves
to a concrete family; `import_batch.format` records which parser ran.
Document-kind detection lives inside each family. Full detail in Phase 3.

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
3. **Grid rundown sheet** — generated from the imported starting grid, in grid
   order with storyline fields (champ position, best/last, notes). Grid *data*
   imports today; this presentation sheet is **deferred to after hosting**
   (Phase 6). Exact contents to be specced with the user then.
4. Free-text **notes per entry** (pit-lane intel: sponsor pronunciation, driver
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
columns, instead of a separate cup PDF; cup rounds match the event via
`venueAbbrev`, reliable for the 5 endurance venues).
**Class-name normalization — ✅ DONE.**
IMEC standings spell classes long-form ("GT Daytona PRO") while entries/results
use short codes ("GTDPRO"). Rather than a per-series alias map, the entry list
is the per-season **class authority**: an import that references a class matching
no entry-list class (compared case/space-insensitively) is **flagged in the
Imports review screen and blocks commit** until the reviewer maps it to a known
class (`ImportService.classReview` / `canonicalizeClass`; commit takes an
optional `classMapping`). A space/case-only difference auto-resolves; a cold
season with no entry list yet accepts the file's classes as canon. Mappings are
not persisted (re-imports re-ask). Migration V10 back-filled the existing IMEC
championships to the canonical short codes.
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
  (`ImportParser.parseGridCsv`: BOM/CRLF/trailing-`;` tolerant, driver columns
  dropped — the entry list is the driver authority) including the **qualifying
  time** per slot (`grid_position.qualifying_time`, V18). Because the CSV carries
  no session/event metadata, review gains `needsSession`: the reviewer picks an
  **existing event** (all-events fallback list; class review recomputed via
  `?eventId=`) and the session type + race number; commit derives the season from
  the event and synthesizes the session name ("Race 2"). `upsertEntry` now
  COALESCEs manufacturer/class_group so a metadata-poor grid never erases
  entry-list richness. **Next slices on this seam:** manual entry (paste-a-table
  → editable grid → staged as a normal batch, `format=MANUAL`) and per-provider
  PDF parsers (e.g. `CCA_PDF`, config-mapped sidecar scripts).
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
- **Hosting — the active next block (see Phase 4a below).** Deploy + auth +
  multi-user/sharing + the S3 image migration. Elevated to the priority because
  it's the substrate the deferred notes dashboard needs, and it unblocks sharing
  sheets/tables with the broadcast team.
- **Per-entry notes — DEFERRED, and reframed.** Not a field on the generated PDF
  (a print artifact read on air); instead a **dynamic dashboard** where the user
  clicks an entry to load its detailed notes (pit-lane intel: sponsor
  pronunciation, driver storylines). Living, searchable, shared content — so it is
  built *after* hosting provides the auth/multi-user substrate, not retrofitted.
- **Championship-consolidation presentation — DEFERRED (post-hosting polish):** the
  hub grouping each class's championship + cup(s) together, and a sheet Endurance
  Cup points column shown only at endurance rounds (cup rounds matched to the event
  via `venueAbbrev`). Presentation on data the model already holds; not MVP.

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
