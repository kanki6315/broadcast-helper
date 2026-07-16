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


# --- Carrera Cup Asia (two-up ruled grid, class on the driver line) -----------

PACCA_SAMPLE = Path(__file__).parent / "samples" / "2026_PACCA_Bangsaen_EntryList.pdf"


@pytest.fixture(scope="module")
def pacca_doc():
    if not PACCA_SAMPLE.exists():
        pytest.skip("PACCA sample PDF not present")
    return p.parse(PACCA_SAMPLE, series=None)


def test_pacca_layout_is_detected_without_a_series_hint(pacca_doc):
    # The two-up grid is sniffed from the table header, and the series comes
    # from the filename.
    assert pacca_doc["event"]["series"] == "PACCA"


def test_pacca_event_header(pacca_doc):
    ev = pacca_doc["event"]
    assert ev["name"] == "Round 7&8 – Bangsaen Street Circuit"
    assert ev["circuit"] == "Bangsaen Street Circuit"
    assert ev["location"] == "Thailand"
    # "3 – 5 July 2026" — day-first, unlike the IMSA "July 3 - July 5" form.
    assert (ev["start_date"], ev["end_date"]) == ("2026-07-03", "2026-07-05")


def test_pacca_entries_single_driver_with_class(pacca_doc):
    from collections import Counter
    entries = pacca_doc["entries"]
    assert len(entries) == 26
    assert {len(e["drivers"]) for e in entries} == {1}
    # The class lives on the driver line, one class per car.
    assert Counter(e["class_code"] for e in entries) == {
        "PRO": 14, "PRO-AM": 3, "AM": 6, "MASTERS": 3,
    }
    assert all(e["class_order"] == {"PRO": 1, "PRO-AM": 2, "AM": 3, "MASTERS": 4}[e["class_code"]]
               for e in entries)
    unparsed = [d for e in entries for d in e["drivers"] if d.get("unparsed")]
    assert unparsed == []


def test_pacca_team_line_split(pacca_doc):
    # Team, the '#' Dealer Trophy marker, and the team's own nationality all sit
    # on one line: "Porsche Own Retail 69 Team # CHN".
    car969 = next(e for e in pacca_doc["entries"] if e["car_number"] == "969")
    assert car969["team"] == "Porsche Own Retail 69 Team"
    assert car969["team_nationality"] == "CHN"
    assert car969["dealer_trophy"] is True
    dealers = {e["car_number"] for e in pacca_doc["entries"] if e["dealer_trophy"]}
    assert dealers == {"3", "12", "17", "24", "55", "66", "969"}
    # A digits-leading team name isn't mistaken for a car number or nationality.
    car25 = next(e for e in pacca_doc["entries"] if e["car_number"] == "25")
    assert car25["team"] == "610 Racing" and car25["dealer_trophy"] is False


def test_pacca_non_series_star_is_a_driver_marker(pacca_doc):
    # "*Non series registered" per the sheet's legend. Starred drivers still
    # score points on the real 2026 sheets (XIE An), so this must NOT map onto
    # the IMSA invitational/guest treatment — it is its own marker.
    starred = {d["name"] for e in pacca_doc["entries"] for d in e["drivers"]
               if "non_series" in d["markers"]}
    assert starred == {
        "LI Kerong", "Dale WOOD", "XU Zhefeng", "XIE An",
        "Chris VAN DER DRIFT", "Dylan PEREIRA", "YANG Haojie",
    }
    invitational = [d for e in pacca_doc["entries"] for d in e["drivers"]
                    if "invitational" in d["markers"]]
    assert invitational == []


def test_pacca_pro_am_never_splits_into_pro(pacca_doc):
    # "LI Kerong* Pro-Am USA": the class alternation must try Pro-Am before Pro.
    li = next(e for e in pacca_doc["entries"]
              if e["drivers"][0]["name"] == "LI Kerong")
    assert li["class_code"] == "PRO-AM"
    assert li["drivers"][0]["nationality"] == "USA"


def test_a_points_pdf_yields_no_entries_rather_than_fabricated_ones():
    # A points sheet's first column is the finishing position, which this parser
    # would otherwise read as a car number — staging ~51 plausible-looking
    # entries from the 2026 PACCA sheet instead of failing. The IMSA points PDFs
    # are unruled and yield no tables at all, so this only bites on a ruled
    # sheet. Zero entries is what fires the loader's "this may be a points PDF"
    # guard, so it is the contract here.
    points = Path(__file__).parent / "samples" / "2026_PACCA_Points_R8.pdf"
    if not points.exists():
        pytest.skip("PACCA points sample not present")
    assert p.parse(points, series=None)["entries"] == []
