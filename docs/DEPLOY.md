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

## Auth (Google login)

Auth is **off by default** (local dev is open). To turn it on for the deployment:

1. **Create a Google OAuth client** — Google Cloud Console → APIs & Services →
   Credentials → Create Credentials → OAuth client ID → *Web application*.
   - **Authorized redirect URI:** `https://<your-app>.up.railway.app/login/oauth2/code/google`
     (add `http://localhost:8080/login/oauth2/code/google` too if you test the
     container locally). The path is fixed by Spring.
   - Copy the **Client ID** and **Client secret**.
2. **Set these env vars** on the Railway app service:

   | Variable | Value |
   |---|---|
   | `AUTH_ENABLED` | `true` |
   | `AUTH_ALLOWED_EMAILS` | comma-separated Google emails allowed to sign in, e.g. `you@gmail.com,teammate@x.com` |
   | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | the Client ID |
   | `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | the Client secret |

How it behaves: the SPA polls `/api/me`; if auth is on and you're not signed in it
redirects to Google, and a signed-in email **not** on `AUTH_ALLOWED_EMAILS` is
rejected with an access-denied message. An **empty allowlist admits nobody** — set
it. All `/api/**` (except `/api/me`) require a signed-in, allowed session.

> Enabling auth requires the Google client id/secret to be present, or startup
> fails. Set all four vars together.

## Team sharing

Deferred by design. The pit-lane sheet ships as an exported **PDF**, so the team
doesn't need live access yet, and no one else is expected to sign in soon. When
live viewing is wanted, the plan is **account-based view access** (allowlist
specific Google accounts, view-only) rather than share tokens — a small future
add. Until then, anything live requires being signed in and on `AUTH_ALLOWED_EMAILS`.

## Config reference (env)

| Variable | Default (local) | Notes |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/broadcast_helper` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `broadcast` | |
| `SPRING_DATASOURCE_PASSWORD` | `broadcast` | |
| `PORT` | `8080` | Listen port (Railway sets this) |
| `PARSER_PYTHON` | `python3` | Set to the venv python in-image (`/opt/venv/bin/python3`) |
| `PARSER_SCRIPT` | `../parser/parse_entry_list.py` | In-image path (`/app/parser/parse_entry_list.py`) |
| `AUTH_ENABLED` | `false` | Set `true` on the deployment to require Google login |
| `AUTH_ALLOWED_EMAILS` | (empty) | Comma-separated allowed Google emails (empty = nobody) |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID` | — | Google OAuth client id (when auth on) |
| `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET` | — | Google OAuth client secret (when auth on) |
