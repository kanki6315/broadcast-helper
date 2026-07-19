# Broadcast Helper

Event-preparation tool for motorsports broadcasters: import results, standings,
entry lists, and car photos; generate broadcast reference documents as PDFs.

**Status:** Phases 1–3 delivered — multi-series importers (results/standings/grid
JSON, grid CSV, entry-list PDF, championship-points PDF, flags/RC-message JSON)
with staging + review, bulk car images, manufacturer logos, and the pit-lane
entry-list sheet with one-click PDF export.
The browse UI was rebuilt (2026-07-15) into a series directory + season hub with
a season recap and six sub-pages, on a documented design system
([PRODUCT.md](PRODUCT.md) / [DESIGN.md](DESIGN.md)); the Results sub-page was then
rebuilt (2026-07-17) around session tabs, a side-by-side starting-grid modal,
stewards' notes, and a race-control panel. **Per-race-format stats** landed
2026-07-18 — wins, podiums, top-5s and poles counted separately for each kind of
race a weekend runs (sprint vs main, heat vs feature) — together with the
**grid-driver attribution** that makes a pole count for the driver who actually
set the lap. **Multi-user access** landed 2026-07-19 — Google sign-in with
admin/viewer roles managed from Manage → Users. Phase 4 (single-container
hosting + Google login) is code-complete pending deploy. See [PLAN.md](PLAN.md)
for the full plan, domain model, and phase roadmap.

## What it does today

- **Import** timing-provider results, standings (team/driver/Michelin Endurance
  Cup championships), and starting-grid JSON, plus entry-list PDFs, starting-grid
  CSVs, championship-points PDFs, and flags/RC-message JSON — across multiple
  series (IMSA, Mustang Challenge), staged and reviewed before touching the
  database. Uploads choose a **format** (parser family = provider × medium, e.g.
  `IMSA_JSON`/`IMSA_PDF`/`IMSA_CSV`/`IMSA_POINTS_PDF`); the default auto-detects
  JSON (results, grid, standings, and flags) and entry-list PDFs. A grid CSV
  carries no event/session metadata, so review attaches it to an existing event
  and session (race number), and it keeps the per-car qualifying time. Grid files
  also name **who qualified the car and who takes the start** — the JSON as seat
  indexes into its own per-car driver roster, the CSV as names matched against
  its `DRIVER_1..6` columns — which is what lets a pole count for one driver
  rather than the whole crew. A **points
  PDF** covers the series that publish no standings JSON: one sheet holds every
  championship, so an upload stages one batch each (see
  [parser/POINTS_SCHEMA.md](parser/POINTS_SCHEMA.md)). Where a standings JSON
  exists, import that instead — it splits pole from fastest-lap points, which the
  sheet prints added together. A **flags file** (`FlagsAnalysisWithRCMessages`
  JSON) carries the session's flag periods and race-control messages, and also a
  fuller copy of the stewards' report than the results file — committing one
  refreshes the session notes.
- **Browse** from a series directory into a **season hub**: the hub sets a season
  context (year switcher; class filter chips persisted in the URL, so a filtered
  view is bookmarkable) and opens on five live widgets — latest/next round,
  championship leaders, last winners, entry counts, stat leaders — over the
  **season recap** table (rows = cars, columns = rounds, each cell the car's
  start → finish in class, tinted by result tier). The recap switches between the
  WeatherTech championship and the Michelin Endurance Cup — each with its own
  round numbering — and between Teams and Drivers. Six sub-pages hang off the hub:
  **Schedule**, **Standings** (points per round, each round a column),
  **Stats** (per-driver wins, podiums, top-5s, DNFs and poles, split by race
  format, with a season / all-time toggle and per-format column toggles),
  **Results** (pick a round, then tab between qualifying and race; the starting
  grid opens as a side-by-side modal naming each car's starting driver,
  stewards' notes sit above the table with the affected cars marked, and a
  race-control panel lists the flag periods and message log — filterable by
  car), **Entries** (driver lineups per car per round, rotations highlighted,
  skipped rounds blank), and **Photos**.
- **Count** results by **race format**. A weekend's races aren't
  interchangeable — a sprint win and a six-car heat win are different facts — so
  each race session is classified into a per-series format (Sprint/Main, or
  Heat/Consolation/Feature for a rallycross-style round, or a single Race). The
  classifier reads the event's *shape*, not session names, because the same name
  means different things on different weekends; it runs on import and any
  assignment can be pinned by hand in Manage, which survives re-imports.
- **Manage** car photos (matched per season by car number), manufacturer and
  series logos (matched by name), per-series class colours, and per-series race
  formats (rename, merge, or reassign a session's format).
- **Share it with the team.** Google sign-in with two tiers, managed from
  Manage → Users: **admins** do everything, **viewers** browse everything and
  change nothing (the Manage tab and every inline editor are hidden, and the
  backend refuses their writes regardless). Access is checked against the
  current roster on every request, so removing someone takes effect immediately
  rather than whenever their session expires. Rejected sign-ins are recorded and
  listed on the same page, so an account that can't get in — usually the wrong
  Google account — is added with one click.
- **Generate** the pit-lane entry-list sheet per event: drivers with flags and
  ratings, qualifying, prior-year-at-venue (auto or manual), championship
  position, car photo, and a per-round season form strip under each entry
  (round + venue over start→finish in class, prior rounds only) — as a
  print-ready US Letter PDF.
- **Deep-link team sheets**: attach the series' team-sheets PDF to an event
  (car → page map auto-extracted, manually correctable) and click any row on
  the live sheet to open a modal scrolled straight to that team's pages.
  Screen-only — the printed sheet is unchanged.

## Stack

- **Backend** — Java 21, Spring Boot 3, Flyway, PostgreSQL (`backend/`)
- **Frontend** — React + TypeScript, Vite (`frontend/`)
- **Database** — PostgreSQL 16 via Docker Compose
- **PDF parsers** — Python sidecars (`parser/`), invoked by the backend to turn
  entry-list PDFs (`parser/SCHEMA.md`) and championship-points PDFs
  (`parser/POINTS_SCHEMA.md`) into JSON

## Running locally

Prerequisites: JDK 21, Node 20+, Docker, Python 3.10+ with
`pip install pdfplumber` (for the PDF importers), and Chrome/Chromium
(only for exporting a sheet to PDF).

```bash
# 1. Database
docker compose up -d

# 2. Backend (http://localhost:8080) — applies Flyway migrations on startup
cd backend && ./gradlew bootRun

# 3. Frontend (http://localhost:5173) — proxies /api to the backend
cd frontend && npm install && npm run dev
```

Open http://localhost:5173.

## Generating a sheet PDF

Each event's sheet renders as a standalone print-first page at
`http://localhost:5173/#/sheet/{eventId}` (linked from the Events tab). Use the
page's **Print / Save PDF** button and choose "Save as PDF", or export headless:

```bash
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  --headless=new --disable-gpu --no-pdf-header-footer \
  --print-to-pdf=sheet.pdf "http://localhost:5173/#/sheet/{eventId}"
```

## Layout

```
backend/    Spring Boot API + Flyway migrations (src/main/resources/db/migration)
frontend/   React UI (Vite)
              src/index.css            design tokens (see DESIGN.md)
              src/pages/season/        the season hub + its sub-pages
              src/pages/SheetPage.tsx  the print-first pit-lane sheet
parser/     Python PDF -> JSON sidecars: entry lists (SCHEMA.md) and
              championship points (POINTS_SCHEMA.md)
docker-compose.yml
PLAN.md     build plan / roadmap
PRODUCT.md  who it's for, design principles, anti-references
DESIGN.md   the visual system: tokens, components, named rules
```
