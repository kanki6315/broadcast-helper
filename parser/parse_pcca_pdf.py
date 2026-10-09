"""Parse a Porsche Carrera Cup Asia timing PDF into JSON (the generic PCCA parser).

Carrera Cup Asia rounds away from Formula 1 weekends are timed locally, in
three layouts this parser tells apart from the first page:

* **Time Service Asia** (Bangsaen, Sepang) — described below.
* **Fuji** — a Japanese Excel export ("Round.5 2026 / 5 / 9", "Official
  classification", "Fuji Speedway(4,563m)"). One car per line at fixed column
  positions, read by x because the text run glues columns together
  ("MOZWetrade", "Masters1"); minutes are written 1'39.953, course records
  carry an R prefix, a penalised total prints its pre-penalty time beneath
  and loses its printed gap (gaps are recomputed from totals). Grids are a
  four-column stagger: a "position number" pair with the driver and entrant
  on the two lines below. Some letters are full-width ("Poｓrche").
* **Zhuhai** — a Chinese timing system ("官方成绩" results, "排位表" grid).
  Line per car with the entrant below, like Sepang, but no nationality, and
  no English venue (the file name names it). Grids are two-up, each half a
  position / "number name" / time stack, the right half a few points lower.

The Time Service Asia (timingresult.com / results.timing.asia) sheets share one family
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
import unicodedata
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


# Misspellings a sheet prints for a team that exists under its real name.
# Fuji's "Poｓrche Vietnam" (a full-width s, transposed) reads "Posrche".
TEAM_TYPOS = {"Posrche": "Porsche"}


def _team(text: str | None) -> tuple[str | None, bool]:
    """'Team Jebsen #' -> ('Team Jebsen', True)."""
    if not text:
        return None, False
    text = " ".join(TEAM_TYPOS.get(w, w) for w in text.split())
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

def _nfkc(text: str) -> str:
    """Fuji's sheets set some letters full-width ("Poｓrche Vietnam")."""
    return unicodedata.normalize("NFKC", text)


def _page_lines(pdf) -> list[list[str]]:
    return [_nfkc(p.extract_text() or "").split("\n") for p in pdf.pages]


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
        words = [{**w, "text": _nfkc(w["text"])} for w in page.extract_words()]
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


# ---------------------------------------------------------------- shared

def _ms(text: str) -> int:
    """'1:39.953' / "25'12.035" / '1.341' -> milliseconds (integer: gaps
    computed from totals must not pick up float error)."""
    text = text.replace("'", ":")
    whole, _, frac = text.partition(".")
    total = 0
    for part in whole.split(":"):
        total = total * 60 + int(part)
    return total * 1000 + int((frac + "000")[:3])


def _fmt_ms(ms: int) -> str:
    s, milli = divmod(ms, 1000)
    m, s = divmod(s, 60)
    return f"+{m}:{s:02d}.{milli:03d}" if m else f"+{s}.{milli:03d}"


def _lap_time(text: str | None) -> str | None:
    """Fuji prints minutes with an apostrophe (1'39.953) and course records
    with an R prefix; the importer wants 1:39.953."""
    if not text:
        return None
    m = re.search(r"(\d{1,2})'(\d{2}\.\d{3})", text) or re.search(r"(\d{1,2}):(\d{2}\.\d{3})", text)
    return f"{int(m.group(1))}:{m.group(2)}" if m else None


def _race_gaps(rows: list[dict]) -> int | None:
    """Gaps to the winner from laps and total times, for sheets whose printed
    gap is missing on some rows (Fuji drops it beside a time penalty)."""
    leader = next((r for r in rows if r["classified"] and r["position"] == 1), None)
    if leader is None:
        return None
    for r in rows:
        if not r["classified"] or r is leader or r["laps"] is None:
            continue
        if r["laps"] < leader["laps"]:
            n = leader["laps"] - r["laps"]
            r["gap"] = f"{n} Lap" if n == 1 else f"{n} Laps"
        elif r["time"] and leader["time"]:
            r["gap"] = _fmt_ms(_ms(r["time"]) - _ms(leader["time"]))
    return leader["laps"]


def _location_from_filename(filename: str | None) -> str | None:
    """'PCCA 2026_Zhuhai Rounds 3&4_Round 3 Official Results.pdf' -> 'Zhuhai'."""
    m = re.search(r"(?:^|_)\s*([A-Za-z][A-Za-z ]*?)\s+Rounds?\s+\d", filename or "")
    return m.group(1).strip() if m else None


def _row(number, name, star, cls, team, penalised, position=None, nat=None) -> dict:
    first, surname = _split_name(" ".join(name.split()))
    team, dealer = _team(team)
    return {"position": position, "number": number, "first_name": first, "surname": surname,
            "class": cls, "penalty": number in penalised, "non_series": bool(star),
            "nationality": nat, "team": team, "dealer_trophy": dealer}


def _words(page) -> list[dict]:
    words = page.extract_words(x_tolerance=1.5, keep_blank_chars=False)
    for w in words:
        w["text"] = _nfkc(w["text"])
    return words


def _word_rows(words: list[dict], tolerance: float = 2.0) -> list[tuple[float, list[dict]]]:
    rows: list[tuple[float, list[dict]]] = []
    for w in sorted(words, key=lambda w: w["top"]):
        if rows and w["top"] - rows[-1][0] <= tolerance:
            rows[-1][1].append(w)
        else:
            rows.append((w["top"], [w]))
    return [(top, sorted(ws, key=lambda w: w["x0"])) for top, ws in rows]


# ---------------------------------------------------------------- Time Service Asia

def _parse_tsa(pdf, pages: list[list[str]], filename: str) -> dict:
    header = _header(pages[0])
    title = header["title"]
    notes: list[str] = []
    out = {"event": header["event"], "location": header["location"], "year": header["year"],
           "rounds": _rounds(header["head"], filename), "title": title}

    if m := GRID_TITLE_RE.match(title):
        for page in pages:
            notes += [l for l in page if NOTE_START_RE.match(l)]
        notes = _join_notes(notes)
        out.update(document="GRID", rows=_grid(pdf, _penalised(notes)),
                   kind=m.group("kind").lower(), n=int(m.group("n")))
    elif m := RACE_TITLE_RE.match(title):
        cars, notes, _ = _car_rows(pages)
        rows, laps = _race(cars, _penalised(notes))
        out.update(document="RACE", rows=rows, laps=laps, kind=m.group("kind").lower(), n=int(m.group("n")))
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
        rest = m.group("rest").strip()
        out.update(document="QUALIFYING", rows=_quali(cars, _penalised(notes), combined_sum),
                   qualifying="combined" if combined_sum or rest.lower().startswith("combined") else rest)
    else:
        raise ValueError(f"unrecognised session title: {title!r}")
    out["notes"] = notes
    return out


# ---------------------------------------------------------------- Fuji (Japanese Excel export)

FUJI_TITLE_RE = re.compile(r"^(?:Round\.?\s*(?P<round>\d+)|Qualifying\s+Q(?P<q>\d+))\s+(?P<year>20\d\d)\s*/")
FUJI_CIRCUIT_RE = re.compile(r"^(?P<loc>.+?)\s*\([\d,.]+\s*m\)")
FUJI_TIME_RE = re.compile(r"R?\d{1,2}'\d{2}\.\d{3}.*")
FUJI_STOP_RE = re.compile(r"^(?:Steward|Stewards|Fastest Lap|Start Time|Entry\s*:|Chief Steward|R\s*:)",
                          re.IGNORECASE)


def _fuji_header(page) -> dict:
    lines = (page.extract_text() or "").split("\n")
    m = next((FUJI_TITLE_RE.match(l) for l in lines[:3] if FUJI_TITLE_RE.match(l)), None)
    if m is None:
        raise ValueError("not a Carrera Cup Asia timing PDF: no 'Round.N' / 'Qualifying QN' title")
    loc = next((FUJI_CIRCUIT_RE.match(l).group("loc") for l in lines[:8] if FUJI_CIRCUIT_RE.match(l)), None)
    return {"match": m, "location": loc, "year": int(m.group("year")), "lines": lines}


def _fuji_columns(rows, names: list[str]) -> dict | None:
    """x0 of each header word (first occurrence, left to right)."""
    for _, ws in rows:
        texts = [w["text"] for w in ws]
        if texts[:2] == ["Pos", "No"]:
            cols = {}
            for w in ws:
                cols.setdefault(w["text"], w["x0"])
            if all(n in cols for n in names):
                return cols
    return None


def _fuji_table(pdf, quali: bool, penalised: set[str]) -> list[dict]:
    """Fuji's classification: one car per line, columns at fixed x. Read by
    position, not by text (the text joins the nationality onto the entrant)."""
    out = []
    not_classified = False
    for page in pdf.pages:
        rows = _word_rows(_words(page))
        cols = _fuji_columns(rows, ["Class", "Driver", "Nat", "Entrant"])
        if cols is None:
            continue
        number_end = cols["Class"] - 5
        driver_x, nat_x, entrant_x = cols["Driver"] - 1, cols["Nat"] - 1, cols["Entrant"] - 1
        figures_x = (cols["Best"] if quali else cols["Lap"]) - 2
        started = False
        for top, ws in rows:
            line = " ".join(w["text"] for w in ws)
            if not started:
                started = [w["text"] for w in ws][:2] == ["Pos", "No"]
                continue
            if FUJI_STOP_RE.match(line):
                break
            if "Not Classified" in line or "Not Qualified" in line:
                not_classified = "Not Classified" in line
                continue
            pos = next((w["text"] for w in ws if w["x1"] < cols["No"] - 2), None)
            number = next((w["text"] for w in ws if cols["No"] - 4 <= w["x0"] < number_end
                           and w["text"].isdigit()), None)
            if number is None:
                continue  # a penalty sub-line ("(26'31.243 + 5sec)") or a page header
            cls = next((w["text"] for w in ws if number_end <= w["x0"] < driver_x
                        and re.fullmatch(CLASSES, w["text"])), None)
            driver = [w["text"] for w in ws if driver_x <= w["x0"] < nat_x]
            nat = next((w["text"] for w in ws if nat_x <= w["x0"] < entrant_x), None)
            # A course-record time ("R1'41.767") starts inside the entrant
            # column, so lap times are told apart by shape as well as place.
            timed = [w for w in ws if FUJI_TIME_RE.fullmatch(w["text"])]
            team = [w["text"] for w in ws if entrant_x <= w["x0"] < figures_x and w not in timed]
            # Some rows set the entrant flush against the nationality, so the
            # two come out as one word ("MOZWetrade").
            if nat and len(nat) > 3 and re.match(r"[A-Z]{3}[A-Z]?[a-z0-9]", nat):
                nat, team = nat[:3], [nat[3:]] + team
            figures = [w["text"] for w in ws if w["x0"] >= figures_x or w in timed]
            name = " ".join(driver)
            star = name.endswith("*")
            row = _row(number, name.rstrip("*"), star, cls, " ".join(team), penalised,
                       position=int(pos) if pos and pos.isdigit() else None, nat=nat)
            status = "DSQ" if "DSQ" in figures else None
            if quali:
                times = [f for f in figures if _lap_time(f)]
                row.update({"classified": row["position"] is not None, "status": status,
                            "time": _lap_time(times[0]) if times else None, "second_time": None,
                            "laps": None})
            else:
                laps = int(figures[0]) if figures and figures[0].isdigit() else None
                total = _lap_time(figures[1]) if len(figures) > 1 else None
                total = None if total is None else total  # keep minutes:seconds
                if len(figures) > 1 and re.match(r"\d+'\d{2}\.\d{3}", figures[1]):
                    t = re.match(r"(\d+)'(\d{2}\.\d{3})", figures[1])
                    total = f"{int(t.group(1))}:{t.group(2)}"
                kph = next((float(f) for f in figures if re.fullmatch(r"\d{2,3}\.\d{3}", f)), None)
                lap_times = [f for f in figures[2:] if re.fullmatch(r"R?\d{1,2}'\d{2}\.\d{3}", f)]
                best = _lap_time(lap_times[-1]) if lap_times else None
                slash = figures.index("/") if "/" in figures else None
                best_lap = int(figures[slash - 1]) if slash and figures[slash - 1].isdigit() else None
                row.update({"classified": row["position"] is not None and not not_classified,
                            "status": status, "laps": laps, "time": total, "gap": None, "interval": None,
                            "kph": kph, "fastest_lap": best, "fastest_lap_number": best_lap or None})
            out.append(row)
    return out


def _fuji_grid(pdf, penalised: set[str]) -> list[dict]:
    """Fuji's staggered grid: each slot is a position and car number side by
    side, the driver on the line below and the entrant below that, all in the
    slot's column. Notes printed beside a short column stay out of it because
    only words just below a slot, between its column and the next, join it."""
    slots = []
    for page in pdf.pages:
        words = _words(page)
        anchors = []
        for w in words:
            if not w["text"].isdigit():
                continue
            car = next((c for c in words if c is not w and c["text"].isdigit()
                        and abs(c["top"] - w["top"]) <= 2 and 35 <= c["x0"] - w["x0"] <= 55), None)
            if car is not None:
                anchors.append((w, car))
        # A real column repeats down the page; a stray digit pair (a car
        # number beside the "69" in "Porsche Own Retail 69 Team") does not.
        xs = [p["x0"] for p, _ in anchors]
        starts = sorted({round(x) for x in xs if sum(abs(x - o) <= 4 for o in xs) >= 3})
        anchors = [(p, c) for p, c in anchors if any(abs(p["x0"] - x) <= 4 for x in starts)]
        for pos, car in anchors:
            col_start = pos["x0"] - 2
            nxt = [x for x in starts if x > pos["x0"] + 5]
            col_end = (min(nxt) - 25) if nxt else page.width
            def band(lo, hi):
                return " ".join(w["text"] for w in sorted(
                    (w for w in words if lo < w["top"] - pos["top"] <= hi and col_start <= w["x0"] < col_end
                     and w is not pos and w is not car), key=lambda w: w["x0"]))
            name = band(4, 16)
            team = band(16, 28)
            if not name:
                continue
            star = name.endswith("*")
            slot = _row(car["text"], name.rstrip("*"), star, None, team, penalised, position=int(pos["text"]))
            slot["time"] = None
            slots.append(slot)
    slots.sort(key=lambda s: s["position"])
    return slots


def _parse_fuji(pdf, pages: list[list[str]], filename: str) -> dict:
    head = _fuji_header(pdf.pages[0])
    text = "\n".join("\n".join(p) for p in pages)
    # Notes start a line, except on the grid, where one sits beside a row.
    notes = _join_notes([m.group(0) for p in pages for l in p
                         if (m := re.search(r"Stewards?\s+Decision.*$", l, re.IGNORECASE))])
    penalised = _penalised(notes)
    out = {"event": f"{head['location'] or 'Fuji'} {head['year']}", "location": head["location"],
           "year": head["year"], "rounds": _rounds("", filename), "title": head["lines"][0], "notes": notes}
    m = head["match"]
    if "Official Starting Grid" in text:
        out.update(document="GRID", rows=_fuji_grid(pdf, penalised), kind="round", n=int(m.group("round")))
    elif m.group("q"):
        out.update(document="QUALIFYING", rows=_fuji_table(pdf, True, penalised), qualifying=m.group("q"))
    else:
        rows = _fuji_table(pdf, False, penalised)
        out.update(document="RACE", rows=rows, laps=_race_gaps(rows), kind="round", n=int(m.group("round")))
        counts = re.search(r"Start\s*:\s*(\d+)\s+Finish\s*:\s*(\d+)", text)
        if counts and sum(r["classified"] for r in rows) != int(counts.group(2)):
            raise ValueError(f"read {sum(r['classified'] for r in rows)} classified, "
                             f"but the sheet says Finish : {counts.group(2)}")
    return out


# ---------------------------------------------------------------- Zhuhai (Chinese timing system)

ZH_SESSION_RE = re.compile(r"^Porsche Carrera Cup Asia\s*-\s*(?:Round\s+(?P<round>\d+)|Qualifying\s+(?P<q>\d+))",
                           re.IGNORECASE)
ZH_CAR_RE = re.compile(
    rf"^(?P<pos>\d+|{STATUS_WORDS})\s+(?P<nbr>\d+)\s+(?P<name>.+?)\s*(?P<star>\*)?\s+"
    rf"(?P<cls>{CLASSES})\s+(?P<pic>\d+|{STATUS_WORDS})(?P<tail>(?:\s+\S+)*)\s*$")
ZH_STOP_RE = re.compile(r"^(?:比赛场地|仲裁|第\s*\d+\s*页|\d+-\(|\$\()")
ZH_NOTE_RE = re.compile(r"^(?:\d+\.\s*)?Stewards?\s+Decision", re.IGNORECASE)


def _zh_cars(pages: list[list[str]], penalised: set[str], quali: bool) -> list[dict]:
    out = []
    for page in pages:
        start = next((i for i, l in enumerate(page) if l.startswith("Pos No")), None)
        if start is None:
            continue
        not_classified = False
        for line in page[start + 1:]:
            if ZH_STOP_RE.match(line) or ZH_NOTE_RE.match(line) or re.match(r"^#\d+\s*\+", line):
                break
            if line.lower().startswith("not classified"):
                not_classified = True
                continue
            m = ZH_CAR_RE.match(line)
            if m is None:
                if out and out[-1]["team"] is None:
                    out[-1]["team"], out[-1]["dealer_trophy"] = _team(line)
                continue
            pos = m.group("pos")
            row = _row(m.group("nbr"), m.group("name"), m.group("star"), m.group("cls"), None, penalised,
                       position=int(pos) if pos.isdigit() else None)
            t = m.group("tail").split()
            gaps = [x for x in t if x.startswith("+")]
            plain = [x for x in t if not x.startswith("+")]
            status = None if pos.isdigit() else pos
            if quali:
                # [+Diff +Gap] laps best
                row.update({"classified": row["position"] is not None, "status": status,
                            "laps": int(plain[0]) if plain and plain[0].isdigit() else None,
                            "time": _lap_time(plain[1]) if len(plain) > 1 else None, "second_time": None})
            else:
                # laps total [+Diff +Gap] best in
                laps = int(plain[0]) if plain and plain[0].isdigit() else None
                total = plain[1] if len(plain) > 1 and LAP_TIME_RE.match(plain[1]) or \
                    (len(plain) > 1 and re.fullmatch(r"\d+:\d{2}:\d{2}\.\d{3}|\d+:\d{2}\.\d{3}", plain[1])) else None
                best = plain[2] if len(plain) > 2 and LAP_TIME_RE.match(plain[2]) else None
                lap_no = int(plain[3]) if len(plain) > 3 and plain[3].isdigit() else None
                row.update({"classified": row["position"] is not None and not not_classified, "status": status,
                            "laps": laps, "time": total, "gap": gaps[0] if gaps and row["position"] else None,
                            "interval": gaps[1] if len(gaps) > 1 and row["position"] else None, "kph": None,
                            "fastest_lap": best, "fastest_lap_number": lap_no})
            row["team"] = None
            out.append(row)
    return out


def _zh_grid(pdf, penalised: set[str]) -> list[dict]:
    """Zhuhai's two-up grid. Each half of the page runs position, then "number
    name", then time, one line each; the right half sits a few points lower
    than the left and the row label on its own line at the far left, so each
    half is read on its own rather than line by line across the page."""
    slots = []
    for page in pdf.pages:
        mid = page.width / 2
        words = [w for w in _words(page) if w["x0"] > 60 and w["top"] > 150]
        for side in ([w for w in words if w["x0"] < mid], [w for w in words if w["x0"] >= mid]):
            position = None
            last = None
            for _, ws in _word_rows(side, tolerance=2.0):
                texts = [w["text"] for w in ws]
                if NOTE_START_RE.match(" ".join(texts)) or ZH_STOP_RE.match(" ".join(texts)):
                    break
                if len(texts) == 1 and texts[0].isdigit():
                    position = int(texts[0])
                elif len(texts) >= 2 and texts[0].isdigit() and position is not None:
                    name = " ".join(texts[1:])
                    star = name.endswith("*")
                    last = _row(texts[0], name.rstrip("*"), star, None, None, penalised, position=position)
                    last["time"] = None
                    slots.append(last)
                    position = None
                elif last is not None and len(texts) == 1 and LAP_TIME_RE.match(texts[0]):
                    last["time"] = texts[0]
                    last = None
    slots.sort(key=lambda s: s["position"])
    return slots


def _parse_zhuhai(pdf, pages: list[list[str]], filename: str) -> dict:
    first = pages[0]
    m = next((ZH_SESSION_RE.match(l) for l in first[:4] if ZH_SESSION_RE.match(l)), None)
    if m is None:
        raise ValueError("not a Carrera Cup Asia timing PDF: no 'Porsche Carrera Cup Asia - <session>' title")
    year = next((int(y.group(1)) for l in first[:3] if (y := re.match(r"^(20\d\d)\b", l))), None)
    location = _location_from_filename(filename)
    notes = _join_notes([re.sub(r"^\d+\.\s*", "", l) for p in pages for l in p if ZH_NOTE_RE.match(l)])
    penalised = _penalised(notes)
    out = {"event": f"{location or 'Zhuhai'} {year}", "location": location, "year": year,
           "rounds": _rounds("", filename), "title": first[1], "notes": notes}
    if any("排位表" in l for l in first[:6]):
        out.update(document="GRID", rows=_zh_grid(pdf, penalised), kind="round", n=int(m.group("round")))
    elif m.group("q"):
        out.update(document="QUALIFYING", rows=_zh_cars(pages, penalised, True), qualifying=m.group("q"))
    else:
        rows = _zh_cars(pages, penalised, False)
        out.update(document="RACE", rows=rows, laps=next((r["laps"] for r in rows if r["position"] == 1), None),
                   kind="round", n=int(m.group("round")))
    return out


# ---------------------------------------------------------------- entry point

def _layout(pages: list[list[str]]) -> str:
    head = pages[0][:8]
    if any(l in ("官方成绩", "排位表") for l in head):
        return "zhuhai"
    if any(FUJI_TITLE_RE.match(l) for l in head[:3]) and any(l.startswith("Official") for l in head):
        return "fuji"
    return "tsa"


def parse(pdf_path: Path, filename: str | None = None) -> dict:
    filename = filename or pdf_path.name
    with pdfplumber.open(pdf_path) as pdf:
        pages = _page_lines(pdf)
        layout = _layout(pages)
        out = {"tsa": _parse_tsa, "fuji": _parse_fuji, "zhuhai": _parse_zhuhai}[layout](pdf, pages, filename)

    rows = out["rows"]
    if not rows:
        raise ValueError(f"no rows found under '{out['title']}'")
    numbers = [r["number"] for r in rows]
    dupes = sorted({x for x in numbers if numbers.count(x) > 1})
    if dupes:
        raise ValueError(f"car numbers appear twice: {', '.join(dupes)}")

    rounds = out["rounds"]
    round_no = None
    if out["document"] == "QUALIFYING":
        q = str(out.get("qualifying") or "").strip()
        if q == "combined":
            session, number = "Qualifying Combined", 1
        elif q.isdigit():
            session, number = f"Qualifying {q}", int(q)
        else:
            session, number = "Qualifying", 1
    else:
        # "Race 1" is already the weekend's race; "Round 10" is the season's
        # round, placed in the weekend by the round list when there is one.
        kind, n = out["kind"], out["n"]
        if kind == "race":
            number, round_no = n, (rounds[n - 1] if 0 < n <= len(rounds) else None)
        else:
            number, round_no = (rounds.index(n) + 1 if n in rounds else None), n
        session = f"Race {number}" if number else f"Round {n}"
    return {
        "document": out["document"],
        "layout": layout,
        "event": out["event"],
        "location": out["location"],
        "year": out["year"],
        "session": session,
        "race": number,
        "round": round_no,
        "status": "Final",
        "revised": False,
        "laps": out.get("laps"),
        "notes": "\n".join(out["notes"]) or None,
        "rows": rows,
    }


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
