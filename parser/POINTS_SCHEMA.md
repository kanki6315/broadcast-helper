# points.json contract

The boundary between `parse_points.py` and the Java loader, the sibling of
[`entries.json`](SCHEMA.md). The parser emits exactly this shape; the loader maps
each entry to `championship`/`championship_session`/`standings_row`/
`standings_session_points`. Keep changes here in lockstep on both sides.

**Prefer the standings JSON where the series publishes one.** It splits a
session's extras into pole and fastest-lap points; the IMSA PDF cannot (see
`bonus_points`). The PDF path exists for the series that only ever publish a
sheet — 2024 Mustang Challenge and Carrera Cup Asia, for two.

Two sheet layouts are recognized, sniffed page by page: the IMSA rotated-header
geometry and the PACCA ruled grid (see "PACCA layout" below).

One PDF holds **every championship of the series** (3 for a Mustang Challenge
sheet, 11 across 17 pages for a WeatherTech one), so the top level is a list and
the loader stages one import batch per entry. A championship spanning several
pages is merged into one classification.

```jsonc
{
  "source_file": "00_Championship Points - Provisional.pdf",
  "championships": [
    {
      "championship": {
        // The sheet has no short code (the JSON's "IWSC GTP DRIVERS"), so the
        // full title is the identity. NOTE: commitStandings replaces on
        // (season, name), so a PDF-sourced championship will not overwrite a
        // JSON-sourced one for the same class — they key differently.
        "name": "Mustang Challenge DH Drivers",
        "main_title": "Mustang Challenge DH Drivers",  // -> series + class + kind
        "sub_title": "",
        "year": "2024",                                // PDF creation year; --year overrides
        "sessions": [
          // "Extra" is never a session of its own (see bonus_points); every
          // other column label is, named as the sheet prints it. Mustang gives
          // each event two rounds, WeatherTech one Qualifying + one Race.
          {"session_index": 1, "event_name": "Mid-Ohio", "session_name": "Round 1"},
          {"session_index": 2, "event_name": "Mid-Ohio", "session_name": "Round 2"}
        ]
      },
      "classification": [
        {
          "position": 1,
          // Keyed the way the standings JSON keys it: a Teams sheet has a car
          // number column, so key = car number and team = the team's name; a
          // Drivers or Manufacturers sheet has none, so key = the name and team
          // is empty.
          "key": "Robert Noaker",
          "team": "",
          "total_points": 3270,                        // as printed on the sheet
          "points_by_session": [
            {
              "session_index": 1,
              "total_points": 330,                     // race + bonus
              "race_points": 320,
              "bonus_points": 10,                      // see below
              "pole_points": 0,                        // always 0 from a PDF
              "fastest_lap_points": 0,                 // always 0 from a PDF
              "penalty_points": 0,                     // always 0 from a PDF
              "status": ""                             // "" | did_not_race | not_classified
            }
          ]
        }
      ]
    }
  ]
}
```

## Field notes / loader guidance

- **bonus_points** is the sheet's "Extra" column: pole and fastest-lap points
  already added together. On a 2024 Mustang sheet a 20 is both bonuses and a 10
  is one of them — and the sheet never says which, so 31 of that season's 44
  extras are genuinely ambiguous. Rather than guess a split into `pole_points`,
  it is recorded whole. A standings JSON never sets this field; a points PDF
  never sets the split ones. Where a series publishes both, import the JSON.
- **status** maps the sheet's own printed legend, `/ DNP  * DNS`, onto the
  values the standings JSON already uses. Confirmed against a season published in
  both formats: `/` is `did_not_race`, `*` is `not_classified`. A blank cell is a
  round with no data yet — *not* the same as a DNP — and carries `""`. The PACCA
  sheet spells its sentinels out: `-` → `did_not_race`, `DNS` → `not_classified`
  (the IMSA mapping), plus two of its own, `DNF` → `did_not_finish` and `DSQ` →
  `disqualified`. Only `did_not_race` gets special treatment downstream (a blank
  round on the season view); DNF/DSQ rounds correctly count as contested.
- **total_points** on the row is what the sheet prints. The parser guarantees the
  session cells re-add to it and fails rather than emit a row where they don't,
  so the loader can trust it without re-checking.
- **year** comes from the PDF's creation date, because the page text is not
  reliable (a points value can look like a year — a 2024 sheet contains "2035").
  A full-season sheet republished the following January would therefore need
  `--year`. The reviewer confirms the season before commit.
- **key** on a Drivers sheet is a person's name, so it is weaker than the JSON's
  car-number key: two drivers sharing a name would collide.

## PACCA layout (Carrera Cup Asia)

The PACCA sheet is an Excel-exported, fully ruled grid — `find_tables()` reads
it cell-perfectly, so none of the geometry heroics below apply. Its quirks are
its own:

- **One championship per page** (Overall / Pro-Am / Am / Masters / Porsche
  Dealer Trophy), titled on the page's first line. The title leads with the
  season ("2026 Porsche ..."), which beats the PDF creation date as the `year`
  source.
- **Each "Round N" is a session**, and driver pages split it into FLQ / Race /
  FL sub-columns. Unlike the IMSA sheet this one CAN split its bonuses: FLQ
  (fastest lap in qualifying — pole, in a one-make cup) → `pole_points`, FL →
  `fastest_lap_points`, and `bonus_points` stays 0. An FLQ point survives a race
  DSQ — the printed total only re-adds if it's counted.
- **Points come in halves** (a shortened race pays 12.5), so points fields are
  numbers, not necessarily ints, and the checksum compares with a tolerance.
- **Ties share one merged position cell** whose glyph can land on any of its
  rows (or between two rulings and on none). Numbering is dense — 14, 14, 15 —
  so a blank position is the previous row's on equal points, else the next.
- **The team page has no car-number column**, so the team name is both `key`
  and `team`, with the trailing `#` (the entry list's Dealer Trophy marker)
  stripped. Driver pages key on the driver name, like an IMSA Drivers sheet.

## Layout notes (why the IMSA parser is geometry-driven)

Read with `extract_text()` these sheets are quietly wrong, which is the whole
reason the code looks the way it does:

1. **Columns collide.** Points come in pairs (Extra+Round, Qualifying+Race) and
   adjacent cells render flush: Robert Noaker's row prints `350 10320 10320 320`,
   where `10320` is Extra 10 + Round 320. Read naively his row totals 43230
   against a printed 3270. The column headers are rotated 90°, giving an exact x
   anchor per column, so bucketing each *character* by centre x splits the pair.
2. **Names overprint the numbers.** `Acura Meyer Shank Racing w/ C19u0rb0` is a
   team name drawn straight through its own total. Names and points use different
   fonts, which separates them — and keeps the `/` in `w/` from being read as a
   DNP sentinel.
3. **A name can carry a digit.** `Bryan Herta Autosport with PR1/Mathiasen`
   overprints its total in the *same* font with interleaved x. Only the baseline
   separates them: a Teams sheet sets the name a fraction of a point off the
   row's own baseline.
4. **Draw order beats x order.** Reading an overprinted name left-to-right
   splices the numbers into it (`COMPETIT ION`). Each text run is drawn whole, so
   the name is read in draw order.

Every row is verified by re-adding its cells to the printed total. That checksum
caught (1), and it is a hard gate: on a mismatch the parser exits non-zero rather
than emit plausible-looking points.
