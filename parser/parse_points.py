#!/usr/bin/env python3
"""Parse an IMSA championship-points PDF into the points.json contract.

Sibling of parse_entry_list.py and the same sidecar shape: PDF in, JSON out, the
Java loader owns persistence. Some series publish a standings JSON and some only
publish this PDF; where the JSON exists, import that instead — it splits pole and
fastest-lap points, which the PDF cannot (see `bonus_points` in POINTS_SCHEMA.md).

One PDF holds every championship for the series, so this emits a list and the
loader stages one import batch per championship.

Why this is geometry-driven rather than text-driven:

  * Points columns come in pairs — "Extra"+"Round N" (Mustang Challenge) or
    "Qualifying"+"Race" (WeatherTech) — and adjacent cells render flush together.
    Robert Noaker's 2024 row reads "350 10320 10320 320 ..." where "10320" is
    Extra 10 + Round 320, not ten thousand. extract_text() cannot tell those
    apart: read naively the row totals 43230 instead of its printed 3270. The
    column headers are rotated 90 degrees, which hands us an exact x anchor per
    column, so bucketing each *character* by its centre x splits the pair at the
    right place.
  * Long names overprint the number columns ("Acura Meyer Shank Racing w/
    C19u0rb0 Aga3j5ani2a6n0"). Names and points are set in different fonts, so
    the two runs separate cleanly — and the '/' in "w/" stays part of the name
    instead of being read as a did-not-participate sentinel.

Every row is verified by re-adding its cells to the printed total. That checksum
is what caught the flush-column collision in the first place, so it is a hard
gate: a mismatch means a layout assumption broke and the parser exits non-zero
rather than emitting plausible-looking points.

Usage:
    python parse_points.py INPUT.pdf [-o OUTPUT.json] [--year 2024]

With no -o, JSON is written to stdout (summary goes to stderr).
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

import pdfplumber

# --- layout constants -------------------------------------------------------

# Left of this x is the finishing-position column; the name/car number follow.
POS_MAX_X = 50.0
# The total-points column sits right of this; a car number, where present, sits
# left of it. Both are digits on the row's own baseline, so x is what separates
# them.
TOTAL_MIN_X = 150.0
# The points columns start a little left of the first rotated header anchor.
# The gap absorbs right-aligned cells that overhang their anchor.
NUMERIC_LEAD = 12.0
# Event names sit in a band directly above the rotated headers, wrapping over at
# most two lines ("WeatherTech" / "Raceway").
EVENT_BAND_HEIGHT = 30.0
# Vertical slack when grouping characters into one standings row. Row pitch is
# ~10pt and the widest within-row baseline drift seen is 0.4pt.
ROW_TOLERANCE = 2.0

# The one column label that is a bonus rather than a session of its own. Its
# points belong to the session in the column immediately to its right.
BONUS_LABEL = "Extra"

# The PDF prints its own legend: "/ DNP  * DNS". These map onto the status values
# the standings JSON uses, confirmed against a season published in both formats.
SENTINEL_STATUS = {
    "/": "did_not_race",      # DNP — did not participate
    "*": "not_classified",    # DNS — did not start
}


def _rotated_headers(page):
    """The 90-degree-rotated column headers as [(x, label)], left to right.

    These anchors are the whole trick: they are the only thing that says where
    one points column ends and the next begins.
    """
    cols = defaultdict(list)
    for c in page.chars:
        if not c.get("upright", True):
            cols[round(c["x0"])].append(c)
    return sorted(
        (x, "".join(ch["text"] for ch in sorted(cs, key=lambda ch: -ch["top"])).strip())
        for x, cs in cols.items()
    )


def _band_words(page, header_top, first_anchor_x):
    """The event-name words sitting above the rotated column headers."""
    return [
        w for w in page.extract_words()
        if w["bottom"] <= header_top + 1
        and w["top"] >= header_top - EVENT_BAND_HEIGHT
        and w["x0"] >= first_anchor_x - 20
    ]


def _columns(page):
    """Points columns as [{label, x, event}], left to right.

    Every event owns the same number of columns — 4 for Mustang Challenge
    (Extra+Round, twice) and 2 for WeatherTech (Qualifying+Race) — and its name
    is centred over that block. So rather than guessing where one name ends and
    the next begins from the gaps between words (the gap between "Beach" and
    "WeatherTech" is 7.6pt on a Drivers page but 4.6pt on a Teams page, against
    2.3pt for the space inside "Long Beach" — far too tight to separate on),
    split the columns into equal blocks and let each word fall to the nearest
    block centre. Words landing on the same block are one event name.

    The block count is whichever divisor of the column count names every block:
    tried largest first, so the finest split that still works wins.
    """
    anchors = _rotated_headers(page)
    if not anchors:
        return []
    header_top = min(c["top"] for c in page.chars if not c.get("upright", True))
    words = _band_words(page, header_top, anchors[0][0])
    if not words:
        raise ValueError("found rotated column headers but no event names above them")

    n = len(anchors)
    for events in [d for d in range(n, 0, -1) if n % d == 0]:
        per_event = n // events
        blocks = [anchors[i * per_event:(i + 1) * per_event] for i in range(events)]
        centres = [sum(x for x, _ in b) / len(b) for b in blocks]

        assigned = defaultdict(list)
        for w in words:
            wc = (w["x0"] + w["x1"]) / 2
            assigned[min(range(events), key=lambda i: abs(centres[i] - wc))].append(w)
        if len(assigned) != events:
            continue  # a block with no name over it: wrong split, try coarser

        names = [
            " ".join(w["text"] for w in sorted(assigned[i], key=lambda w: (round(w["top"]), w["x0"])))
            for i in range(events)
        ]
        return [
            {"label": label, "x": x, "event": names[i]}
            for i, block in enumerate(blocks)
            for x, label in block
        ]
    raise ValueError(f"could not map {len(words)} event name(s) onto {n} columns")


def _rows(page):
    """Upright characters grouped into visual rows, as [(top, chars)].

    A row's name can sit a fraction of a point above its own numbers (0.4pt on a
    Teams sheet, where the team name is set on a slightly different baseline from
    the car number beside it), so rows cluster with a tolerance rather than key
    on an exact top. Row pitch is ~10pt, so neighbours never merge.
    """
    rows = []
    for c in sorted((c for c in page.chars if c.get("upright", True)), key=lambda c: c["top"]):
        if rows and c["top"] - rows[-1][0] <= ROW_TOLERANCE:
            rows[-1][1].append(c)
        else:
            rows.append((c["top"], [c]))
    return rows


def _cell_values(chars, cols, numeric_from, points_font):
    """Raw text per points column, bucketing each character by its centre x.

    Only glyphs in the points font count: that is what keeps an overprinting
    team name out of the numbers.
    """
    edges = [(cols[i]["x"] + cols[i + 1]["x"]) / 2 for i in range(len(cols) - 1)]
    buckets = defaultdict(str)
    for c in chars:
        if c["fontname"] != points_font:
            continue
        cx = (c["x0"] + c["x1"]) / 2
        if cx < numeric_from:
            continue
        idx = 0
        while idx < len(edges) and cx > edges[idx]:
            idx += 1
        buckets[idx] += c["text"]
    return [buckets.get(i, "").strip() for i in range(len(cols))]


def _parse_rows(page, cols):
    """Standings rows on one page: [{position, car_number, name, total, cells}]."""
    numeric_from = cols[0]["x"] - NUMERIC_LEAD
    out = []
    for _, row_chars in _rows(page):
        chars = sorted(row_chars, key=lambda c: c["x0"])
        pos_chars = [c for c in chars if c["x0"] < POS_MAX_X and c["text"].isdigit()]
        if not pos_chars:
            continue  # header, legend or footer line, not a competitor

        # Points share the position column's font; the name, car number and total
        # are set in the other face. Both are read off the row rather than
        # hardcoded, so a sheet in different fonts still parses.
        points_font = pos_chars[0]["fontname"]
        baseline = round(pos_chars[0]["top"], 2)
        # Deliberately left in the order the PDF draws them, not x order: a name
        # long enough to overprint the totals interleaves with them on the page,
        # so reading by x splices the two together ("COMPETIT ION"). Each text
        # run is drawn whole, so draw order keeps the name intact.
        label_chars = [c for c in row_chars if c["x0"] >= POS_MAX_X and c["fontname"] != points_font]
        if not label_chars:
            continue

        # Telling the name apart from the numbers printed through it. A Teams
        # sheet sets the team name a fraction of a point off the row's baseline,
        # which is the only thing separating "Bryan Herta Autosport with
        # PR1/Mathiasen" from its total: same font, interleaved x, and the name
        # carries a digit of its own. Every other sheet puts the name on the
        # row's baseline, where it is instead the digits that give the numbers
        # away.
        if {round(c["top"], 2) for c in label_chars} - {baseline}:
            def is_name(c):
                return round(c["top"], 2) != baseline
        else:
            def is_name(c):
                return not c["text"].isdigit()

        def is_number(c):
            return not is_name(c) and c["text"].isdigit()

        # A Teams sheet numbers the car between position and team name; Drivers
        # and Manufacturers sheets have no such column. Numbers read left to
        # right; only the name needs draw order.
        by_x = sorted(label_chars, key=lambda c: c["x0"])
        car_chars = [c for c in by_x if is_number(c) and c["x0"] < TOTAL_MIN_X]
        car_number = "".join(c["text"] for c in car_chars) or None
        name_from = max((c["x1"] for c in car_chars), default=POS_MAX_X)

        name = " ".join("".join(
            c["text"] for c in label_chars if c["x0"] >= name_from and is_name(c)
        ).split())
        total = "".join(
            c["text"] for c in by_x if c["x0"] >= TOTAL_MIN_X and is_number(c)
        )
        if not name or not total:
            continue

        out.append({
            "position": int("".join(c["text"] for c in pos_chars)),
            "car_number": car_number,
            "name": name,
            "total": int(total),
            "cells": _cell_values(chars, cols, numeric_from, points_font),
        })
    return out


def _sessions(cols):
    """Columns -> ordered sessions, each with the bonus column feeding it.

    "Extra" is not a session: it is a bonus on the round to its right. Every
    other label ("Round 3", "Qualifying", "Race") names a session of its own,
    matching how the standings JSON names them.
    """
    sessions = []
    pending_bonus = None
    for idx, col in enumerate(cols):
        if col["label"] == BONUS_LABEL:
            pending_bonus = idx
            continue
        sessions.append({
            "session_index": len(sessions) + 1,
            "event_name": col["event"],
            "session_name": col["label"],
            "_col": idx,
            "_bonus_col": pending_bonus if pending_bonus is not None else None,
        })
        pending_bonus = None
    return sessions


def _points_for(row, sessions):
    """A row's cells -> points_by_session, plus the recomputed total."""
    out = []
    running = 0
    for s in sessions:
        raw = row["cells"][s["_col"]]
        bonus_raw = row["cells"][s["_bonus_col"]] if s["_bonus_col"] is not None else ""

        status = SENTINEL_STATUS.get(raw, "")
        race = int(raw) if raw.isdigit() else 0
        bonus = int(bonus_raw) if bonus_raw.isdigit() else 0
        if not raw.isdigit() and raw not in SENTINEL_STATUS and raw != "":
            raise ValueError(f"unreadable cell {raw!r} in {s['session_name']!r}")
        if not bonus_raw.isdigit() and bonus_raw not in SENTINEL_STATUS and bonus_raw != "":
            raise ValueError(f"unreadable bonus {bonus_raw!r} in {s['session_name']!r}")

        running += race + bonus
        out.append({
            "session_index": s["session_index"],
            "total_points": race + bonus,
            "race_points": race,
            # The PDF gives one undifferentiated bonus. Where a JSON exists it
            # splits this into pole vs fastest lap; from a PDF we cannot, so it
            # is recorded as-is rather than guessed into the wrong bucket.
            "bonus_points": bonus,
            "pole_points": 0,
            "fastest_lap_points": 0,
            "penalty_points": 0,
            "status": status,
        })
    return out, running


def _year_of(pdf, override):
    if override:
        return str(override)
    # The page text is not a reliable source (a points value can look like a
    # year), so fall back to when the sheet was generated. A full-season sheet
    # republished in January would need --year.
    raw = (pdf.metadata or {}).get("CreationDate", "")
    m = re.search(r"D:(\d{4})", raw)
    return m.group(1) if m else ""


def parse(path: Path, year: int | None = None) -> dict:
    """PDF -> the points.json contract. Raises if any row fails its checksum."""
    pdf = pdfplumber.open(path)
    by_title = {}
    for pno, page in enumerate(pdf.pages, 1):
        cols = _columns(page)
        if not cols:
            continue  # not a standings grid
        title = (page.extract_text() or "").split("\n")[0].strip()
        if not title:
            continue
        sessions = _sessions(cols)
        entry = by_title.setdefault(title, {"sessions": sessions, "rows": []})
        # A championship can run over several pages; later pages repeat the same
        # column layout and continue the classification.
        if [s["session_name"] for s in entry["sessions"]] != [s["session_name"] for s in sessions]:
            raise ValueError(f"page {pno}: column layout differs from earlier {title!r} page")
        for row in _parse_rows(page, cols):
            points, recomputed = _points_for(row, sessions)
            if recomputed != row["total"]:
                raise ValueError(
                    f"page {pno}: {title} #{row['position']} {row['name']!r} "
                    f"cells sum to {recomputed} but the sheet prints {row['total']}"
                )
            entry["rows"].append((row, points))

    championships = []
    for title, entry in by_title.items():
        classification = []
        for row, points in entry["rows"]:
            # Match how the standings JSON keys rows: a Teams sheet keys on the
            # car number and carries the team as the name; a Drivers sheet keys
            # on the driver's name and leaves the name blank.
            key = row["car_number"] if row["car_number"] else row["name"]
            team = row["name"] if row["car_number"] else ""
            classification.append({
                "position": row["position"],
                "key": key,
                "team": team,
                "total_points": row["total"],
                "points_by_session": points,
            })
        championships.append({
            "championship": {
                # The PDF has no short code (the JSON's "IWSC GTP DRIVERS"), so
                # the full title is the identity here. See POINTS_SCHEMA.md.
                "name": title,
                "main_title": title,
                "sub_title": "",
                "year": _year_of(pdf, year),
                "sessions": [
                    {k: s[k] for k in ("session_index", "event_name", "session_name")}
                    for s in entry["sessions"]
                ],
            },
            "classification": classification,
        })
    return {"source_file": Path(path).name, "championships": championships}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("pdf", type=Path)
    ap.add_argument("-o", "--output", type=Path)
    ap.add_argument("--year", type=int, help="season year; defaults to the PDF's creation year")
    ap.add_argument("--indent", type=int, default=2)
    args = ap.parse_args()

    if not args.pdf.exists():
        print(f"error: no such file: {args.pdf}", file=sys.stderr)
        return 2

    try:
        doc = parse(args.pdf, args.year)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    payload = json.dumps(doc, ensure_ascii=False, indent=args.indent)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload)

    rows = sum(len(c["classification"]) for c in doc["championships"])
    print(
        f"parsed {len(doc['championships'])} championship(s), {rows} rows; "
        f"every row re-adds to its printed total",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
