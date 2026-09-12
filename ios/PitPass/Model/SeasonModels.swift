import Foundation

// Season wire shapes, mirroring frontend/src/lib/api.ts. JSON object keys are
// strings, so every `Record<number, …>` lands as `[String: …]`; the helpers at
// the bottom look them up by round/session number.

struct CalendarEvent: Codable, Sendable, Identifiable, Hashable {
    let id: Int
    let name: String
    let circuitName: String?
    let eventDate: String?
    let roundOrdinal: Int?
    let entryCount: Int
    let sessionCount: Int
}

struct ChampionshipSummary: Codable, Sendable, Identifiable, Hashable {
    let id: Int
    let title: String
    let groupTitle: String?
    let className: String?
    let kind: String?
    /// The series' own wording for the kind ("Entrants"), else nil.
    let kindLabel: String?
    let isCup: Bool
    let year: Int
    let seasonId: Int
    let seriesName: String
    let rowCount: Int

    var family: String { groupTitle ?? title }
}

struct SeasonHub: Codable, Sendable {
    let id: Int
    let year: Int
    let seriesId: Int
    let seriesName: String
    let primaryKind: String?
    let kind: String
    let label: String?
    let events: [CalendarEvent]
    let championships: [ChampionshipSummary]
    let entryClasses: [String]?

    var isQualifier: Bool { kind == "QUALIFIER" }
}

// MARK: recap

struct RecapRaceRef: Codable, Sendable, Hashable {
    let ordinal: Int
    let name: String?
}

struct RecapSession: Codable, Sendable, Hashable {
    let sessionIndex: Int
    let name: String
}

struct RecapRound: Codable, Sendable, Hashable {
    let round: Int
    let venue: String
    let eventId: Int?
    let raceCount: Int
    let sessions: [RecapSession]
    let races: [RecapRaceRef]
}

struct RecapRace: Codable, Sendable, Hashable {
    let race: Int
    let name: String?
    let carNumber: String?
    let start: Int?
    let finish: Int?
    let status: String?
    let notFinished: Bool
}

struct RecapSessionPoints: Codable, Sendable, Hashable {
    let total: Double
    let race: Double
    let pole: Double
    let fastestLap: Double
    let penalty: Double
    let bonus: Double
    let contested: Bool
}

struct RecapRow: Codable, Sendable, Hashable {
    let position: Int
    let competitorKey: String
    let competitorName: String?
    let carNumber: String?
    let teamName: String?
    let teamNames: [String]?
    let totalPoints: Double
    let pointsByRound: [String: Double]
    let sessionPoints: [String: RecapSessionPoints]
    let cells: [String: [RecapRace]]

    func points(round: Int) -> Double? { pointsByRound[String(round)] }
    func races(round: Int) -> [RecapRace]? { cells[String(round)] }
    func sessionPoints(index: Int) -> RecapSessionPoints? { sessionPoints[String(index)] }
}

struct RecapChampionship: Codable, Sendable, Hashable {
    let id: Int
    let title: String
    let className: String?
    let kind: String?
    let family: String
    let isCup: Bool
    let isOverall: Bool
    let seasonId: Int
    let year: Int
    let seriesName: String
}

struct Recap: Codable, Sendable {
    let championship: RecapChampionship
    let rounds: [RecapRound]
    let rows: [RecapRow]
}

// MARK: lineups

struct LineupRound: Codable, Sendable, Hashable {
    let ordinal: Int
    let venue: String
    let eventId: Int
    let eventName: String
    let eventDate: String?
}

struct LineupDriver: Codable, Sendable, Hashable {
    let name: String
    let rating: String?
    let country: String?
    let isTbd: Bool
}

struct LineupCar: Codable, Sendable, Hashable {
    let carNumber: String
    let teamName: String?
    let isGuest: Bool
    let byRound: [String: [LineupDriver]]

    func crew(round: Int) -> [LineupDriver]? { byRound[String(round)] }
}

struct LineupClass: Codable, Sendable, Hashable {
    let className: String
    let color: String
    let cars: [LineupCar]
}

struct Lineups: Codable, Sendable {
    let seasonId: Int
    let rounds: [LineupRound]
    let classes: [LineupClass]
}

// MARK: season reference

struct RefRound: Codable, Sendable, Hashable {
    let ordinal: Int
    let venue: String
    let circuitName: String?
    let eventId: Int
    let raceCount: Int
}

struct RefRace: Codable, Sendable, Hashable {
    let raceOrdinal: Int
    let start: Int?
    let finish: Int?
    let status: String?
}

struct RefEntry: Codable, Sendable, Hashable {
    let carNumber: String
    let team: String?
    let isGuest: Bool
    let byRound: [String: [RefRace]]

    func races(round: Int) -> [RefRace]? { byRound[String(round)] }
}

struct RefClass: Codable, Sendable, Hashable {
    let className: String
    let color: String
    let entries: [RefEntry]
}

struct ReferenceTable: Codable, Sendable {
    let seasonId: Int
    let year: Int
    let seriesName: String
    let rounds: [RefRound]
    let classes: [RefClass]
}

// MARK: event results

struct ResultRow: Codable, Sendable, Hashable {
    let posOverall: Int?
    let posInClass: Int?
    let carNumber: String
    let className: String?
    let teamName: String?
    let drivers: String?
    let fastestLapDriver: String?
    let qualifyingDriver: String?
    let vehicle: String?
    let status: String?
    let laps: Int?
    let elapsedTime: String?
    let gapFirst: String?
    let fastestLapTime: String?
    let fastestLapNumber: Int?
    let pitStops: Int?
}

struct StartingGridRow: Codable, Sendable, Hashable {
    let posOverall: Int?
    let posInClass: Int?
    let carNumber: String
    let className: String?
    let teamName: String?
    let qualifyingTime: String?
    let startingDriver: String?
    let qualifyingDriver: String?
}

struct SessionNote: Codable, Sendable, Hashable {
    let text: String
    let carNumbers: [String]
}

struct SessionResults: Codable, Sendable, Hashable, Identifiable {
    let sessionId: Int
    let sessionType: String
    let name: String
    let reportMark: String?
    let notes: [SessionNote]
    let hasFlags: Bool
    let gridBasis: String?
    let results: [ResultRow]
    let grid: [StartingGridRow]

    var id: Int { sessionId }
    var isQualifying: Bool { sessionType == "QUALIFYING" }
    var isRace: Bool { sessionType == "RACE" }
}

struct FlagRecord: Codable, Sendable, Hashable, Identifiable {
    let seq: Int
    let wallTime: String?
    let elapsed: String?
    let recType: String
    let flag: String?
    let message: String?
    let flagTime: String?
    let accumTime: String?
    let lap: Int?
    let carNumbers: [String]

    var id: Int { seq }
}

struct EventResults: Codable, Sendable {
    let eventId: Int
    let eventName: String
    let circuitName: String?
    let eventDate: String?
    let roundOrdinal: Int?
    let seasonId: Int
    let year: Int
    let seriesName: String
    let sessions: [SessionResults]
}

// MARK: stats

struct FormatInfo: Codable, Sendable, Hashable {
    let id: Int?
    let name: String
    let ordinal: Int

    /// Stable key for a format, "none" for the unassigned bucket.
    var key: String { id.map(String.init) ?? "none" }
}

struct FormatLine: Codable, Sendable, Hashable {
    let formatId: Int?
    let starts: Int
    let wins: Int
    let podiums: Int
    let top5s: Int
    let dnfs: Int

    var key: String { formatId.map(String.init) ?? "none" }
}

struct QualiLine: Codable, Sendable, Hashable {
    let sessions: Int
    let poles: Int
    let top5s: Int
}

struct DriverStatsRow: Codable, Sendable, Hashable {
    let driverId: Int
    let driverName: String
    let className: String
    let carNumber: String?
    let teamName: String?
    let byFormat: [FormatLine]
    let quali: QualiLine
}

struct StatsTable: Codable, Sendable {
    let formats: [FormatInfo]
    let rows: [DriverStatsRow]
}

struct TeamStatsRow: Codable, Sendable, Hashable {
    let teamId: Int
    let teamName: String
    let className: String
    let carNumbers: String?
    let byFormat: [FormatLine]
    let quali: QualiLine
}

struct TeamStatsTable: Codable, Sendable {
    let formats: [FormatInfo]
    let rows: [TeamStatsRow]
}

// MARK: photos

struct CarImageSummary: Codable, Sendable, Hashable, Identifiable {
    let id: Int
    let carNumber: String
    let sourceFilename: String?
    let uploadedAt: String
}

/// `GET /api/car-images?seasonId=`: the uploaded images plus the cars still
/// missing one (the app only shows the former; matching stays on the website).
struct CarImagesOverview: Codable, Sendable {
    let images: [CarImageSummary]
}
