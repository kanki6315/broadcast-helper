# Deploying Broadcast Helper (Railway)

The app ships as a **single container** (`Dockerfile` at the repo root): the Spring
Boot backend serves the built React bundle as static assets, with the Python
entry-list parser (pdfplumber) bundled in a venv. One image, one process.

## Build

```bash
docker build -t broadcast-helper .
```

Multi-stage: (1) `npm ci && npm run build` the frontend, (2) fold `dist/` into the
backend's `resources/static/` and `gradlew bootJar`, (3) JRE + Python venv +
`parser/` + the jar. HashRouter means client routes are `#/...`, so the server only
serves `index.html` at `/` — no SPA fallback route needed.

### Apple Silicon note

On an ARM Mac, `docker run` of the image can't run the **parser** — pdfplumber's
`cryptography` native binding hits SIGILL under Docker Desktop's ARM emulation.
That is a local artifact only. **Railway builds and runs amd64**, where it works.
To exercise the parser locally, build/run for amd64:

```bash
docker build --platform linux/amd64 -t broadcast-helper .
```

Everything else (frontend, API, DB) runs fine on the native ARM image.

## Railway setup

1. **New project → Deploy from repo** (or `railway up`). Railway auto-detects the
   root `Dockerfile`.
2. **Add a Postgres** service to the project (Railway → New → Database → Postgres).
3. **Set the app service's variables** (Railway lets one service reference
   another's with `${{Postgres.*}}`):

   | Variable | Value |
   |---|---|
   | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` |
   | `SPRING_DATASOURCE_USERNAME` | `${{Postgres.PGUSER}}` |
   | `SPRING_DATASOURCE_PASSWORD` | `${{Postgres.PGPASSWORD}}` |

   `PORT` is injected by Railway automatically; the app reads it (`server.port`).
   Flyway runs the migrations (V1–V17) on first boot.

4. Deploy. The app comes up on the Railway-provided URL.

## Not yet wired

- **Auth (Step 2):** Google login is not in place yet, so the deployed URL is
  currently **unauthenticated** — don't share it publicly until auth lands. It
  needs a Google OAuth client (you create it in Google Cloud Console) with the
  client id/secret provided as env vars, plus an email allowlist.
- **Share links (Step 3):** unlisted read-only share tokens for sheets/reference
  tables come after auth.

## Config reference (env)

| Variable | Default (local) | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/broadcast_helper` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `broadcast` | |
| `SPRING_DATASOURCE_PASSWORD` | `broadcast` | |
| `PORT` | `8080` | Listen port (Railway sets this) |
| `PARSER_PYTHON` | `python3` | Set to the venv python in-image (`/opt/venv/bin/python3`) |
| `PARSER_SCRIPT` | `../parser/parse_entry_list.py` | In-image path (`/app/parser/parse_entry_list.py`) |
