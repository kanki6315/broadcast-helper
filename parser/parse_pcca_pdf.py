"""Parse a Porsche Carrera Cup Asia timing PDF into JSON (the generic PCCA parser).

Carrera Cup Asia rounds away from Formula 1 weekends are timed by Time Service
Asia (timingresult.com / results.timing.asia). Their sheets share one family
look — a header block naming the event, dates and "<Circuit> - <N>mtr.", a
"Final result <session>" title, then one line per car — in a few variants
this parser reads by their column header:

* **Race classification** — "Final result Race 1" / "Final Result Round 7".
  Columns Pos Nbr Name (NAT) [Entrant] Cls PIC Gap Total-time Fastest In
  Speed. Gaps are to the leader of the current lap block: a "-- 13 laps --"
  in the gap column starts a block (the leader, a lapped car, or the first
  retirement), and later gaps in that block are measured to it. A "Not
  Classified" heading starts the retirements, whose Pos column reads DNF.
* **Qualifying classification** — "Final result Qualifying 1", "Final Result
  Qualifying Combined" (Bangsaen's groups merged on best lap). Columns Pos
  Nbr Name (NAT) [Entrant] Cls PIC Fastest In [Gap Diff] Laps, then Km/h or
  the second-best time.
* **Combined qualifying** — "Final Combined Qualifying 1 & Qualifying 2"
  (Sepang: the endurance race's grid from the sum of both sessions). Columns
  Pos Nbr Cls PIC Name (NAT) Qual-1 Qual-2 Total Gap; a "Not Qualified"
  heading marks cars outside 107%, still placed.
* **Starting grid** — "Final starting grid Round 7". Either one car per line
  (Bangsaen: no team, no class) or two-up with the teams on the line below
  (Sepang), split by which side of the "Row N" label each word sits.

The entrant is either on the car's own line between the nationality and the
class (Bangsaen) or alone on the next line (Sepang: "Name / Entrant"). A
trailing "#" on it marks a Porsche Dealer Trophy entry and is dropped. A "*"
after a name is the entry list's "non series registered" marker, kept as
``non_series``.

The output is the F1 sidecar's shape (see parse_f1_pdf.py) so the importer
maps both with one mapper. Names split at the first space, the way the
Carrera Cup Asia entry list stores them ("LING" / "Kang", "Marco" /
"GILTRAP"). The weekend's race number comes from the round list in the
header ("ROUNDS 9&10&11") or, failing that, the file name ("Bangsaen Rounds
7&8"); without either it is null and the reviewer picks the session.
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

TITLE_RE = re.compile(r"^Final\s+(?:result|starting\s+grid|combined)\b", re.IGNORECASE)
GRID_TITLE_RE = re.compile(r"^Final\s+starting\s+grid\s+(?P<kind>Race|Round)\s+(?P<n>\d+)", re.IGNORECASE)
RACE_TITLE_RE = re.compile(r"^Final\s+result\s+(?P<kind>Race|Round)\s+(?P<n>\d+)", re.IGNORECASE)
QUALI_TITLE_RE = re.compile(r"^Final\s+(?:result\s+|combined\s+)?Qualifying(?P<rest>.*)$", re.IGNORECASE)
ROUNDS_RE = re.compile(r"\bRounds?\s+(?P<list>\d+(?:\s*(?:&|,|and)\s*\d+)+)", re.IGNORECASE)
CIRCUIT_RE = re.compile(r"^(?P<loc>.+?)\s+-\s+(?P<m>\d+)\s*mtr\.?$")
DATES_RE = re.compile(r"\b\d{1,2}\s*(?:[A-Za-z]+\s*)?[-–]\s*\d{1,2}\s+[A-Za-z]+\s+(?P<year>\d{4})\b")
SERIES_PREFIX_RE = re.compile(r"^(?:Combined Qualifications\s*-\s*)?Porsche\s+ARbox\s+Carrera\s+Cup\s+Asia\s*",
                              re.IGNORECASE)

CLASSES = "Pro-Am|Pro|Am|Masters"
STATUS_WORDS = "DNF|DNS|DSQ|DQ|NC|EXC"
# A car line: position (or a retirement status), car number, optionally the
# class + PIC before the name (combined qualifying), then the name ending in a
# bracketed nationality. Everything after the nationality is the "rest".
CAR_RE = re.compile(
    rf"^(?P<pos>\d+|{STATUS_WORDS})\s+(?P<nbr>\d+)\s+"
    rf"(?:(?P<cls1>{CLASSES})\s+(?P<pic1>\d+)\s+)?"
    r"(?P<name>[^()]+?)\s*(?P<star>\*)?\s*\((?P<nat>[A-Z]{3})\)\s*(?P<rest>.*)$")
# The rest of a car line: an optional inline entrant, the class and PIC, then
# only numbers ("1:46.328", "0.547"), "--" and "laps".
REST_RE = re.compile(
    rf"^(?P<team>.*?)\s*(?:\b(?P<cls>{CLASSES})(?:\s+(?P<pic>\d+))?)?"
    r"(?P<tail>(?:\s*(?:\d[\d:.]*|--|laps?))*)\s*$", re.IGNORECASE)
GRID_CAR_RE = re.compile(r"(?P<pos>\d+)\s+(?P<nbr>\d+)\s+(?P<name>[^\d]+?)\s*(?P<star>\*)?\s+(?P<time>\d+:\d{2}\.\d{3})")
LAP_TIME_RE = re.compile(r"^\d{1,2}:\d{2}\.\d{3}$")
SECONDS_RE = re.compile(r"^\d+(?:\.\d+)?$")
COUNTS_RE = re.compile(r"Started\s*:\s*(?P<started>\d+).*?Classified\s*:\s*(?P<classified>\d+)"
                       r"\s*Not\s+Classified\s*:\s*(?P<nc>\d+)", re.IGNORECASE)
# A "Car 969" / "CAR #22" / "Car55" / "Car #8 & Car #12" reference in a note.
NOTE_CAR_RE = re.compile(r"\bcar\s*#?\s*(\d+)", re.IGNORECASE)
NOTE_START_RE = re.compile(r"^(?:SD\s*\d+|Car\s*#?\s*\d+|CAR\b|Steward'?s?\s+Decision)", re.IGNORECASE)
# Footer lines end the table and any notes.
FOOTER_RE = re.compile(r"^(?:Fastest time|Publication-time|Results:|Page \d+|Chairman|Chief Steward|Ri+kki)",
                       re.IGNORECASE)


# ---------------------------------------------------------------- helpers

def _split_name(name: str) -> tuple[str, str]:
    """First word / the rest — the entry list's split ("LING" / "Kang")."""
    words = name.split()
    if len(words) == 1:
        return "", words[0]
    return words[0], " ".join(words[1:])


def _team(text: str | None) -> tuple[str | None, bool]:
    """'Team Jebsen #' -> ('Team Jebsen', True)."""
    if not text:
        return None, False
    text = " ".join(text.split())
    dealer = text.endswith("#")
    text = text.rstrip("#").strip()
    return text or None, dealer


def _seconds(text: str) -> float:
    parts = text.split(":")
    total = 0.0
    for p in parts:
        total = total * 60 + float(p)
    return total


def _fmt_gap(seconds: float) -> str:
    if seconds >= 60:
        m, s = divmod(seconds, 60)
        return f"+{int(m)}:{s:06.3f}"
    return f"+{seconds:.3f}"


def _tail_tokens(tail: str) -> list[str]:
    return tail.split()


def _rounds(header_text: str, filename: str | None) -> list[int]:
    for source in (header_text, filename or ""):
        m = ROUNDS_RE.search(source)
        if m:
            return [int(n) for n in re.findall(r"\d+", m.group("list"))]
    return []


# ---------------------------------------------------------------- header

def _header(lines: list[str]) -> dict:
    """Event name, location and year from the lines above the title."""
    title_idx = next((i for i, l in enumerate(lines) if TITLE_RE.match(l)), None)
    if title_idx is None:
        raise ValueError("not a Carrera Cup Asia timing PDF: no 'Final result / starting grid' title")
    head = lines[:title_idx]
    if not any("carrera cup asia" in l.lower() for l in head):
        raise ValueError("not a Carrera Cup Asia timing PDF: no series name above the title")
    location = None
    year = None
    for line in head:
        stripped = SERIES_PREFIX_RE.sub("", line).strip()
        if m := CIRCUIT_RE.match(stripped):
            location = m.group("loc").strip()
        if m := DATES_RE.search(line):
            year = int(m.group("year"))
    event = SERIES_PREFIX_RE.sub("", head[0]).strip() or head[0]
    # "Bangsaen Grand Prix 2026 1 - 5 July 2026" carries the dates on the title line.
    event = DATES_RE.sub("", event).strip()
    return {"event": event, "location": location, "year": year,
            "title": lines[title_idx], "title_idx": title_idx, "head": " ".join(head)}


# ---------------------------------------------------------------- tables

def _page_lines(pdf) -> list[list[str]]:
    return [(p.extract_text() or "").split("\n") for p in pdf.pages]


def _body(page: list[str]) -> tuple[list[str], list[str]]:
    """(table lines, note lines) of one page: below the column header, above the footer."""
    start = next((i for i, l in enumerate(page) if re.match(r"^Pos\s+N[bu]?[mb]?r?\b", l, re.IGNORECASE)), None)
    if start is None:
        return [], []
    table, notes = [], []
    in_notes = False
    for line in page[start + 1:]:
        if FOOTER_RE.match(line):
            break
        if NOTE_START_RE.match(line):
            in_notes = True
        if in_notes:
            notes.append(line)
        else:
            table.append(line)
    return table, notes


def _join_notes(lines: list[str]) -> list[str]:
    """Notes, a wrapped line folded into the one it continues, deduplicated
    (multi-page sheets repeat them on every page)."""
    out: list[str] = []
    for line in lines:
        line = line.replace("⁠", "").strip()
        if out and not NOTE_START_RE.match(line):
            out[-1] = f"{out[-1]} {line}"
        else:
            out.append(line)
    seen, unique = set(), []
    for n in out:
        key = re.sub(r"\s+", "", n).lower()
        if key not in seen:
            seen.add(key)
            unique.append(n)
    return unique


def _car_rows(pages: list[list[str]]) -> tuple[list[dict], list[str], bool]:
    """Every car line with its entrant, in order, plus the notes. Returns
    (cars, notes, two_line) where each car carries the raw tail tokens and the
    block it sits in (classified / not classified / not qualified)."""
    cars: list[dict] = []
    notes: list[str] = []
    two_line = False
    block = "main"
    for page in pages:
        header = next((l for l in page if re.match(r"^Pos\s+N", l, re.IGNORECASE)), "")
        two_line = two_line or "/ Entrant" in header or "/Entrant" in header
        table, page_notes = _body(page)
        notes += page_notes
        for line in table:
            line = line.replace("⁠", "").strip()
            if not line:
                continue
            if re.match(r"^Not\s+Classified", line, re.IGNORECASE):
                block = "not_classified"
                continue
            if re.match(r"^Not\s+Qualified", line, re.IGNORECASE):
                block = "not_qualified"
                continue
            m = CAR_RE.match(line)
            if m:
                rest = REST_RE.match(m.group("rest"))
                if rest is None:
                    raise ValueError(f"unreadable car line: {line!r}")
                cls = m.group("cls1") or rest.group("cls")
                pic = m.group("pic1") or rest.group("pic")
                team, dealer = _team(rest.group("team")) if not two_line else (None, False)
                cars.append({
                    "pos": m.group("pos"), "number": m.group("nbr"),
                    "name": " ".join(m.group("name").split()), "star": bool(m.group("star")),
                    "nat": m.group("nat"), "class": cls, "pic": int(pic) if pic else None,
                    "team": team, "dealer": dealer, "tokens": _tail_tokens(rest.group("tail")),
                    "block": block,
                })
                continue
            if re.match(rf"^(?:{STATUS_WORDS})\s+\d+\s", line) or re.match(r"^\d+\s+\d+\s+\S", line) and "(" in line:
                raise ValueError(f"unreadable car line: {line!r}")
            # An entrant line, below its car (also the first line of a page
            # whose car sat at the foot of the previous one).
            if two_line and cars and cars[-1]["team"] is None:
                cars[-1]["team"], cars[-1]["dealer"] = _team(line)
    return cars, _join_notes(notes), two_line


def _penalised(notes: list[str]) -> set[str]:
    return {n for note in notes for n in NOTE_CAR_RE.findall(note)}


def _base(car: dict, penalised: set[str]) -> dict:
    first, surname = _split_name(car["name"])
    return {
        "position": int(car["pos"]) if car["pos"].isdigit() else None,
        "number": car["number"],
        "first_name": first,
        "surname": surname,
        "class": car["class"],
        "penalty": car["number"] in penalised,
        "non_series": car["star"],
        "nationality": car["nat"],
        "team": car["team"],
        "dealer_trophy": car["dealer"],
    }


# ---------------------------------------------------------------- documents

def _race(cars: list[dict], penalised: set[str]) -> tuple[list[dict], int | None]:
    rows = []
    leader_laps = None
    block_laps = None
    block_is_leaders = False  # whether gaps in the current block are to the race leader
    leader_time = None  # the race leader's total time, in seconds
    for car in cars:
        t = list(car["tokens"])
        gap_to_block = None
        printed_gap = None
        if t[:1] == ["--"]:
            # "-- 13 laps --" opens a block: this car leads it.
            if len(t) < 4 or not t[1].isdigit():
                raise ValueError(f"car {car['number']}: unreadable lap block {' '.join(t)!r}")
            block_laps = int(t[1])
            t = t[4:]
            gap_to_block = 0.0
        elif t and SECONDS_RE.match(t[0]) or (t and LAP_TIME_RE.match(t[0]) and len(t) in (3, 5)):
            gap_to_block = _seconds(t[0])
            printed_gap = t[0]
            t = t[1:]
        total = fastest = lap_no = kph = None
        if len(t) == 4:
            total, fastest, lap_no, kph = t
        elif len(t) == 2:
            total, kph = t
        elif t:
            raise ValueError(f"car {car['number']}: unexpected timing columns {' '.join(car['tokens'])!r}")
        laps = block_laps if t or gap_to_block is not None else None

        row = _base(car, penalised)
        classified = car["block"] == "main" and row["position"] is not None
        if leader_laps is None and classified:
            leader_laps = laps
            leader_time = _seconds(total) if total else None
        if gap_to_block == 0.0:
            block_is_leaders = classified and row["position"] == 1
        gap = None
        if classified and row["position"] != 1 and laps is not None:
            if laps < leader_laps:
                n = leader_laps - laps
                gap = f"{n} Lap" if n == 1 else f"{n} Laps"
            elif printed_gap is not None and block_is_leaders:
                gap = "+" + printed_gap  # measured to the race leader, as printed
            elif total and leader_time is not None:
                gap = _fmt_gap(_seconds(total) - leader_time)
        row.update({
            "classified": classified,
            "status": None if car["pos"].isdigit() else car["pos"],
            "laps": laps,
            "time": total,
            "gap": gap,
            "interval": None,
            "kph": float(kph) if kph else None,
            "fastest_lap": fastest if fastest and LAP_TIME_RE.match(fastest) else None,
            "fastest_lap_number": int(lap_no) if lap_no and lap_no.isdigit() else None,
        })
        rows.append(row)
    return rows, leader_laps


def _quali(cars: list[dict], penalised: set[str], combined_sum: bool) -> list[dict]:
    rows = []
    for car in cars:
        t = car["tokens"]
        row = _base(car, penalised)
        row["classified"] = row["position"] is not None
        row["status"] = None if car["pos"].isdigit() else car["pos"]
        if combined_sum:
            # Qual-1, Qual-2, Total[, Gap]: the best lap is the faster session's.
            laps_times = [x for x in t[:2] if LAP_TIME_RE.match(x)]
            best = min(laps_times, key=_seconds) if laps_times else None
            other = next((x for x in laps_times if x != best), None)
            row.update({"time": best, "second_time": other, "laps": None,
                        "qualifying_1": t[0] if len(t) > 0 else None,
                        "qualifying_2": t[1] if len(t) > 1 else None,
                        "total_time": t[2] if len(t) > 2 else None,
                        "not_qualified": car["block"] == "not_qualified"})
        else:
            # Fastest In [Gap Diff] Laps (Km/h | 2nd time); a car with no
            # time has an empty tail.
            time = second = laps = None
            if len(t) == 6:
                time, _, _, _, laps, last = t
            elif len(t) == 4:
                time, _, laps, last = t
            elif t:
                raise ValueError(f"car {car['number']}: unexpected timing columns {' '.join(t)!r}")
            else:
                last = None
            if last and LAP_TIME_RE.match(last):
                second = last
            row.update({"time": time, "second_time": second,
                        "laps": int(laps) if laps and laps.isdigit() else None})
        rows.append(row)
    return rows


def _grid_slot(text: str, penalised: set[str]) -> dict | None:
    m = GRID_CAR_RE.fullmatch(" ".join(text.split()))
    if not m:
        return None
    first, surname = _split_name(" ".join(m.group("name").split()))
    return {"position": int(m.group("pos")), "number": m.group("nbr"),
            "first_name": first, "surname": surname, "class": None,
            "penalty": m.group("nbr") in penalised, "non_series": bool(m.group("star")),
            "team": None, "dealer_trophy": False, "time": m.group("time")}


def _two_up_grid(words: list[dict], penalised: set[str]) -> list[dict]:
    """Sepang's two-up grid: each "Row N" label sits between two cars, whose
    words straddle it by a few points (the name rides higher than the time),
    with both entrants on a line just below. Anchoring on the label keeps a
    car's words and its entrant's apart where a top-tolerance clustering
    can't. Empty when the sheet has no such rows."""
    slots = []
    labels = [(i, w) for i, w in enumerate(words) if w["text"] == "Row"]
    for _, label in labels:
        num = next((w for w in words if w is not label and w["text"].isdigit()
                    and abs(w["top"] - label["top"]) <= 1 and 0 < w["x0"] - label["x1"] < 20), None)
        if num is None:
            continue
        band = [w for w in words if abs(w["top"] - label["top"]) <= 4 and w is not label and w is not num]
        below = [w for w in words if 5 < w["top"] - label["top"] <= 10]
        sides = [([w for w in band if w["x1"] <= label["x0"]], [w for w in below if w["x1"] <= label["x0"] + 40]),
                 ([w for w in band if w["x0"] >= num["x1"]], [w for w in below if w["x0"] > label["x0"] + 40])]
        for car_words, team_words in sides:
            if not car_words:
                continue
            slot = _grid_slot(" ".join(w["text"] for w in sorted(car_words, key=lambda w: w["x0"])), penalised)
            if slot is None:
                continue
            slot["team"], slot["dealer_trophy"] = _team(
                " ".join(w["text"] for w in sorted(team_words, key=lambda w: w["x0"])))
            slots.append(slot)
    return slots


def _grid(pdf, penalised: set[str]) -> list[dict]:
    """Grid slots: the two-up layout when the sheet has it, else one car per
    line (Bangsaen, with no entrants)."""
    slots = []
    for page in pdf.pages:
        words = page.extract_words()
        two_up = _two_up_grid(words, penalised)
        if two_up:
            slots += two_up
            continue
        rows: list[tuple[float, list[dict]]] = []
        for w in sorted(words, key=lambda w: w["top"]):
            if rows and abs(w["top"] - rows[-1][0]) <= 2.5:
                rows[-1][1].append(w)
            else:
                rows.append((w["top"], [w]))
        rows = [(top, sorted(ws, key=lambda w: w["x0"])) for top, ws in rows]
        pending: list[tuple[float, dict]] = []  # (x0 of the slot's name, slot) awaiting entrants
        for top, ws in rows:
            line = " ".join(w["text"] for w in ws)
            if FOOTER_RE.match(line) or NOTE_START_RE.match(line):
                pending = []
                continue
            row_label = next((i for i, w in enumerate(ws) if w["text"] == "Row"), None)
            # Split the line at the "Row N" label: left of it, right of it.
            if row_label is not None and row_label + 1 < len(ws) and ws[row_label + 1]["text"].isdigit():
                halves = [ws[:row_label], ws[row_label + 2:]]
            else:
                halves = [ws]
            found = []
            for half in halves:
                text = " ".join(w["text"] for w in half)
                m = GRID_CAR_RE.fullmatch(text.strip()) if text.strip() else None
                if m:
                    first, surname = _split_name(" ".join(m.group("name").split()))
                    slot = {"position": int(m.group("pos")), "number": m.group("nbr"),
                            "first_name": first, "surname": surname, "class": None,
                            "penalty": m.group("nbr") in penalised, "non_series": bool(m.group("star")),
                            "team": None, "dealer_trophy": False, "time": m.group("time")}
                    slots.append(slot)
                    found.append((half[0]["x0"], slot))
            if found:
                pending = found
                continue
            if pending and not re.match(r"^(?:Row\s+\d+|Pos\s|Name|Entrant|POLE|Pole|S T A R T|-)", line):
                # The entrants under the cars just read: each word to the slot
                # whose column it falls in.
                if len(pending) == 1:
                    groups = [ws]
                else:
                    cut = pending[1][0] - 1
                    groups = [[w for w in ws if w["x0"] < cut], [w for w in ws if w["x0"] >= cut]]
                for (_, slot), group in zip(pending, groups):
                    if group and slot["team"] is None:
                        slot["team"], slot["dealer_trophy"] = _team(" ".join(w["text"] for w in group))
                pending = []
    slots.sort(key=lambda s: s["position"])
    return slots


def parse(pdf_path: Path, filename: str | None = None) -> dict:
    filename = filename or pdf_path.name
    with pdfplumber.open(pdf_path) as pdf:
        pages = _page_lines(pdf)
        header = _header(pages[0])
        title = header["title"]
        rounds = _rounds(header["head"], filename)
        notes: list[str] = []

        if m := GRID_TITLE_RE.match(title):
            for page in pages:
                notes += [l for l in page if NOTE_START_RE.match(l)]
            notes = _join_notes(notes)
            rows = _grid(pdf, _penalised(notes))
            doc = {"document": "GRID"}
            kind, n = m.group("kind").lower(), int(m.group("n"))
        elif m := RACE_TITLE_RE.match(title):
            cars, notes, _ = _car_rows(pages)
            rows, laps = _race(cars, _penalised(notes))
            doc = {"document": "RACE", "laps": laps}
            kind, n = m.group("kind").lower(), int(m.group("n"))
            counts = COUNTS_RE.search(" ".join(pages[0][:header["title_idx"] + 3]))
            if counts:
                classified = sum(1 for r in rows if r["classified"])
                not_classified = len(rows) - classified
                if (classified, not_classified) != (int(counts.group("classified")), int(counts.group("nc"))):
                    raise ValueError(
                        f"read {classified} classified + {not_classified} not classified, but the sheet "
                        f"says {counts.group('classified')} + {counts.group('nc')}")
        elif m := QUALI_TITLE_RE.match(title):
            cars, notes, _ = _car_rows(pages)
            combined_sum = any("Qual-1" in l for page in pages for l in page[:12])
            rows = _quali(cars, _penalised(notes), combined_sum)
            doc = {"document": "QUALIFYING"}
            rest = m.group("rest").strip()
            if combined_sum or rest.lower().startswith("combined"):
                session, number = "Qualifying Combined", 1
            elif rest.isdigit():
                session, number = f"Qualifying {rest}", int(rest)
            else:
                session, number = "Qualifying", 1
            kind = None
        else:
            raise ValueError(f"unrecognised session title: {title!r}")

    if not rows:
        raise ValueError(f"no rows found under '{title}'")
    numbers = [r["number"] for r in rows]
    dupes = sorted({x for x in numbers if numbers.count(x) > 1})
    if dupes:
        raise ValueError(f"car numbers appear twice: {', '.join(dupes)}")

    if kind is not None:
        # "Race 1" is already the weekend's race; "Round 10" is the season's
        # round, placed in the weekend by the round list when there is one.
        if kind == "race":
            race, round_no = n, (rounds[n - 1] if 0 < n <= len(rounds) else None)
        else:
            race, round_no = (rounds.index(n) + 1 if n in rounds else None), n
        session = f"Race {race}" if race else f"Round {n}"
        number = race
    doc.update({
        "event": header["event"],
        "location": header["location"],
        "year": header["year"],
        "session": session,
        "race": number,
        "round": round_no if kind is not None else None,
        "status": "Final",
        "revised": False,
        "notes": "\n".join(notes) or None,
        "rows": rows,
    })
    doc.setdefault("laps", None)
    return doc


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Parse a Carrera Cup Asia (Time Service Asia) timing PDF to JSON.")
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
    sys.exit(main())
