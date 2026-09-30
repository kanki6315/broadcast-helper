import XCTest
@testable import PitPass

/// The timing page's formats — the same strings the web page prints
/// (frontend/src/lib/liveTiming.ts), and the API decoding they read from.
final class TimingFormatTests: XCTestCase {

    func testLapTimes() {
        XCTAssertEqual(TimingFormat.lapTime(98_765), "1:38.765")
        XCTAssertEqual(TimingFormat.lapTime(38_005), "38.005")
        XCTAssertEqual(TimingFormat.lapTime(3_723_004), "1:02:03.004")
        XCTAssertEqual(TimingFormat.lapTime(nil), "—")
        XCTAssertEqual(TimingFormat.lapTime(0), "—")
    }

    func testGapsAndIntervals() {
        XCTAssertEqual(TimingFormat.gap(ms: 4200, laps: nil), "+4.200")
        XCTAssertEqual(TimingFormat.gap(ms: 62_345, laps: nil), "+1:02.345")
        XCTAssertEqual(TimingFormat.gap(ms: 4200, laps: 1), "+1 lap", "laps win over time")
        XCTAssertEqual(TimingFormat.gap(ms: nil, laps: -2), "+2 laps")
        XCTAssertEqual(TimingFormat.gap(ms: nil, laps: nil), "")
    }

    func testDurationsAndRuleTimes() {
        XCTAssertEqual(TimingFormat.duration(7_449_000), "2:04:09")
        XCTAssertEqual(TimingFormat.duration(249_000), "4:09")
        XCTAssertEqual(TimingFormat.duration(-5), "0:00")
        XCTAssertEqual(TimingFormat.ruleTime(5_400_000), "1:30:00")
        XCTAssertEqual(TimingFormat.ruleTime(nil), "")
    }

    func testFlagsAndRatings() {
        XCTAssertEqual(TimingFormat.flagLabel("FULL_YELLOW"), "Full yellow")
        XCTAssertEqual(TimingFormat.flagTone("FULL_YELLOW"), .yellow)
        XCTAssertEqual(TimingFormat.flagTone("RED"), .red)
        XCTAssertEqual(TimingFormat.flagTone("GREEN"), .green)
        XCTAssertNil(TimingFormat.flagLabel(nil))
        XCTAssertEqual(TimingFormat.ratingName("B"), "Bronze")
        XCTAssertEqual(TimingFormat.ratingName("Platinum"), "Platinum")
    }

    func testTheStintClockStopsForAReplayOrAFinishedSession() {
        let wall = 1_800_000_000_000
        XCTAssertEqual(TimingFormat.feedNow(state: "LIVE", finished: false, feedClockMs: wall - 20_000, wallMs: wall), wall)
        XCTAssertEqual(TimingFormat.feedNow(state: "LIVE", finished: false, feedClockMs: wall - 3_600_000, wallMs: wall),
                       wall - 3_600_000, "an old feed time: a replay")
        XCTAssertEqual(TimingFormat.feedNow(state: "LIVE", finished: true, feedClockMs: wall - 20_000, wallMs: wall), wall - 20_000)
        XCTAssertEqual(TimingFormat.feedNow(state: "BACKING_OFF", finished: false, feedClockMs: wall - 20_000, wallMs: wall), wall - 20_000)
        XCTAssertNil(TimingFormat.feedNow(state: "OFF", finished: false, feedClockMs: nil, wallMs: wall))
    }

    func testDecodesTheTowerAsTheServerWritesIt() throws {
        let json = """
        {"state":"LIVE","eventId":22,"eventName":"Petit Le Mans","session":{"championship":"IMSA","event":"PLM","name":"Race",
          "type":"RACE","flag":"GREEN","running":true,"finished":false},"sessionDbId":3150,"feedClockMs":1769000000000,
         "classes":[{"className":"GTP","feedClass":"GTP","color":"#1a1a1a","cars":[{"position":1,"carNumber":"04","entryId":12,
          "teamName":"Team","vehicle":null,"manufacturer":null,"status":"CLASSIFIED","laps":50,"gapToLeaderMs":null,"gapToLeaderLaps":null,
          "intervalMs":null,"intervalLaps":null,"driverOrder":1,"driverName":"Ann One","driverShortName":"One","driverRating":"G",
          "lastLap":50,"lastLapMs":98000,"bestLap":12,"bestLapMs":97500,"inPit":false,"stintStartMs":1768999000000,"stintLaps":18,
          "energyPct":62.4,"energyLapsLeft":9.6}]}],"matched":1,"total":1}
        """
        let tower = try JSONDecoder().decode(Tower.self, from: Data(json.utf8))
        XCTAssertEqual(tower.classes.first?.cars.first?.carNumber, "04")
        XCTAssertEqual(tower.classes.first?.cars.first?.energyPct, 62.4)
        XCTAssertEqual(TimingFormat.classBest(tower.classes[0].cars), 97_500)
        XCTAssertTrue(tower.classes[0].cars[0].running)
    }

    // MARK: Session clock and lap marks

    /// Road Atlanta practice 1 (2026-09-30): an hour from 16:25:00Z. At
    /// 16:50:14Z Al Kamel's own tower said 34:46, under a red flag that did
    /// not stop the clock.
    private func tower(state: String = "LIVE", finished: Bool = false, stopMs: Int? = nil, stoppedMs: Int = 0,
                       feedClockMs: Int? = nil, finalType: String = "BY_TIME", finalLaps: Int? = nil,
                       currentLap: Int? = nil, startMs: Int? = 1_790_785_500_000) throws -> Tower {
        let clock: [String: Any?] = ["finalType": finalType, "startMs": startMs, "finalMs": 3_600_000, "finalLaps": finalLaps,
                                     "currentLap": currentLap, "stopMs": stopMs, "stoppedMs": stoppedMs, "utcOffsetHours": -4.0]
        let session: [String: Any?] = ["championship": "IMSA Michelin Pilot Challenge", "event": nil, "name": "Practice 1",
                                       "type": "FREE_PRACTICE", "flag": "RED", "running": true, "finished": finished,
                                       "clock": clock.mapValues { $0 ?? NSNull() }]
        let body: [String: Any?] = ["state": state, "eventId": 232, "eventName": nil, "session": session.mapValues { $0 ?? NSNull() },
                                    "sessionDbId": 3183, "feedClockMs": feedClockMs, "classes": [], "matched": 0, "total": 0]
        let data = try JSONSerialization.data(withJSONObject: body.mapValues { $0 ?? NSNull() })
        return try JSONDecoder().decode(Tower.self, from: data)
    }

    func testTheClockCountsDownAsAlKamelsDid() throws {
        let screenshot = 1_790_787_014_000
        let reading = TimingFormat.sessionClock(try tower(), wallMs: screenshot)
        XCTAssertEqual(reading, TimingFormat.ClockReading(time: "34:46", note: "to go", stopped: false, laps: nil))
        XCTAssertEqual(TimingFormat.trackTime(wallMs: screenshot, utcOffsetHours: -4), "12:50:14")
    }

    func testAStoppedClockFreezesAtTheStop() throws {
        let start = 1_790_785_500_000
        let reading = TimingFormat.sessionClock(try tower(stopMs: start + 300_000, stoppedMs: 60_000), wallMs: start + 3_000_000)
        XCTAssertEqual(reading?.time, "56:00", "4 min run: 5 to the stop, less 1 stopped before")
        XCTAssertEqual(reading?.note, "Clock stopped")
        XCTAssertEqual(reading?.stopped, true)
    }

    func testAReplayShowsTheClockAsItStood() throws {
        let lastLap = 1_790_788_106_637 // the recording's newest lap end
        let reading = TimingFormat.sessionClock(try tower(feedClockMs: lastLap), wallMs: 1_800_000_000_000)
        XCTAssertEqual(reading?.time, "16:33", "past the scheduled end: the feed's own time, not 0:00")
    }

    func testBeforeTheStartLapRacesAndTheFinish() throws {
        XCTAssertEqual(TimingFormat.sessionClock(try tower(startMs: nil), wallMs: 0)?.note, "Not started")
        XCTAssertEqual(TimingFormat.sessionClock(try tower(startMs: nil), wallMs: 0)?.time, "1:00:00")
        XCTAssertEqual(TimingFormat.sessionClock(try tower(finalType: "BY_LAPS", finalLaps: 30, currentLap: 12), wallMs: 0)?.time,
                       "Lap 12 of 30")
        XCTAssertNil(TimingFormat.sessionClock(try tower(finished: true), wallMs: 0), "the Finished chip says it")
    }

    func testLastLapMarks() throws {
        let json = #"{"position":1,"carNumber":"7","inPit":false,"lastLapMs":97100,"bestLapMs":97100}"#
        let car = try JSONDecoder().decode(TowerCar.self, from: Data(json.utf8))
        XCTAssertEqual(TimingFormat.lastLapMark(car, classBest: 97_100), .classBest)
        XCTAssertEqual(TimingFormat.lastLapMark(car, classBest: 96_000), .personalBest)
        let slower = try JSONDecoder().decode(TowerCar.self, from: Data(#"{"position":1,"carNumber":"7","inPit":false,"lastLapMs":98000,"bestLapMs":97100}"#.utf8))
        XCTAssertNil(TimingFormat.lastLapMark(slower, classBest: 97_100))
    }

    // MARK: Places gained, field counts, moves

    private func towerOf(_ cars: [(String, [String: Any])]) throws -> Tower {
        let rows: [[String: Any]] = cars.map { name, extra in
            ["position": extra["position"] ?? 1, "carNumber": name, "inPit": extra["inPit"] ?? false]
                .merging(extra) { _, new in new }
        }
        let body: [String: Any] = ["state": "LIVE", "classes": [["className": "GTD", "feedClass": "GTD", "cars": rows]],
                                   "matched": rows.count, "total": rows.count]
        return try JSONDecoder().decode(Tower.self, from: JSONSerialization.data(withJSONObject: body))
    }

    func testPlacesGainedAndTheFieldAtAGlance() throws {
        let t = try towerOf([
            ("7", ["position": 1, "startPosition": 3, "trackStatus": "TRACK"]),
            ("31", ["position": 2, "startPosition": 1, "inPit": true, "trackStatus": "BOX"]),
            ("04", ["position": 3, "trackStatus": "STOPPED"]),
            ("23", ["position": 4, "status": "RETIRED"]),
        ])
        XCTAssertEqual(TimingFormat.placesGained(t.classes[0].cars[0]), 2)
        XCTAssertEqual(TimingFormat.placesGained(t.classes[0].cars[1]), -1)
        XCTAssertNil(TimingFormat.placesGained(t.classes[0].cars[2]))
        XCTAssertEqual(TimingFormat.fieldCounts(t).line, "1 on track · 1 in pit · 1 stopped · 1 retired")
        let noDetails = try towerOf([("7", [:])])
        XCTAssertEqual(TimingFormat.fieldCounts(noDetails).line, "1 on track · 0 in pit · 0 retired",
                       "stopped only with participant details")
    }

    func testOnlyAChangeOfPlaceCountsAsAMove() throws {
        let before = try towerOf([("4", ["position": 1]), ("04", ["position": 2]), ("23", ["position": 3])])
        let after = try towerOf([("04", ["position": 1]), ("4", ["position": 2]), ("23", ["position": 3])])
        XCTAssertEqual(TimingFormat.moved(from: before, to: after), ["4", "04"])
        XCTAssertEqual(TimingFormat.moved(from: nil, to: after), [], "the first tower seen never flashes")
        XCTAssertEqual(TimingFormat.moved(from: after, to: after), [])
    }

    // MARK: Analysis (gaps, sectors, pits)

    func testTheGapReadoutSaysLeaderGapOrLapsDown() {
        let car = GapCar(carNumber: "7", firstLap: 3, gapMs: [0, 4200, nil, nil], lapsDown: [0, 0, 1, nil], pitLaps: [4])
        XCTAssertEqual(TimingFormat.gapAt(car, lap: 3), "Leader")
        XCTAssertEqual(TimingFormat.gapAt(car, lap: 4), "+4.200")
        XCTAssertEqual(TimingFormat.gapAt(car, lap: 5), "+1 lap")
        XCTAssertNil(TimingFormat.gapAt(car, lap: 6), "untimed")
        XCTAssertNil(TimingFormat.gapAt(car, lap: 2), "before its first lap")
        XCTAssertEqual(car.lastLap, 6)
    }

    func testBestSectorsSkipInvalidLaps() {
        func lap(_ n: Int, _ valid: Bool?, _ ms: [Int?]) -> LapRow {
            LapRow(lap: n, driverOrder: 1, driverLap: n, position: nil, startTimeMs: nil, lapTimeMs: 90_000, sectorMs: ms,
                   sectorFlags: nil, valid: valid, longLap: nil, shortLap: nil, trackLimits: nil, topSpeed: nil,
                   pitInMs: nil, pitOutMs: nil, energyPct: nil, energyUsedPct: nil)
        }
        let best = TimingFormat.bestSectors([lap(1, true, [30_000, 31_000, 29_000]),
                                             lap(2, false, [20_000, 20_000, 20_000]),
                                             lap(3, nil, [29_500, nil, 29_200])], count: 3)
        XCTAssertEqual(best, [29_500, 31_000, 29_000])
    }

    func testDecodesTheAnalysisAsTheServerWritesIt() throws {
        let gaps = try JSONDecoder().decode(GapsResponse.self, from: Data("""
        {"sessionDbId":9,"classes":[{"className":"GTD","color":null,"cars":[{"carNumber":"04","teamName":"Team","className":"GTD"}],
          "gaps":[{"carNumber":"04","firstLap":1,"gapMs":[0,null],"lapsDown":[0,null],"pitLaps":[]}]}]}
        """.utf8))
        XCTAssertEqual(gaps.classes[0].gaps[0].gapMs, [0, nil])

        let sectors = try JSONDecoder().decode(SectorsResponse.self, from: Data("""
        {"sessionDbId":9,"classes":[{"className":"GTD","color":"#00a","cars":[],"bests":{"sectors":2,"classBestSectorMs":[30000,null],
          "cars":[{"carNumber":"04","bestSectorMs":[30000,null],"bestSectorLap":[2,null],"bestLap":2,"bestLapMs":91000,"theoreticalMs":null}]}}]}
        """.utf8))
        XCTAssertNil(sectors.classes[0].bests.cars[0].theoreticalMs)

        let pits = try JSONDecoder().decode(PitsResponse.self, from: Data("""
        {"sessionDbId":9,"drivers":{"04":{"1":"One","2":null}},"classes":[{"className":"GTD","color":null,"cars":[],
          "pits":[{"carNumber":"04","stops":[{"number":1,"startTimeMs":1,"durationMs":65000,"lap":30,"pitType":null,
            "driverIn":1,"driverOut":2,"driverChange":true}],"totalMs":65000,"averageMs":65000,"inPit":false,"lapsSinceStop":4}]}]}
        """.utf8))
        XCTAssertTrue(pits.classes[0].pits[0].stops[0].driverChange)
        XCTAssertEqual(pits.driverName(car: "04", order: 1), "One")
        XCTAssertEqual(pits.driverName(car: "04", order: 2), "Driver 2", "a driver with no name on record")
        XCTAssertNil(pits.driverName(car: "04", order: nil))
    }
}
