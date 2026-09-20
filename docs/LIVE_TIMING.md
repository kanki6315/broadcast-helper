# Live timing (Al Kamel AKS V2)

The backend can hold a connection to Al Kamel's live timing feed and keep the
current state of the session in memory. This is the pipeline only: live
championship points, and later a live timing page, are built on top of it.

Code: `backend/src/main/java/com/pitpass/live/`. Schema: `V54__live_timing.sql`.
Protocol reference: *Timing AKS V2 Protocol (JSON)* 1.0.36.

## The one rule: a single login

The account allows **one concurrent login**. Everything below follows from it.

- **Production is the only thing that ever connects.** Never put
  `ALKAMELV2_HOST` in a local environment. Local development uses a recording
  (see *Developing without the feed*).
- **Don't use the same credentials in another tool while Pit Pass is
  connected** (Al Kamel's own timing app, a second deployment). What the server
  does with a second login — refuse it, or drop the first — is not documented
  and **has not been tested yet**. Find out on a practice session, not a race.
- **Only one process dials.** The connecting process holds a lease in the
  `live_timing` row and renews it every 3 s (30 s lease). On a redeploy the old
  and new processes overlap: the new one reports `STANDBY` until the old one
  receives SIGTERM, closes the socket and releases the lease — a few seconds.
  A process that crashes simply stops renewing and is replaced after 30 s.
- **A refused login waits at least 60 s** before the next attempt, so a login
  that is in use elsewhere is not hammered. A dropped link retries on a
  2 → 60 s ladder, reset once a connection has held for a minute.

What the admin asked for (connected or not, and for which event) lives in the
same row, not in memory, so a redeploy mid-race resumes on its own.

Live data lives in the memory of the process holding the lease. That is fine
on a single Railway replica; with more than one, requests landing on the other
replica would see `STANDBY` and no data.

## Control

| Endpoint | Who | |
|---|---|---|
| `GET /api/live/status` | member | State, bound event, what the feed says is running, message counters, last error. Poll it. |
| `POST /api/live/connect` `{ "eventId": n }` | admin | Ask for the connection and bind it to the Pit Pass event it is scored against. |
| `POST /api/live/disconnect` | admin | Close the socket and free the login. |
| `GET /api/live/state?path=timing.session.info` | admin | The merged feed at a dotted path (blank = everything). The licensed feed verbatim, hence admin-only. |

States: `NOT_CONFIGURED` (no host), `OFF`, `CONNECTING`, `LIVE`, `BACKING_OFF`
(link lost or login refused — see `lastError` / `nextAttemptAt`; the last-known
data is kept), `STANDBY` (another process holds the lease). `OFF` and
`STANDBY` hold no data.

The feed's own session (`status.session`) is shown next to the bound event so
a mismatch — connected during the wrong series' session — is visible. The feed
is never trusted to pick the event.

## Configuration

All `ALKAMELV2_*`. Set the first three on Railway; the rest have working defaults.

| Variable | Default | |
|---|---|---|
| `ALKAMELV2_HOST` | — | Timing server host. **Blank = feature off, no background thread.** |
| `ALKAMELV2_USERNAME` | — | |
| `ALKAMELV2_PASSWORD` | — | Never logged, never recorded (only inbound lines are recorded). |
| `ALKAMELV2_PORT` | `11001` | |
| `ALKAMELV2_TLS_ENABLED` | `true` | The feed is TLS. Off only for the local replay server. |
| `ALKAMELV2_TLS_VERIFY_CERTIFICATE` | `false` | The spec says to ignore certificate errors. The trust-all context is scoped to this one socket. Set `true` if the server's certificate proves valid. |
| `ALKAMELV2_CLIENT_APP_NAME` | `Pit Pass` | Sent in LOGIN. |
| `ALKAMELV2_CHANNELS` | info, entry, classes, status, standings.byClass.active, startingGrid (all under `timing.session.`) | Comma-separated. Deliberately excludes `timing.analysis` — every lap of every car, the part that reaches ~100 MB over 24 hours. |
| `ALKAMELV2_MAX_LINE_BYTES` | `33554432` | A longer line is a protocol fault, not buffered. |
| `ALKAMELV2_CONNECT_TIMEOUT_SECONDS` | `10` | |
| `ALKAMELV2_RECORDING_ENABLED` | `true` | |
| `ALKAMELV2_RECORDING_BUCKET` | — | A **private** R2 bucket on the same account/keys as images. Never the public images bucket (the code refuses it). Blank = segments stay on local disk, which on Railway does not survive a redeploy. |
| `ALKAMELV2_RECORDING_DIRECTORY` | system temp `/pit-pass-aks` | Where segments are written before upload. |
| `ALKAMELV2_RECORDING_SEGMENT_MINUTES` | `10` | |
| `ALKAMELV2_RECORDING_MAX_LOCAL_MEGABYTES` | `512` | Oldest local segments pruned past this. |
| `ALKAMELV2_REPLAY_FILE` | — | Local dev: replay this recording instead of connecting. |
| `ALKAMELV2_REPLAY_SPEED` | `1.0` | `10` = ten times faster; `0` = no pauses. |

## Railway settings

Beyond the `ALKAMELV2_*` variables, on the backend service:

| Setting | Value | Why |
|---|---|---|
| `RAILWAY_DEPLOYMENT_DRAINING_SECONDS` | `30` | **Railway's default is 0: SIGKILL straight after SIGTERM.** With 0 a redeploy never runs the clean shutdown — the lease is left to lapse (a ~30 s gap in the feed) and the recording segment in progress is lost with the container's disk. With 30 the old process closes the socket, releases the lease so the new one dials within seconds, and uploads the last segment. |
| `RAILWAY_DEPLOYMENT_OVERLAP_SECONDS` | leave at `0` | The lease already makes the overlap safe; a longer overlap only delays the handover. |
| Replicas | `1` | Live data is in the memory of the process holding the lease. |
| `R2_IMAGES_ENABLED` | `true` (already, in production) | Recordings upload through the same R2 client; with it off they stay on local disk. |

Nothing else: outbound TCP to port 11001 needs no Railway configuration. If
Al Kamel turns out to allow-list client addresses, the service would need
Railway's static outbound IP — `lastError` would show a connect timeout or
refusal rather than a login error.

The container runs with `-Xmx256m` (Dockerfile). The default channels need a
few MB. Joining `timing.analysis` for a future timing page does **not** fit in
that heap over a long race; revisit the cap when that work starts.

## Recordings

Al Kamel offers no test or replay server, so every session production connects
to is recorded: gzip segments named `aks-v2/<date>/<connection start>-<n>.aks.gz`,
one line per inbound message as `<receive epoch ms> TAB <raw line>`. With the
narrow default channels a race is a few MB.

## Developing without the feed

Point a local backend at a recording (`.aks` or `.aks.gz`):

```yaml
# backend/src/main/resources/application-local.yml (gitignored)
pit-pass:
  alkamel-v2:
    replay:
      file: /path/to/recording.aks.gz
      speed: 10
```

The backend starts a stand-in AKS server on a loopback port and the real client
code connects to it — login, joins, pings, diffs — then `POST /api/live/connect`
as usual. Each connect replays from the start. Pauses longer than 10 s are
shortened. The same stand-in drives `LiveTimingServiceTest`.

## First real connection — checklist

1. Create the private R2 bucket; set `ALKAMELV2_RECORDING_BUCKET`.
2. Set `ALKAMELV2_HOST`, `ALKAMELV2_USERNAME`, `ALKAMELV2_PASSWORD` on Railway.
3. During a **practice** session, connect from the app and watch
   `/api/live/status`: `LIVE`, `session` naming the right session, `messages`
   climbing, `lastWarning` empty (a refused JOIN shows up there).
4. Check `GET /api/live/state?path=timing.session.standings.byClass.active`.
5. Deliberately test the second login once (another client, same credentials)
   and write down what happens here.
6. Disconnect; confirm segments arrived in the bucket. That recording is the
   fixture everything after this is built against.

## Verification

`cd backend && ./gradlew test --tests 'com.pitpass.live.*' --tests com.pitpass.auth.SecuredChainTest`
