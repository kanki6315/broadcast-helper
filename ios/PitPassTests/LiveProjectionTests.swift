import XCTest
@testable import PitPass

@MainActor
final class LiveProjectionTests: XCTestCase {
    private func recap(kind: String, series: String = "IMSA", rows: [(String, Double)]) -> Recap {
        Recap(championship: RecapChampionship(id: 1, title: "GTP", className: "GTP", kind: kind, family: "IMSA", isCup: false,
                                              isOverall: false, seasonId: 1, year: 2026, seriesName: series),
              rounds: [RecapRound(round: 1, venue: "DAY", eventId: 11, raceCount: 1, sessions: [], races: []),
                       RecapRound(round: 2, venue: "ATL", eventId: 22, raceCount: 1, sessions: [], races: [])],
              rows: rows.enumerated().map { i, entry in
                  RecapRow(position: i + 1, competitorKey: entry.0, competitorName: nil, carNumber: "7",
                           teamName: "Porsche Penske Motorsport", teamNames: nil, totalPoints: entry.1,
                           pointsByRound: ["1": entry.1], sessionPoints: [:], cells: [:])
              })
    }
    private func live(kind: String, phase: String?, rows: [LiveRow], newcomers: [LiveNewcomer] = []) -> LiveChampionship {
        LiveChampionship(state: "LIVE", eventId: 22, session: nil, championshipId: 1, kind: kind, className: "GTP",
                         livePhase: phase, qualifyingImported: true, rows: rows, newcomers: newcomers)
    }
    private func running(_ position: Int, car: String = "7", gapMs: Int? = nil, gapLaps: Int? = nil) -> LiveRunning {
        LiveRunning(position: position, carNumber: car, teamName: nil, status: "CLASSIFIED", laps: 70,
                    gapToLeaderMs: gapMs, gapToLeaderLaps: gapLaps)
    }

    func testRaceAddsLiveRacePointsAndImportedQualifying() {
        // Cadillac leads the standings by 30; Porsche is winning from pole.
        let recap = recap(kind: "MANUFACTURERS", rows: [("Cadillac", 2562), ("Porsche", 2532), ("BMW", 2228)])
        let live = live(kind: "MANUFACTURERS", phase: "RACE", rows: [
            LiveRow(competitorKey: "Cadillac", live: running(2, car: "31"), qualifyingPosition: 2),
            LiveRow(competitorKey: "Porsche", live: running(1), qualifyingPosition: 1),
            LiveRow(competitorKey: "BMW", live: nil, qualifyingPosition: nil)])
        let lines = ChampionshipCalculator.liveLines(recap, live: live)

        XCTAssertEqual(lines.map(\.id), ["Porsche", "Cadillac", "BMW"])
        XCTAssertEqual(lines.map(\.projection.total), [2532 + 35 + 350, 2562 + 32 + 320, 2228])
        XCTAssertEqual(lines.map(\.movement), [1, -1, 0], "Porsche takes the lead as it stands")
        XCTAssertEqual(lines[1].projection.gap, 3)
        XCTAssertEqual(lines[2].projection.added, 0, "not running: in the table, adding nothing")
        XCTAssertNil(lines[2].running)
    }

    func testMovementIgnoresHowTheSourceNumbersTies() {
        // Co-drivers tied on points: the standings source numbers them 1, 1, 2, 2.
        var recap = recap(kind: "DRIVERS", rows: [("Ellis", 2373), ("Ward", 2373), ("Gallagher", 2337), ("Foley", 2337)])
        recap = Recap(championship: recap.championship, rounds: recap.rounds, rows: zip(recap.rows, [1, 1, 2, 2]).map { row, position in
            RecapRow(position: position, competitorKey: row.competitorKey, competitorName: nil, carNumber: nil, teamName: nil,
                     teamNames: nil, totalPoints: row.totalPoints, pointsByRound: row.pointsByRound, sessionPoints: [:], cells: [:])
        })
        let live = live(kind: "DRIVERS", phase: "RACE", rows: ["Ellis", "Ward"].map { LiveRow(competitorKey: $0, live: running(1, car: "57"), qualifyingPosition: nil) }
                        + ["Gallagher", "Foley"].map { LiveRow(competitorKey: $0, live: running(2, car: "96"), qualifyingPosition: nil) })
        let lines = ChampionshipCalculator.liveLines(recap, live: live)
        XCTAssertEqual(lines.map(\.projection.rank), [1, 1, 3, 3])
        XCTAssertEqual(lines.map(\.movement), [0, 0, 0, 0], "nobody has moved: the order on points is unchanged")
    }

    func testQualifyingSessionFillsTheQualifyingColumnOnly() {
        let recap = recap(kind: "TEAMS", rows: [("7", 1000), ("31", 990)])
        let live = live(kind: "TEAMS", phase: "QUALIFYING", rows: [
            LiveRow(competitorKey: "7", live: running(3), qualifyingPosition: 9),
            LiveRow(competitorKey: "31", live: running(1, car: "31"), qualifyingPosition: nil)])
        let scenario = ChampionshipCalculator.liveScenario(live, seriesName: "IMSA")
        XCTAssertEqual(scenario["7"]?.positions, [3, 0], "the live order IS qualifying; an imported one is ignored")
        XCTAssertEqual(scenario["31"]?.positions, [1, 0])
        XCTAssertEqual(ChampionshipCalculator.liveLines(recap, live: live).map(\.projection.total), [1030, 1025])
        XCTAssertEqual(ChampionshipCalculator.liveLines(recap, live: live).map(\.id), ["7", "31"])
    }

    func testPracticeScoresNothingAndPilotChallengeHasNoQualifyingPoints() {
        let rows = [LiveRow(competitorKey: "7", live: running(1), qualifyingPosition: 1)]
        XCTAssertEqual(ChampionshipCalculator.liveScenario(live(kind: "TEAMS", phase: nil, rows: rows), seriesName: "IMSA")["7"]?.positions, [0, 0])
        let pilot = "IMSA Michelin Pilot Challenge"
        XCTAssertEqual(ChampionshipCalculator.liveScenario(live(kind: "TEAMS", phase: "RACE", rows: rows), seriesName: pilot)["7"]?.positions, [1])
        XCTAssertEqual(ChampionshipCalculator.liveScenario(live(kind: "TEAMS", phase: "QUALIFYING", rows: rows), seriesName: pilot)["7"]?.positions, [0])
    }

    func testWhichChampionshipsLiveModeCovers() {
        func summary(kind: String?, cup: Bool = false, series: String = "IMSA WeatherTech SportsCar Championship", rows: Int = 5, cls: String? = "GTP") -> ChampionshipSummary {
            ChampionshipSummary(id: 1, title: "t", groupTitle: "g", className: cls, kind: kind, kindLabel: nil, isCup: cup,
                                year: 2026, seasonId: 1, seriesName: series, rowCount: rows)
        }
        XCTAssertTrue(["TEAMS", "DRIVERS", "MANUFACTURERS"].allSatisfy { ChampionshipCalculator.supportedLive(summary(kind: $0)) })
        XCTAssertFalse(ChampionshipCalculator.supportedLive(summary(kind: "TEAMS", cup: true)), "Endurance Cup is not modelled live")
        XCTAssertFalse(ChampionshipCalculator.supportedLive(summary(kind: "DRIVERS", series: "Porsche Carrera Cup")))
        XCTAssertFalse(ChampionshipCalculator.supportedLive(summary(kind: "DRIVERS", rows: 0)))
        XCTAssertFalse(ChampionshipCalculator.supportedLive(summary(kind: "DRIVERS", cls: nil)))
        XCTAssertFalse(ChampionshipCalculator.supported(summary(kind: "DRIVERS")), "the hand-built calculator stays teams-only")
    }

    func testNamesAndGaps() {
        let driver = RecapRow(position: 1, competitorKey: "Felipe Nasr", competitorName: nil, carNumber: "7",
                              teamName: "Porsche Penske Motorsport", teamNames: nil, totalPoints: 1,
                              pointsByRound: [:], sessionPoints: [:], cells: [:])
        XCTAssertEqual(ChampionshipCalculator.liveName(driver, kind: "DRIVERS"), "Felipe Nasr")
        XCTAssertEqual(ChampionshipCalculator.liveName(driver, kind: "TEAMS"), "#7 · Porsche Penske Motorsport")
        XCTAssertEqual(ChampionshipCalculator.liveGap(running(1)), "Leader")
        XCTAssertEqual(ChampionshipCalculator.liveGap(running(2, gapMs: 1830)), "+1.830")
        XCTAssertEqual(ChampionshipCalculator.liveGap(running(9, gapMs: 62_400)), "+1:02.4")
        XCTAssertEqual(ChampionshipCalculator.liveGap(running(12, gapLaps: 1)), "+1 lap")
        XCTAssertEqual(ChampionshipCalculator.liveGap(running(14, gapMs: 500, gapLaps: 3)), "+3 laps")
    }

    func testDecodesTheServersShape() throws {
        let json = """
        {"state":"LIVE","eventId":22,"eventName":"Petit","session":{"championship":"IMSA","event":"Petit","name":"Race","type":"RACE","flag":"GREEN","running":true,"finished":false},
         "championshipId":322,"kind":"MANUFACTURERS","className":"GTP","livePhase":"RACE","qualifyingImported":false,
         "rows":[{"competitorKey":"Porsche","live":{"position":1,"carNumber":"7","teamName":"Porsche Penske Motorsport","status":"CLASSIFIED","laps":72,"gapToLeaderMs":null,"gapToLeaderLaps":null},"qualifyingPosition":null},
                 {"competitorKey":"BMW","live":null,"qualifyingPosition":null}],
         "newcomers":[{"name":"Endurance Only","carNumber":"93","position":5}]}
        """
        let decoded = try JSONDecoder.api.decode(LiveChampionship.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.rows.first?.live?.carNumber, "7")
        XCTAssertNil(decoded.rows.last?.live)
        XCTAssertEqual(decoded.newcomers.first?.position, 5)
        XCTAssertFalse(decoded.qualifyingImported)
    }
}
