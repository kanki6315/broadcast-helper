"""Parser tests against the committed WGI sample. Skips if the fixture is absent."""
from pathlib import Path

import pytest

import parse_entry_list as p

SAMPLE = Path(__file__).parent / "samples" / "01_IWSC_Pre-Event_Entry_List.pdf"
pytestmark = pytest.mark.skipif(not SAMPLE.exists(), reason="sample PDF not present")


@pytest.fixture(scope="module")
def doc():
    return p.parse(SAMPLE, series="IWSC")


def test_total_entry_count(doc):
    assert len(doc["entries"]) == 54
    assert doc["event"]["total_entries"] == 54


def test_class_breakdown(doc):
    from collections import Counter
    counts = Counter(e["class_code"] for e in doc["entries"])
    assert counts == {"GTP": 11, "LMP2": 11, "GTD PRO": 12, "GTD": 20}


def test_driver_counts_are_two_or_three(doc):
    assert {len(e["drivers"]) for e in doc["entries"]} == {2, 3}


def test_class_order_matches_pdf_section_order(doc):
    # Each class has one stable order; they ascend in PDF appearance order.
    order = {e["class_code"]: e["class_order"] for e in doc["entries"]}
    assert order == {"GTP": 1, "LMP2": 2, "GTD PRO": 3, "GTD": 4}
    # Every entry carries its class's order.
    assert all(e["class_order"] == order[e["class_code"]] for e in doc["entries"])


def test_leading_zero_car_numbers_preserved(doc):
    numbers = {e["car_number"] for e in doc["entries"]}
    assert "04" in numbers and "033" in numbers


def test_tbd_placeholder(doc):
    car28 = next(e for e in doc["entries"] if e["car_number"] == "28")
    tbds = [d for d in car28["drivers"] if d["is_tbd"]]
    assert len(tbds) == 2
    assert all(d["rating"] is None and d["nationality"] is None for d in tbds)


def test_team_sponsor_split(doc):
    car5 = next(e for e in doc["entries"] if e["car_number"] == "5")
    assert car5["team"] == "JDC-Miller MotorSports"
    assert car5["sponsor"] and "Mustang Sampling" in car5["sponsor"]
    # A car with no sponsor line should split to a null sponsor.
    car6 = next(e for e in doc["entries"] if e["car_number"] == "6")
    assert car6["team"] == "Porsche Penske Motorsport"
    assert car6["sponsor"] is None


def test_no_unparsed_driver_lines(doc):
    unparsed = [d for e in doc["entries"] for d in e["drivers"] if d.get("unparsed")]
    assert unparsed == []


# --- Mustang Challenge (one-make, single-driver, "Name NAT" driver lines) ----

MC_SAMPLE = Path(__file__).parent / "samples" / "2026_MC_MidOhio_EntryList.pdf"


@pytest.fixture(scope="module")
def mc_doc():
    if not MC_SAMPLE.exists():
        pytest.skip("Mustang sample PDF not present")
    return p.parse(MC_SAMPLE, series="MC")


def test_mustang_single_driver_entries(mc_doc):
    assert {len(e["drivers"]) for e in mc_doc["entries"]} == {1}


def test_mustang_name_nationality_parsed(mc_doc):
    # "Name NAT" lines (no rating, no hometown) parse cleanly, nationality kept.
    unparsed = [d for e in mc_doc["entries"] for d in e["drivers"] if d.get("unparsed")]
    assert unparsed == []
    farley = next(d for e in mc_doc["entries"] for d in e["drivers"] if d["name"] == "Jim Farley")
    assert farley["nationality"] == "USA" and farley["rating"] is None


def test_mustang_vip_marker(mc_doc):
    # The VIP / Invitational blue-V icon tags the guest driver (#17 Jim Farley).
    car17 = next(e for e in mc_doc["entries"] if e["car_number"] == "17")
    assert "invitational" in car17["drivers"][0]["markers"]


def test_mustang_classes(mc_doc):
    assert {e["class_code"] for e in mc_doc["entries"]} == {"DH", "DHL"}
