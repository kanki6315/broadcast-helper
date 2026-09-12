import Foundation

// The event sheet payload (`GET /api/events/{id}/sheet`, SheetController) and
// the pit-lane assignments — mirrors of SheetPage.tsx / PitLaneModal.tsx.

struct SheetDriver: Codable, Sendable, Hashable {
    let name: String
    let rating: String?
    let isTbd: Bool
    let nationality: String?
}

/// One race in the form strip — the recap's start→finish vocabulary.
struct FormRace: Codable, Sendable, Hashable {
    let raceOrdinal: Int
    let start: Int?
    let finish: Int?
    let status: String?
}

struct FormRound: Codable, Sendable, Hashable {
    let ordinal: Int
    let venue: String
    let raceCount: Int
}

struct SheetEntry: Codable, Sendable, Hashable, Identifiable {
    let entryId: Int
    let carNumber: String
    let teamName: String
    let vehicle: String?
    let manufacturer: String?
    let manufacturerLogoVersion: Int?
    /// Monochrome mark: recolour it white in dark theme instead of the white pill.
    let manufacturerLogoInvert: Bool
    let isGuest: Bool
    let drivers: [SheetDriver]
    let qualifying: String?
    let startingDriver: String?
    let championship: String?
    let form: [String: [FormRace]]
    let priorYearNote: String?
    let priorYearAuto: Bool
    let imageVersion: Int?
    let teamSheetPage: Int?

    var id: Int { entryId }
    func races(round: Int) -> [FormRace] { form[String(round)] ?? [] }
}

struct SheetClass: Codable, Sendable, Hashable, Identifiable {
    let className: String
    let color: String
    let entries: [SheetEntry]
    var id: String { className }
}

struct Sheet: Codable, Sendable {
    let eventId: Int
    let seasonId: Int?
    let eventName: String
    let circuitName: String?
    let eventDate: String?
    let year: Int
    let roundOrdinal: Int?
    let seriesName: String
    let championshipLabel: String
    let priorYearLabel: String
    let teamSheetsVersion: Int?
    let pitAssignmentsVersion: Int?
    let storylinesVersion: Int?
    let formRounds: [FormRound]
    let classes: [SheetClass]

    var teamSheetsPath: String? { teamSheetsVersion.map { "/api/events/\(eventId)/team-sheets/data?v=\($0)" } }
    var storylinesPath: String? { storylinesVersion.map { "/api/events/\(eventId)/storylines/data?v=\($0)" } }
}

// MARK: pit lane

struct PitLandmark: Codable, Sendable, Hashable {
    let afterBox: Int
    let label: String
}

struct PitAssignmentRow: Codable, Sendable, Hashable {
    let boxNumber: Int
    let carNumber: String
    let teamName: String?
    let entryId: Int?
    let entryTeam: String?
    let className: String?
}

struct PitAnchor: Codable, Sendable, Hashable {
    let boxNumber: Int
    let lat: Double
    let lng: Double
    let accuracyM: Double?
}

struct PitAssignments: Codable, Sendable {
    let filename: String?
    let uploadedAt: String
    let version: Int
    let versionNote: String?
    let rows: [PitAssignmentRow]
    let landmarks: [PitLandmark]
    let anchors: [PitAnchor]
}

/// Alpha-3 nationality (IMSA timing files) → flag emoji via alpha-2.
enum Flags {
    private static let alpha3ToAlpha2: [String: String] = [
        "ARG": "ar", "AUS": "au", "AUT": "at", "BEL": "be", "BGR": "bg", "BHR": "bh", "BRA": "br",
        "CAN": "ca", "CHE": "ch", "CHL": "cl", "CHN": "cn", "COL": "co", "CRI": "cr", "CYM": "ky",
        "CZE": "cz", "DEU": "de", "DNK": "dk", "EGY": "eg", "ESP": "es", "EST": "ee", "FIN": "fi",
        "FRA": "fr", "GBR": "gb", "GRC": "gr", "HKG": "hk", "HRV": "hr", "HUN": "hu", "IDN": "id",
        "IND": "in", "IRL": "ie", "ISL": "is", "ISR": "il", "ITA": "it", "JPN": "jp", "KOR": "kr",
        "LUX": "lu", "MCO": "mc", "MEX": "mx", "MYS": "my", "NLD": "nl", "NOR": "no", "NZL": "nz",
        "PHL": "ph", "POL": "pl", "PRT": "pt", "QAT": "qa", "ROU": "ro", "RUS": "ru", "SAU": "sa",
        "SGP": "sg", "SRB": "rs", "SVK": "sk", "SVN": "si", "SWE": "se", "THA": "th", "TUR": "tr",
        "TWN": "tw", "UKR": "ua", "URY": "uy", "USA": "us", "VEN": "ve", "ZAF": "za", "ZWE": "zw",
    ]

    static func emoji(_ alpha3: String?) -> String? {
        guard let alpha3, let a2 = alpha3ToAlpha2[alpha3.uppercased()] else { return nil }
        return String(a2.uppercased().unicodeScalars.compactMap { UnicodeScalar(0x1F1E6 + Int($0.value) - 65).map(Character.init) })
    }
}
