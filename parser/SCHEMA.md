# entries.json contract

The boundary between the Python parser and its consumers: this repo's Java
loader (PLAN.md §4) and imsa-fantasy's C# entry-list import. The parser emits
exactly this shape — [`schemas/entries.schema.json`](schemas/entries.schema.json)
is the machine-readable version, enforced by `test_schema.py` — and the loader
maps it to `event`/`entry`/`lineup`/`driver` and owns dedup + persistence.

## Compatibility policy
Consumers are **tolerant readers**: each binds only the fields it uses and
ignores the rest, so **adding** a field is always safe and requires no consumer
changes. What breaks consumers silently is renaming, retyping, or repurposing
an existing field, changing which keys are emitted vs. omitted, or changing the
semantics of `unparsed` / the `markers` values — treat those as breaking
changes and check both consumers (this repo's loader and imsa-fantasy's subset
DTOs in `EntryListImportEndpoints.cs`) before shipping. `unparsed` is the one
field every consumer must honor: SCHEMA'd loaders fail loud on it rather than
silently importing an unrecognized driver line.

```jsonc
{
  "event": {
    "name": "Sahlen's Six Hours of the Glen",
    "circuit": "Watkins Glen International",   // may be null
    "location": "Watkins Glen, New York",      // may be null
    "series": "IWSC",                          // from --series or filename; may be null
    "start_date": "2026-06-25",                // ISO; may be null
    "end_date": "2026-06-28",                  // ISO; may be null
    "total_entries": 54,                       // from the PDF header; may be null
    "source_file": "01_IWSC Pre-Event Entry List.pdf"
  },
  "entries": [
    {
      "class_name": "GRAND TOURING PROTOTYPE (GTP)",
      "class_code": "GTP",                     // GTP | LMP2 | GTD PRO | GTD | GS | TCR | ...
      "class_order": 1,                        // 1-based section order in the PDF
      "car_number": "04",                      // string — preserves leading zeros
      "team": "Crowdstrike Racing by APR",
      "sponsor": "Mustang Sampling / St Jude", // may be null
      "team_nationality": null,                // 3-letter code (PACCA prints one per team); null for IMSA
      "bronze_cup": false,                     // entry eligible for the Bronze Cup (trophy icon)
      "dealer_trophy": false,                  // PACCA '#': entry scores in the Porsche Dealer Trophy
      "car_type": "ORECA LMP2 07",             // may be null
      "tire": "Michelin",                      // may be null
      "engine": "Gibson",                      // may be null
      "fuel": "E-20C",                         // may be null
      "drivers": [
        {
          "order": 1,                          // 1-based position in the cell
          "rating": "B",                       // P|G|S|B, or null (TBD)
          "name": "George Kurtz",
          "nationality": "USA",                // 3-letter code (pro series), or null
          "hometown": null,                    // "City, State/Country" (challenge series), or null
          "markers": [],                       // "coach" | "rookie" | "invitational" (IMSA icons)
                                               //   | "non_series" (PACCA's '*' legend)
          "is_tbd": false,
          "unparsed": true                     // present (true) only if the line
        }                                      //   didn't match the driver pattern
      ]
    }
  ]
}
```

## Field notes / loader guidance
- **car_number** is a string by design. Never coerce to int.
- **class_code** is derived from the parenthetical in the section header and
  upper-cased. Treat it as the join key to `racing_class.abbrev`.
- **nationality vs hometown** are mutually exclusive by layout: the pro series
  (IWSC) gives a 3-letter `nationality`; the challenge series (IMPC/VPRC/MX5)
  gives a free-text `hometown` ("Austin, TX", "Wellington, New Zealand") instead.
  The unused one is `null`.
- **markers / bronze_cup** come from small raster icons the challenge-series
  lists print next to a name, per the PDF's own legend. The trophy is scoped to
  the entry ("entry is eligible for the Bronze Cup") so it's the entry-level
  `bronze_cup` bool; `coach` / `rookie` / `invitational` stay per-driver in
  `markers`. Pro-series (IWSC) entries carry `bronze_cup:false` and empty
  `markers` — those lists have no icons (ranking is implicit in the P/G/S/B).
- **PACCA (Carrera Cup Asia)** entry lists are a two-up ruled grid, sniffed from
  the table header, one driver per car with the cup class (`Pro` / `Pro-Am` /
  `Am` / `Masters`) printed on the driver line — so `class_code` is that label
  upper-cased and `class_order` is the cup's fixed rank, not PDF section order.
  `dealer_trophy` is the team line's `#` ("Porsche Dealer Trophy" legend), the
  Bronze Cup's analogue. The driver-line `*` ("Non series registered") becomes
  the `non_series` marker — it must NOT be treated as the invitational/guest
  flag: starred drivers still score on the real 2026 points sheets (XIE An).
  `car_type`/`tire`/`engine`/`fuel` are null (a one-make cup doesn't print them).
- **rating** maps to `lineup.rating`; `null` + `is_tbd:true` is a placeholder
  seat (`(?) TBD`). The loader should set `lineup.is_tbd` and leave `driver_id` null.
- **unparsed** drivers should fail loud in the loader (a layout the parser didn't
  recognize), not be silently imported.
- The parser does **not** dedup or assign IDs — that's the loader's job.
