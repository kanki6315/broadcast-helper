# Al Kamel import automation — plan

Status: **proposed 2026-09-16**, not started. Two admin-clicked workflows on the
web app that fetch from `https://imsa.results.alkamelcloud.com/Results/` and
feed the existing staging → review → commit-group pipeline:

1. **Import a past season** — one year, every recognised series or one series.
2. **Refresh an event** — during a weekend, pull the sessions that are new or
   have changed since the last import.

No scheduled polling (user's direction: network admins). The site is an open
Apache index, not an API — every fetch is throttled and listings are cached.

Coverage basis: the 2026-09-16 crawl (352 series-events, 813 sessions,
WeatherTech / Pilot Challenge / Carrera Cup NA / Mustang / VP Racing, 2016–2026).
Report: https://claude.ai/artifact/Pxh4K7bezMt3pdfuuwF1k8. Results import for
97% of sessions with the parsers already built; JSON from 2024, CSV before.

## What already exists and is reused unchanged

- `ImportService.stage(filename, bytes, format)` → staged batches; formats
  `IMSA_JSON` (results / grid / flags / standings / entry list),
  `IMSA_CSV` (results / qualifying / grid), `IMSA_GRID_PDF`, `IMSA_POINTS_PDF`,
  `IMSA_PDF` (entry list), `F1_PDF` (Carrera Cup NA on F1 weekends).
- CSVs carry no metadata; the **Al Kamel file name** already supplies the
  session name and ordinal (`SessionNames.sessionNameFromFilename`), and 2021's
  class-split qualifying is handled (V51).
- Review + `ConfirmImportStep` + `POST /api/imports/commit-group`: one
  transaction per event group, round ordinals renumbered by date, items needing
  per-batch review set aside for the table. Re-import is idempotent (sessions
  keyed `(event, type, ordinal)`, each commit replaces only its own child rows).
- The iRacing fetch path is the template: a client, `stageFrom*` methods, a
  resilient result record (`requested / staged / batches / failures`), a modal
  with modes that hands off to `ConfirmImportStep`.

## Principles

- The Al Kamel layer only **finds files and fetches bytes**. Parsing, review
  and commit stay where they are. A source context rides along so files with no
  metadata of their own (CSV era) can place themselves.
- **Never guess the series.** A series folder resolves through `series_alias`
  (plus name / abbreviation); unmatched folders are shown for a one-time mapping.
- **Prefer the best file** per session and kind: JSON > CSV > PDF;
  Official > Provisional > Unofficial > unmarked; highest "Amended N" /
  "Revised"; then newest last-modified.
- **Resilient**: one file failing (bad header, parser error, 404) is reported
  and the run continues — same as `IRacingImport`.
- **Browser-driven, no job queue**: the browser stages one event at a time and
  shows progress. A whole year is a few hundred requests at ~0.35 s spacing, a
  few minutes; nothing runs unattended, and a synchronous request never has to
  hold for that long.

## Backend

### `AlKamelClient`
`RestClient`, base URL + delay + listing-cache TTL from `application.yml`
(`pit-pass.alkamel.*`, env-overridable, defaults to the live site; tests point it
at a `com.sun.net.httpserver` stub like `IRacingClientHttpTest`). Fixed delay
before every uncached request, a project User-Agent, in-memory listing cache
(TTL ~10 min; the refresh flow bypasses it for the event folder). Downloads have
a size cap; a 404 or timeout becomes a failure record, never an exception that
sinks the run. Apache index rows are parsed with a regex on
`<a href="…">…</a></td><td align="right">YYYY-MM-DD HH:MM` — no HTML
dependency needed.

### `AlKamelCatalog` (pure functions, ported from the crawl's `coverage.py`)
- Path grammar: `YY_YYYY/NN_Event/NN_Series/YYYYMMDDHHMM_Session/file`.
  Event folder numbers are **not** rounds (tests, Roar, one-series weekends).
- Series folder → series: strip `NN_`, match `series.name` / `abbreviation` /
  `series_alias` case-insensitively. Era names to seed as aliases: "TUDOR United
  SportsCar Championship" (2016 is WeatherTech-branded already; verify), "IMSA
  Continental Tire SportsCar Challenge" (≤2018), "IMSA Michelin Pilot Challenge",
  "Pilot SportsCar Challenge" (2020–22), "IMSA Prototype Challenge" (VPRC's
  earlier name), "Porsche Carrera Cup North America", "Mustang Challenge".
- Session folder → start (the stamp, track-local wall time), label, type:
  `qualif*` → QUALIFYING; `race` / `heat` / `hour` → RACE; practice, warm-up
  and test folders skipped unless "include practice" is on.
- File classification: results (`NN_Results…` / `Classification…`, excluding
  "by Class", "2nd Fastest", "by Division/Group"), grid ("grid", not "by
  number"; the JSON is named "Starting Grid SbS"), flags ("FlagsAnalysis"),
  standings ("points" anywhere — 2017 spells it "Champonship Points"; found at
  series level, in `00_Points/`, or `POINTS DATA - Official/` from 2024),
  entry list ("entry list" PDF at series level).
- Descent: `00_Starting Grids/` (2024+); endurance results in the **last**
  `NN_Hour N/` subfolder when the session root has none; `CSV and Replay/`
  zips ignored.
- Format decision per file: `.json` → `IMSA_JSON`; `.csv` → `IMSA_CSV`; grid
  PDF → `IMSA_GRID_PDF`; points PDF → `IMSA_POINTS_PDF`; entry-list PDF →
  `IMSA_PDF`; a Carrera Cup NA session whose results exist **only** as PDF →
  `F1_PDF` for its results, qualifying and grid sheets. Flags PDFs have no
  parser → "needs parser", never staged.
- Misfiled weekends (session folders directly under the event, three PCCNA
  weekends 2023–24) → series unresolved; the plan UI asks.

### Source context on staged batches (migration V52 — check the head first)
`import_batch` gains `source_url`, `source_modified`, `source_event` (the
`YY_YYYY/NN_Event` key). `event` gains `source_ref` (same key), set when a
commit creates or attaches an event from a context-bearing batch (COALESCE —
never overwrites a value already there).

`ImportService.stage(filename, bytes, format, SourceContext)`: for payloads
that lack it, the context fills `championshipName` (series name), `eventName`
(folder name minus `NN_`), and `sessionStart` (folder stamp) — the same spot
`withFileSession` already fills the session name. That flips `needsSession`
off for CSV / grid-PDF / F1-PDF batches, so the existing guess and group-commit
paths carry them without a reviewer picking anything. Points PDFs take the
season year from the context instead of the PDF creation date. Circuit stays
null for CSV-era events (the folder name is not reliably a circuit), so
`findMatchingEvent`'s circuit match can't place them — `event.source_ref` is
the key that does, for re-runs and refreshes alike.

Batch `filename` is stored as the path under the year folder so it stays unique
across events (`sessionNameFromFilename` already strips directories).

### `AlKamelImportService` + `/api/imports/alkamel`
- `GET /years` — year folders on the site.
- `GET /plan?year=&seriesId=` — `YearPlan`: per event × series: folder, name,
  resolved series (or null), `existingEventId` via `source_ref`, sessions with
  the chosen file per kind (`url`, `format`, `status`, `modified`, importable
  or a reason), the standings file, the entry list; plus
  `unmatchedSeriesFolders`. Listings only — no downloads.
- `GET /events/{eventId}/plan` — one event folder (via `source_ref`, or
  candidates from the year listing by name similarity + date within 14 days
  when unset), listing fetched fresh. Each file is `NEW` (no committed batch
  for that session + kind), `UPDATED` (best file's URL differs from the last
  committed one, or its last-modified is newer), or `UNCHANGED`.
- `POST /stage` — body: the chosen file refs for **one** event × series (the
  browser loops). Downloads in a deterministic order — entry list, qualifying,
  grids, races, flags, standings — so lineups exist before grids resolve seats
  and grids before results (the F1 PDF rule). Returns
  `AlKamelImport{requested, staged, batches, failures}`.
- Plan GETs hit an external site: give `/api/imports/alkamel/**` its own
  `live.admin()` matcher in `SecurityConfig`, the way `/api/users/**` has one,
  rather than leaving them viewer-readable under the GET-for-members rule.

Past-season standings: Al Kamel posts a points snapshot after **every** round.
The year plan stages only the **last** event folder's standings per series by
default (commit replaces on `(season, name)` anyway; per-round points are inside
the file). The refresh plan stages the event's own snapshot once the race is in.

## Frontend

### `AlKamelImportModal` (sibling of `IRacingImportModal`)
Opened from Manage → Imports ("Fetch from Al Kamel", beside "Import from
iRacing") and from the event page ("Refresh from Al Kamel", admin only, pinned
to that event).

**Past season** mode: year select, series select (all recognised / one) →
"Build plan" → a table of event × series rows with session counts, per-kind
format chips (JSON / CSV / PDF / needs parser), an "already imported" badge,
and unmatched series folders each with a "map to series" control (writes a
`series_alias` through the existing series alias API, then re-plans).
Checkboxes per event (default on: importable and not already imported).
Toggles: standings (final only, default on), entry lists (on), flags (on),
practice (off). "Fetch & stage" loops `POST /stage` per event × series with a
progress line ("Sebring · WeatherTech, 3 of 12") and a Cancel that stops after
the current event; failures accumulate in the iRacing-style list. Then
`ConfirmImportStep` over every staged id — `pinnedSeriesId` only in
single-series mode; in all-series mode each batch carries its series from the
context.

**Refresh event** mode: `SeriesEventPicker` → `GET /events/{id}/plan` → session
rows marked New / Updated / Unchanged (default-checked: New + Updated), plus
entry-list and standings rows → stage → `ConfirmImportStep` pinned to the
event → commit. When `source_ref` is unset the modal offers the candidate
folders and the choice is stamped on commit.

### `ConfirmImportStep`
Group items by `sourceEvent` when present (fallback: filename, as today), so a
weekend is one card with all its sessions. Proposed event name = folder name
minus `NN_`; date = earliest session in the group. Set-aside rules unchanged
(unknown classes, new cars on a rostered event, standings with no kind).

## Parser work — gated by the 2026-09-16 spike

Both were tried on real 2017 Long Beach WeatherTech files (kept in this
session's scratchpad; add them as `backend/src/test/resources/fixtures/imsa-2017/`).

- **Grid PDF (2016–2020)**: same column layout as the 2021 Carrera Cup sheet the
  parser was built on, but titled **"Race Official Starting Grid"** (no race
  number — the parser's title regex rejects it), header **"Drivers\*"**, crews
  printed "R. Taylor / J. Taylor" with **bold = qualifying driver, italic =
  starting driver**. So attribution *is* recoverable from fonts, which the 2021
  layout could not offer. Work: accept the numberless title (ordinal from the
  folder label), split crews on " / ", emit `qualifying_driver` /
  `starting_driver` as initialled names and resolve them through the stored
  lineup (`resolveDriver` already handles "N. LASTOCHKIN"-style names). Small.
- **Points PDF (before 2024)**: the parser recognises the 2017 headers (9
  championships, sessions "Fast Lap" + "Round N" per event) but reads **0
  rows** — it separates names from points by font, and the 2017 sheet uses only
  Arial and Arial Bold. Two fixes: a layout branch for the older sheet, and
  **"0 rows" must fail hard** (today it passes the checksum trivially and would
  stage empty championships). Medium.
- Flags PDF (2022–25, 206 sessions): no parser; out of scope. Those seasons
  import without stewards' notes.

The workflows do not wait for either parser: the plan marks PDF-only files
"needs parser" and stages everything else. Results are JSON/CSV for 97% of
sessions, so a CSV-era season imports complete apart from grids and standings.

## Sequencing

1. **Catalog + client — DONE 2026-09-16.** `imports/alkamel/`:
   `AlKamelCatalog` (pure naming rules), `AlKamelClient` (RestClient, fixed
   pause, listing cache with TTL, size-capped downloads, verbatim encoded
   paths), `AlKamelIndex` (years → events → series → sessions with the grids /
   last-hour / points-folder descents folded in). Config under
   `pit-pass.alkamel.*`. Tests run against 21 recorded listings in
   `fixtures/alkamel/` (2017, 2021, 2023, 2025, 2026 eras) served by a stub
   (`FixtureSite`); `AlKamelLiveTest` makes one real root request only when
   `ALKAMEL_LIVE=1`, proven 2026-09-16. Quirks the fixtures pinned down beyond
   the crawl: 2017 Daytona's hour folders are numbered by label, not prefix
   (`09_Hour 9` then `Hour 10`); its final classification is named "Results
   **by Hour**"; unmarked files (`05_Results.CSV`, `03_Starting Grid.PDF`) are
   that era's final publication, so they rank between Official and
   Provisional; 2021's series folder is "…SportsCar Championship**s**"; 2025
   PCCNA's points folder is `POINTS DATA` with no status suffix; the "Sprint
   Cup" and award PDFs beside the points sheet are left unclassified.
2. **Source context** through stage / review / commit, V52, confirm-step
   grouping by event. Test: a context-bearing 2021 CSV reviews with
   `needsSession=false`, group-commits into a new dated event, stamps
   `source_ref`.
3. **Past season**: plan + stage endpoints, modal, progress loop. Verify
   locally on 2024 Pilot Challenge (JSON era, standings JSON), then 2019
   WeatherTech (CSV era, endurance folders, PDF-only grids marked "needs parser").
4. **Refresh event**: event plan + modal mode + event-page button. Verify on a
   local event by importing a Provisional results file, then re-planning after
   the Official one exists (the same folder, different best file → Updated).
5. Grid PDF extension; 6. points PDF older layout. Each lifts "needs parser"
   rows in the year plan without touching the workflows.

## Production notes

- V52 migration; series aliases for the era folder names (data, via the UI).
- Existing production events have no `source_ref`; the refresh modal's
  candidate picker fills them one at a time as they're refreshed.
- The site has no robots.txt and no API terms; keep the delay, the cache and
  the identifying User-Agent, and never run it on a schedule.

## Decisions taken here (say if any should change)

- Browser-driven sequential staging instead of a background job.
- Series only via explicit aliases; unmatched folders are asked, never guessed.
- Final standings only for a past season; practice sessions off by default;
  the Roar and other pre-season weekends stay in the plan (their qualifying set
  Daytona's grid) but are easy to untick.
- Circuit name left null for CSV-era events rather than copied from the folder.
- Session start for CSV-era sessions = the folder stamp as local wall time, the
  same imprecision the results JSON already carries for its `session_date`.
