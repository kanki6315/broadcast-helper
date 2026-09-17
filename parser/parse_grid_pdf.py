"""Parse an IMSA starting-grid PDF into JSON.

The grid PDF is a single ruled-less table: a title line ("Race 2 Official
Starting Grid", sometimes suffixed "Revised"; IMSA's own series through 2021
print "Race Official Starting Grid" with no number), one upright header row
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

Crew sheets (WeatherTech, Pilot Challenge) print every driver of the car in
one cell, "F. Nasr / M. Conway / P. Derani", and mark roles by emphasis the
legend under the title explains: "* Bold: Starting Driver / Underline:
Qualifying Driver". Bold is not a font of its own on these sheets but text
render mode 2 (fill and stroke), which pdfplumber does not surface, so a
second pdfminer pass records the render mode of each glyph in draw order;
an underline is a hairline rectangle under the name. Such rows also carry

    "drivers": [{"name": "F. Nasr"}, {"name": "M. Conway"}, ...],
    "starting_driver_seat": 1, "qualifying_driver_seat": 2   (1-based; null when unmarked)

and "race" is null when the title names no number.
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

TITLE_RE = re.compile(r"Race(?:\s+(\d+))?\s+Official\s+Starting\s+Grid(\s+Revised)?", re.IGNORECASE)

# "* Bold: Starting Driver / Underline: Qualifying Driver" — which emphasis
# marks which role. Read from the sheet, never assumed: the 2017 sheets used
# bold for the qualifier and italic for the starter.
LEGEND_RE = re.compile(r"(Bold|Underline|Italic)\s*:\s*(Starting|Qualifying)\s+Driver", re.IGNORECASE)
CREW_SEP = re.compile(r"\s*/\s*")

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


def _render_modes(pdf_path, page_index):
    """Text render mode per glyph of one page, in the order pdfplumber lists
    its chars (both walk the same content stream). Mode 2 = fill+stroke, how
    Crystal Reports fakes bold on these sheets."""
    from pdfminer.converter import PDFPageAggregator
    from pdfminer.layout import LTChar
    from pdfminer.pdfinterp import PDFPageInterpreter, PDFResourceManager
    from pdfminer.pdfpage import PDFPage

    modes = []

    class Recorder(PDFPageAggregator):
        def render_string(self, textstate, seq, ncs, graphicstate):
            self._mode = textstate.render
            return super().render_string(textstate, seq, ncs, graphicstate)

        def render_char(self, *args, **kwargs):
            adv = super().render_char(*args, **kwargs)
            modes.append(getattr(self, "_mode", 0))
            return adv

    with open(pdf_path, "rb") as fh:
        rsrc = PDFResourceManager()
        device = Recorder(rsrc)
        interp = PDFPageInterpreter(rsrc, device)
        for i, pg in enumerate(PDFPage.get_pages(fh)):
            if i == page_index:
                interp.process_page(pg)
                break
    return modes


def _crew(page, words, driver_x0, driver_x1, row_top, bold_at, legend):
    """The driver cell's crew as [{name}], plus the seats the emphasis marks.

    Names are split on " / ". A name is bold when most of its letters were
    drawn in render mode 2; underlined when a hairline rectangle sits under
    its x-range within the row. The legend says what each emphasis means.
    """
    chars = sorted((c for c in page.chars
                    if driver_x0 - ANCHOR_PAD <= c["x0"] < driver_x1 - ANCHOR_PAD
                    and abs(c["top"] - row_top) <= ROW_TOLERANCE + 3),
                   key=lambda c: c["x0"])
    text = "".join(c["text"] for c in chars)
    if "/" not in text:
        return None, None, None
    # Walk the names along the chars so each keeps its own glyphs.
    names, i = [], 0
    for part in CREW_SEP.split(text):
        part = part.strip()
        if not part:
            continue
        while i < len(chars) and chars[i]["text"] in " /":
            i += 1
        run = []
        need = part.replace(" ", "")
        got = ""
        while i < len(chars) and len(got) < len(need):
            c = chars[i]
            i += 1
            if c["text"] != " ":
                got += c["text"]
            run.append(c)
        names.append((part, run))
    if [n for n, _ in names] != [p.strip() for p in CREW_SEP.split(text) if p.strip()]:
        return None, None, None
    underlines = [r for r in page.rects if r["height"] < 1.2
                  and row_top - 1 <= r["top"] <= row_top + 12]
    starting = qualifying = None
    for seat, (name, run) in enumerate(names, 1):
        letters = [c for c in run if c["text"].strip()]
        bold = letters and sum(1 for c in letters if bold_at.get(id(c), 0) in (1, 2, 5, 6)) * 2 > len(letters)
        x0, x1 = run[0]["x0"], run[-1]["x1"]
        underlined = any(r["x0"] <= (x0 + x1) / 2 <= r["x1"] for r in underlines)
        for emphasis, present in (("bold", bold), ("underline", underlined)):
            role = legend.get(emphasis)
            if present and role == "starting":
                starting = seat
            elif present and role == "qualifying":
                qualifying = seat
    return [{"name": n} for n, _ in names], starting, qualifying


def _word_rows(page):
    """Words grouped into visual rows by top, as [(top, [word, ...])]."""
    rows = []
    for w in sorted(page.extract_words(), key=lambda w: w["top"]):
        if rows and w["top"] - rows[-1][0] <= ROW_TOLERANCE:
            rows[-1][1].append(w)
        else:
            rows.append((w["top"], [w]))
    return [(top, sorted(ws, key=lambda w: w["x0"])) for top, ws in rows]


GLUED_NR_DRIVER = re.compile(r"^Nr\.Drivers?\*?$")


def _header_anchors(rows, chars=()):
    """The header row's (top, [x0 per column]).

    Found as the topmost row containing every header label in left-to-right
    order — "Team" alone also appears inside team names ("Team Hardpoint"),
    so a full-set match is what tells the header apart from data. On the 2021
    Laguna Seca sheets "Nr." and "Drivers*" print so close that they read as
    one word; the Driver anchor is then the x of its "D" glyph.
    """
    for top, words in rows:
        anchors, texts = [], []
        for w in words:
            if GLUED_NR_DRIVER.match(w["text"]):
                d = next((c for c in chars if c["text"] == "D" and abs(c["top"] - w["top"]) <= ROW_TOLERANCE
                          and w["x0"] <= c["x0"] <= w["x1"]), None)
                if d is None:
                    break
                anchors += [w["x0"], d["x0"]]
                texts += ["Nr.", "Driver"]
                continue
            # "Drivers" (2022 Toronto) and "Drivers*" (2017, footnoted bold/italic
            # attribution) head the same column as "Driver".
            texts.append(re.sub(r"^Drivers\*?$", "Driver", w["text"]))
            anchors.append(w["x0"])
        if texts == HEADER_LABELS:
            return top, anchors
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
        legend = {}
        grid_rows = []
        for pno, page in enumerate(pdf.pages):
            rows = _word_rows(page)
            if title is None:
                for _, words in rows[:4]:
                    line = " ".join(w["text"] for w in words)
                    m = TITLE_RE.search(line)
                    if m and title is None:
                        title = m
                    for emphasis, role in LEGEND_RE.findall(line):
                        legend[emphasis.lower()] = role.lower()
            if title is None:
                raise ValueError("not a starting-grid PDF: no 'Race N Official Starting Grid' title")
            header_top, anchors = _header_anchors(rows, page.chars)
            bold_at = {}
            if legend:
                modes = _render_modes(pdf_path, pno)
                if len(modes) == len(page.chars):
                    bold_at = {id(c): m for c, m in zip(page.chars, modes)}
            for top, words in rows:
                if top <= header_top:
                    continue
                line = " ".join(w["text"] for w in words)
                if FOOTER_RE.search(line):
                    break
                cells = _cells(words, anchors)
                if not cells[0].isdigit():
                    continue  # wrapped fragment or stray footer text
                row = {
                    "position": int(cells[0]),
                    "class": cells[1] or None,
                    "number": cells[2],
                    "driver": cells[3] or None,
                    "team": cells[4] or None,
                    "car": cells[5] or None,
                    "time": cells[6] or None,
                }
                if legend and cells[3] and "/" in cells[3]:
                    crew, starting, qualifying = _crew(page, words, anchors[3], anchors[4], top, bold_at, legend)
                    if crew:
                        row.update({"drivers": crew, "starting_driver_seat": starting,
                                    "qualifying_driver_seat": qualifying})
                grid_rows.append(row)

    if not grid_rows:
        raise ValueError("no grid rows found under the header")
    race = int(title.group(1)) if title.group(1) else None
    return {
        "session": f"Race {race}" if race else "Race",
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
