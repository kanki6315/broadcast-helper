# PDF parser sidecars

Isolated Python sidecars that turn an IMSA PDF into a JSON contract consumed by
the Java loader. See PLAN.md §4 for why this task lives in Python (pdfplumber
wins on the multi-line driver cells and italic team/sponsor split) while the rest
of the system is Java.

| Script | In | Out |
| --- | --- | --- |
| `parse_entry_list.py` | entry-list PDF | [`entries.json`](SCHEMA.md) |
| `parse_points.py` | championship-points PDF | [`points.json`](POINTS_SCHEMA.md) |

`parse_points.py` exists for the series that publish no points JSON. Where a JSON
exists, import that instead — it splits pole from fastest-lap points, which the
sheet does not.

## Setup
```bash
cd parser
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
```

## Run
```bash
python parse_entry_list.py samples/01_IWSC_Pre-Event_Entry_List.pdf -o /tmp/entries.json
# or stream to stdout:
python parse_entry_list.py path/to/EntryList.pdf

python parse_points.py samples/2024_MC_FullSeason_Points.pdf -o /tmp/points.json
```
`--series IWSC` overrides entry-list series detection; `--strict` exits non-zero
if the parsed entry count doesn't match the PDF's "Total Entries" header.

`parse_points.py` takes `--year` to override the season (it defaults to the PDF's
creation year). It exits non-zero if any row's points don't re-add to the total
printed beside them — see POINTS_SCHEMA.md for why that check earns its keep.

## Test
```bash
pip install pytest
pytest                      # skips if samples/ has no PDF fixture
```

## How it works — `parse_points.py`
The sheet is read by geometry, not text: `extract_text()` renders adjacent points
columns flush together (`10320` is Extra 10 + Round 320, not ten thousand) and
splices overprinting team names through the numbers. The rotated column headers
give exact x anchors, fonts and baselines separate names from points, and every
row is checked against its printed total. POINTS_SCHEMA.md has the details.

## How it works — `parse_entry_list.py`
1. `find_tables()` — the ruled table groups each car's 2–3 drivers into one row,
   so the variable driver count needs no special handling.
2. The driver cell is split line-by-line via a `(rating) Name NAT` regex.
3. Team vs. sponsor is split on **font**: sponsors are italic. Falls back to
   "first line = team" if font info is unavailable.
4. Class sections come from the `... (CODE) Entries:N` header rows.
