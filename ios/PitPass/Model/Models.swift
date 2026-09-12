import Foundation

// Wire shapes, mirroring frontend/src/lib/api.ts. Keep field names identical
// to the JSON — decoding is by name.

struct Me: Codable, Sendable, Equatable {
    let authEnabled: Bool
    let email: String?
    let isAdmin: Bool
}

struct DeviceIssued: Codable, Sendable {
    let token: String
    let email: String
    let deviceName: String
}

struct SeriesInfo: Codable, Sendable, Identifiable, Hashable {
    let id: Int
    let name: String
    let abbreviation: String?
    let primaryKind: String?
    let aliases: [String]
    let logoVersion: Int?
}

struct SeasonSummary: Codable, Sendable, Identifiable, Hashable {
    let id: Int
    let year: Int
    let seriesId: Int
    let seriesName: String
    /// MAIN is the series proper; QUALIFIER a stage that selects its grid.
    let kind: String
    let label: String?
    let roundCount: Int
    let championshipCount: Int

    var isQualifier: Bool { kind == "QUALIFIER" }
}

struct ClassStyle: Codable, Sendable, Hashable {
    let classCode: String
    let ordinal: Int
    let color: String
}

struct ClassStylesResponse: Codable, Sendable {
    let styles: [ClassStyle]
    let unconfiguredClasses: [String]
}
