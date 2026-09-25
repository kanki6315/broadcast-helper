# IMSA Esports correction (artifactracing.com)

The IMSA Esports Global Championship races as iRacing **hosted sessions**, and
the stewards apply penalties after the race. iRacing's result file therefore
isn't the official classification, and hosted sessions carry no championship.
Both are published by the series' own site, artifactracing.com, whose public
JSON API this import reads.

## Workflow

1. Import each round from iRacing as usual (single subsession). This brings the
   qualifying session, grid and lap data, which the site doesn't publish.
2. Run the correction for the season. It **never creates** events, sessions or
   results. It corrects what iRacing supplied:
   - race results are overwritten with the site's classification;
   - each paired entry takes the site's car number and registered team name;
   - team standings are replaced, one championship per class.

Re-run it whenever the site changes (appeals, late penalties). A re-run with
nothing new to apply is a no-op. **Re-importing a round from iRacing undoes
that round's correction**, so run the correction again afterwards.

## In the app

Manage → Imports → **IMSA Esports official results**. The review:
- picks a site season and the Pit Pass season (guessed from the year);
- opens the rounds with something to decide, and every decision re-plans;
- enables Apply only once the server says nothing blocks.

## API

All admin-only, including the GET, because every call reads the external site.

| Call | Purpose |
|---|---|
| `GET /api/imports/artifact/leagues` | The site's seasons, newest first. |
| `POST /api/imports/artifact/plan` | Read-only. Returns every change and every decision still needed. |
| `POST /api/imports/artifact/apply` | Re-plans with the decisions and writes it all in one transaction. Returns 422 while anything blocks. |

Plan and apply take the same body, `{leagueId, seasonId}` plus these optional
decisions:

| Field | Meaning |
|---|---|
| `eventOverrides` | Site event id → Pit Pass event id. |
| `pairings` | Site result id → entry id. A `null` value leaves that site row out. |
| `consolidations` | `[{driverId, name}]`: the Pit Pass driver adopts the site's spelling. |
| `dropEntryIds` | Entries the site doesn't classify, to delete from their event. |
| `classMapping` | Site class → Pit Pass class. |
| `importStandings` | `false` to correct results only. |
| `keepTeamIds` | Team merges to skip, by the old team's id (`teamFolds[].fromTeamId`). |

## Matching

**Rounds → events.** A round matches, in order:
- the event stamped `event.source_ref = 'artifact:<id>'` by an earlier run;
- otherwise the season event within 3 days of the race that shares the most
  drivers with it.

Each corrected event records the site's round number in `event.source_round`
(V56). Rounds are numbered by date, so this breaks same-day ties: in 2025 GTP
raced Long Beach while GTD raced VIR the same evening, and without the
tiebreak their standings columns could swap.

**Cars → entries.** Cars are matched by **drivers, not car numbers**, because
a team that registered the wrong iRacing number races under it.

| Match | When | Needs confirming? |
|---|---|---|
| Exact | Case- and spacing-insensitive name, including a driver's retired spellings (`driver_alias`). The site's names are the iRacing display names, so this pairs nearly every car (211 of 212 in 2025). | No |
| Near-miss | Same name once accents and digits are dropped (`Munoz2`/`Muñoz`), or within two edits for names of 8+ letters. | Yes. The review can also consolidate the driver to the site's spelling: it merges into an existing driver of that name, or renames and keeps the old spelling as an alias. |
| Car number only | No drivers match, but the number does. | Only when both sides list drivers. The site lists no crew for a car that never turned a lap. |
| Ambiguous / none | Several entries share the car's drivers, or nothing matches at all. | Must be paired by hand or left out. |

An entry nothing pairs with blocks the correction if it has a race result, or
if it holds a number a corrected car is taking. It must be dropped, like the
2025 "Team iRacing DT2" car.

## What gets written

- **Result:**
  - Overwritten: positions, status, not-finished flag and cause, laps, gap to
    leader, gap to the car ahead, fastest lap time and lap number.
  - Every non-"Running" end status counts as not finished, with the site's
    status as the cause; this includes a Drive Time Violation.
  - Kept from iRacing: pit stops, lap speed and fastest-lap seat. The
    qualifying session isn't touched.
- **Formats:** gaps are converted to the iRacing importer's shapes (`-`,
  `+64.911`, `1 Lap`). A gap or lap time within 0.002 s of Pit Pass's is kept
  as-is, because the two sources round the same lap differently.
- **Teams:** an iRacing-name team ("Porsche Coanda $91") left with no entries
  anywhere is folded into the registered team with the existing team merge.
  Its spelling becomes an alias, so the next iRacing import resolves to the
  right team. Each merge is a checkbox in the review, on by default.
- **Standings:**
  - One TEAMS championship per class, keyed by car number.
  - Each round is stored as two sessions, qualifying points and race points,
    which the recap sums into one column.
  - Every round up to the last scored one is listed for every class, so a
    class that sat a round out reads `did_not_race` there and keeps later
    rounds aligned.
  - A car whose rounds don't add up to its total gets the difference as a
    season adjustment (V28), reported in the plan.

## Site quirks

- `/api/leagues` lists only the active season; `?includeInactive=true` lists
  them all.
- `isComplete` stays false on finished rounds. "Has results" means the results
  list is non-empty.
- Numbers arrive as strings, and blanks as `""`.
- `interval` is the gap to the overall leader: `-00.000` for the winner, `-`
  for a car classified with no gap.
