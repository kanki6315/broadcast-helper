# Entry-list PDF parser

Isolated Python sidecar that turns an IMSA entry-list PDF into the
[`entries.json`](SCHEMA.md) contract consumed by the Java loader. See PLAN.md §4
for why this one task lives in Python (pdfplumber wins on the multi-line driver
cells and italic team/sponsor split) while the rest of the system is Java.

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
```
`--series IWSC` overrides series detection; `--strict` exits non-zero if the
parsed entry count doesn't match the PDF's "Total Entries" header.

## Test
```bash
pip install pytest
pytest                      # skips if samples/ has no PDF fixture
```

## How it works
1. `find_tables()` — the ruled table groups each car's 2–3 drivers into one row,
   so the variable driver count needs no special handling.
2. The driver cell is split line-by-line via a `(rating) Name NAT` regex.
3. Team vs. sponsor is split on **font**: sponsors are italic. Falls back to
   "first line = team" if font info is unavailable.
4. Class sections come from the `... (CODE) Entries:N` header rows.
