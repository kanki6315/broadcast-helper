#!/usr/bin/env python3
"""Parse an IMSA entry-list PDF into the entries.json contract.

This is the isolated PDF-parsing sidecar described in PLAN.md §4: its only job
is `entry-list.pdf -> entries.json`. The Java app invokes it as a subprocess at
ingest time and owns everything downstream (mapping to entry/lineup/driver,
dedup, persistence). The two communicate only via the JSON documented in
SCHEMA.md, so this tool stays swappable and testable in isolation.

Usage:
    python parse_entry_list.py INPUT.pdf [-o OUTPUT.json] [--series IWSC] [--strict]

With no -o, JSON is written to stdout (summary goes to stderr).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

import pdfplumber

# --- patterns ---------------------------------------------------------------

# Class section header, e.g. "GRAND TOURING PROTOTYPE (GTP) Entries:11"
CLASS_HEADER_RE = re.compile(r"^(?P<name>.+?)\s+Entries:\s*(?P<count>\d+)\s*$")
# Pro-series driver line, e.g. "(G) Tijmen van der Helm NLD"  /  "(?) TBD ?"
DRIVER_RE = re.compile(r"^\((?P<rating>[A-Z?])\)\s+(?P<name>.+?)\s+(?P<nat>[A-Z]{3}|\?)$")
# Challenge-series driver line: optional rating, name, then hometown after " / ".
# e.g. "(S) Oscar Tunjo / Cali, Colombia"  /  "Max Stallone / Austin, TX"
DRIVER_HOMETOWN_RE = re.compile(
    r"^(?:\((?P<rating>[A-Z?])\)\s+)?(?P<name>.+?)\s*/\s*(?P<hometown>.+)$"
)
# One-make driver line: name then a 3-letter nationality, no rating, no hometown.
# e.g. "Jim Farley USA" (Mustang Challenge). Checked after the hometown form so
# a "Name / Hometown" line is never mistaken for a nationality.
DRIVER_NAT_RE = re.compile(r"^(?P<name>.+?)\s+(?P<nat>[A-Z]{3})$")
# A TBD placeholder seat, with or without a rating prefix: "TBD" / "(?) TBD".
TBD_RE = re.compile(r"^(?:\([A-Z?]\)\s+)?TBD$", re.IGNORECASE)
# A bare car number cell (preserve leading zeros: 04, 033, 912)
CAR_NO_RE = re.compile(r"^\d{1,3}$")

VALID_RATINGS = {"P", "G", "S", "B"}

# Small raster icons the challenge-series lists render next to a driver/entry,
# explained by the printed legend. Keyed by the md5 of the image XObject's raw
# stream bytes — the same icon reuses one XObject, so the hash is a stable id,
# independent of the (slightly varying) render size. "bronze_cup" is scoped to
# the ENTRY by the legend ("entry is eligible for the Bronze Cup"); the rest are
# per-driver ("driver is a Driver Coach / rookie / Invitational Entry").
ICON_MARKERS = {
    "4a583a88": "bronze_cup",    # trophy
    "1643cf33": "coach",         # red C
    "c1b3a47c": "rookie",        # red R
    "ff562c76": "invitational",  # blue V
}

MONTHS = {
    "january": 1, "february": 2, "march": 3, "april": 4, "may": 5, "june": 6,
    "july": 7, "august": 8, "september": 9, "october": 10, "november": 11,
    "december": 12,
}


# --- helpers ----------------------------------------------------------------

def class_code(name: str) -> str:
    """Derive a short class code from the section name.

    "GRAND TOURING PROTOTYPE (GTP)" -> "GTP"
    "GT DAYTONA PRO (GTD Pro)"      -> "GTD PRO"
    Falls back to the upper-cased name if there's no parenthetical.
    """
    m = re.search(r"\(([^)]+)\)\s*$", name)
    return (m.group(1) if m else name).strip().upper()


def join_wrapped(cell: str | None) -> str | None:
    """Rejoin a cell that PDF layout wrapped across lines.

    Honors hyphenated wraps ("Cadillac V-" + "Series.R" -> "Cadillac V-Series.R")
    and otherwise joins with a single space.
    """
    if not cell:
        return None
    out = ""
    for seg in (s.strip() for s in cell.split("\n") if s.strip()):
        if not out:
            out = seg
        elif out.endswith("-"):
            out += seg
        else:
            out += " " + seg
    return out or None


def parse_drivers(cell: str | None) -> list[dict]:
    """Split the multi-line driver cell into ordered, structured records.

    Handles both entry-list layouts: the pro series' "(rating) Name NAT" and the
    challenge series' "[(rating)] Name / Hometown" (Michelin Pilot, VP Racing,
    MX-5 Cup), where drivers carry a hometown instead of a 3-letter nationality.
    """
    drivers: list[dict] = []
    order = 0
    for line in (cell or "").split("\n"):
        line = line.strip()
        if not line:
            continue
        order += 1

        m = DRIVER_RE.match(line)
        if m:
            rating = m.group("rating")
            nat = m.group("nat")
            name = m.group("name").strip()
            drivers.append({
                "order": order,
                "rating": rating if rating in VALID_RATINGS else None,
                "name": name,
                "nationality": None if nat == "?" else nat,
                "hometown": None,
                "is_tbd": rating == "?" or name.upper() == "TBD",
            })
            continue

        if TBD_RE.match(line):
            drivers.append({
                "order": order, "rating": None, "name": "TBD",
                "nationality": None, "hometown": None, "is_tbd": True,
            })
            continue

        hm = DRIVER_HOMETOWN_RE.match(line)
        if hm:
            rating = hm.group("rating")
            name = hm.group("name").strip()
            drivers.append({
                "order": order,
                "rating": rating if rating in VALID_RATINGS else None,
                "name": name,
                "nationality": None,
                "hometown": hm.group("hometown").strip(),
                "is_tbd": rating == "?" or name.upper() == "TBD",
            })
            continue

        nm = DRIVER_NAT_RE.match(line)
        if nm:
            name = nm.group("name").strip()
            drivers.append({
                "order": order,
                "rating": None,
                "name": name,
                "nationality": nm.group("nat"),
                "hometown": None,
                "is_tbd": name.upper() == "TBD",
            })
            continue

        # Keep the raw text rather than dropping it; flag for review.
        drivers.append({
            "order": order, "rating": None, "name": line, "nationality": None,
            "hometown": None, "is_tbd": False, "unparsed": True,
        })
    return drivers


# A sheared text matrix means synthetic-italic. IMSA renders sponsors this way
# using the SAME font as the team name, so the c-component of the char matrix —
# not the font name — is the reliable italic signal.
_ITALIC_SHEAR = 0.5


def classify_team_lines(page, bbox) -> list[tuple[str, bool]] | None:
    """Return [(line_text, is_italic), ...] for the team/sponsor cell.

    Sponsors are rendered in italic (a matrix shear); the team name is upright.
    Splitting on the shear is robust — it doesn't assume the team fits on one
    line. Returns None if the cell can't be read, so the caller can fall back.
    """
    try:
        chars = page.crop(bbox).chars
    except Exception:
        return None
    if not chars:
        return None
    lines: dict[int, list[dict]] = {}
    for c in chars:
        lines.setdefault(round(c["top"]), []).append(c)
    out: list[tuple[str, bool]] = []
    for top in sorted(lines):
        cs = sorted(lines[top], key=lambda c: c["x0"])
        text = "".join(c["text"] for c in cs).strip()
        if not text:
            continue
        # matrix is (a, b, c, d, e, f); a non-zero c-component is the italic shear.
        sheared = sum(1 for ch in cs if abs(ch.get("matrix", (0, 0, 0, 0, 0, 0))[2]) > _ITALIC_SHEAR)
        out.append((text, sheared > len(cs) / 2))
    return out or None


def split_team_sponsor(cell_text: str | None, team_lines) -> tuple[str | None, str | None]:
    """Team = non-italic lines, sponsor = italic lines. Falls back to the
    proven "first line is the team, the rest is sponsor" heuristic when no font
    info is available."""
    if team_lines:
        team = " ".join(t for t, it in team_lines if not it).strip()
        sponsor = " ".join(t for t, it in team_lines if it).strip()
        if team:
            return team, (sponsor or None)
    parts = [s.strip() for s in (cell_text or "").split("\n") if s.strip()]
    if not parts:
        return None, None
    return parts[0], (" ".join(parts[1:]) or None)


def small_icons(page) -> list:
    """Marker-sized raster icons on a page (excludes logos/banners by size)."""
    return [im for im in page.images
            if round(im["width"]) < 40 and round(im["height"]) < 40]


def _icon_marker(image) -> str | None:
    try:
        h = hashlib.md5(image["stream"].get_rawdata()).hexdigest()[:8]
    except Exception:
        return None
    return ICON_MARKERS.get(h)


def detect_marker_lines(page, cell_bbox, icons) -> dict[int, list[str]]:
    """Map 1-based driver-line index -> marker names for one drivers cell.

    Each icon sits in the drivers column just left of a name; we assign it to the
    text line it vertically aligns with. That line order matches the order the
    cell text is split into, so it lines up with parse_drivers' `order`. Legend
    icons live outside any entry's cell bbox and are naturally excluded.
    """
    if not cell_bbox:
        return {}
    x0, top, x1, bottom = cell_bbox
    tops = sorted({round(c["top"]) for c in page.chars
                   if x0 - 1 <= c["x0"] <= x1 and top - 1 <= c["top"] <= bottom + 1})
    out: dict[int, list[str]] = {}
    for im in icons:
        marker = _icon_marker(im)
        if not marker:
            continue
        center = (im["top"] + im["bottom"]) / 2
        if not (top - 2 <= center <= bottom + 2 and x0 - 2 <= im["x0"] <= x1):
            continue
        if not tops:
            continue
        idx = min(range(len(tops)), key=lambda i: abs(tops[i] - im["top"]))
        if abs(tops[idx] - im["top"]) > 10:
            continue
        out.setdefault(idx + 1, []).append(marker)
    return out


def parse_event_header(page) -> dict:
    """Pull event name, circuit, location, dates and total entries from page 1."""
    text = page.extract_text() or ""
    lines = [l.strip() for l in text.split("\n") if l.strip()]
    event: dict = {}
    if lines:
        event["name"] = lines[0]
    if len(lines) > 1 and " - " in lines[1]:
        circuit, _, location = lines[1].partition(" - ")
        event["circuit"] = circuit.strip()
        event["location"] = location.strip()

    m = re.search(r"Total Entries:\s*(\d+)", text)
    if m:
        event["total_entries"] = int(m.group(1))

    # "June 25 - June 28, 2026"  or  "May 30 - June 1, 2026"
    dm = re.search(
        r"([A-Za-z]+)\s+(\d{1,2})\s*[-–]\s*(?:([A-Za-z]+)\s+)?(\d{1,2}),\s*(\d{4})",
        text,
    )
    if dm:
        sm, sd, em, ed, yr = dm.groups()
        smn = MONTHS.get(sm.lower())
        emn = MONTHS.get((em or sm).lower())
        if smn and emn:
            event["start_date"] = f"{yr}-{smn:02d}-{int(sd):02d}"
            event["end_date"] = f"{yr}-{emn:02d}-{int(ed):02d}"
    return event


def detect_series(path: Path) -> str | None:
    name = path.name.upper()
    for code in ("IWSC", "IMPC", "VPRC", "MX5", "MC"):
        if code in name:
            return code
    return None


# --- main parse -------------------------------------------------------------

def parse(pdf_path: Path, series: str | None) -> dict:
    entries: list[dict] = []
    current_name: str | None = None
    current_code: str | None = None
    current_order: int | None = None
    # First-appearance order of each class section in the PDF (1-based), so the
    # consolidated view can group classes in the document's authoritative order.
    class_order: dict[str, int] = {}

    with pdfplumber.open(str(pdf_path)) as pdf:
        event = parse_event_header(pdf.pages[0])
        for page in pdf.pages:
            page_icons = small_icons(page)
            for table in page.find_tables():
                data = table.extract()
                for ri, row in enumerate(data):
                    first = (row[0] or "").strip()
                    header = first.split("\n")[0]
                    cm = CLASS_HEADER_RE.match(header)
                    if cm:
                        current_name = cm.group("name").strip()
                        current_code = class_code(current_name)
                        if current_code not in class_order:
                            class_order[current_code] = len(class_order) + 1
                        current_order = class_order[current_code]
                        continue
                    if CAR_NO_RE.match(first) and len(row) > 1 and row[1]:
                        team_lines = None
                        marker_lines: dict[int, list[str]] = {}
                        try:
                            cells = table.rows[ri].cells
                            if cells[2]:
                                team_lines = classify_team_lines(page, cells[2])
                            marker_lines = detect_marker_lines(page, cells[1], page_icons)
                        except (IndexError, TypeError):
                            pass
                        team, sponsor = split_team_sponsor(
                            row[2] if len(row) > 2 else None, team_lines
                        )
                        drivers = parse_drivers(row[1])
                        # Split the icons: the Bronze Cup is per-entry; coach /
                        # rookie / invitational stay on the driver they mark.
                        bronze_cup = False
                        for d in drivers:
                            marks = marker_lines.get(d["order"], [])
                            if "bronze_cup" in marks:
                                bronze_cup = True
                                marks = [m for m in marks if m != "bronze_cup"]
                            d["markers"] = marks
                        entries.append({
                            "class_name": current_name,
                            "class_code": current_code,
                            "class_order": current_order,
                            "car_number": first,
                            "team": team,
                            "sponsor": sponsor,
                            "bronze_cup": bronze_cup,
                            "car_type": join_wrapped(row[3] if len(row) > 3 else None),
                            "tire": join_wrapped(row[4] if len(row) > 4 else None),
                            "engine": join_wrapped(row[5] if len(row) > 5 else None),
                            "fuel": join_wrapped(row[6] if len(row) > 6 else None),
                            "drivers": drivers,
                        })

    event["series"] = series or detect_series(pdf_path)
    event["source_file"] = pdf_path.name
    return {"event": event, "entries": entries}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Parse an IMSA entry-list PDF to JSON.")
    ap.add_argument("pdf", type=Path, help="path to the entry-list PDF")
    ap.add_argument("-o", "--output", type=Path, help="output JSON (default: stdout)")
    ap.add_argument("--series", help="series code override (IWSC/IMPC/...)")
    ap.add_argument("--indent", type=int, default=2, help="JSON indent (default 2)")
    ap.add_argument("--strict", action="store_true",
                    help="exit non-zero if entry count != header Total Entries")
    args = ap.parse_args(argv)

    if not args.pdf.exists():
        print(f"error: no such file: {args.pdf}", file=sys.stderr)
        return 2

    doc = parse(args.pdf, args.series)
    payload = json.dumps(doc, ensure_ascii=False, indent=args.indent)
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    else:
        print(payload)

    n = len(doc["entries"])
    total = doc["event"].get("total_entries")
    unparsed = sum(1 for e in doc["entries"] for d in e["drivers"] if d.get("unparsed"))
    msg = f"parsed {n} entries"
    if total is not None:
        msg += f" (header says {total})"
    if unparsed:
        msg += f"; {unparsed} unparsed driver line(s)"
    print(msg, file=sys.stderr)

    if args.strict and total is not None and n != total:
        print(f"strict: entry count {n} != header total {total}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
