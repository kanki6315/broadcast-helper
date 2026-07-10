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

Real sample files for each format to be provided by the user when the
importer is built — parsers are written against real files, not assumptions.
The 2026 samples live in `backend/src/test/resources/fixtures/imsa/` and the
parser test suite runs against them.

Every importer output goes to the staging review screen (edit cells, drop rows,
then commit). Unrecognized formats fail loudly with the raw text shown, never
silently guess.

## 5. Outputs

1. **Pit-lane entry list PDF (MVP)** — ✅ DONE. Same layout as the hand-made
   example plus nationality flags, a manufacturer column (logo/name), and
   class-colored headers + zebra rows. Details in Phase 1 above.
2. **Season reference table** — rows = entries, columns = rounds, each cell
   quali/finish; per class.
3. **Grid rundown sheet** — generated from a qualifying session (or derived
   grid), in grid order with storyline fields (champ position, best/last,
   notes). Exact contents to be specced with the user in Phase 3.
4. Free-text **notes per entry** that flow onto sheets (pit-lane intel:
   sponsor pronunciation, driver storylines).

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

**Phase 2 — Season tools.** Season reference table, per-entry notes.
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
**Image size variants:** keep the full-resolution upload as the source of
truth, but on upload generate a few downscaled variants (e.g. a ~400px
sheet-sized WebP that preserves the transparent cutout). The sheet points at
the small variant so the exported PDF drops from ~24MB to ~1–3MB and the
hosted live page lazy-loads light thumbnails; full-res stays available for
larger uses. Serving stays behind the existing `/data` and
`/entries/{id}/image` endpoints (+ a variant param), so this composes with the
Phase 4 S3 move. Root cause: headless Chrome embeds each raster at ~source
resolution regardless of display size; Ghostscript output-compression is a
possible fallback but lossy and only helps the PDF, not the live page.

**Phase 3 — Multi-series generalization.** Add one contrasting series (two-race
sprint weekend, fastest-two-laps grids, single-driver entries). Grid rundown
sheet. Grid-source engine exercised for real. Per-series configuration UI
instead of code. Design the automated prior-year-at-this-track feature,
including change context (manufacturer, lineup, team) alongside the raw result.
**Harden import keys (tech debt from Phase 1):** sessions are currently keyed
by `(event_id, raw session_name string)` and events by
`(season_id, event_name string)`, with re-import as delete-then-insert on that
key. This is brittle — a renamed file (`"Race"` vs `"Race 1"`) won't overwrite
its predecessor, it adds alongside it, and multi-race weekends need a stable
`(session_type, ordinal)` key rather than a free-text name. Replace string keys
with the `Session` ordinal + grid-source model from §3, and make
`normalizeSessionType` (keyword heuristic, defaults everything to RACE) explicit
per-series config instead of a guess.

**Phase 4 — Hosted.** Deploy (single container + managed Postgres), simple
auth, multi-user (share sheets with the broadcast team). **Move car images to
S3**: the local BYTEA storage in `car_image` becomes metadata + S3 object key,
with images served from S3 (presigned URLs or CDN) instead of through the
backend. The upload/matching flow and `(season, car_number)` keying stay as-is
— only the byte storage and the `/data` / `/entries/{id}/image` serving paths
change, so keep those endpoints as the single indirection point. Also: any
reverse proxy in front of the backend needs its request-size limit raised to
match the multipart limits in application.yml (nginx defaults to 1MB).

**Phase 5 — Live timing.** Ingest a timing feed (provider TBD per series),
WebSocket push to a dashboard, storyline surfacing (position changes vs champ
implications, guest running ahead of points leader, pit-cycle notes). Separate
design effort when Phase 4 is stable.

## 7. Open questions (parked until Phase 3 per user)

- Prior-year-at-this-track automation: what change context to surface
  (manufacturer, driver lineup, team) and how to present it without cluttering
  the sheet.
- Grid rundown sheet contents.
- Live timing providers and feed access per series (Phase 5).
