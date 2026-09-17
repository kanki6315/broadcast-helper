"""Grid-PDF parser tests against real 2021 Carrera Cup NA sheets.

The three samples cover the format's variations: Sebring (a normal grid with
one slot missing its time), COTA (qualifying never ran — every time is null),
and Watkins Glen (a "Revised" reissue).
"""
from pathlib import Path

import pytest

import parse_grid_pdf as p

HERE = Path(__file__).parent
SEBRING = HERE / "samples" / "2021_PCCNA_Sebring_Grid_R1.pdf"
COTA = HERE / "samples" / "2021_PCCNA_COTA_Grid_R1.pdf"
WGI = HERE / "samples" / "2021_PCCNA_WGI_Grid_R1_Revised.pdf"

pytestmark = pytest.mark.skipif(not SEBRING.exists(), reason="sample PDFs not present")


@pytest.fixture(scope="module")
def sebring():
    return p.parse(SEBRING)


@pytest.fixture(scope="module")
def cota():
    return p.parse(COTA)


def test_title_and_session(sebring):
    assert sebring["session"] == "Race 1"
    assert sebring["race"] == 1
    assert sebring["revised"] is False


def test_rows_complete(sebring):
    rows = sebring["rows"]
    assert len(rows) == 34
    assert [r["position"] for r in rows] == list(range(1, 35))
    assert all(r["class"] for r in rows)
    assert all(r["number"] for r in rows)


def test_pole_row(sebring):
    pole = sebring["rows"][0]
    assert pole == {
        "position": 1,
        "class": "Pro",
        "number": "15",
        "driver": "Seb Priaulx(J)",
        "team": "Kelly-Moss Road and Race",
        "car": "Porsche 992",
        "time": "2:03.109",
    }


def test_wide_team_name_stays_out_of_car_column(sebring):
    root = next(r for r in sebring["rows"] if r["position"] == 5)
    assert root["team"] == "Moorespeed-Wright Motorsports"
    assert root["car"] == "Porsche 992"


def test_missing_time_is_null(sebring):
    last = sebring["rows"][-1]
    assert last["driver"] == "Ted Giovanis"
    assert last["time"] is None


def test_no_qualifying_grid_has_no_times(cota):
    # COTA's qualifying never ran; the grid was set by other means and the
    # sheet prints no times at all. That absence is the importer's signal.
    assert len(cota["rows"]) == 26
    assert all(r["time"] is None for r in cota["rows"])


def test_revised_flag():
    doc = p.parse(WGI)
    assert doc["revised"] is True
    assert doc["race"] == 1
    assert len(doc["rows"]) == 30


def test_rejects_non_grid_pdf():
    entry_list = HERE / "samples" / "2026_IWSC_CTMP_PreEvent_EntryList.pdf"
    if not entry_list.exists():
        pytest.skip("entry-list sample not present")
    with pytest.raises(ValueError, match="Starting Grid"):
        p.parse(entry_list)


TORONTO = HERE / "samples" / "2022_PCCNA_Toronto_Grid_R1.pdf"


@pytest.mark.skipif(not TORONTO.exists(), reason="sample PDF not present")
def test_drivers_header_word_is_the_driver_column():
    # 2022 Toronto heads the column "Drivers"; the layout is otherwise the 2021 one.
    doc = p.parse(TORONTO)
    assert doc["session"] == "Race 1"
    assert doc["rows"][0]["number"] == "6"
    assert doc["rows"][0]["driver"] == "Trenton Estep"
    assert doc["rows"][0]["class"] == "Pro"
    assert doc["rows"][0]["time"] == "1:11.135"
    assert len(doc["rows"]) >= 15



# --- 2021 crew sheets (WeatherTech, Pilot Challenge) --------------------------
#
# IMSA's own series printed a numberless "Race Official Starting Grid" through
# 2021, with the whole crew in the driver cell and roles marked by emphasis the
# legend explains: bold (render mode 2, not a font) = starting driver, and on
# the Pilot Challenge sheet an underline (a hairline rect) = qualifying driver.

IWSC21 = Path(__file__).parent / "samples" / "2021_IWSC_Daytona_Grid.pdf"
IMPC21 = Path(__file__).parent / "samples" / "2021_IMPC_Sebring_Grid.pdf"


@pytest.mark.skipif(not IWSC21.exists(), reason="sample not present")
def test_2021_numberless_title_and_bold_starting_driver():
    doc = p.parse(IWSC21)
    assert (doc["session"], doc["race"], doc["revised"]) == ("Race", None, False)
    row = doc["rows"][0]
    assert row["number"] == "31" and row["class"] == "DPi"
    assert [d["name"] for d in row["drivers"]] == ["F. Nasr", "M. Conway", "P. Derani", "C. Elliott"]
    assert row["starting_driver_seat"] == 1
    assert row["qualifying_driver_seat"] is None  # this sheet's legend marks starters only
    assert all(r.get("starting_driver_seat") for r in doc["rows"])


@pytest.mark.skipif(not IMPC21.exists(), reason="sample not present")
def test_2021_underline_marks_the_qualifying_driver():
    doc = p.parse(IMPC21)
    rows = doc["rows"]
    assert doc["race"] is None
    assert [d["name"] for d in rows[0]["drivers"]] == ["M. Root", "J. Heylen"]
    assert (rows[0]["starting_driver_seat"], rows[0]["qualifying_driver_seat"]) == (1, 1)
    # Row 2's underline sits under the second name.
    assert rows[1]["driver"] == "K. Wittmer / O. Fidani"
    assert rows[1]["qualifying_driver_seat"] == 2
    assert all(r.get("qualifying_driver_seat") for r in rows)


LAGUNA21 = Path(__file__).parent / "samples" / "2021_IWSC_Laguna_Grid.pdf"


@pytest.mark.skipif(not LAGUNA21.exists(), reason="sample not present")
def test_2021_header_with_nr_and_drivers_run_together():
    # "Pos Class Nr.Drivers* Team Car Time": the Nr. and Driver anchors come apart.
    doc = p.parse(LAGUNA21)
    row = doc["rows"][0]
    assert (row["number"], row["driver"], row["team"]) == ("10", "R. Taylor / F. Albuquerque", "Konica Minolta Acura ARX-05")
    assert row["time"] == "1:14.441"
    assert all(r.get("starting_driver_seat") and r.get("qualifying_driver_seat") for r in doc["rows"])
