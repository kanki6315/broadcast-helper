"""Parse a Formula 1 support-race timing PDF into JSON.

Series racing on a Formula 1 weekend (Porsche Carrera Cup North America at
Miami, Montréal and Austin) publish their timing in the F1 paddock's document
style, not IMSA's. Al Kamel posts these as PDF only — the XML beside some of
them is the Unofficial copy — so this sidecar reads the three documents the
importer needs:

* **Race classification** — "Race 1 Official Classification after 16 Laps -
  86.434 km", columns NO DRIVER NAT ENTRANT LAPS TIME GAP INT KM/H FASTEST ON,
  a "NOT CLASSIFIED" block, a FASTEST LAP footer, and (often on page 2) a
  "* PENALTIES" list keyed to the asterisks beside driver names.
* **Qualifying classification** — "Qualifying Session Classification",
  columns NO DRIVER NAT ENTRANT 1ST TIME % TIME OF DAY 2ND TIME % TIME OF DAY
  LAPS. The 2ND TIME cell is prefixed with that lap's rank.
* **Starting grid** — "Race 2 Official [Revised] Starting Grid": a staggered
  two-column grid (odd positions on the right), each slot a car-number line,
  a larger position digit printed just below it, and a team line.

None of the three carries a date, so the importer's reviewer picks the event;
the title's race number only pre-fills the session. Tables have no rules, so
cells come from geometry: header words anchor columns and each value goes to
the column whose header centre is nearest its own centre (values are centred
or right-aligned under their headers, so a left-edge rule would misfile them).

The driver cell reads "Riley DICKINSON (P) *": given names in mixed case, the
surname in capitals, the class in brackets (P/PA/A in 2023–24, PRO/PRO-AM/MAS
later, sometimes "Pro-Am"), and an asterisk when a penalty applies. Some
sheets shorten the given name to an initial ("N. LASTOCHKIN"); that is passed
through as printed and the importer resolves it against known drivers. The
nationality column is flag images only, so it is never read.

Output shape (rows vary by document):

    {"document": "RACE" | "QUALIFYING" | "GRID",
     "event": "FORMULA 1 CRYPTO.COM MIAMI GRAND PRIX 2026",
     "location": "Miami Gardens", "year": 2026,
     "session": "Race 1", "race": 1, "status": "Official" | null,
     "revised": false, "laps": 16 | null, "distance_km": 86.434 | null,
     "notes": "Car 9 - 10 second time penalty ..." | null,
     "rows": [{"position": 1 | null, "number": "53",
               "first_name": "Riley", "surname": "DICKINSON",
               "class": "P", "penalty": false, "team": "Kellymoss",
               ...document-specific fields...}]}

RACE rows add classified, status (null | "DNF" | "DNS" | "DSQ" | ...),
laps, time, gap, interval, kph (race average), fastest_lap,
fastest_lap_number. QUALIFYING rows add time, laps, second_time. GRID rows
add time. Car numbers stay strings (04 != 4).
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import sys
from pathlib import Path

import pdfplumber

logging.getLogger("pdfminer").setLevel(logging.ERROR)

EVENT_RE = re.compile(r"^(?P<event>.*?\b(?P<year>(?:19|20)\d{2}))\s*-\s*(?P<location>.+)$")
RACE_TITLE_RE = re.compile(
    r"^(?P<session>Race\s+(?P<race>\d+))\s+(?:(?P<status>Official|Provisional|Unofficial)\s+)?"
    r"(?P<revised>Revised\s+)?Classification"
    r"(?:\s+after\s+(?P<laps>\d+)\s+Laps?(?:\s*-\s*(?P<km>[\d.]+)\s*km)?)?",
    re.IGNORECASE)
QUALI_TITLE_RE = re.compile(
    r"^Qualifying(?:\s+Session)?\s+(?:(?P<status>Official|Provisional|Unofficial)\s+)?"
    r"(?P<revised>Revised\s+)?Classification", re.IGNORECASE)
GRID_TITLE_RE = re.compile(
    r"^(?P<session>Race\s+(?P<race>\d+))\s+(?:(?P<status>Official|Provisional|Unofficial)\s+)?"
    r"(?P<revised>Revised\s+)?Starting\s+Grid", re.IGNORECASE)

LAP_TIME_RE = re.compile(r"^\d{1,2}:\d{2}\.\d{3}$")
LAPPED_RE = re.compile(r"^(\d+)\s+LAPS?$", re.IGNORECASE)
INITIAL_RE = re.compile(r"^(?:[A-Z]\.)+$|^[A-Z]{1,3}\.$")
CLASS_RE = re.compile(r"^\((?P<cls>[^()]+)\)$")
# Words in capitals — the surname. Letters (incl. accented), apostrophes,
# hyphens and dots ("MC", "O'CONNELL", "VAN", "ST.").
SURNAME_WORD_RE = re.compile(r"^[^a-z]*[A-ZÀ-Þ][^a-zà-ÿ]*$")

# Rows cluster on top with a tolerance: the driver cell is set in a smaller
# font whose baseline sits half a point off the rest of its row.
ROW_TOLERANCE = 2.0

# Text below any of these is footer, not table.
FOOTER_RE = re.compile(r"^(Timekeeper:|Page \d+ of \d+|©|No part of these|prior permission|results/data relate)")
NOTE_HEADINGS = ("* PENALTIES", "PENALTIES", "NOTES")

# (key, header phrase, required). The "%" columns first appear in 2024.
RACE_COLUMNS = [("laps", "LAPS", True), ("time", "TIME", True), ("gap", "GAP", True),
                ("interval", "INT", True), ("kph", "KM/H", True), ("fastest", "FASTEST", True),
                ("on", "ON", True)]
QUALI_COLUMNS = [("time", "1ST TIME", True), ("pct", "%", False), ("tod", "TIME OF DAY", True),
                 ("second", "2ND TIME", True), ("pct2", "%", False), ("tod2", "TIME OF DAY", True),
                 ("laps", "LAPS", True)]


# ---------------------------------------------------------------- geometry

def _word_rows(words):
    """Words grouped into visual rows by top, as [(top, [word, ...])]."""
    rows = []
    for w in sorted(words, key=lambda w: w["top"]):
        if rows and w["top"] - rows[-1][0] <= ROW_TOLERANCE:
            rows[-1][1].append(w)
        else:
            rows.append((w["top"], [w]))
    return [(top, sorted(ws, key=lambda w: w["x0"])) for top, ws in rows]


def _line(words):
    return " ".join(w["text"] for w in words)


def _find_columns(words, columns):
    """{key: (x0, x1)} of each column's header phrase, matched left to right.

    An optional phrase only matches at the very next position, so a missing
    "%" can't swallow a later header word."""
    spans = {}
    i = 0
    for key, phrase, required in columns:
        parts = phrase.split()
        texts = lambda j: [w["text"] for w in words[j:j + len(parts)]]
        if not required:
            if texts(i) == parts:
                spans[key] = (words[i]["x0"], words[i + len(parts) - 1]["x1"])
                i += len(parts)
            continue
        while i + len(parts) <= len(words) and texts(i) != parts:
            i += 1
        if i + len(parts) > len(words):
            raise ValueError(f"table header is missing column '{phrase}'")
        spans[key] = (words[i]["x0"], words[i + len(parts) - 1]["x1"])
        i += len(parts)
    return spans


def _nearest(centres, x):
    return min(range(len(centres)), key=lambda i: abs(centres[i] - x))


# ---------------------------------------------------------------- cells

def split_driver(text):
    """'Riley DICKINSON (P) *' -> first, surname, class, penalty."""
    tokens = text.split()
    penalty = False
    while tokens and tokens[-1] == "*":
        penalty = True
        tokens.pop()
    cls = None
    if tokens:
        m = CLASS_RE.match(tokens[-1])
        if m:
            cls = m.group("cls").strip()
            tokens.pop()
    # The surname is the trailing run of capitalised words; everything before
    # it is the given name. "A." is an initial, not a surname, even though it
    # is upper case — it can only ever lead.
    cut = len(tokens)
    while cut > 1 and SURNAME_WORD_RE.match(tokens[cut - 1]):
        cut -= 1
    if cut == len(tokens):  # no capitalised word: treat the last word as surname
        cut = max(len(tokens) - 1, 0)
    # Initials lead ("A. R. FERNANDES", "A.R.", "JP.") even though they are caps.
    while cut < len(tokens) - 1 and INITIAL_RE.match(tokens[cut]):
        cut += 1
    first = " ".join(tokens[:cut]) or None
    surname = " ".join(tokens[cut:]) or None
    return first, surname, cls, penalty


def _time_or_none(text):
    return text if text and LAP_TIME_RE.match(text) else None


def _int_or_none(text):
    return int(text) if text and text.isdigit() else None


def _gap(text):
    """'1.895' -> '+1.895', '1 LAP' -> '1 Lap', blank -> None (the JSON's spelling)."""
    if not text:
        return None
    m = LAPPED_RE.match(text)
    if m:
        n = int(m.group(1))
        return f"{n} Lap" if n == 1 else f"{n} Laps"
    if re.match(r"^\d+(?:\.\d+)?$", text) or LAP_TIME_RE.match(text):
        return "+" + text
    return text


# ---------------------------------------------------------------- documents

def _titles(page_rows):
    """(event match, session title text) from the first two text lines."""
    lines = [_line(ws) for _, ws in page_rows[:4]]
    event = next((EVENT_RE.match(l) for l in lines if EVENT_RE.match(l)), None)
    if event is None:
        raise ValueError("not an F1-style timing PDF: no '<EVENT> <YEAR> - <LOCATION>' title")
    idx = lines.index(event.group(0))
    session = lines[idx + 1] if idx + 1 < len(lines) else ""
    return event, session


def _notes(rows):
    """Text lines under a NOTES / PENALTIES heading, up to the footer."""
    out = []
    capturing = False
    for _, ws in rows:
        line = _line(ws)
        if line in NOTE_HEADINGS:
            capturing = True
            continue
        if FOOTER_RE.match(line):
            capturing = False
            continue
        if capturing:
            out.append(line)
    return out


def _table(pages, columns, parse_row):
    """Rows of a classification table across pages, plus its notes.

    Rows are anchored on their car number. A long name or team is set in a
    smaller font and may wrap onto two lines printed just above and below the
    number's baseline, so every other word joins the anchor whose vertical
    centre is nearest its own — never a fixed-tolerance row cluster, which
    splits those cells off their row."""
    rows_out = []
    notes = []
    for rows in pages:
        header_idx = next((i for i, (_, ws) in enumerate(rows)
                           if [w["text"] for w in ws[:4]] == ["NO", "DRIVER", "NAT", "ENTRANT"]), None)
        notes += _notes(rows)
        if header_idx is None:
            continue  # a continuation page holding only notes
        header = rows[header_idx][1]
        no_col, driver_col, _, entrant_col = header[:4]
        spans = _find_columns(header[4:], columns)
        keys = list(spans)
        centres = [(a + b) / 2 for a, b in spans.values()]
        table_left = min(a for a, _ in spans.values())

        body = []
        unclassified_from = None
        for top, ws in rows[header_idx + 1:]:
            line = _line(ws)
            if line == "NOT CLASSIFIED":
                unclassified_from = top
                continue
            if line.startswith("FASTEST LAP") or line in NOTE_HEADINGS or FOOTER_RE.match(line):
                break
            body += ws

        def cy(w):
            return (w["top"] + w["bottom"]) / 2

        anchors = [w for w in body
                   if no_col["x0"] <= w["x1"] <= no_col["x1"] + 3 and w["x1"] < driver_col["x0"]
                   and w["text"].isdigit()]
        if not anchors:
            continue
        members = {id(a): [] for a in anchors}
        for w in body:
            if any(w is a for a in anchors):
                continue
            nearest = min(anchors, key=lambda a: abs(cy(a) - cy(w)))
            members[id(nearest)].append(w)

        for a in anchors:
            ws = sorted(members[id(a)], key=lambda w: (round(w["top"]), w["x0"]))
            classified = unclassified_from is None or a["top"] < unclassified_from
            pos = [w for w in ws if w["x1"] < no_col["x0"] and abs(cy(w) - cy(a)) < 3]
            driver = [w for w in ws if driver_col["x0"] - 3 <= w["x0"] < entrant_col["x0"] - 2]
            team = [w for w in ws if entrant_col["x0"] - 2 <= w["x0"] < table_left - 2]
            cells = {k: [] for k in keys}
            for w in ws:
                if w["x0"] >= table_left - 8 and not any(w is t for t in team):
                    cells[keys[_nearest(centres, (w["x0"] + w["x1"]) / 2)]].append(w["text"])
            first, surname, cls, penalty = split_driver(_line(driver))
            row = {
                "position": int(pos[0]["text"]) if pos and pos[0]["text"].isdigit() and classified else None,
                "number": a["text"],
                "first_name": first,
                "surname": surname,
                "class": cls,
                "penalty": penalty,
                "team": _line(team) or None,
            }
            row.update(parse_row({k: " ".join(v) or None for k, v in cells.items()}, classified))
            rows_out.append(row)
    # The sheet prints a penalised car where it crossed the line, not where it
    # was classified (2024 Miami R1 lists P25 after P29); the numbers are right.
    rows_out.sort(key=lambda r: (r["position"] is None, r["position"] or 0))
    return rows_out, notes


def _race_row(cells, classified):
    laps, time, gap, interval, kph, fastest, on = (cells[k] for k in
                                                   ("laps", "time", "gap", "interval", "kph", "fastest", "on"))
    status = None
    if gap and not re.match(r"^[\d.:]+$", gap) and not LAPPED_RE.match(gap):
        status, gap = gap.upper(), None  # DNF / DNS / DSQ / NC printed in the gap column
    return {
        "classified": classified,
        "status": status,
        "laps": _int_or_none(laps),
        "time": time,
        "gap": _gap(gap),
        "interval": _gap(interval),
        "kph": float(kph) if kph and re.match(r"^\d+(?:\.\d+)?$", kph) else None,
        "fastest_lap": _time_or_none(fastest),
        "fastest_lap_number": _int_or_none(on),
    }


def _quali_row(cells, classified):
    first_time, second, laps = cells["time"], cells["second"], cells["laps"]
    second_time = next((t for t in (second or "").split() if LAP_TIME_RE.match(t)), None)
    return {
        "classified": classified,
        "time": _time_or_none(first_time),
        "second_time": second_time,
        "laps": _int_or_none(laps),
    }


def _grid(pages):
    """Grid slots from the staggered two-column layout."""
    slots = []
    notes = []
    for page, rows in pages:
        mid = page.width / 2
        body = []
        for top, ws in rows:
            line = _line(ws)
            if line in NOTE_HEADINGS or FOOTER_RE.match(line):
                break
            body.append((top, ws))
        notes += _notes(rows)
        words = [w for _, ws in body for w in ws]
        for side in (lambda w: w["x1"] <= mid, lambda w: w["x0"] >= mid):
            half = [w for w in words if side(w)]
            big = [w for w in half if w["size"] >= 10 and w["text"].isdigit()]
            small_rows = _word_rows([w for w in half if w["size"] < 10])
            for i, (top, ws) in enumerate(small_rows):
                if not ws[0]["text"].isdigit() or len(ws) < 2:
                    continue  # a team line, or the title
                pos = next((b for b in big if abs(b["top"] - top) <= 5), None)
                if pos is None:
                    continue
                time = ws[-1]["text"] if LAP_TIME_RE.match(ws[-1]["text"]) else None
                name_words = ws[1:-1] if time else ws[1:]
                first, surname, cls, penalty = split_driver(_line(name_words))
                team = None
                if i + 1 < len(small_rows):
                    nxt_top, nxt = small_rows[i + 1]
                    if 0 < nxt_top - top <= 14 and not nxt[0]["text"].isdigit():
                        team = _line(nxt)
                slots.append({
                    "position": int(pos["text"]),
                    "number": ws[0]["text"],
                    "first_name": first,
                    "surname": surname,
                    "class": cls,
                    "penalty": penalty,
                    "team": team,
                    "time": time,
                })
    slots.sort(key=lambda s: s["position"])
    return slots, notes


def parse(pdf_path: Path) -> dict:
    with pdfplumber.open(pdf_path) as pdf:
        pages = [(p, _word_rows(p.extract_words(extra_attrs=["size"]))) for p in pdf.pages]
    event, title = _titles(pages[0][1])

    race = quali = grid = None
    if m := GRID_TITLE_RE.match(title):
        grid = m
        rows, notes = _grid(pages)
        doc = {"document": "GRID"}
    elif m := RACE_TITLE_RE.match(title):
        race = m
        rows, notes = _table([r for _, r in pages], RACE_COLUMNS, _race_row)
        doc = {"document": "RACE"}
    elif m := QUALI_TITLE_RE.match(title):
        quali = m
        rows, notes = _table([r for _, r in pages], QUALI_COLUMNS, _quali_row)
        doc = {"document": "QUALIFYING"}
    else:
        raise ValueError(f"unrecognised session title: {title!r}")
    if not rows:
        raise ValueError(f"no rows found under '{title}'")

    numbers = [r["number"] for r in rows]
    dupes = sorted({n for n in numbers if numbers.count(n) > 1})
    if dupes:
        raise ValueError(f"car numbers appear twice: {', '.join(dupes)}")

    title_m = race or quali or grid
    doc.update({
        "event": event.group("event").strip(),
        "location": event.group("location").strip(),
        "year": int(event.group("year")),
        "session": title_m.groupdict().get("session") or "Qualifying",
        "race": int(title_m.group("race")) if "race" in title_m.groupdict() else 1,
        "status": (title_m.group("status") or "").capitalize() or None,
        "revised": bool(title_m.group("revised")),
        "laps": int(race.group("laps")) if race and race.group("laps") else None,
        "distance_km": float(race.group("km")) if race and race.group("km") else None,
        "notes": "\n".join(notes) or None,
        "rows": rows,
    })
    return doc


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Parse an F1 support-race timing PDF to JSON.")
    ap.add_argument("pdf", type=Path, help="race classification, qualifying classification or starting grid PDF")
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
    print(f"parsed {doc['document'].lower()} {doc['session']} ({doc['event']}): {len(doc['rows'])} cars",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
