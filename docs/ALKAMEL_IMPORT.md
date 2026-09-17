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

- **Grid PDF (2016–2021, crew sheets)** — done 2026-09-17 for the 2021 layout:
  same column layout as the 2021 Carrera Cup sheet the parser was built on, but
  titled **"Race Official Starting Grid"** (no race number), header
  **"Drivers\*"**, crews printed "F. Nasr / M. Conway / P. Derani" with the
  roles marked by emphasis the legend under the title explains — 2021
  WeatherTech "Bold: Starting Driver", 2021 Pilot Challenge "Bold: Starting
  Driver / Underline: Qualifying Driver"; 2017 used bold = qualifying driver,
  italic = starting driver. Bold on these sheets is text render mode 2, not a
  font, so `parse_grid_pdf.py` runs a second pdfminer pass to record each
  glyph's render mode; an underline is a hairline rect under the name; the
  legend is parsed, never assumed. Rows carry `drivers` (initialled names) and
  `starting_driver_seat` / `qualifying_driver_seat`; `mapGridPdfJson` turns
  them into the row's roster and seats, resolved at commit through the stored
  lineup like an initialled results name. Italic (2017) is not yet detected —
  the 2017–2020 sheets still need checking against real files.
- **Points PDF (before 2024)**: 2021's sheets read (see the audit notes
  below). The parser recognises the 2017 headers (9 championships, sessions
  "Fast Lap" + "Round N" per event) but reads **0 rows** — it separates names
  from points by font, and the 2017 sheet uses only Arial and Arial Bold.
  Remaining fix: a layout branch for the older sheet. "0 rows" now fails
  hard rather than staging empty championships. Medium.
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
2. **Source context — DONE 2026-09-16.** `SourceContext` record;
   `ImportService.stage(filename, bytes, format, context)` completes blanks in
   the staged payload (`applyContext`: series name, event name, session start,
   session label + its ordinal when the payload names no session; a points
   PDF takes the folder's year) so `needsSession` is false and the ordinary
   JSON-branch commit runs. V52 adds `import_batch.source_url /
   source_modified / source_event` and `event.source_ref`; the four event-kind
   commits now return their event id so `commit()` stamps the folder key (first
   stamp wins). The review's event guess tries the season's `source_ref` match
   before the circuit-and-date one. `BatchSummary` carries `sourceUrl` /
   `sourceEvent`; `ConfirmImportStep` groups fetched batches by `sourceEvent`
   (uploads still by filename) and the imports table shows a path-shaped
   filename's leaf. `SourceContextImportTest` proves the whole path on the
   real 2021 Mid-Ohio CSVs: stage with context → review self-places → group
   commit creates the dated event with `source_ref` and the split-named
   qualifying session → the race CSV from the same folder guesses that event.
3. **Past season — DONE 2026-09-17.** `AlKamelImportService` (`planYear`,
   `stage`) behind `/api/imports/alkamel/{years,plan,stage}` (admin-only
   matcher in `SecurityConfig`), `AlKamelImportModal` on Manage → Imports.
   The plan is one row per weekend × series with the chosen file per session
   and kind, "already imported" via `source_ref`, a series picker for weekends
   posted without a series folder, and unmatched series folders mapped through
   the existing alias API. Standings: every JSON, plus the best PDF per name
   stem, ticked by default only on the series' final weekend; award sheets
   listed unticked. F1 weekends are detected by shape (no machine-readable
   results, PDF ones present). The browser stages one weekend per POST with a
   progress bar and a stop-after-this-one; failures accumulate; then the
   confirm step. Verified end to end in the browser against a local stub of
   the site (`stub_site.py` in the session scratchpad, launch configs
   `alkamel-stub` + `backend-alkamel-stub`): a two-weekend 2017 planned,
   staged (PDFs the stub lacked reported as 404 failures, CSVs staged),
   grouped one card per weekend, committed, re-plan showed both as imported.
   That run exposed a group-commit rule worth knowing: **the season's known
   classes are frozen at group start** (`classSeeding`, request-scoped) —
   before, the first batch in a group (a one-class qualifying CSV) seeded the
   canon the next batch (the four-class race) was rejected against, though the
   review had promised nothing unknown. Also fixed: bare dates in the confirm
   cards showed a day early west of Greenwich. Not yet verified on the live
   site beyond the root listing; the first real run should be a single
   series of a JSON-era year (2024 Pilot Challenge).
4. **Refresh event — DONE 2026-09-17.** `AlKamelImportService.planEvent`
   behind `GET /api/imports/alkamel/events/{id}/plan[?sourceEvent=]`: reads
   the event's stamped folder **fresh** (every listing bypasses the cache)
   and marks each file NEW / UPDATED / UNCHANGED against the batches already
   committed from that folder — same session folder and kind, then the very
   URL at the very last-modified is unchanged, anything else is updated
   (Provisional → Official, an amendment, a re-post). An event with no stamp
   gets the season's weekends of its series as candidates, nearest its date
   first; picking one plans that folder and the stamp lands when the batches
   commit. The modal grew a "Refresh event" mode (series/event picker, per
   file rows with state chips, unchanged files unticked) and the event page
   an admin-only "Refresh from Al Kamel" button that opens it pre-pinned and
   reads the site at once; the confirm step is pinned to the event. Verified
   in the browser against the stub: a stamped event with an unchanged race
   and a stale qualifying planned as updated / unchanged / new, staged (the
   PDFs the stub lacks reported), committed onto the existing event, and the
   re-plan flipped the qualifying to unchanged.
5. Grid PDF extension; 6. points PDF older layout. Each lifts "needs parser"
   rows in the year plan without touching the workflows.

## Found on the first live run (2026-09-17)

- 2026 weekends post **two points-data folders side by side**, `Points Data -
  Offiical/` (the site's own spelling) and `Points Data - Provisional/`, with
  identically named, status-less championship JSONs inside. The refresh
  ticked both sets. Now the folder's status is inherited by the files inside
  (`SourceFile.of(..., folderRevision)`), the misspelling counts as Official,
  and `planStandings` ticks one copy per sheet — the best by status,
  amendment and date — listing the rest unticked.
- Every file's publication status is shown wherever it is offered: a status
  chip on refresh rows (dashed for provisional / unofficial) and inside the
  season rows' session chips ("R · JSON official · grid provisional"). Some
  sessions only ever publish an Unofficial file (2026 VIR qualifying), and
  the chip says so rather than hiding it.

Found importing 2023 WeatherTech (2026-09-17):

- **The site posts empty files.** 2023 Sebring's `00_Grid_Race_Official.CSV`
  is 0 bytes; the amended Provisional beside it is the grid. The index's Size
  column is now read (`IndexEntry.size` / `empty()`), an empty file is never
  the chosen copy, and downloading one anyway fails with "empty (0 bytes)"
  rather than "unrecognized CSV".
- **Cup points sheets are a different layout.** `00_IMEC Championship
  Points`, `00_IMEC MRRA Points`, `00_IMEC Sebring 12H Points`, `02_TPNAEC
  Points` lay points out per checkpoint (Hour 6 / 12 / 18 / Finish); the
  points parser reads the series sheet's Extra + Round pairs and fails its
  checksum on these, loudly. Only the series' own points sheet
  (`isMainPointsSheet`) is ticked by default; cup sheets are listed with
  "cup sheet — tick to try". Reading them is parser work for later (the JSON
  era already imports the cups from `POINTS DATA`).
- **The Roar is not a round.** Imported as its own event (its qualifying
  sets Daytona's grid) it took round ordinal 1, and the recap — which matches
  championship round N to the event numbered N — shifted every column by
  one. `renumberSeasonRounds` now numbers only events that race or have no
  sessions yet; a qualifying-only or practice-only weekend gets a null
  ordinal, and the commit renumbers again after writing its session so the
  rule sees the event's real shape. The confirm step's round preview applies
  the same rule to the weekends it is about to create. **Backfill** (done on
  the local DB, owed in production): the renumber SQL in
  `ImportService.renumberSeasonRounds`, run over every season.

Found importing 2025 Carrera Cup NA (2026-09-17): its points PDF names the
championship on the standings line ("Masters Drivers - Championship Points
Standings OFFICIAL") under a series-only first line, so the points parser
merged every page into one championship and failed on the Entrants page's
different columns. `parse_points._title` now appends that name; the sheet
yields Pro / Pro-Am / Masters Drivers, Entrants and Rookie Drivers, all
checksummed (`parser/samples/2025_PCCNA_COTA_Points.pdf`). "Rookie Drivers"
will review as class "Rookie", which the series has no class for — it is a
cup, so the reviewer flips is_cup on that one batch. Its "Entrants" sheet
used to review as a kind of its own and be set aside: "Entrants" (Mustang
Challenge too), "Crews", "Team", "Makes" are now read as the closed kinds
(`ImportService.canonicalKind` → TEAMS / DRIVERS / MANUFACTURERS), and a
brand-new championship group takes the sheet's word as its wording
(`sheetKindWording`) when no earlier season set one — so the season pages
say "Entrants" without anyone typing it.

Found importing 2022 WeatherTech (2026-09-17):

- **The Roar races.** In 2021–22 the Roar ran a qualifying race (a `Race`
  session folder) that set Daytona's grid, so the "an event that races is a
  round" rule counted it and the recap shifted every column by one again.
  `renumberSeasonRounds` now excludes events named like the Roar
  (`~* '\yroar\y'`) outright, and the planner marks Roar / test / prologue
  weekends `preseason` — badge "pre-season", unticked by default, still
  importable as their own event. An unnumbered event stays visible: the
  season calendar orders by date so it sits before Daytona with a
  "pre-season" badge on the schedule, and the Results page lists it with a
  "Pre-season" chip in place of "Rd N". The reference table and lineups
  matrix key their columns by round number and so leave it out; the recap
  cannot show it since it scores nothing. **Backfill** in production: the
  renumber SQL again.
- **Classes with no `class_style` row sort last.** WeatherTech had rows for
  GTP / LMP2 / GTDPRO / GTD only, so the 2022 DPi and LMP3 tables fell to the
  bottom. Added locally (owed in production, via Series → Class colours or
  SQL): DPi at GTP's ordinal 0 and colour, LMP3 at 2 (`#f57c00`), GTLM at
  GTDPRO's 3 and colour; GTDPRO/GTD moved to 3/4. Classes that never share a
  season can share an ordinal.

## Carrera Cup North America 2021–2025, imported and audited (2026-09-17)

Driven through the API with `drive.py` / `refresh.py` / `audit.py` (session
scratchpad, same calls the modal makes). Every weekend on the site is in the
local DB with qualifying, both races, grids and results; 2022–2025 carry
their four championships. What the run fixed on the way:

- **A grid row with no class** (2024 Miami #74, Montréal #65 — the F1 grid
  sheet prints neither class nor time for a car that skipped qualifying)
  failed the entry INSERT's NOT NULL. `upsertEntry` now takes the class the
  event already has, else the car's class earlier in the season, and a
  class-less row never overwrites a known class; only a car nothing
  classifies is refused, by name. (Postgres checks NOT NULL on the proposed
  row before ON CONFLICT, so the value has to be supplied on the INSERT.)
- **Loose weekends in single-series mode** (2023 COTA, 2024 COTA posted
  without a series folder) are now that series', so an imported one is
  recognised instead of duplicated.
- **Standings JSON titled with the series alone** (Carrera Cup's `POINTS
  DATA`: main_title "Porsche Carrera Cup North America", subtitle "Pro
  Drivers - Championship Points Standings") review their class and kind from
  the subtitle.
- **Series aliases owed**: "Porsche Deluxe Carrera Cup North America" (2022
  Sebring test, 2023), "Porsche Carrera Cup North America Presented by The
  Cayman Islands" (2021–22). Without them a single-series plan silently
  shows one weekend — the planner reports unmatched folders only in
  all-series mode.
- **Class aliases owed** for the F1-weekend sheets and the upper-case CSVs:
  P → Pro, PA → Pro-Am, A → Am, M / MAS → Masters, PRO → Pro, PRO-AM →
  Pro-Am. Before them 2023–25 held both spellings (the review can't flag a
  variant on a brand-new season, since the season's known classes are empty
  when the whole season commits at once). Entries were normalised locally;
  `class_style` rows Pro / Pro-Am / Am / Masters / PA991 added.
- **The 2022 Toronto grid PDF** heads its column "Drivers"; the grid parser
  now accepts that (and 2017's "Drivers*"). Sample + test added.
- **Initialled drivers on F1 sheets with no full-named result anywhere**
  (2025 Miami "A. R. FERNANDES", "JP. VEGA") now resolve through the season's
  standings rows, whose keys are full names. Re-refreshing Miami re-linked
  four drivers; the initial-only records were deleted.
- **2024 "PCCNA Pro" JSON** labels one Road Atlanta session "WeatherTech
  Raceway", a ninth "round" that would shift the recap's last column;
  patched in `championship_session` locally. Source slip.

Second-pass audit (identity, positions, standings-vs-results): every race
winner 2022–2025 holds that round's top race points in its class's Drivers
championship (a guest, Laurin Heinrich at 2022 Petit R2, scores nothing and
has no row; his entry is not flagged guest). Classified positions run 1..N in
every race. The 2024 standings had been the Road Atlanta snapshot (COTA was
a loose folder when they were ticked) — refreshed from COTA's `POINTS DATA`.
Observations for the user: the "Pro Drivers" table is the whole field scored
outright (Pro-Am and Masters drivers at the bottom), i.e. the outright
championship under the class name "Pro"; 2025 "Rookie Drivers" is a cup with
no entries of that class; team names drift across seasons (Kelly-Moss /
Kellymoss / Kelly Moss, MRS GT Racing / MRS GT-Racing, Alegra Motorsports
LLC / , LLC) and are separate `team` rows; CSV-era and F1-weekend events
have no circuit name (the folder name usually is one).

Known, not fixed (the audit's remaining findings are the sources' own):

- **2021 standings** (fixed 2026-09-17): the 2021 points PDFs read now. The
  parser reads each row and each event name as the text runs the sheet was
  drawn with instead of by x thresholds and baseline drift, which is what had
  merged adjacent event columns ("Watkins Glen Road America"), dropped the
  first letter of every Carrera Cup name and read no rows at all off the
  Pilot Challenge, Prototype Challenge and Super Trofeo sheets (their totals
  sit 0.6pt below the baseline). Events may now own different numbers of
  columns (Carrera Cup's three-race finale), a "Pole" column lands in
  `pole_points`, and the template's unlabelled zero columns on the Pilot and
  Prototype sheets are tolerated. All six 2021 season-final sheets
  (WeatherTech, Pilot Challenge, Prototype Challenge, Carrera Cup, MX-5 Cup,
  Super Trofeo) parse with every row re-adding to its printed total. A page
  with columns but no rows now fails the import instead of staging an empty
  table. Super Trofeo titles name no series ("PRO Driver Championship"), so a
  fetched sheet takes the folder's series as a title prefix, and a trailing
  "Championship"/"Cup" no longer masquerades as the kind.
- **2025 Montréal's Race 1 folder is `202506141895_Race 1`** — a stamp with
  minute 95. The session-folder parser dropped it as not-a-stamp, so the
  session vanished from the plan without a word. A mistyped time now keeps
  the day (start at 00:00) and only a mistyped date disqualifies a folder;
  Race 1 imported on refresh. Its session start sorts before qualifying —
  the sheet itself carries no time to correct it from.
- **Team names that begin with digits** ("311RS Motorsport", "762
  Motorsports") were read as car number + name on the Entrants sheets: the
  points parser assumed a Teams sheet numbers its cars. A number is now only
  a car number when it is its own word and every row on the page has one;
  otherwise the digits stay in the name. Both seasons' Entrants re-imported
  and match their entries.
- 2025 "Rookie Drivers" was committed as class "Rookie" by the earlier UI
  run; it is a cup and should be flipped in Manage.
- 2024 Jerez (a non-PCCNA weekend posted under the season with no series
  folder) is skipped by name.

## Full 2021–2026 import, four series, validated locally (2026-09-17)

Driven through the API the way the modal does (a scratchpad script: plan per
series, stage every recommended file per weekend, review, one commit-group per
season; then a pre-season pass for the Roar weekends and standings-only /
grid-only re-stage passes), into a throwaway database `pit_pass_full` seeded
with the dev database's series, alias and class rows. WeatherTech, Pilot
Challenge, Mustang Challenge and Carrera Cup North America, every weekend
with results, grids, entry lists where the site has them, and the season-final
standings. Every commit group landed; nothing was left for the review table.

Found and fixed on the way:

- **Loose folders stole the final-standings mark.** Planning one series
  attributes every folder posted without a series folder to it, and such a
  folder carries its own points sheet — "PCCNA COTA" 2023 and "Jerez" 2024
  outranked Road Atlanta by date for every series, so 2023–24 imported no
  season standings (and Carrera Cup 2023 a mid-season snapshot). A loose
  weekend now takes the mark only when the series has no proper one.
- **Series aliases** for the era folder names: "IMSA WeatherTech SportsCar
  Championships" (2021, plural), "IMSA Michelin Pilot SportsCar Challenge"
  (2021–22), "_Porsche Carrera Cup North America" (2021), "Mustang Challenge
  North America" (2026). **Class aliases** for Pilot Challenge: the points
  sheets say "Grand Sport" / "Touring Car" where the results say GS / TCR.
- **Cup sheets in the JSON era**: the `POINTS DATA` folder holds the Michelin
  Endurance Cup tables (one "OVERALL" and one per-race checkpoint file per
  class and kind) beside the season tables, all recommended since the
  cup-sheet rule covered PDFs only. The reviewer's call, made in the script:
  the OVERALL ones commit as cups (family "Michelin Endurance Cup"), the
  checkpoint ones are left out; "GS BRONZE" is a Bronze Cup on GS; "Rookie"
  a cup with no class; 2021's "Pro & Pro-Am Drivers" the outright table (no
  class). The modal still needs the reviewer to do this by hand.
- **Points parser**: a Teams page with no "Points" header (2023 Pilot
  Challenge) let the template's unlabelled zero column pose as the total; a
  team name set in the points face ran into the cells (2022); an all-Arial
  "Revised" sheet (2024 Mustang) has an empty overflow page. All read now.
- **Grid parser**: the 2021 crew sheets (see above); Laguna Seca 2021 prints
  "Nr.Drivers*" as one word.
- **Driver identity**: "A. R. FERNANDES" off an F1 sheet resolves to
  "Andre Renha / Fernandes" while the timing JSON says "Andre / Renha
  Fernandes"; a driver whose whole name matches is now the same driver.
- **The site posts 2025 Mustang Challenge COTA twice** ("17_Circuit of the
  Americas" and "19_Circuit of the Americas (MC)", identical sessions and
  results). The plan now names a later folder whose scoring sessions all
  repeat an earlier folder's for the same series (`YearPlan.duplicates`) and
  leaves it unticked; importing both would give the season a seventh weekend
  and shift the rounds.
- **An event's round flag is editable on its page** ("counts as a round",
  admin only, `PUT /api/events/{id}/round`), renumbering the season — the
  override for whatever the planner's pre-season verdict or an upload's
  default decided.

Known, by design or by source:

- **The 2021–2023 WeatherTech season-final sheets print whole classes as
  DNP at rounds they raced** — LMP2 and LMP3 at Daytona each year, GTD at
  Detroit and the Watkins Glen 240 in 2021, GTD at Long Beach and CTMP in
  2022 — and their printed totals exclude those rounds, so the import is
  faithful to the sheet (every row re-adds to its total). The results for
  those races are imported in full; only the standings' per-round columns
  are the sheet's. Read as IMSA's class-specific full-season round sets
  (the sprint-cup years); worth confirming before relying on per-round
  standings for those classes.
- 2023 GTP Daytona: the race winner (#60) is not the top scorer (#10) — the
  Meyer Shank points penalty, real.
- 2026 standings list the whole calendar, so they carry more rounds than the
  events imported so far.
- Grid attribution stays null where an initialled crew name does not resolve
  uniquely through the stored lineup; never guessed.

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
