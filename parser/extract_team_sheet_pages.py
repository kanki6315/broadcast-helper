#!/usr/bin/env python3
"""Extract the car-number -> page map from a spotter/team-sheets PDF.

Companion sidecar to parse_entry_list.py (PLAN.md §4): the Java app invokes it
when a team-sheets PDF is attached to an event, and stores the returned map so
the sheet page can deep-link each entry row to its team's first page.

The IMSA team-sheets layout puts the car number on the first text line of every
page (the team's later pages — driver bios — repeat it), so the map is simply
the first page on which each number appears. Pages that don't start with a car
number (covers, notes) are skipped.

Usage:
    python extract_team_sheet_pages.py INPUT.pdf

Output (stdout):
    {
      "page_count": 64,
      "cars": [
        {"car_number": "04", "team": "CrowdStrike Racing by APR", "page": 3},
        ...
      ]
    }

car_number is a string and preserves leading zeros, matching the entries.json
contract; the consumer owns any normalisation ("04" vs "4").
"""
from __future__ import annotations

import argparse
import json
import logging
import re
import sys

import pdfplumber

# Team-sheet fonts often ship broken descriptors; the resulting pdfminer
# warnings would flood stderr, which the Java caller surfaces on failure.
logging.getLogger("pdfminer").setLevel(logging.ERROR)

# A car number alone on the first line ("2", "04"), or leading a combined
# "04 CrowdStrike Racing by APR" line in layouts that merge the header.
NUMBER_LINE_RE = re.compile(r"^(?P<number>\d{1,3})(?:\s+(?P<rest>\S.*))?$")


def extract(pdf_path: str) -> dict:
    cars: dict[str, dict] = {}
    with pdfplumber.open(pdf_path) as pdf:
        for page_no, page in enumerate(pdf.pages, start=1):
            lines = [ln.strip() for ln in (page.extract_text() or "").splitlines()]
            lines = [ln for ln in lines if ln]
            if not lines:
                continue
            m = NUMBER_LINE_RE.match(lines[0])
            if not m:
                continue
            number = m.group("number")
            if number in cars:
                continue  # later pages of the same team (driver bios)
            team = m.group("rest") or (lines[1] if len(lines) > 1 else None)
            cars[number] = {"car_number": number, "team": team, "page": page_no}
        return {"page_count": len(pdf.pages), "cars": list(cars.values())}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("pdf", help="team-sheets PDF to map")
    args = ap.parse_args()

    result = extract(args.pdf)
    json.dump(result, sys.stdout, indent=2)
    sys.stdout.write("\n")
    print(f"{args.pdf}: {result['page_count']} pages, {len(result['cars'])} cars", file=sys.stderr)


if __name__ == "__main__":
    main()
