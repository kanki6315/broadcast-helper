import Foundation
import Testing
@testable import PitPass

struct ProfileLogicTests {
    private func line(_ name: String, starts: Int, wins: Int, podiums: Int) -> NamedFormatLine {
        NamedFormatLine(formatId: nil, formatName: name, starts: starts, wins: wins, podiums: podiums, top5s: 0, dnfs: 0)
    }

    private func season(_ series: String, year: Int, qualifier: Bool = false) -> SeasonStatLine {
        SeasonStatLine(seasonId: year, year: year, seriesName: series, className: "GTD", qualifier: qualifier,
                       seasonLabel: qualifier ? "Qualifying" : nil, byFormat: [], quali: QualiLine(sessions: 0, poles: 0, top5s: 0))
    }

    @Test func formatSplitNamesOnlyWhatItHas() {
        let text = CareerLines.formatSplit([line("Sprint", starts: 6, wins: 3, podiums: 4), line("Main", starts: 5, wins: 2, podiums: 0),
                                            line("Endurance", starts: 0, wins: 0, podiums: 0)],
                                           quali: QualiLine(sessions: 6, poles: 2, top5s: 5))
        #expect(text == "Sprint 3W 4P3 · Main 2W · 2 poles")
        #expect(CareerLines.formatSplit([line("Main", starts: 1, wins: 0, podiums: 0)], quali: QualiLine(sessions: 1, poles: 1, top5s: 1)) == "Main 0W · 1 pole")
        #expect(CareerLines.formatSplit([], quali: QualiLine(sessions: 0, poles: 0, top5s: 0)) == "")
    }

    @Test func allTimeLineNeedsTwoMainSeasons() {
        let stats = DriverStats(driverId: 1, career: CareerTotals(starts: 4, wins: 0, podiums: 0, top5s: 0, poles: 0, qualiTop5s: 0, dnfs: 0),
                                bySeries: [SeriesStatLine(seriesId: 10, seriesName: "PESC", byFormat: [], quali: QualiLine(sessions: 0, poles: 0, top5s: 0)),
                                           SeriesStatLine(seriesId: 20, seriesName: "IMSA", byFormat: [], quali: QualiLine(sessions: 0, poles: 0, top5s: 0))],
                                seasons: [season("PESC", year: 2025), season("PESC", year: 2026),
                                          season("IMSA", year: 2026), season("IMSA", year: 2025, qualifier: true)])
        #expect(CareerLines.multiSeasonSeries(stats) == [10])
        #expect(!CareerLines.isEmpty(stats))
    }

    @Test func chipsVanishWithoutStarts() {
        let none = CareerTotals(starts: 0, wins: 0, podiums: 0, top5s: 0, poles: 0, qualiTop5s: 0, dnfs: 0)
        #expect(CareerLines.chips(none).isEmpty)
        let some = CareerTotals(starts: 12, wins: 3, podiums: 5, top5s: 8, poles: 2, qualiTop5s: 6, dnfs: 1)
        #expect(CareerLines.chips(some).map(\.label) == ["Starts", "Wins", "Podiums", "Top 5s", "Poles", "DNFs"])
        #expect(CareerLines.chips(some).map(\.value) == [12, 3, 5, 8, 2, 1])
    }

    @Test func bioDatesAndAge() {
        let now = Calendar(identifier: .gregorian).date(from: DateComponents(year: 2026, month: 9, day: 12))!
        #expect(Bio.formatDob("2001-05-04") == "4 May 2001")
        #expect(Bio.formatDob("sometime") == "sometime")
        #expect(Bio.age(from: "2001-05-04", now: now) == 25)
        #expect(Bio.age(from: "2001-09-13", now: now) == 24)
        #expect(Bio.age(from: "2001-09-12", now: now) == 25)
        #expect(Bio.age(from: "2030-01-01", now: now) == nil)
        #expect(Bio.age(from: "1800-01-01", now: now) == nil)
        #expect(Bio.age(from: "nope", now: now) == nil)
    }

    @Test func factsFollowTheWebOrder() {
        let p = DriverProfile(id: 1, name: "A B", country: "USA", hometown: "Portland, OR", dateOfBirth: "2001-05-04",
                              placeOfBirth: "", pronunciation: nil, notes: nil, photoVersion: nil, rating: nil,
                              carNumber: nil, teamName: nil, className: nil, year: nil, seriesName: nil, championships: [])
        let now = Calendar(identifier: .gregorian).date(from: DateComponents(year: 2026, month: 9, day: 12))!
        #expect(Bio.facts(p, now: now).map(\.label) == ["Born", "Age", "Hometown"])
        #expect(Bio.isPrivateer(" privateer "))
        #expect(!Bio.isPrivateer("Privateer Racing"))
    }

    @Test func driverLookupRules() {
        #expect(!DriverLookup.isLookupable("TBD"))
        #expect(!DriverLookup.isLookupable("  "))
        #expect(DriverLookup.isLookupable("Jack Aitken"))
        #expect(DriverLookup.searchPath("Jack Aitken") == "/api/drivers/search?q=Jack%20Aitken&limit=5")
        #expect(DriverLookup.searchPath("O'Ward") == "/api/drivers/search?q=O%27Ward&limit=5")
        func hit(_ id: Int, _ name: String) -> DriverSearchHit {
            DriverSearchHit(id: id, name: name, country: nil, rating: nil, carNumber: nil, teamName: nil, className: nil, year: nil, seriesName: nil)
        }
        #expect(DriverLookup.pick([hit(1, "Jack Aitkens"), hit(2, " jack aitken ")], wanted: "Jack Aitken")?.id == 2)
        #expect(DriverLookup.pick([hit(1, "Jack Aitkens")], wanted: "Jack Aitken")?.id == 1)
        #expect(DriverLookup.pick([], wanted: "Jack Aitken") == nil)
        #expect(InfoTarget.driver(named: "TBD") == nil)
        #expect(InfoTarget.driver(named: " Jack Aitken ") == .driverName("Jack Aitken"))
        #expect(InfoTarget.team(named: "Privateer") == nil)
        #expect(InfoTarget.team(named: "") == nil)
        #expect(InfoTarget.team(named: "Cadillac Whelen") == .team(name: "Cadillac Whelen"))
    }

    @Test func crewSplitting() {
        #expect(CrewNames.split("A B, C D") == ["A B", "C D"])
        #expect(CrewNames.split(nil).isEmpty)
        #expect(CrewNames.split("") .isEmpty)
    }

    @Test func roundKeyedMapsDecodeFromObjectKeys() throws {
        let json = """
        {"championshipId":5,"title":"GTD Drivers","className":"GTD","seriesName":"IMSA","year":2026,"seasonId":3,
         "position":2,"totalPoints":1234.5,"carNumber":"31","teamName":"Cadillac Whelen",
         "rounds":[],"cells":{"7":[{"race":1,"name":null,"carNumber":"31","start":3,"finish":1,"status":"Running","notFinished":false}]},
         "pointsByRound":{"7":350}}
        """
        let m = try JSONDecoder().decode(DriverChampMatrix.self, from: Data(json.utf8))
        #expect(m.races(round: 7)?.first?.finish == 1)
        #expect(m.points(round: 7) == 350)
        #expect(m.points(round: 8) == nil)
    }
}
