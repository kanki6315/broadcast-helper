import Foundation

// Driver and team profiles — mirrors of the `driver profile`, `stats` and
// `team profile` blocks in frontend/src/lib/api.ts (DriverController and
// TeamController records). Round-keyed maps arrive as JSON objects, so their
// keys decode as strings like the recap's.

// MARK: drivers

struct DriverSearchHit: Codable, Sendable, Hashable, Identifiable {
    let id: Int
    let name: String
    let country: String?
    let rating: String?
    let carNumber: String?
    let teamName: String?
    let className: String?
    let year: Int?
    let seriesName: String?
}

struct DriverChampMatrix: Codable, Sendable, Hashable, Identifiable {
    let championshipId: Int
    let title: String
    let className: String?
    let seriesName: String
    let year: Int
    let seasonId: Int
    let position: Int
    let totalPoints: Double
    let carNumber: String?
    let teamName: String?
    let rounds: [RecapRound]
    let cells: [String: [RecapRace]]
    let pointsByRound: [String: Double]

    var id: Int { championshipId }
    func races(round: Int) -> [RecapRace]? { cells[String(round)] }
    func points(round: Int) -> Double? { pointsByRound[String(round)] }
}

struct DriverProfile: Codable, Sendable, Hashable {
    let id: Int
    let name: String
    let country: String?
    let hometown: String?
    /// ISO date ("2001-05-04").
    let dateOfBirth: String?
    let placeOfBirth: String?
    let pronunciation: String?
    let notes: String?
    let photoVersion: Int?
    let rating: String?
    let carNumber: String?
    let teamName: String?
    let className: String?
    let year: Int?
    let seriesName: String?
    let championships: [DriverChampMatrix]

    var photoPath: String? { photoVersion.map { "/api/drivers/\(id)/photo?v=\($0)" } }
}

// MARK: career stats (shared by both profiles)

struct NamedFormatLine: Codable, Sendable, Hashable {
    let formatId: Int?
    let formatName: String
    let starts: Int
    let wins: Int
    let podiums: Int
    let top5s: Int
    let dnfs: Int
}

struct SeasonStatLine: Codable, Sendable, Hashable {
    let seasonId: Int
    let year: Int
    let seriesName: String
    let className: String
    /// True for qualifying stages — shown badged, excluded from every rollup.
    let qualifier: Bool
    let seasonLabel: String?
    let byFormat: [NamedFormatLine]
    let quali: QualiLine
}

struct SeriesStatLine: Codable, Sendable, Hashable {
    let seriesId: Int
    let seriesName: String
    let byFormat: [NamedFormatLine]
    let quali: QualiLine
}

struct CareerTotals: Codable, Sendable, Hashable {
    let starts: Int
    let wins: Int
    let podiums: Int
    let top5s: Int
    let poles: Int
    let qualiTop5s: Int
    let dnfs: Int
}

/// The three grains the driver and team stats endpoints share (CareerStats.tsx).
protocol CareerStatsData {
    var career: CareerTotals { get }
    var bySeries: [SeriesStatLine] { get }
    var seasons: [SeasonStatLine] { get }
}

struct DriverStats: Codable, Sendable, Hashable, CareerStatsData {
    let driverId: Int
    let career: CareerTotals
    let bySeries: [SeriesStatLine]
    let seasons: [SeasonStatLine]
}

struct TeamStats: Codable, Sendable, Hashable, CareerStatsData {
    let teamId: Int
    let career: CareerTotals
    let bySeries: [SeriesStatLine]
    let seasons: [SeasonStatLine]
}

// MARK: teams

struct TeamRosterDriver: Codable, Sendable, Hashable {
    let driverId: Int?
    let name: String
    let rating: String?
    let isTbd: Bool
}

struct TeamRosterCar: Codable, Sendable, Hashable, Identifiable {
    let entryId: Int
    let carNumber: String
    let className: String
    let classColor: String
    let vehicle: String?
    let imageVersion: Int?
    let drivers: [TeamRosterDriver]

    var id: Int { entryId }
    var liveryPath: String? { imageVersion.map { "/api/entries/\(entryId)/image?variant=sheet&v=\($0)" } }
}

struct TeamRosterSeason: Codable, Sendable, Hashable, Identifiable {
    let seasonId: Int
    let year: Int
    let seriesName: String
    let eventName: String
    let cars: [TeamRosterCar]

    var id: Int { seasonId }
}

struct TeamChampEntry: Codable, Sendable, Hashable, Identifiable {
    let carNumber: String
    let position: Int
    let totalPoints: Double
    let cells: [String: [RecapRace]]
    let pointsByRound: [String: Double]

    var id: String { carNumber }
    func races(round: Int) -> [RecapRace]? { cells[String(round)] }
    func points(round: Int) -> Double? { pointsByRound[String(round)] }
}

struct TeamChampMatrix: Codable, Sendable, Hashable, Identifiable {
    let championshipId: Int
    let title: String
    let className: String?
    let seriesName: String
    let year: Int
    let seasonId: Int
    let rounds: [RecapRound]
    let entries: [TeamChampEntry]

    var id: Int { championshipId }
}

struct TeamRef: Codable, Sendable, Hashable, Identifiable {
    let id: Int
    let name: String
}

struct TeamLineage: Codable, Sendable, Hashable {
    let predecessor: TeamRef?
    let successors: [TeamRef]
}

struct TeamProfile: Codable, Sendable, Hashable {
    /// Null only for a legacy spelling that has entries but no team entity.
    let teamId: Int?
    let name: String
    let notes: String?
    let lineage: TeamLineage?
    let roster: [TeamRosterSeason]
    let championships: [TeamChampMatrix]
}
