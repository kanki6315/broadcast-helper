#!/usr/bin/env python3
"""Parse an IMSA pit-lane-assignments PDF into box -> car/team per series.

Sidecar to the Java backend, invoked when a pit-assignments PDF is attached to
an event. The sheet is a single-page grid: pit boxes numbered from pit out
(box 1) to pit in, one CAR # / TEAM NAME column pair per series sharing the
lane, and full-width landmark rows (penalty box, breaks, S/F line) between
boxes. The backend picks the series column that matches the event's entries;
this script just reports everything it can see.

Usage:
    python parse_pit_assignments.py INPUT.pdf

Output (stdout):
    {
      "title": "2026 MOTUL SPORTSCAR GRAND PRIX AT ROAD AMERICA ...",
      "version_note": "7/28/26 · VERSION 3",
      "series": ["IWSC", "IMPC", "LST", "PCCNA"],
      "boxes": [
        {"box": 1, "cars": {"IWSC": {"car_number": "31", "team": "Cadillac Whelen"}}},
        ...
      ],
      "landmarks": [
        {"after_box": 0, "label": "PIT OUT | >> EXIT PIT TO PADDOCK >>"},
        {"after_box": 16, "label": "BREAK"},
        ...
      ]
    }

car_number is a string and preserves leading zeros ("033"), matching the
entries.json contract; the consumer owns any normalisation.
"""
from __future__ import annotations

import argparse
import json
import logging
import re
import sys

import pdfplumber

logging.getLogger("pdfminer").setLevel(logging.ERROR)

# Vertical clustering: words on one visual row differ in `top` by <1pt
# (numbers vs. text baselines); adjacent rows sit ~9pt apart.
ROW_TOLERANCE = 3.0

BOX_NUMBER_RE = re.compile(r"^\d{1,3}$")
DATE_RE = re.compile(r"^\d{1,2}/\d{1,2}/\d{2,4}$")
VERSION_RE = re.compile(r"^VERSION\b", re.IGNORECASE)


def _cluster_rows(words: list[dict]) -> list[list[dict]]:
    rows: list[list[dict]] = []
    for w in sorted(words, key=lambda w: (w["top"], w["x0"])):
        if rows and w["top"] - rows[-1][0]["top"] <= ROW_TOLERANCE:
            rows[-1].append(w)
        else:
            rows.append([w])
    return [sorted(r, key=lambda w: w["x0"]) for r in rows]


class Layout:
    """Column geometry derived from the header row (BOX # CAR # <series> TEAM
    NAME [DATA FUEL] ... BOX #). Words are assigned to zones by their x0."""

    def __init__(self, header: list[dict]):
        self.series: list[str] = []
        # (start_x, kind, series) — kind is "car" or "team"; anything falling
        # before the first zone is the left box column, anything after the
        # right-box start is the mirrored box number.
        self.zones: list[tuple[float, str, str]] = []
        self.right_box_start: float | None = None

        i = 0
        pending_name: list[str] = []
        while i < len(header):
            text = header[i]["text"]
            if text == "CAR":
                # Car numbers are right-aligned under "CAR #": the zone runs
                # from just left of the header to just past the "#".
                hash_x1 = header[i + 1]["x1"] if i + 1 < len(header) and header[i + 1]["text"] == "#" else header[i]["x1"]
                self.zones.append((header[i]["x0"] - 10, "car", ""))
                self._pending_team_start = hash_x1 + 2
                pending_name = []
            elif text == "TEAM":
                name = " ".join(pending_name) or f"SERIES{len(self.series) + 1}"
                self.series.append(name)
                self.zones.append((self._pending_team_start, "team", name))
                # Back-fill the car zone's series now that we know the name.
                for j in range(len(self.zones) - 2, -1, -1):
                    if self.zones[j][1] == "car" and not self.zones[j][2]:
                        self.zones[j] = (self.zones[j][0], "car", name)
                        break
            elif text in ("NAME", "#"):
                pass
            elif text == "BOX":
                if self.zones:  # the mirrored right-hand box column
                    self.right_box_start = header[i]["x0"] - 8
            elif text in ("DATA", "FUEL"):
                # Logistics-only columns (IWSC data/fuel rig): give them an
                # anonymous zone so their values don't bleed into a team name.
                self.zones.append((header[i]["x0"] - 4, "ignore", ""))
            else:
                pending_name.append(text)
            i += 1

    def assign(self, word: dict) -> tuple[str, str] | None:
        """-> (kind, series) for the zone containing word.x0, None for the
        left box column, ("box", "") for the right one."""
        x = word["x0"]
        if self.right_box_start is not None and x >= self.right_box_start:
            return ("box", "")
        result = None
        for start, kind, series in self.zones:
            if x >= start:
                result = (kind, series)
            else:
                break
        return result


def _is_header(row: list[dict]) -> bool:
    texts = [w["text"] for w in row]
    return texts.count("BOX") >= 1 and "CAR" in texts and "TEAM" in texts


def parse(pdf_path) -> dict:
    title_parts: list[str] = []
    stamp_parts: list[str] = []
    boxes: dict[int, dict] = {}
    landmarks: list[dict] = []
    layout: Layout | None = None
    last_box = 0

    with pdfplumber.open(pdf_path) as pdf:
        for page in pdf.pages:
            for row in _cluster_rows(page.extract_words()):
                texts = [w["text"] for w in row]
                if layout is None:
                    if _is_header(row):
                        layout = Layout(row)
                    else:
                        title_parts.append(" ".join(texts))
                    continue

                line = " ".join(texts)
                if all(DATE_RE.match(t) for t in texts) or VERSION_RE.match(line):
                    stamp_parts.append(line)
                    continue

                if not BOX_NUMBER_RE.match(texts[0]) or layout.assign(row[0]) is not None:
                    # No left-hand box number: a full-width landmark row.
                    landmarks.append({"after_box": last_box, "label": line})
                    continue

                box = int(texts[0])
                last_box = box
                cars: dict[str, dict] = {}
                team_words: dict[str, list[str]] = {}
                mirror: int | None = None
                for w in row[1:]:
                    zone = layout.assign(w)
                    if zone is None or zone[0] == "ignore":
                        continue
                    kind, series = zone
                    if kind == "box" and BOX_NUMBER_RE.match(w["text"]):
                        mirror = int(w["text"])
                    elif kind == "car":
                        cars[series] = {"car_number": w["text"], "team": None}
                    elif kind == "team":
                        team_words.setdefault(series, []).append(w["text"])
                for series, words in team_words.items():
                    # A team with no car number (or vice versa) is a parse
                    # smell but worth surfacing rather than dropping.
                    cars.setdefault(series, {"car_number": None, "team": None})
                    cars[series]["team"] = " ".join(words)
                if mirror is not None and mirror != box:
                    print(f"box {box}: right-hand column reads {mirror}", file=sys.stderr)
                boxes[box] = {"box": box, "cars": cars}

    if layout is None:
        raise SystemExit("no pit-assignments header row (BOX # / CAR # / TEAM NAME) found")

    return {
        "title": " ".join(title_parts) or None,
        "version_note": " · ".join(stamp_parts) or None,
        "series": layout.series,
        "boxes": [boxes[b] for b in sorted(boxes)],
        "landmarks": landmarks,
    }


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("pdf", help="pit-lane-assignments PDF to parse")
    args = ap.parse_args()

    result = parse(args.pdf)
    json.dump(result, sys.stdout, indent=2)
    sys.stdout.write("\n")
    occupied = sum(1 for b in result["boxes"] if b["cars"])
    print(f"{args.pdf}: {len(result['boxes'])} boxes ({occupied} occupied), "
          f"series {', '.join(result['series'])}", file=sys.stderr)


if __name__ == "__main__":
    main()
