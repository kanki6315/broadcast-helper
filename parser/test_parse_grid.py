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
