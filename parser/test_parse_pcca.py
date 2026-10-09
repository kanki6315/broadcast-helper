"""Carrera Cup Asia (Time Service Asia) timing PDF parser tests, real 2026 sheets.

Bangsaen puts each car's entrant on its own line and has no round list in its
header (the file name supplies it); Sepang puts the entrant on the line below,
spans two pages, and adds a combined (Q1 + Q2) qualifying and a two-up grid.
"""
from pathlib import Path

import pytest

import parse_pcca_pdf as p

SAMPLES = Path(__file__).parent / "samples"
BANGSAEN_RACE = SAMPLES / "2026_PCCA_Bangsaen Rounds 7&8_Results_R7.pdf"
BANGSAEN_QUALI = SAMPLES / "2026_PCCA_Bangsaen Rounds 7&8_Qualifying_Combined.pdf"
BANGSAEN_GRID = SAMPLES / "2026_PCCA_Bangsaen Rounds 7&8_Grid_R8.pdf"
SEPANG_RACE = SAMPLES / "2026_PCCA_Sepang_Results_R9.pdf"
SEPANG_RACE_10 = SAMPLES / "2026_PCCA_Sepang_Results_R10.pdf"
SEPANG_QUALI = SAMPLES / "2026_PCCA_Sepang_Qualifying_1.pdf"
SEPANG_COMBINED = SAMPLES / "2026_PCCA_Sepang_Qualifying_Combined.pdf"
SEPANG_GRID = SAMPLES / "2026_PCCA_Sepang_Grid_R11.pdf"

pytestmark = pytest.mark.skipif(not SEPANG_RACE.exists(), reason="sample PDFs not present")


def by_number(doc, number):
    return next(r for r in doc["rows"] if r["number"] == number)


# ---------------------------------------------------------------- race

def test_inline_entrant_race_with_lapped_cars_and_retirements():
    doc = p.parse(BANGSAEN_RACE)
    assert (doc["document"], doc["session"], doc["race"], doc["round"]) == ("RACE", "Race 1", 1, 7)
    assert (doc["location"], doc["year"], doc["laps"]) == ("Bang Saen", 2026, 14)
    assert doc["event"] == "Bangsaen Grand Prix 2026"
    rows = doc["rows"]
    assert [r["position"] for r in rows if r["classified"]] == list(range(1, 21))

    amand = rows[0]
    assert (amand["first_name"], amand["surname"], amand["class"]) == ("Marcus", "AMAND", "Pro")
    assert (amand["team"], amand["dealer_trophy"]) == ("BWT Shanghai Yonda", True)
    assert (amand["laps"], amand["time"], amand["gap"], amand["fastest_lap"], amand["fastest_lap_number"]) == \
        (14, "22:15.754", None, "1:33.821", 11)

    # Gaps are printed to the leader of each lap block; a lapped block reads in laps.
    assert by_number(doc, "89")["gap"] == "+4.788"
    assert (by_number(doc, "16")["laps"], by_number(doc, "16")["gap"]) == (13, "1 Lap")
    assert (by_number(doc, "11")["laps"], by_number(doc, "11")["gap"]) == (12, "2 Laps")
    # Family name first stays in the entry list's split.
    assert (by_number(doc, "610")["first_name"], by_number(doc, "610")["surname"]) == ("YANG", "Haojie")
    assert by_number(doc, "610")["non_series"] is True

    wood = by_number(doc, "65")  # retired with no time at all
    assert (wood["classified"], wood["status"], wood["laps"], wood["time"]) == (False, "DNF", None, None)
    wu = by_number(doc, "969")
    assert (wu["status"], wu["laps"], wu["time"]) == ("DNF", 5, "11:14.912")


def test_entrant_below_the_car_across_two_pages():
    doc = p.parse(SEPANG_RACE)
    assert (doc["session"], doc["race"], doc["round"], doc["location"]) == ("Race 1", 1, 9, "Sepang")
    assert len(doc["rows"]) == 29 and sum(r["classified"] for r in doc["rows"]) == 27
    assert all(r["team"] for r in doc["rows"])
    assert by_number(doc, "66")["team"] == "Team Porsche New Zealand"
    # The second retirement's gap is to the first; its laps are the block's.
    lim = by_number(doc, "89")
    assert (lim["status"], lim["laps"], lim["time"]) == ("DNF", 4, "9:37.511")
    # Steward decisions are the notes; the cars they name carry the flag.
    assert doc["notes"].startswith("SD07 - Car 969")
    assert len(doc["notes"].splitlines()) == 4  # repeated on page 2, kept once
    assert {r["number"] for r in doc["rows"] if r["penalty"]} == {"969", "55", "610", "22"}


def test_round_number_maps_to_the_weekends_race():
    doc = p.parse(SEPANG_RACE_10)
    assert (doc["session"], doc["race"], doc["round"]) == ("Race 2", 2, 10)
    # The entrant printed at the top of page 2 belongs to page 1's last car.
    assert all(r["team"] for r in doc["rows"])


# ---------------------------------------------------------------- qualifying

def test_single_session_qualifying():
    doc = p.parse(SEPANG_QUALI)
    assert (doc["document"], doc["session"], doc["race"]) == ("QUALIFYING", "Qualifying 1", 1)
    rows = doc["rows"]
    assert [r["position"] for r in rows] == list(range(1, 30))
    assert (rows[0]["time"], rows[0]["laps"]) == ("2:08.300", 6)
    assert (rows[1]["time"], rows[1]["laps"]) == ("2:08.306", 6)
    assert by_number(doc, "65")["laps"] == 7


def test_combined_best_lap_qualifying_keeps_the_second_time():
    doc = p.parse(BANGSAEN_QUALI)
    assert doc["session"] == "Qualifying Combined"
    azlan = doc["rows"][0]
    assert (azlan["time"], azlan["second_time"], azlan["laps"], azlan["team"]) == \
        ("1:46.328", "1:50.408", 11, "Team Porsche Malaysia")
    sawer = by_number(doc, "12")  # placed, no time
    assert (sawer["position"], sawer["time"]) == (26, None)


def test_summed_combined_qualifying():
    doc = p.parse(SEPANG_COMBINED)
    assert doc["session"] == "Qualifying Combined"
    giltrap = doc["rows"][0]
    assert (giltrap["class"], giltrap["qualifying_1"], giltrap["qualifying_2"], giltrap["total_time"]) == \
        ("Pro", "2:08.300", "2:07.579", "4:15.879")
    assert (giltrap["time"], giltrap["second_time"]) == ("2:07.579", "2:08.300")  # best lap first
    azlan = by_number(doc, "17")
    assert (azlan["position"], azlan["not_qualified"]) == (29, True)
    assert not any(r["not_qualified"] for r in doc["rows"][:28])


# ---------------------------------------------------------------- grid

def test_two_up_grid_splits_cars_and_entrants_at_the_row_label():
    doc = p.parse(SEPANG_GRID)
    assert (doc["document"], doc["session"], doc["race"], doc["round"]) == ("GRID", "Race 3", 3, 11)
    rows = doc["rows"]
    assert [r["position"] for r in rows] == list(range(1, 30))
    assert (rows[0]["number"], rows[0]["team"], rows[0]["time"]) == ("66", "Team Porsche New Zealand", "4:15.579")
    assert (rows[1]["number"], rows[1]["team"], rows[1]["non_series"]) == ("3", "BWT Shanghai Yonda", True)
    assert by_number(doc, "79")["team"] == "Phantom Global Racing"
    assert (rows[-1]["number"], rows[-1]["team"]) == ("17", "Team Porsche Malaysia")
    assert by_number(doc, "17")["penalty"] is True  # "SD06 - Car #17 - Start ... from the back"


def test_one_car_per_line_grid_without_entrants():
    doc = p.parse(BANGSAEN_GRID)
    # No round list in the header: the file name places Round 8 as race 2.
    assert (doc["session"], doc["race"], doc["round"]) == ("Race 2", 2, 8)
    rows = doc["rows"]
    assert [r["position"] for r in rows] == list(range(1, 26))
    assert (rows[0]["number"], rows[0]["first_name"], rows[0]["surname"], rows[0]["time"]) == \
        ("3", "Marcus", "AMAND", "1:49.128")
    assert all(r["team"] is None for r in rows)
    assert rows[-1]["number"] == "8" and rows[-1]["penalty"] is True


# ---------------------------------------------------------------- errors

def test_rejects_other_pdfs():
    other = SAMPLES / "2026_PCCA_Shanghai_Grid_R1.pdf"  # an F1-paddock sheet
    with pytest.raises(ValueError, match="not a Carrera Cup Asia timing PDF"):
        p.parse(other)


# ---------------------------------------------------------------- Fuji (Japanese Excel export)

FUJI_RACE = SAMPLES / "2026_PCCA_Fuji Rounds 5&6_Results_R5.pdf"
FUJI_QUALI = SAMPLES / "2026_PCCA_Fuji Rounds 5&6_Qualifying_1.pdf"
FUJI_GRID = SAMPLES / "2026_PCCA_Fuji Rounds 5&6_Grid_R5.pdf"


def test_fuji_race_by_column_position():
    doc = p.parse(FUJI_RACE)
    assert (doc["layout"], doc["document"], doc["session"], doc["round"]) == ("fuji", "RACE", "Race 1", 5)
    assert (doc["location"], doc["year"], doc["laps"]) == ("Fuji Speedway", 2026, 15)
    rows = doc["rows"]
    assert sum(r["classified"] for r in rows) == 25  # "Finish :25"
    amand = rows[0]
    assert (amand["time"], amand["fastest_lap"], amand["fastest_lap_number"], amand["kph"]) == \
        ("25:12.035", "1:39.953", 5, 162.237)
    # The text run glues nationality to entrant ("MOZWetrade"); columns don't.
    dias = by_number(doc, "8")
    assert (dias["nationality"], dias["team"]) == ("MOZ", "Wetrade X Phantom Global")
    # A penalised total prints its pre-penalty time beneath and drops the
    # printed gap; the gap comes from the totals instead.
    ling = by_number(doc, "87")
    assert (ling["time"], ling["gap"], ling["penalty"]) == ("25:38.489", "+26.454", True)
    # The full-width typo in "Poｓrche Vietnam" reads as the real team.
    assert by_number(doc, "12")["team"] == "Porsche Vietnam"
    tan = by_number(doc, "89")  # not classified, still has its laps
    assert (tan["classified"], tan["laps"], tan["position"]) == (False, 4, None)
    wang = by_number(doc, "22")
    assert (wang["status"], wang["classified"]) == ("DSQ", False)


def test_fuji_qualifying_course_record_times():
    doc = p.parse(FUJI_QUALI)
    assert (doc["session"], doc["race"]) == ("Qualifying 1", 1)
    assert [r["position"] for r in doc["rows"]] == list(range(1, 31))
    # "R1'41.767" (course record) starts inside the entrant column.
    wang = by_number(doc, "22")
    assert (wang["time"], wang["team"]) == ("1:41.767", "Origine Motorsport")
    assert all(r["time"] for r in doc["rows"])


def test_fuji_staggered_grid():
    doc = p.parse(FUJI_GRID)
    assert (doc["document"], doc["session"], doc["round"]) == ("GRID", "Race 1", 5)
    rows = doc["rows"]
    assert [r["position"] for r in rows] == list(range(1, 31))
    assert all(r["team"] for r in rows)
    assert (rows[0]["number"], rows[0]["first_name"], rows[0]["surname"], rows[0]["team"]) == \
        ("88", "Marvin", "KLEIN", "Absolute Racing")
    # The far column (25-30) keeps its names despite "69" in a team name.
    assert (rows[29]["number"], rows[29]["team"]) == ("969", "Porsche Own Retail 69 Team")
    # The steward note printed beside row 5 names car 66 and joins no slot.
    assert by_number(doc, "66")["penalty"] is True
    assert by_number(doc, "66")["team"] == "Team Porsche New Zealand"


# ---------------------------------------------------------------- Zhuhai (Chinese timing system)

ZHUHAI_RACE = SAMPLES / "2026_PCCA_Zhuhai Rounds 3&4_Results_R4.pdf"
ZHUHAI_QUALI = SAMPLES / "2026_PCCA_Zhuhai Rounds 3&4_Qualifying_2.pdf"
ZHUHAI_GRID = SAMPLES / "2026_PCCA_Zhuhai Rounds 3&4_Grid_R4.pdf"


def test_zhuhai_race():
    doc = p.parse(ZHUHAI_RACE)
    # No English venue on the sheet: the file name names it.
    assert (doc["layout"], doc["session"], doc["round"], doc["location"]) == ("zhuhai", "Race 2", 4, "Zhuhai")
    rows = doc["rows"]
    assert len(rows) == 30 and sum(r["classified"] for r in rows) == 25
    winner = rows[0]
    assert (winner["number"], winner["time"], winner["gap"], winner["fastest_lap"], winner["non_series"]) == \
        ("88", "30:29.937", None, "1:37.053", True)
    assert (rows[1]["gap"], rows[1]["interval"]) == ("+0.566", "+0.566")
    giltrap = by_number(doc, "66")  # DNF after one lap, no best lap
    assert (giltrap["status"], giltrap["laps"], giltrap["time"], giltrap["fastest_lap"]) == \
        ("DNF", 1, "1:52.958", None)
    assert all(r["team"] for r in rows)
    assert by_number(doc, "77")["penalty"] is True


def test_zhuhai_qualifying_across_pages():
    doc = p.parse(ZHUHAI_QUALI)
    assert (doc["session"], doc["race"]) == ("Qualifying 2", 2)
    rows = doc["rows"]
    assert [r["position"] for r in rows] == list(range(1, 31))
    assert (rows[0]["number"], rows[0]["time"], rows[0]["laps"]) == ("17", "1:35.401", 4)
    assert (rows[-1]["number"], rows[-1]["team"]) == ("69", "OpenRoad Racing")


def test_zhuhai_two_up_grid_reads_each_half():
    doc = p.parse(ZHUHAI_GRID)
    assert (doc["session"], doc["round"]) == ("Race 2", 4)
    rows = doc["rows"]
    assert [r["position"] for r in rows] == list(range(1, 31))
    assert all(r["time"] for r in rows)
    assert (rows[1]["number"], rows[1]["surname"], rows[1]["time"]) == ("3", "AMAND", "1:35.712")
    assert (rows[-1]["number"], rows[-1]["penalty"]) == ("99", True)  # dropped five places
