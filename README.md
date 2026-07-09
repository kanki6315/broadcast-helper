# Broadcast Helper

Event-preparation tool for motorsports broadcasters: import results, standings,
and car photos; generate broadcast reference documents (augmented entry lists,
season tables, grid rundowns) as PDFs.

See [PLAN.md](PLAN.md) for the full build plan, domain model, and phase roadmap.

## Stack

- **Backend** — Java 21, Spring Boot 3, Flyway, PostgreSQL (`backend/`)
- **Frontend** — React + TypeScript, Vite (`frontend/`)
- **Database** — PostgreSQL 16 via Docker Compose
- **Entry-list parser** — Python sidecar (`parser/`), invoked by the backend
  to turn entry-list PDFs into JSON (see `parser/SCHEMA.md`)

## Running locally

Prerequisites: JDK 21, Node 20+, Docker, Python 3.10+ with
`pip install pdfplumber` (for entry-list PDF imports).

```bash
# 1. Database
docker compose up -d

# 2. Backend (http://localhost:8080) — applies Flyway migrations on startup
cd backend && ./gradlew bootRun

# 3. Frontend (http://localhost:5173) — proxies /api to the backend
cd frontend && npm install && npm run dev
```

Open http://localhost:5173.

## Layout

```
backend/    Spring Boot API + Flyway migrations (src/main/resources/db/migration)
frontend/   React UI
docker-compose.yml
PLAN.md     build plan / roadmap
```
