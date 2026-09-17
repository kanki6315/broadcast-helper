#!/usr/bin/env python3
"""Parse a championship-points PDF into the points.json contract.

Sibling of parse_entry_list.py and the same sidecar shape: PDF in, JSON out, the
Java loader owns persistence. Some series publish a standings JSON and some only
publish this PDF; where the JSON exists, import that instead — it splits pole and
fastest-lap points, which the IMSA PDF cannot (see `bonus_points` in
POINTS_SCHEMA.md).

One PDF holds every championship for the series, so this emits a list and the
loader stages one import batch per championship.

Two source layouts are recognized, sniffed page by page:
  * IMSA — rotated column headers, no table rules; the geometry-driven path
    below.
  * Porsche Carrera Cup Asia (PACCA) — a fully ruled Excel-exported grid, one
    championship per page, each round split into FLQ / Race / FL sub-columns.
    Unlike the IMSA sheet, this one DOES split its bonuses: FLQ (fastest lap in
    qualifying — pole, in a one-make cup) lands in pole_points and FL (race
    fastest lap) in fastest_lap_points.

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
  * Within a row, the position, car number, name and total are each drawn as
    one text run (Crystal Reports draws each field whole), so the row is read
    as runs — glyphs consecutive in draw order that each start where the last
    ended — rather than by x thresholds or baseline drift. The 2021 sheets
    moved the name column 3pt left of the old threshold (every name lost its
    first letter) and set the total 0.6pt below the baseline (read as name
    text, so every row was dropped); runs are indifferent to both.
  * Event names over the columns are runs too, one per event and one per
    wrapped line, which is what tells "Sebring" from "CotA" when the gap
    between them is no wider than the space inside "Road America". Columns
    then go to names by nearest centre, each name owning a contiguous block —
    blocks need not be equal (2021 Carrera Cup gave its finale four rounds
    and every other weekend two).

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

# The total-points column is found from the "Points" header over it; this is
# the fallback when a page has no such header row. A car number, where present,
# is the digits-only run left of the name.
TOTAL_MIN_X = 150.0
# A right-aligned total may start a little left of its header's first glyph.
TOTAL_SLACK = 8.0
# Glyphs of one text run start where the previous glyph ended, give or take
# kerning; a wider gap (or a different baseline) starts a new run.
RUN_GAP = 1.5
# The points columns start a little left of the first rotated header anchor.
# The gap absorbs right-aligned cells that overhang their anchor.
NUMERIC_LEAD = 12.0
# Event names sit in a band directly above the rotated headers, wrapping over at
# most two lines ("WeatherTech" / "Raceway").
EVENT_BAND_HEIGHT = 30.0
# Vertical slack when grouping characters into one standings row. Row pitch is
# ~10pt and the widest within-row baseline drift seen is 0.4pt.
ROW_TOLERANCE = 2.0

# The column labels that are a bonus rather than a session of their own. Their
# points belong to the session in the next session column to the right:
# "Extra" (Mustang Challenge, undifferentiated) lands in bonus_points, "Pole"
# (2021 Carrera Cup / Super Trofeo, "Pole**" where a footnote applies) in
# pole_points.
BONUS_LABEL = "Extra"
POLE_LABEL = re.compile(r"(?i)^pole\b")

# The PDF prints its own legend: "/ DNP  * DNS". These map onto the status values
# the standings JSON uses, confirmed against a season published in both formats.
SENTINEL_STATUS = {
    "/": "did_not_race",      # DNP — did not participate
    "*": "not_classified",    # DNS — did not start
}

# --- PACCA (Carrera Cup Asia) ruled grid --------------------------------------

# The PACCA sheet spells its sentinels out. '-' is the DNP analogue and 'DNS'
# keeps the IMSA mapping; DNF and DSQ are new — both took part, so the season
# view correctly counts their round as contested.
PACCA_SENTINEL_STATUS = {
    "-": "did_not_race",
    "DNS": "not_classified",
    "DNF": "did_not_finish",
    "DSQ": "disqualified",
}

# Sub-column label -> points bucket. FLQ (fastest lap in qualifying — pole, in a
# one-make cup) still scores when the race ends DNF/DSQ, so every numeric cell
# counts regardless of the Race cell's sentinel.
PACCA_BUCKETS = {"Race": "race", "FLQ": "pole", "FL": "fastest_lap"}

_PACCA_NUM_RE = re.compile(r"^\d+(?:\.\d+)?$")


def _pacca_num(raw: str) -> float:
    return float(raw)


def _emit(x: float):
    """Points as printed: ints where integral, so 25 stays 25 and 12.5 stays 12.5."""
    return int(x) if x == int(x) else x


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


def _runs(chars):
    """Characters (in draw order) grouped into the text runs they were drawn as.

    A run is consecutive glyphs on one baseline, each starting where the last
    ended. The generator draws every field as one run, so a run is a field:
    a name is one run however far it overprints, and two names side by side
    with no space glyph between them are two.
    """
    runs = []
    for c in chars:
        if runs:
            last = runs[-1][-1]
            if abs(c["top"] - last["top"]) <= RUN_GAP and -RUN_GAP <= c["x0"] - last["x1"] <= RUN_GAP:
                runs[-1].append(c)
                continue
        runs.append([c])
    return runs


def _text(run):
    return "".join(c["text"] for c in run).strip()


def _event_names(page, header_top, first_anchor_x):
    """The event names above the rotated column headers, as [(centre_x, text)].

    One run per name on the top line of the band; a name that wraps ("Watkins"
    / "Glen 1") continues in a run on the line below, attached to the top-line
    run nearest it.
    """
    band = [
        c for c in page.chars
        if c.get("upright", True)
        and c["bottom"] <= header_top + 1
        and c["top"] >= header_top - EVENT_BAND_HEIGHT
        and c["x0"] >= first_anchor_x - 20
    ]
    runs = [r for r in _runs(band) if _text(r)]
    if not runs:
        return []
    top_line = min(r[0]["top"] for r in runs)
    names = [[r] for r in runs if abs(r[0]["top"] - top_line) <= RUN_GAP]
    centre = lambda r: (r[0]["x0"] + r[-1]["x1"]) / 2
    for r in runs:
        if abs(r[0]["top"] - top_line) <= RUN_GAP:
            continue
        min(names, key=lambda nm: abs(centre(nm[0]) - centre(r))).append(r)
    return sorted(
        (centre(nm[0]), " ".join(_text(r) for r in sorted(nm, key=lambda r: r[0]["top"])))
        for nm in names
    )


def _blocks(xs, centres):
    """Split the sorted column positions into len(centres) contiguous blocks,
    one per name, minimising the distance from each block's centre to its name's.

    Dynamic programme over (columns used, names used); blocks may differ in
    width, which is what a season with a three-race finale needs.
    """
    n, k = len(xs), len(centres)
    if k == 0 or k > n:
        raise ValueError(f"cannot map {k} event name(s) onto {n} columns")
    inf = float("inf")
    best = [[inf] * (n + 1) for _ in range(k + 1)]
    cut = [[0] * (n + 1) for _ in range(k + 1)]
    best[0][0] = 0.0
    for j in range(1, k + 1):
        for i in range(j, n + 1):
            for s in range(j - 1, i):
                if best[j - 1][s] == inf:
                    continue
                cost = best[j - 1][s] + abs(sum(xs[s:i]) / (i - s) - centres[j - 1])
                if cost < best[j][i]:
                    best[j][i], cut[j][i] = cost, s
    sizes, i = [], n
    for j in range(k, 0, -1):
        s = cut[j][i]
        sizes.append(i - s)
        i = s
    return list(reversed(sizes))


def _columns(page):
    """Points columns as [{label, x, event}], left to right.

    Each event's name is centred over its block of columns. The names are read
    as text runs (see _event_names) and the columns split into as many
    contiguous blocks as there are names, each block to the name whose centre
    it sits under.
    """
    anchors = _rotated_headers(page)
    if not anchors:
        return []
    header_top = min(c["top"] for c in page.chars if not c.get("upright", True))
    names = _event_names(page, header_top, anchors[0][0])
    if not names:
        raise ValueError("found rotated column headers but no event names above them")
    sizes = _blocks([x for x, _ in anchors], [cx for cx, _ in names])
    # The rotated glyphs' horizontal centre, which a right-aligned cell sits
    # under; x is their left edge and stays the column's key.
    centres = defaultdict(list)
    for c in page.chars:
        if not c.get("upright", True):
            centres[round(c["x0"])].append((c["x0"] + c["x1"]) / 2)
    cols = []
    start = 0
    for (_, event), size in zip(names, sizes):
        for x, label in anchors[start:start + size]:
            cols.append({"label": " ".join(label.split()), "x": x,
                         "cx": sum(centres[x]) / len(centres[x]), "event": event})
        start += size
    return cols


def _rows(page):
    """Upright characters grouped into visual rows, as [(top, chars)], each
    row's characters back in draw order.

    A row's fields can sit a fraction of a point off each other's baseline
    (0.4–0.7pt between a name and its total), so rows cluster with a tolerance
    rather than key on an exact top. Row pitch is ~10pt, so neighbours never
    merge. Draw order is what _runs needs: a name is drawn whole even where it
    overprints the numbers drawn after it.
    """
    rows = []
    indexed = [(i, c) for i, c in enumerate(page.chars) if c.get("upright", True)]
    for i, c in sorted(indexed, key=lambda ic: ic[1]["top"]):
        if rows and c["top"] - rows[-1][0] <= ROW_TOLERANCE:
            rows[-1][1].append((i, c))
        else:
            rows.append((c["top"], [(i, c)]))
    return [(top, [c for _, c in sorted(chars)]) for top, chars in rows]


def _points_header(page):
    """The "Points" header over the total column, as (x0, x1), or None when
    the page has no "Pos … Points" line."""
    for _, chars in _rows(page):
        runs = sorted((r for r in _runs(chars) if _text(r)), key=lambda r: r[0]["x0"])
        if len(runs) >= 2 and _text(runs[0]) == "Pos" and _text(runs[-1]).startswith("Point"):
            return runs[-1][0]["x0"], runs[-1][-1]["x1"]
    return None


def _cell_values(chars, cols, numeric_from, points_font, claimed=()):
    """Raw text per points column, bucketing each character by its centre x.

    Only glyphs in the points font count, and none that a field run already
    claimed: that is what keeps an overprinting team name out of the numbers
    even where the sheet sets the name in the points face (2022 Pilot
    Challenge Teams). Bucketing is per character because a bonus
    and its round can render flush ("10320"); the midpoint between their
    anchors is what splits them.

    A sheet can also carry cells under no header at all: the 2021 Pilot and
    Prototype Challenge sheets print the template's pole slot before every
    round and two spare round slots after the last, all zero, with the labels
    suppressed. Those fall into the neighbouring labelled bucket as a run of
    their own (a cell is one run; a gap separates it from the next). The run
    under the header is the cell; any other run in the bucket must be empty
    or zero, or the sheet has a column nothing can attribute.
    """
    edges = [(cols[i]["x"] + cols[i + 1]["x"]) / 2 for i in range(len(cols) - 1)]
    buckets = defaultdict(list)
    claimed_ids = {id(c) for c in claimed}
    for c in chars:
        if c["fontname"] != points_font or id(c) in claimed_ids:
            continue
        cx = (c["x0"] + c["x1"]) / 2
        if cx < numeric_from:
            continue
        idx = 0
        while idx < len(edges) and cx > edges[idx]:
            idx += 1
        buckets[idx].append(c)
    out = []
    for i, col in enumerate(cols):
        runs = []
        for c in sorted(buckets.get(i, []), key=lambda c: c["x0"]):
            if runs and c["x0"] - runs[-1][-1]["x1"] <= RUN_GAP:
                runs[-1].append(c)
            else:
                runs.append([c])
        runs = [r for r in runs if _text(r)]
        if not runs:
            out.append("")
            continue
        cell = min(runs, key=lambda r: abs((r[0]["x0"] + r[-1]["x1"]) / 2 - col["cx"]))
        for r in runs:
            if r is not cell and _text(r) != "0":
                raise ValueError(
                    f"a cell {_text(r)!r} under no column header, beside {col['label']!r} "
                    f"(the header names only the cell {_text(cell)!r})")
        out.append(_text(cell))
    return out


def _parse_rows(page, cols):
    """Standings rows on one page: [{position, car_number, name, total, cells}]."""
    header = _points_header(page)
    points_x = header[0] if header else TOTAL_MIN_X
    # The points cells start right of the total column: just past its header
    # where the page has one (a template's unlabelled pole slot can sit closer
    # to the total than the first labelled column does), else a little left of
    # the first column anchor.
    numeric_from = header[1] + 1 if header else cols[0]["x"] - NUMERIC_LEAD
    out = []
    seen_rows = 0
    for _, row_chars in _rows(page):
        runs = sorted((r for r in _runs(row_chars) if _text(r)), key=lambda r: r[0]["x0"])
        if not runs:
            continue
        # A competitor row leads with its position: a digits-only run left of
        # the points columns. Anything else is a header, legend or footer line.
        first = _text(runs[0])
        if not first.isdigit() or runs[0][0]["x0"] >= numeric_from:
            continue
        seen_rows += 1
        # Points share the position's font. The fields — car number, name and
        # total — are the runs that start left of the points cells, whatever
        # their face: usually the other one, but a 2021 Teams sheet sets the
        # team name in the points face.
        points_font = runs[0][0]["fontname"]
        fields = [r for r in runs[1:] if r[0]["x0"] < numeric_from]

        # Among the label fields: the digits-only run under the "Points" header
        # is the total; a digits-only run before the name is the car number (a
        # Teams sheet); everything else is the name, however many runs it took
        # ("Seb Priaulx" + "(J)") and however far it overprints the numbers. A
        # name that merely starts with digits ("311RS Motorsport") is one run
        # with its letters, so it never reads as a car number.
        # The car number is the digits-only run before the name; the total is
        # the rightmost digits-only run under the "Points" header, preferring
        # one set in the other face when both faces offer one (a page with no
        # header falls back to a guessed boundary, and a points cell can then
        # start left of it — it stays unclaimed and reads as a cell). Any other
        # digits-only run after the name is such a cell too.
        car_run = None
        name_runs, digit_runs = [], []
        for r in fields:
            text = _text(r)
            if text.isdigit() and not name_runs and car_run is None and r[0]["x0"] < points_x - TOTAL_SLACK:
                car_run = r
            elif text.isdigit():
                digit_runs.append(r)
            else:
                name_runs.append(r)
        candidates = [r for r in digit_runs if r[0]["x0"] >= points_x - TOTAL_SLACK]
        other_face = [r for r in candidates if r[0]["fontname"] != points_font]
        total_run = (other_face or candidates or [None])[-1]
        fields = [r for r in (runs[0], car_run, *name_runs, total_run) if r is not None]
        if total_run is None or not name_runs:
            continue
        name = ""
        for r in name_runs:
            if name and r[0]["x0"] - prev_x1 > RUN_GAP:
                name += " "
            name += _text(r)
            prev_x1 = r[-1]["x1"]

        out.append({
            "position": int(first),
            "car_number": _text(car_run) if car_run else None,
            "name": " ".join(name.split()),
            "total": int(_text(total_run)),
            "cells": _cell_values(row_chars, cols, numeric_from, points_font,
                                  claimed=[c for r in fields for c in r]),
        })
    if seen_rows and not out:
        # Rows are there but none read: a layout the row reader does not
        # understand, not an empty page. Fail rather than stage nobody.
        raise ValueError(f"{seen_rows} competitor rows on the page but none could be read")
    return out


def _sessions(cols):
    """Columns -> ordered sessions, each with the bonus columns feeding it.

    "Extra" and "Pole" are not sessions: each is a bonus on the round to its
    right. Every other label ("Round 3", "Qualifying", "Race") names a session
    of its own, matching how the standings JSON names them.
    """
    sessions = []
    pending_bonus = pending_pole = None
    for idx, col in enumerate(cols):
        if col["label"] == BONUS_LABEL:
            pending_bonus = idx
            continue
        if POLE_LABEL.match(col["label"]):
            pending_pole = idx
            continue
        sessions.append({
            "session_index": len(sessions) + 1,
            "event_name": col["event"],
            "session_name": col["label"],
            "_col": idx,
            "_bonus_col": pending_bonus,
            "_pole_col": pending_pole,
        })
        pending_bonus = pending_pole = None
    if pending_bonus is not None or pending_pole is not None:
        raise ValueError("a bonus column has no session column to its right")
    return sessions


def _points_for(row, sessions):
    """A row's cells -> points_by_session, plus the recomputed total."""
    out = []
    running = 0
    for s in sessions:
        raw = row["cells"][s["_col"]]
        bonus_raw = row["cells"][s["_bonus_col"]] if s["_bonus_col"] is not None else ""
        pole_raw = row["cells"][s["_pole_col"]] if s["_pole_col"] is not None else ""

        status = SENTINEL_STATUS.get(raw, "")
        race = int(raw) if raw.isdigit() else 0
        bonus = int(bonus_raw) if bonus_raw.isdigit() else 0
        pole = int(pole_raw) if pole_raw.isdigit() else 0
        for kind, cell in (("cell", raw), ("bonus", bonus_raw), ("pole", pole_raw)):
            if not cell.isdigit() and cell not in SENTINEL_STATUS and cell != "":
                raise ValueError(f"unreadable {kind} {cell!r} in {s['session_name']!r}")

        running += race + bonus + pole
        out.append({
            "session_index": s["session_index"],
            "total_points": race + bonus + pole,
            "race_points": race,
            # An "Extra" column is one undifferentiated bonus. Where a JSON
            # exists it splits this into pole vs fastest lap; from a PDF we
            # cannot, so it is recorded as-is rather than guessed into the wrong
            # bucket. A "Pole" column, by contrast, says what it is.
            "bonus_points": bonus,
            "pole_points": pole,
            "fastest_lap_points": 0,
            "penalty_points": 0,
            "status": status,
        })
    return out, running


def _parse_ruled_page(page, pno):
    """PACCA-style ruled grid -> (title, title_year, sessions, [(row, points)]).

    Returns None if the page is not such a grid (the IMSA path handles it
    instead). One page is one championship: a header block of event / date /
    "Round N" / sub-label rows over the points columns, then one row per
    competitor. Driver pages split each round into FLQ+Race+FL; the team page
    has one column per round.
    """
    tables = page.find_tables()
    if not tables:
        return None
    data = tables[0].extract()
    if not data or not data[0]:
        return None
    header = [" ".join((c or "").split()) for c in data[0]]
    if header[0] != "Pos." or "Total" not in header:
        return None
    total_idx = header.index("Total")
    name_idx = next((i for i, h in enumerate(header[:total_idx])
                     if h.upper() in ("DRIVER", "TEAM")), None)
    if name_idx is None:
        return None
    is_team_sheet = header[name_idx].upper() == "TEAM"

    title = (page.extract_text() or "").split("\n")[0].strip()
    ym = re.match(r"(20\d\d)\b", title)
    title_year = ym.group(1) if ym else None

    # Header rows: the "Round N" row anchors everything; a row of FLQ/Race/FL
    # sub-labels directly under it is present on driver pages, absent on the
    # team page (where each round is a single Race column).
    def norm(row, i):
        return " ".join((row[i] or "").split()) if i < len(row) else ""

    round_ri = next((ri for ri, row in enumerate(data)
                     if any(re.fullmatch(r"Round \d+", norm(row, i))
                            for i in range(total_idx + 1, len(header)))), None)
    if round_ri is None:
        return None
    sub = data[round_ri + 1] if round_ri + 1 < len(data) else []
    has_sub = any(norm(sub, i) in PACCA_BUCKETS for i in range(total_idx + 1, len(header)))

    # Event names span their round columns; forward-fill from the span starts.
    event = None
    rounds = []  # [{name, event, cols: [(bucket, col_index), ...]}]
    for i in range(total_idx + 1, len(header)):
        if norm(data[0], i):
            event = norm(data[0], i)
        label = norm(data[round_ri], i)
        if re.fullmatch(r"Round \d+", label):
            rounds.append({"name": label, "event": event, "cols": []})
        if not rounds:
            continue
        sub_label = norm(sub, i) if has_sub else "Race"
        if sub_label not in PACCA_BUCKETS:
            raise ValueError(f"page {pno}: unknown sub-column {sub_label!r} under {rounds[-1]['name']}")
        rounds[-1]["cols"].append((PACCA_BUCKETS[sub_label], i))

    sessions = [{"session_index": n, "event_name": r["event"], "session_name": r["name"],
                 } for n, r in enumerate(rounds, 1)]

    out = []
    prev_pos = prev_total = None
    for raw in data[round_ri + (2 if has_sub else 1):]:
        cells = [" ".join((c or "").split()) for c in raw]
        name = cells[name_idx] if name_idx < len(cells) else ""
        total_raw = cells[total_idx] if total_idx < len(cells) else ""
        if not name or not _PACCA_NUM_RE.match(total_raw):
            continue
        total = _pacca_num(total_raw)

        # Tied competitors share one merged position cell, whose glyph can land
        # on any of its rows (or between two rulings and on none). Numbering is
        # dense — the sheet prints 14, 14, 15 — so a blank cell is the previous
        # row's position on equal points and the next position otherwise.
        pos_raw = cells[0]
        if pos_raw.isdigit():
            position = int(pos_raw)
        elif prev_pos is not None:
            position = prev_pos if total == prev_total else prev_pos + 1
        else:
            raise ValueError(f"page {pno}: first row {name!r} has no printed position")
        prev_pos, prev_total = position, total

        points = []
        running = 0.0
        for n, r in enumerate(rounds, 1):
            vals = {"race": 0.0, "pole": 0.0, "fastest_lap": 0.0}
            status = ""
            for bucket, col in r["cols"]:
                v = cells[col] if col < len(cells) else ""
                if not v:
                    continue
                if _PACCA_NUM_RE.match(v):
                    vals[bucket] += _pacca_num(v)
                elif v in PACCA_SENTINEL_STATUS:
                    # The Race cell carries the session's fate; a '-' in a bonus
                    # column is just an empty cell.
                    if bucket == "race":
                        status = PACCA_SENTINEL_STATUS[v]
                else:
                    raise ValueError(f"page {pno}: unreadable cell {v!r} in {r['name']!r} for {name!r}")
            round_total = sum(vals.values())
            running += round_total
            points.append({
                "session_index": n,
                "total_points": _emit(round_total),
                "race_points": _emit(vals["race"]),
                "bonus_points": 0,
                "pole_points": _emit(vals["pole"]),
                "fastest_lap_points": _emit(vals["fastest_lap"]),
                "penalty_points": 0,
                "status": status,
            })
        if abs(running - total) > 1e-6:
            raise ValueError(
                f"page {pno}: {title} #{position} {name!r} "
                f"cells sum to {_emit(running)} but the sheet prints {_emit(total)}"
            )

        # The team sheet has no car-number column, so the team's own name is the
        # key; its trailing '#' is the entry list's Dealer Trophy marker, not
        # part of the name. Driver pages key on the driver, like the IMSA sheet.
        name = re.sub(r"\s*#$", "", name)
        out.append(({
            "position": position,
            "car_number": None,
            "name": name,
            "total": _emit(total),
            "key": name,
            "team": name if is_team_sheet else "",
        }, points))
    return title, title_year, sessions, out


def _year_of(pdf, override):
    if override:
        return str(override)
    # The page text is not a reliable source (a points value can look like a
    # year), so fall back to when the sheet was generated. A full-season sheet
    # republished in January would need --year.
    raw = (pdf.metadata or {}).get("CreationDate", "")
    m = re.search(r"D:(\d{4})", raw)
    return m.group(1) if m else ""


_STANDINGS_LINE = re.compile(r"^(.+?)\s+-\s+Championship Points Standings\b")


def _title(page) -> str:
    """The championship a page belongs to.

    Most IMSA sheets put the whole title on line 1 ("IMSA WeatherTech
    SportsCar Championship GTP Drivers") and a bare "Championship Points
    Standings OFFICIAL" below. The 2025 Carrera Cup North America sheet puts
    only the series on line 1 and names the championship on the standings
    line — "Masters Drivers - Championship Points Standings OFFICIAL" — so
    every page shared one title and a whole season's championships collapsed
    into one (and failed on the first page whose columns differed).
    """
    lines = [l.strip() for l in (page.extract_text() or "").split("\n")]
    title = lines[0] if lines else ""
    for line in lines[1:6]:
        m = _STANDINGS_LINE.match(line)
        if m:
            return f"{title} {m.group(1).strip()}".strip()
    return title


def parse(path: Path, year: int | None = None) -> dict:
    """PDF -> the points.json contract. Raises if any row fails its checksum."""
    pdf = pdfplumber.open(path)
    by_title = {}
    for pno, page in enumerate(pdf.pages, 1):
        ruled = _parse_ruled_page(page, pno)
        if ruled is not None:
            title, title_year, sessions, rows = ruled
            if not title:
                continue
            entry = by_title.setdefault(
                title, {"sessions": sessions, "rows": [], "year": title_year})
            if [s["session_name"] for s in entry["sessions"]] != [s["session_name"] for s in sessions]:
                raise ValueError(f"page {pno}: column layout differs from earlier {title!r} page")
            entry["rows"].extend(rows)
            continue

        cols = _columns(page)
        if not cols:
            continue  # not a standings grid
        title = _title(page)
        if not title:
            continue
        sessions = _sessions(cols)
        entry = by_title.setdefault(title, {"sessions": sessions, "rows": []})
        # A championship can run over several pages; later pages repeat the same
        # column layout and continue the classification.
        if [s["session_name"] for s in entry["sessions"]] != [s["session_name"] for s in sessions]:
            raise ValueError(f"page {pno}: column layout differs from earlier {title!r} page")
        try:
            rows = _parse_rows(page, cols)
        except ValueError as e:
            raise ValueError(f"page {pno}: {title!r}: {e}") from None
        if not rows:
            continue  # an overflow page: header and legend, no competitor rows
        for row in rows:
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
            # on the driver's name and leaves the name blank. The ruled path
            # precomputes both (its team sheet has no car-number column).
            if "key" in row:
                key, team = row["key"], row["team"]
            else:
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
                # A PACCA title leads with its season ("2026 Porsche ..."),
                # which beats guessing from the PDF's creation date.
                "year": str(year) if year else entry.get("year") or _year_of(pdf, None),
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
