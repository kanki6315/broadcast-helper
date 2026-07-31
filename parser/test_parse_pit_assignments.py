"""Pit-lane-assignments parser tests against the 2026 Road America V3 sheet.

The sample exercises the full template: four series sharing the lane, boxes
with any subset of series present, IWSC-only DATA/FUEL columns (which must be
ignored), landmark rows, and the date + VERSION footer.
"""
from pathlib import Path

import pytest

import parse_pit_assignments as p

HERE = Path(__file__).parent
ROAD_AMERICA = HERE / "samples" / "2026_RoadAmerica_PitLaneAssignments_V3.pdf"

pytestmark = pytest.mark.skipif(not ROAD_AMERICA.exists(), reason="sample PDF not present")


@pytest.fixture(scope="module")
def sheet():
    return p.parse(ROAD_AMERICA)


def by_box(sheet, box):
    return next(b["cars"] for b in sheet["boxes"] if b["box"] == box)


def test_all_boxes_and_series(sheet):
    assert [b["box"] for b in sheet["boxes"]] == list(range(1, 67))
    assert sheet["series"] == ["IWSC", "IMPC", "LST", "PCCNA"]


def test_metadata(sheet):
    assert "ROAD AMERICA" in sheet["title"]
    assert sheet["version_note"] == "7/28/26 · VERSION 3"


def test_first_box(sheet):
    assert by_box(sheet, 1) == {"IWSC": {"car_number": "31", "team": "Cadillac Whelen"}}


def test_box_with_all_four_series(sheet):
    cars = by_box(sheet, 45)
    assert cars["IWSC"] == {"car_number": "13", "team": "13 Autosport"}
    assert cars["IMPC"] == {"car_number": "27", "team": "AutoTechnic Racing"}
    assert cars["LST"] == {"car_number": "44", "team": "Kaizen Autosport"}
    assert cars["PCCNA"] == {"car_number": "24", "team": "Kellymoss"}


def test_partially_occupied_boxes(sheet):
    assert set(by_box(sheet, 48)) == {"LST", "PCCNA"}
    # Box 50 carries an IWSC fuel-rig value ("17") but no IWSC car; the
    # DATA/FUEL columns must not leak into assignments.
    assert by_box(sheet, 50) == {"PCCNA": {"car_number": "57", "team": "Kellymoss"}}


def test_leading_zeros_preserved(sheet):
    assert by_box(sheet, 49)["IWSC"]["car_number"] == "068"
    assert by_box(sheet, 30)["IWSC"]["car_number"] == "033"


def test_landmarks(sheet):
    marks = {(m["after_box"], m["label"]) for m in sheet["landmarks"]}
    assert (0, "PENALTY BOX | MICHELIN") in marks
    assert (16, "BREAK") in marks
    assert (40, "S / F | IMSA TIMING & SCORING") in marks
    assert (44, "BREAK | TUNNEL") in marks
    assert any(after == 66 and label.startswith("PIT IN") for after, label in marks)
    assert any(after == 0 and label.startswith("PIT OUT") for after, label in marks)


def test_no_fuel_values_as_cars(sheet):
    for b in sheet["boxes"]:
        for car in b["cars"].values():
            assert car["car_number"] is None or "/" not in car["car_number"]
            assert car["team"] is not None
