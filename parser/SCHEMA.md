# entries.json contract

The boundary between the Python parser and the Java loader (PLAN.md §4). The
parser emits exactly this shape; the loader maps it to `event`/`entry`/`lineup`/
`driver` and owns dedup + persistence. Keep changes here in lockstep on both sides.

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
      "bronze_cup": false,                     // entry eligible for the Bronze Cup (trophy icon)
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
          "markers": [],                       // per-driver icons: "coach" | "rookie" | "invitational"
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
- **rating** maps to `lineup.rating`; `null` + `is_tbd:true` is a placeholder
  seat (`(?) TBD`). The loader should set `lineup.is_tbd` and leave `driver_id` null.
- **unparsed** drivers should fail loud in the loader (a layout the parser didn't
  recognize), not be silently imported.
- The parser does **not** dedup or assign IDs — that's the loader's job.
