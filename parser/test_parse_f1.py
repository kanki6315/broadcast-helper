"""F1 support-race PDF parser tests against real Carrera Cup NA sheets.

Miami samples across three seasons cover the variations:
2023 — race penalties on page 2, lapped cars, qualifying without "%" columns,
and a two-page Revised grid with NOTES and PENALTIES; 2024 — rows printed out
of position order and long names/teams shrunk and wrapped around their row;
2026 — a NOT CLASSIFIED block and initial-only given names.
"""
from pathlib import Path

import pytest

import parse_f1_pdf as p

SAMPLES = Path(__file__).parent / "samples"
RACE_2023 = SAMPLES / "2023_PCCNA_Miami_Results_R1.pdf"
QUALI_2023 = SAMPLES / "2023_PCCNA_Miami_Qualifying.pdf"
GRID_2023 = SAMPLES / "2023_PCCNA_Miami_Grid_R2_Revised.pdf"
RACE_2024 = SAMPLES / "2024_PCCNA_Miami_Results_R1.pdf"
QUALI_2024 = SAMPLES / "2024_PCCNA_Miami_Qualifying.pdf"
RACE_2026 = SAMPLES / "2026_PCCNA_Miami_Results_R1.pdf"
QUALI_2026 = SAMPLES / "2026_PCCNA_Miami_Qualifying.pdf"
GRID_2026 = SAMPLES / "2026_PCCNA_Miami_Grid_R1.pdf"

pytestmark = pytest.mark.skipif(not RACE_2023.exists(), reason="sample PDFs not present")


@pytest.fixture(scope="module")
def race_2023():
    return p.parse(RACE_2023)


@pytest.fixture(scope="module")
def race_2026():
    return p.parse(RACE_2026)


def by_number(doc, number):
    return next(r for r in doc["rows"] if r["number"] == number)


# ---------------------------------------------------------------- race

def test_race_title(race_2023):
    assert race_2023["document"] == "RACE"
    assert race_2023["event"] == "FORMULA 1 CRYPTO.COM MIAMI GRAND PRIX 2023"
    assert race_2023["location"] == "Miami"
    assert race_2023["year"] == 2023
    assert (race_2023["session"], race_2023["race"]) == ("Race 1", 1)
    assert race_2023["status"] == "Official"
    assert race_2023["laps"] == 16
    assert race_2023["distance_km"] == 86.434


def test_race_rows_complete(race_2023):
    rows = race_2023["rows"]
    assert len(rows) == 40
    assert [r["position"] for r in rows] == list(range(1, 41))
    assert all(r["surname"] and r["class"] and r["team"] for r in rows)


def test_winner_row(race_2023):
    assert race_2023["rows"][0] == {
        "position": 1, "number": "53",
        "first_name": "Riley", "surname": "DICKINSON", "class": "P",
        "penalty": False, "team": "Kellymoss",
        "classified": True, "status": None, "laps": 16, "time": "40:34.588",
        "gap": None, "interval": None, "kph": 127.809,
        "fastest_lap": "1:58.527", "fastest_lap_number": 16,
    }


def test_gaps_use_the_json_spelling(race_2023):
    assert by_number(race_2023, "17")["gap"] == "+1.895"
    lapped = by_number(race_2023, "95")
    assert (lapped["gap"], lapped["interval"]) == ("1 Lap", "+127.031")
    assert by_number(race_2023, "9")["gap"] == "6 Laps"


def test_classified_retirement(race_2023):
    dnf = by_number(race_2023, "28")
    assert (dnf["position"], dnf["status"], dnf["laps"], dnf["gap"]) == (30, "DNF", 15, None)


def test_multi_word_surname_and_penalty(race_2023):
    row = by_number(race_2023, "4")
    assert (row["first_name"], row["surname"], row["class"], row["penalty"]) == \
        ("Elias", "DE LA TORRE", "P", True)


def test_penalties_from_page_two(race_2023):
    assert race_2023["notes"].splitlines()[0].startswith("Car 9 - 10 second time penalty")
    assert len(race_2023["notes"].splitlines()) == 4


def test_not_classified_block(race_2026):
    assert len(race_2026["rows"]) == 19
    kleck = by_number(race_2026, "78")
    assert (kleck["position"], kleck["classified"], kleck["status"], kleck["laps"]) == \
        (None, False, "DNF", 0)
    # The not-classified car sorts after every classified one.
    assert race_2026["rows"][-1]["number"] == "78"


def test_initialled_given_names(race_2026):
    assert (by_number(race_2026, "3")["first_name"], by_number(race_2026, "3")["surname"]) == \
        ("N.", "LASTOCHKIN")
    assert by_number(race_2026, "54")["class"] == "PRO-AM"
    assert by_number(race_2026, "29")["class"] == "Pro-Am"


def test_out_of_order_rows_sort_by_position():
    doc = p.parse(RACE_2024)
    assert [r["position"] for r in doc["rows"] if r["position"]] == list(range(1, 36))
    penalised = by_number(doc, "68")
    assert (penalised["position"], penalised["gap"], penalised["penalty"]) == (25, "+18.120", True)


def test_shrunk_name_joins_its_row():
    doc = p.parse(RACE_2024)
    row = by_number(doc, "69")
    assert (row["first_name"], row["surname"], row["class"]) == ("Thomas", "COLLINGWOOD", "PA")
    assert row["position"] == 26


# ---------------------------------------------------------------- qualifying

def test_qualifying_without_percent_columns():
    doc = p.parse(QUALI_2023)
    assert doc["document"] == "QUALIFYING"
    assert (doc["session"], doc["race"], doc["status"]) == ("Qualifying", 1, None)
    assert doc["rows"][0] == {
        "position": 1, "number": "53",
        "first_name": "Riley", "surname": "DICKINSON", "class": "P",
        "penalty": False, "team": "Kellymoss",
        "classified": True, "time": "1:56.014", "second_time": "1:56.293", "laps": 13,
    }
    assert doc["notes"].startswith("Car #4 - 2 fastest lap times invalidated")


def test_qualifying_second_time_drops_its_rank():
    doc = p.parse(QUALI_2026)
    pole = doc["rows"][0]
    assert (pole["number"], pole["time"], pole["second_time"], pole["laps"]) == \
        ("40", "1:55.721", "1:56.105", 14)


def test_wrapped_team_name_joins_its_row():
    doc = p.parse(QUALI_2024)
    row = by_number(doc, "5")
    assert (row["first_name"], row["surname"]) == ("Angel", "BENITEZ")
    assert row["team"] == "MOMENTOP FMS Motorsport x RGB Racing"
    assert all(r["surname"] and r["class"] for r in doc["rows"])


# ---------------------------------------------------------------- grid

def test_grid_slots():
    doc = p.parse(GRID_2026)
    assert (doc["document"], doc["session"], doc["race"], doc["revised"]) == ("GRID", "Race 1", 1, False)
    rows = doc["rows"]
    assert [r["position"] for r in rows] == list(range(1, 20))
    assert rows[0] == {
        "position": 1, "number": "40", "first_name": "Janne", "surname": "STIAK",
        "class": "PRO", "penalty": False, "team": "ACI Motorsports", "time": "1:55.721",
    }
    # Grid sheets print full given names where the results shorten them.
    assert (by_number(doc, "3")["first_name"], by_number(doc, "3")["team"]) == \
        ("Nikita", "CSM - Reis Nichols")
    # Odd final slot sits alone in the right-hand column.
    assert by_number(doc, "60")["position"] == 19


def test_revised_two_page_grid_with_notes():
    doc = p.parse(GRID_2023)
    assert (doc["race"], doc["revised"]) == (2, True)
    assert [r["position"] for r in doc["rows"]] == list(range(1, 41))
    assert by_number(doc, "74")["penalty"] is True
    assert doc["notes"].splitlines() == [
        "Cars 28, 64 - Discontinued participation",
        "Car 74 - Required to start from the back of the starting grid - Replacement engine",
    ]


# ---------------------------------------------------------------- names + errors

@pytest.mark.parametrize("text, expected", [
    ("Riley DICKINSON (P)", ("Riley", "DICKINSON", "P", False)),
    ("Kay VAN BERLO (P) *", ("Kay", "VAN BERLO", "P", True)),
    ("A. R. FERNANDES (PRO)", ("A. R.", "FERNANDES", "PRO", False)),
    ("JP. VEGA (PRO-AM)", ("JP.", "VEGA", "PRO-AM", False)),
    ("Kenton KING", ("Kenton", "KING", None, False)),
])
def test_split_driver(text, expected):
    assert p.split_driver(text) == expected


def test_rejects_other_pdfs():
    grid = SAMPLES / "2021_PCCNA_Sebring_Grid_R1.pdf"
    if not grid.exists():
        pytest.skip("IMSA grid sample not present")
    with pytest.raises(ValueError, match="F1-style"):
        p.parse(grid)
