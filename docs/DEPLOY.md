# Deploying Broadcast Helper (Railway)

The app ships as a **single container** (`Dockerfile` at the repo root): the Spring
Boot backend serves the built React bundle as static assets, with the Python PDF
parsers (pdfplumber) bundled in a venv. One image, one process.

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
   Flyway runs every migration in `backend/src/main/resources/db/migration/` on
   first boot (V1–V27 at the time of writing — check the directory, not this
   line, for the current head).

4. Deploy. The app comes up on the Railway-provided URL.

### After the first deploy: two backfills

Both are idempotent and only needed once per series/event; the app works
without them, it just shows less.

- **Race formats.** V25 adds the tables but leaves assignments empty — the
  classifier is Java, not SQL, so it can't run inside a migration. Press
  **Auto-assign formats** once per series (Manage → Series → Race formats), or
  `POST /api/series/{id}/race-formats/auto-assign`. Until then the Stats tab
  files everything under an "Unassigned" column.
- **Grid-driver attribution.** V27 adds the columns; they fill on import, so
  events imported before the deploy need their **grid file re-uploaded**
  (committing a grid replaces that session's rows, so re-importing is safe).
  Until then multi-driver crews show no pole stats and the grid modal shows no
  driver lines. Single-driver series are unaffected — they resolve at read
  time from the sole crew member.

## Auth (Google login)

Auth is **off by default** (local dev is open). To turn it on for the deployment:

1. **Create a Google OAuth client** — Google Cloud Console → APIs & Services →
   Credentials → Create Credentials → OAuth client ID → *Web application*.
   - **Authorized redirect URI:** `https://<your-app>.up.railway.app/login/oauth2/code/google`
     — it **must be `https`** (Google rejects `http` for non-localhost). The path is
     fixed by Spring. Add `http://localhost:8080/login/oauth2/code/google` too only
     if you test the container locally.
   - Copy the **Client ID** and **Client secret**.

   > Railway terminates TLS at its proxy and forwards plain http to the container.
   > The app sets `server.forward-headers-strategy=framework`, so Spring rebuilds
   > the original `https` origin and generates an `https` redirect URI that matches
   > the one you register. (Without it Spring would emit `http` and Google rejects it.)
2. **Set these env vars** on the Railway app service:

   | Variable | Value |
   |---|---|
   | `AUTH_ENABLED` | `true` |
   | `ADMIN_ALLOWED_EMAILS` | comma-separated Google emails with **full access**, e.g. `you@gmail.com` |
   | `AUTH_ALLOWED_EMAILS` | comma-separated Google emails with **read-only** access, e.g. `teammate@x.com` |
   | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | the Client ID |
   | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | the Client secret |

How it behaves: the SPA polls `/api/me`; if auth is on and you're not signed in it
redirects to Google, and a signed-in email on **neither** list is rejected with an
access-denied message. Admin emails are implicitly allowed to sign in — no need to
repeat them in `AUTH_ALLOWED_EMAILS`. **Both lists empty admits nobody** — set
them. All `/api/**` (except `/api/me`) require a signed-in, allowed session, and
any mutating (non-GET) `/api` call additionally requires an admin session — with
**no admins configured, nobody can write**. Viewers don't see the Manage tab or
inline edit controls; the backend 403s them regardless.

> Enabling auth requires the Google client id/secret to be present, or startup
> fails. Set all five vars together.

## Team sharing

Implemented as **account-based view access**: put a teammate's Google account on
`AUTH_ALLOWED_EMAILS` and they can sign in and browse everything — seasons,
standings, sheets, driver profiles — but change nothing (writes are admin-only,
and the management UI is hidden). The pit-lane sheet still ships as an exported
**PDF** for teams without accounts.

## Config reference (env)

| Variable | Default (local) | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/broadcast_helper` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `broadcast` | |
| `SPRING_DATASOURCE_PASSWORD` | `broadcast` | |
| `PORT` | `8080` | Listen port (Railway sets this) |
| `PARSER_PYTHON` | `python3` | Set to the venv python in-image (`/opt/venv/bin/python3`); shared by every sidecar |
| `PARSER_SCRIPT` | `../parser/parse_entry_list.py` | In-image path (`/app/parser/parse_entry_list.py`) |
| `TEAM_SHEET_PARSER_SCRIPT` | `../parser/extract_team_sheet_pages.py` | In-image path (`/app/parser/extract_team_sheet_pages.py`) |
| `POINTS_PARSER_SCRIPT` | `../parser/parse_points.py` | In-image path (`/app/parser/parse_points.py`) |
| `AUTH_ENABLED` | `false` | Set `true` on the deployment to require Google login |
| `AUTH_ALLOWED_EMAILS` | (empty) | Comma-separated read-only Google emails |
| `ADMIN_ALLOWED_EMAILS` | (empty) | Comma-separated full-access Google emails; implicitly allowed to sign in (empty = nobody can write) |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | — | Google OAuth client id (when auth on) |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | — | Google OAuth client secret (when auth on) |
