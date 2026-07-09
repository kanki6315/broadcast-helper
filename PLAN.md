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
7. Prior-year-at-this-track: **deferred** — in the MVP this column is a
   per-entry manual text field. The eventual feature needs real design: not
   just last year's result, but context on what changed since (manufacturer,
   driver lineup, team) so the number isn't quoted misleadingly on air.
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
3. **IMSA entry list PDF** (PDFBox) — car/team/driver/rating/class per event
   (authoritative for ratings, including derogations).
4. **Standings PDF / manual editor** — for championships without JSON files.
5. **Image bulk upload** — match `#` in filename to entry, review grid,
   carryover from previous event of the same season.

Real sample files for each format to be provided by the user when the
importer is built — parsers are written against real files, not assumptions.
The 2026 samples live in `backend/src/test/resources/fixtures/imsa/` and the
parser test suite runs against them.

Every importer output goes to the staging review screen (edit cells, drop rows,
then commit). Unrecognized formats fail loudly with the raw text shown, never
silently guess.

## 5. Outputs

1. **Pit-lane entry list PDF (MVP)** — same layout as the hand-made example:
   car #, team, drivers + ratings, qualifying result, last-year-at-this-track
   (manual text field for now — see edge case 7), championship position +
   points (configurable source), best result of season (+ venue, ties listed),
   last race result, car photo. Grouped by class, guest badge where
   applicable. US Letter, print-preview page + one-click export.
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

**Phase 1 — MVP: IMSA entry list PDF.**
Domain model + CRUD for series/season/event/entry/drivers. IMSA results JSON
importer and IMSA standings PDF importer (with per-round quali/race points
split), both with staging review. Manual standings editor. Season bootstrap
(load all completed 2026 rounds). Image bulk upload + match review. Entry-list
print page + Playwright PDF export reproducing the hand-made 2026 WGI sheet
(prior-year column as a manual field).
**Acceptance test: regenerate the attached example PDF from imported data.**

**Phase 2 — Season tools.** Season reference table, computed standings with
manual override, IMSA entry-list PDF importer, per-entry notes, better
best/last-result tie handling and footnotes. **Class-name normalization:**
IMEC standings spell classes long-form ("GT Daytona PRO") while results files
use short codes ("GTDPRO") — add a per-series class alias map so championships
and entries join on the same class regardless of source spelling.

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
auth, multi-user (share sheets with the broadcast team).

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
