# Broadcast Helper

Event-preparation tool for motorsports broadcasters: import results, standings,
entry lists, and car photos; generate broadcast reference documents as PDFs.

**Status:** Phase 1 complete — IMSA importers (results/standings JSON, entry-list
PDF), championship browsing grouped by family, bulk car images, manufacturer
logos, and the pit-lane entry-list sheet with one-click PDF export. See
[PLAN.md](PLAN.md) for the full plan, domain model, and phase roadmap.

## What it does today

- **Import** IMSA results JSON, standings JSON (team/driver/Michelin Endurance
  Cup championships), entry-list PDFs, and starting-grid CSVs — staged and
  reviewed before touching the database. Uploads choose a **format** (parser
  family = provider × medium, e.g. `IMSA_JSON`/`IMSA_PDF`/`IMSA_CSV`); the
  default auto-detects JSON and PDF. A grid CSV carries no event/session
  metadata, so review attaches it to an existing event and session (race
  number), and it keeps the per-car qualifying time.
- **Browse** events with class-grouped results, and championships grouped by
  family under each season.
- **Manage** car photos (matched per season by car number) and manufacturer
  logos (matched by name).
- **Generate** the pit-lane entry-list sheet per event: drivers with flags and
  ratings, qualifying, prior-year-at-venue (auto or manual), championship
  position, best/last result, car photo — as a print-ready US Letter PDF.

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
frontend/   React UI (Vite); sheet lives at src/pages/SheetPage.tsx
parser/     Python entry-list PDF -> JSON sidecar (see parser/SCHEMA.md)
docker-compose.yml
PLAN.md     build plan / roadmap
```
