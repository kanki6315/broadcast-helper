"""Points-parser tests against the committed sample sheets.

Two sheets on purpose, because they exercise different halves of the layout:
  * 2024 Mustang Challenge — "Extra"+"Round N" columns, the bonus case, and no
    points JSON in existence (the reason this parser exists at all).
  * 2026 IWSC — "Qualifying"+"Race" columns, entrant names long enough to
    overprint the totals, and a season IMSA also published as JSON, so the
    numbers here have an independent source of truth.
"""
from pathlib import Path

import pytest

import parse_points as p

SAMPLES = Path(__file__).parent / "samples"
MC = SAMPLES / "2024_MC_FullSeason_Points.pdf"
IWSC = SAMPLES / "2026_IWSC_CTMP_Points.pdf"
pytestmark = pytest.mark.skipif(
    not (MC.exists() and IWSC.exists()), reason="sample PDFs not present"
)


@pytest.fixture(scope="module")
def mc():
    return p.parse(MC)


@pytest.fixture(scope="module")
def iwsc():
    return p.parse(IWSC)


def _one(doc, suffix):
    return next(c for c in doc["championships"] if c["championship"]["main_title"].endswith(suffix))


def _row(champ, position):
    return next(r for r in champ["classification"] if r["position"] == position)


# --- the checksum that guards every other claim here ------------------------

@pytest.mark.parametrize("fixture", ["mc", "iwsc"])
def test_every_row_readds_to_its_printed_total(fixture, request):
    doc = request.getfixturevalue(fixture)
    for champ in doc["championships"]:
        for row in champ["classification"]:
            assert sum(s["total_points"] for s in row["points_by_session"]) == row["total_points"], (
                f"{champ['championship']['main_title']} #{row['position']} {row['key']}"
            )


def test_flush_columns_split_instead_of_concatenating(mc):
    # Noaker's row prints "350 10320 10320 320 ..." where 10320 is Extra 10 plus
    # Round 320. Read as one number the row totals 43230 rather than 3270, so
    # this is the regression that the whole geometry approach exists to prevent.
    row = _row(_one(mc, "DH Drivers"), 1)
    assert row["key"] == "Robert Noaker"
    assert row["total_points"] == 3270
    assert [(s["race_points"], s["bonus_points"]) for s in row["points_by_session"]] == [
        (350, 0), (320, 10), (320, 10), (320, 0), (300, 0),
        (280, 0), (320, 0), (350, 0), (320, 10), (350, 10),
    ]


# --- structure --------------------------------------------------------------

def test_mustang_championships_and_sessions(mc):
    titles = [c["championship"]["main_title"] for c in mc["championships"]]
    assert titles == [
        "Mustang Challenge DH Drivers",
        "Mustang Challenge DH Entrants",
        "Mustang Challenge DHL Drivers",
    ]
    sessions = _one(mc, "DH Drivers")["championship"]["sessions"]
    # "Extra" is a bonus on the round beside it, never a session of its own, and
    # each event carries two rounds.
    assert [s["session_name"] for s in sessions] == [f"Round {i}" for i in range(1, 11)]
    assert [s["event_name"] for s in sessions[:4]] == [
        "Mid-Ohio", "Mid-Ohio", "Watkins Glen", "Watkins Glen",
    ]
    assert mc["championships"][0]["championship"]["year"] == "2024"


def test_iwsc_splits_qualifying_and_race_into_separate_sessions(iwsc):
    sessions = _one(iwsc, "GTP Drivers")["championship"]["sessions"]
    assert len(sessions) == 22
    assert [(s["event_name"], s["session_name"]) for s in sessions[:4]] == [
        ("Daytona", "Qualifying"), ("Daytona", "Race"),
        ("Sebring", "Qualifying"), ("Sebring", "Race"),
    ]
    # Qualifying points are a real session score, not a bonus, so nothing lands
    # in bonus_points for this series.
    assert all(
        s["bonus_points"] == 0
        for c in iwsc["championships"] for r in c["classification"] for s in r["points_by_session"]
    )


def test_multi_page_championships_are_merged(iwsc):
    # GTP Drivers runs over pages 1-2; the classification is one list.
    assert len(_one(iwsc, "GTP Drivers")["classification"]) == 39
    assert len(iwsc["championships"]) == 11


# --- the awkward rows -------------------------------------------------------

def test_teams_sheet_keys_on_car_number(iwsc):
    # Matches how the standings JSON keys a Teams row: car number as the key,
    # team as the name. Drivers rows key on the name with no team.
    row = _row(_one(iwsc, "GTP Teams"), 1)
    assert (row["key"], row["team"]) == ("31", "Cadillac Whelen")
    driver = _row(_one(iwsc, "GTP Drivers"), 1)
    assert (driver["key"], driver["team"]) == ("Jack Aitken", "")


def test_name_overprinting_the_totals_is_recovered(iwsc, mc):
    # These names are long enough to be drawn straight through the number
    # columns, interleaving character by character with the points.
    assert _row(_one(iwsc, "GTP Teams"), 2)["team"] == "Acura Meyer Shank Racing w/Curb Agajanian"
    assert _row(_one(mc, "DH Entrants"), 17)["key"] == "AMERASIAN FRAGRANCE COMPETITION MOTORSPORTS"


def test_digit_inside_a_team_name_is_not_swallowed_by_the_total(iwsc):
    # "PR1/Mathiasen" prints through its own total: same font, interleaved x.
    # Only the name's baseline separates the two.
    row = _row(_one(iwsc, "LMP2 Teams"), 5)
    assert row["team"] == "Bryan Herta Autosport with PR1/Mathiasen"
    assert row["total_points"] == 1131


def test_sentinels_map_to_the_json_status_vocabulary(iwsc):
    # The sheet prints its own legend: "/ DNP  * DNS".
    row = _row(_one(iwsc, "LMP2 Teams"), 14)
    assert row["key"] == "79"
    statuses = [s["status"] for s in row["points_by_session"][:6]]
    assert statuses == [
        "did_not_race", "did_not_race", "", "not_classified", "did_not_race", "did_not_race",
    ]
    # A blank cell is a round with no data yet, which is not the same as a DNP.
    assert _row(_one(iwsc, "GTP Drivers"), 1)["points_by_session"][21]["status"] == ""


def test_a_row_that_fails_its_checksum_raises(mc, monkeypatch):
    # The checksum is the contract: corrupt one cell and the parse must fail
    # loudly rather than emit points that look plausible.
    real = p._cell_values

    def corrupt(chars, cols, numeric_from, points_font):
        cells = real(chars, cols, numeric_from, points_font)
        return ["999" if c and c[0].isdigit() else c for c in cells]

    monkeypatch.setattr(p, "_cell_values", corrupt)
    with pytest.raises(ValueError, match="cells sum to"):
        p.parse(MC)


# --- Carrera Cup Asia (ruled grid, FLQ/Race/FL sub-columns) -------------------

PACCA = SAMPLES / "2026_PACCA_Points_R8.pdf"


@pytest.fixture(scope="module")
def pacca():
    if not PACCA.exists():
        pytest.skip("PACCA sample PDF not present")
    return p.parse(PACCA)


def test_pacca_every_row_readds_to_its_printed_total(pacca):
    # Same hard gate as the IMSA path, but over decimal points (half points for
    # shortened races: 12.5, 108.5) — so compare with a float tolerance.
    for champ in pacca["championships"]:
        for row in champ["classification"]:
            assert abs(sum(s["total_points"] for s in row["points_by_session"])
                       - row["total_points"]) < 1e-6, (
                f"{champ['championship']['main_title']} #{row['position']} {row['key']}"
            )


def test_pacca_one_championship_per_page(pacca):
    titles = [c["championship"]["main_title"] for c in pacca["championships"]]
    assert [t[t.rindex("(") + 1:-1] for t in titles] == [
        "Overall", "Pro-Am", "Am", "Masters", "Porsche Dealer Trophy",
    ]
    # The season leads the title ("2026 Porsche ..."), which beats the PDF's
    # creation date as the year source.
    assert all(c["championship"]["year"] == "2026" for c in pacca["championships"])


def test_pacca_rounds_and_events(pacca):
    sessions = pacca["championships"][0]["championship"]["sessions"]
    assert [s["session_name"] for s in sessions] == [f"Round {i}" for i in range(1, 14)]
    # Sepang carries three rounds; every other event two.
    assert [s["event_name"] for s in sessions] == (
        ["Shanghai"] * 2 + ["Zhuhai"] * 2 + ["Fuji"] * 2 + ["Bangsaen"] * 2
        + ["Sepang"] * 3 + ["Marina Bay"] * 2
    )


def test_pacca_splits_pole_and_fastest_lap(pacca):
    # FLQ (pole, in a one-make cup) and FL are separate columns on this sheet,
    # so nothing lands in the IMSA-PDF catch-all bonus_points.
    overall = _one(pacca, "(Overall)")
    amand = _row(overall, 1)
    assert amand["key"] == "Marcus AMAND"
    assert amand["total_points"] == 179
    r7 = amand["points_by_session"][6]
    assert (r7["race_points"], r7["pole_points"], r7["fastest_lap_points"]) == (25, 1, 1)
    assert all(
        s["bonus_points"] == 0
        for c in pacca["championships"] for r in c["classification"] for s in r["points_by_session"]
    )


def test_pacca_flq_scores_even_when_the_race_is_a_dsq(pacca):
    # WANG Zhongwei takes pole for Round 5 then is disqualified from the race:
    # the FLQ point still counts (his printed total re-adds only if it does).
    wang = _row(_one(pacca, "(Pro-Am)"), 1)
    r5 = wang["points_by_session"][4]
    assert (r5["pole_points"], r5["race_points"], r5["status"]) == (2, 0, "disqualified")


def test_pacca_sentinels(pacca):
    lu = next(r for r in _one(pacca, "(Pro-Am)")["classification"] if r["key"] == "LU Wei")
    statuses = [s["status"] for s in lu["points_by_session"]]
    assert statuses[:8] == ["", "did_not_finish", "did_not_finish", "",
                            "not_classified", "did_not_finish", "did_not_race", "did_not_race"]
    # Rounds 9-13 haven't run: blank cells stay "", not a DNP.
    assert statuses[8:] == [""] * 5


def test_pacca_tied_positions_share_a_merged_cell(pacca):
    # 14th is a two-way tie on 20 points and the sheet resumes at 15 (dense
    # numbering); the position glyph itself can land on either row or neither.
    overall = _one(pacca, "(Overall)")
    by_pos = {}
    for r in overall["classification"]:
        by_pos.setdefault(r["position"], []).append(r["key"])
    assert sorted(by_pos[14]) == ["Henry KWONG", "XIE An"]
    assert by_pos[15] == ["LU Wei"]
    # The scoreless tail is a four-way tie for last.
    assert sorted(by_pos[25]) == ["Andy TAN", "Dominic TJIA", "Douglas KHOO", "Francis TJIA"]


def test_pacca_team_sheet_keys_on_the_team_name(pacca):
    # No car-number column on the Dealer Trophy page, so the name is both key
    # and team — with the '#' eligibility marker stripped off.
    teams = _one(pacca, "(Porsche Dealer Trophy)")
    row = _row(teams, 1)
    assert (row["key"], row["team"]) == ("BWT Shanghai Yonda", "BWT Shanghai Yonda")
    assert not any(r["key"].endswith("#") for r in teams["classification"])
    # 82 points apiece is a genuine tie for 5th.
    assert sorted(r["key"] for r in teams["classification"] if r["position"] == 5) == [
        "Porsche Centre Adelaide", "Porsche Vietnam",
    ]


def test_pacca_decimal_points_survive(pacca):
    giltrap = _row(_one(pacca, "(Overall)"), 2)
    assert giltrap["key"] == "Marco GILTRAP"
    assert giltrap["total_points"] == 108.5
    assert giltrap["points_by_session"][0]["race_points"] == 4.5
