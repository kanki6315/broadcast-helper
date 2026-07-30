"""Parse an IMSA starting-grid PDF into JSON.

The grid PDF is a single ruled-less table: a title line ("Race 2 Official
Starting Grid", sometimes suffixed "Revised"), one upright header row
(Pos Class Nr. Driver Team Car Time), the grid rows, and a signature footer.
pdfplumber's find_tables() sees no vertical rules here, so cells are recovered
by geometry instead: each header word's x0 anchors a left-aligned column, and
every data word buckets into the column whose anchor range holds its centre.
Word centres — not midpoints between anchors — because a long team name
("Moorespeed-Wright Motorsports") extends well past the midpoint toward the
Car column, while no word ever starts left of its own column's anchor.

Output shape:

    {"session": "Race 2", "race": 2, "revised": false,
     "rows": [{"position": 1, "class": "Pro", "number": "15",
               "driver": "Seb Priaulx(J)", "team": "...", "car": "...",
               "time": "1:23.456" | null}]}

The time is null when the slot prints none — a whole grid of null times means
qualifying never ran and the grid was set by other means. Car numbers stay
strings (04 != 4). No event or session date exists anywhere in the file; the
importer's reviewer supplies both, the "race" number only pre-fills the form.
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
from pathlib import Path

import pdfplumber

# Grid-sheet fonts ship the same broken descriptors as the other IMSA PDFs;
# the resulting pdfminer warnings would flood stderr, which the Java caller
# surfaces on failure.
logging.getLogger("pdfminer").setLevel(logging.ERROR)

TITLE_RE = re.compile(r"Race\s+(\d+)\s+Official\s+Starting\s+Grid(\s+Revised)?", re.IGNORECASE)

# Header labels in print order. "Nr." carries its dot; match both spellings.
HEADER_LABELS = ["Pos", "Class", "Nr.", "Driver", "Team", "Car", "Time"]

# Rows cluster on top with a tolerance: a class cell can sit a fraction of a
# point off its own row's baseline (same jitter the points sheets show).
ROW_TOLERANCE = 2.0

# A word may start slightly left of its column anchor when the column is
# right-aligned (Pos, Nr., Time); the anchor boundary shifts left by this.
ANCHOR_PAD = 2.0

# Everything below the first of these is signature footer, not grid.
FOOTER_RE = re.compile(r"Published at:|Race Director:|Timekeeper:|Page \d+\s*/\s*\d+")


def _word_rows(page):
    """Words grouped into visual rows by top, as [(top, [word, ...])]."""
    rows = []
    for w in sorted(page.extract_words(), key=lambda w: w["top"]):
        if rows and w["top"] - rows[-1][0] <= ROW_TOLERANCE:
            rows[-1][1].append(w)
        else:
            rows.append((w["top"], [w]))
    return [(top, sorted(ws, key=lambda w: w["x0"])) for top, ws in rows]


def _header_anchors(rows):
    """The header row's (top, [x0 per column]).

    Found as the topmost row containing every header label in left-to-right
    order — "Team" alone also appears inside team names ("Team Hardpoint"),
    so a full-set match is what tells the header apart from data.
    """
    for top, words in rows:
        texts = [w["text"] for w in words]
        if texts == HEADER_LABELS:
            return top, [w["x0"] for w in words]
    raise ValueError("no grid header row (Pos Class Nr. Driver Team Car Time) found")


def _cells(words, anchors):
    """One row's text per column, bucketing each word by its centre x."""
    bounds = [x - ANCHOR_PAD for x in anchors[1:]]
    cells = [""] * len(anchors)
    for w in words:
        centre = (w["x0"] + w["x1"]) / 2
        idx = 0
        while idx < len(bounds) and centre >= bounds[idx]:
            idx += 1
        cells[idx] = f"{cells[idx]} {w['text']}".strip()
    return cells


def parse(pdf_path: Path) -> dict:
    with pdfplumber.open(pdf_path) as pdf:
        title = None
        grid_rows = []
        for page in pdf.pages:
            rows = _word_rows(page)
            if title is None:
                for _, words in rows[:3]:
                    m = TITLE_RE.search(" ".join(w["text"] for w in words))
                    if m:
                        title = m
                        break
            if title is None:
                raise ValueError("not a starting-grid PDF: no 'Race N Official Starting Grid' title")
            header_top, anchors = _header_anchors(rows)
            for top, words in rows:
                if top <= header_top:
                    continue
                line = " ".join(w["text"] for w in words)
                if FOOTER_RE.search(line):
                    break
                cells = _cells(words, anchors)
                if not cells[0].isdigit():
                    continue  # wrapped fragment or stray footer text
                grid_rows.append({
                    "position": int(cells[0]),
                    "class": cells[1] or None,
                    "number": cells[2],
                    "driver": cells[3] or None,
                    "team": cells[4] or None,
                    "car": cells[5] or None,
                    "time": cells[6] or None,
                })

    if not grid_rows:
        raise ValueError("no grid rows found under the header")
    race = int(title.group(1))
    return {
        "session": f"Race {race}",
        "race": race,
        "revised": title.group(2) is not None,
        "rows": grid_rows,
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Parse an IMSA starting-grid PDF to JSON.")
    ap.add_argument("pdf", type=Path, help="path to the starting-grid PDF")
    ap.add_argument("-o", "--output", type=Path, help="output JSON (default: stdout)")
    ap.add_argument("--indent", type=int, default=2, help="JSON indent (default 2)")
    args = ap.parse_args(argv)

    if not args.pdf.exists():
        print(f"error: no such file: {args.pdf}", file=sys.stderr)
        return 2

    try:
        doc = parse(args.pdf)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    payload = json.dumps(doc, ensure_ascii=False, indent=args.indent)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload)

    untimed = sum(1 for r in doc["rows"] if r["time"] is None)
    msg = f"parsed {doc['session']} grid: {len(doc['rows'])} cars"
    if doc["revised"]:
        msg += " (revised)"
    if untimed:
        msg += f"; {untimed} without times"
    print(msg, file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
