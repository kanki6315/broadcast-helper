import Testing
@testable import PitPass

struct RecapRoundTests {
    private let round = RecapRound(round: 7, venue: "CTMP", eventId: 7, raceCount: 1,
                                   sessions: [RecapSession(sessionIndex: 7, name: "Race")], races: [])

    private func row(points: [String: Double] = [:], races: [RecapRace] = [],
                     sessionPoints: [String: RecapSessionPoints] = [:]) -> RecapRow {
        RecapRow(position: 1, competitorKey: "31", competitorName: nil, carNumber: "31",
                 teamName: "Cadillac Whelen", teamNames: nil, totalPoints: 2400,
                 pointsByRound: points, sessionPoints: sessionPoints, cells: ["7": races])
    }

    @Test func blankRoundsAreExcluded() {
        #expect(!round.hasParticipation(in: []))
        #expect(!round.hasParticipation(in: [row(points: ["6": 100])]))
    }

    @Test func zeroPointsAndNonFinishesCount() {
        #expect(round.hasParticipation(in: [row(points: ["7": 0])]))
        for status in ["not started", "retired"] {
            let race = RecapRace(race: 1, name: nil, carNumber: "31", start: nil,
                                 finish: nil, status: status, notFinished: true)
            #expect(round.hasParticipation(in: [row(races: [race])]))
        }
    }

    @Test func contestedSessionCountsWithoutRaceResults() {
        let points = RecapSessionPoints(total: 0, race: 0, pole: 0, fastestLap: 0,
                                        penalty: 0, bonus: 0, contested: true)
        #expect(round.hasParticipation(in: [row(sessionPoints: ["7": points])]))
        #expect(!round.hasParticipation(in: [row(sessionPoints: ["6": points])]))
    }
}
