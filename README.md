# Broadcast Helper

Event-preparation tool for motorsports broadcasters: import results, standings,
entry lists, and car photos; generate broadcast reference documents as PDFs.

**Status:** Phases 1–3 delivered — multi-series importers (results/standings/grid
JSON, grid CSV, entry-list PDF) with staging + review, bulk car images,
manufacturer logos, and the pit-lane entry-list sheet with one-click PDF export.
The browse UI was rebuilt (2026-07-15) into a series directory + season hub with
a season recap and five sub-pages, on a documented design system
([PRODUCT.md](PRODUCT.md) / [DESIGN.md](DESIGN.md)). Phase 4 (single-container
hosting + Google login) is code-complete pending deploy. See [PLAN.md](PLAN.md)
for the full plan, domain model, and phase roadmap.

## What it does today

- **Import** timing-provider results, standings (team/driver/Michelin Endurance
  Cup championships), and starting-grid JSON, plus entry-list PDFs and
  starting-grid CSVs — across multiple series (IMSA, Mustang Challenge), staged
  and reviewed before touching the database. Uploads choose a **format** (parser
  family = provider × medium, e.g. `IMSA_JSON`/`IMSA_PDF`/`IMSA_CSV`); the
  default auto-detects JSON and PDF. A grid CSV carries no event/session
  metadata, so review attaches it to an existing event and session (race
  number), and it keeps the per-car qualifying time.
- **Browse** from a series directory into a **season hub**: the hub sets a season
  context (year switcher; class filter chips persisted in the URL, so a filtered
  view is bookmarkable) and opens on four live widgets — latest/next round,
  championship leaders, last winners, entry counts — over the **season recap**
  table (rows = cars, columns = rounds, each cell the car's start → finish in
  class, tinted by result tier). The recap switches between the WeatherTech
  championship and the Michelin Endurance Cup — each with its own round
  numbering — and between Teams and Drivers. Five sub-pages hang off the hub:
  **Schedule**, **Standings** (points per round, each round a column),
  **Results** (pick a round → qualifying/grid + race classification), **Entries**
  (driver lineups per car per round, rotations highlighted, skipped rounds
  blank), and **Photos**.
- **Manage** car photos (matched per season by car number) and manufacturer
  logos (matched by name).
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
- **Entry-list parser** — Python sidecar (`parser/`), invoked by the backend
  to turn entry-list PDFs into JSON (see `parser/SCHEMA.md`)

## Running locally

Prerequisites: JDK 21, Node 20+, Docker, Python 3.10+ with
`pip install pdfplumber` (for entry-list PDF imports), and Chrome/Chromium
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
parser/     Python entry-list PDF -> JSON sidecar (see parser/SCHEMA.md)
docker-compose.yml
PLAN.md     build plan / roadmap
PRODUCT.md  who it's for, design principles, anti-references
DESIGN.md   the visual system: tokens, components, named rules
```
