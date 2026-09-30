import Foundation

// The live timing page's documents (docs/LIVE_TIMING.md "Timing page API"):
// mirrors of /api/live/timing, /cars/{car}, /drive-time and /sessions. Like
// every live document they are polled through `LiveFeed`, never stored — a
// tower that is not current is not a tower.

/// `GET /api/live/timing`.
struct Tower: Codable, Sendable, Equatable {
    let state: String
    let eventId: Int?
    let eventName: String?
    let session: LiveSession?
    let sessionDbId: Int?
    /// The newest time the feed itself reported: what a stint counts up to in
    /// a replay or a finished session.
    let feedClockMs: Int?
    let classes: [TowerClass]
    let matched: Int
    let total: Int
}

struct TowerClass: Codable, Sendable, Equatable, Identifiable {
    let className: String
    let feedClass: String
    /// The series' class_style colour ("#rrggbb"), or nil.
    let color: String?
    let cars: [TowerCar]
    var id: String { className }
}

struct TowerCar: Codable, Sendable, Equatable, Identifiable {
    let position: Int
    let carNumber: String
    let entryId: Int?
    let teamName: String?
    let vehicle: String?
    let manufacturer: String?
    let status: String?
    let laps: Int?
    let gapToLeaderMs: Int?
    let gapToLeaderLaps: Int?
    let intervalMs: Int?
    let intervalLaps: Int?
    let driverOrder: Int?
    let driverName: String?
    let driverShortName: String?
    let driverRating: String?
    let lastLap: Int?
    let lastLapMs: Int?
    let bestLap: Int?
    let bestLapMs: Int?
    let inPit: Bool
    let stintStartMs: Int?
    let stintLaps: Int?
    /// IMSA telemetry: % remaining, nil when off, unseen or stale.
    let energyPct: Double?
    let energyLapsLeft: Double?
    var id: String { carNumber }

    /// Classified or running; anything else (retired…) stays in place, muted.
    var running: Bool { status == nil || status == "CLASSIFIED" || status == "RUNNING" }
}

/// `GET /api/live/cars/{car}`.
struct CarDetail: Codable, Sendable, Equatable {
    let sessionDbId: Int
    let carNumber: String
    let drivers: [CarDriver]
    let laps: [LapRow]
    let stints: [StintRow]
}

struct CarDriver: Codable, Sendable, Equatable, Identifiable {
    let driverOrder: Int
    let firstName: String?
    let lastName: String?
    let shortName: String?
    let license: String?
    let rating: String?
    let driverId: Int?
    var id: Int { driverOrder }
    var name: String { [firstName, lastName].compactMap { $0 }.joined(separator: " ") }
}

struct LapRow: Codable, Sendable, Equatable, Identifiable {
    let lap: Int
    let driverOrder: Int?
    let driverLap: Int?
    let position: Int?
    let startTimeMs: Int?
    let lapTimeMs: Int?
    let sectorMs: [Int?]?
    let sectorFlags: [String?]?
    let valid: Bool?
    let longLap: Bool?
    let shortLap: Bool?
    let trackLimits: Int?
    let topSpeed: Double?
    let pitInMs: Int?
    let pitOutMs: Int?
    let energyPct: Double?
    let energyUsedPct: Double?
    var id: Int { lap }
}

struct StintRow: Codable, Sendable, Equatable, Identifiable {
    let startTimeMs: Int
    let type: String?
    let pitType: String?
    let driverOrder: Int?
    let openLap: Int?
    let closeLap: Int?
    let finishTimeMs: Int?
    let driverAccumSessionTrackMs: Int?
    let driverAccumSessionMs: Int?
    let driverAccumTrackMs: Int?
    let driverAccumMs: Int?
    let avgEnergyPerLapPct: Double?
    var id: Int { startTimeMs }
    var open: Bool { finishTimeMs == nil || finishTimeMs! <= 0 }
}

/// `GET /api/live/drive-time`.
struct DriveTimeResponse: Codable, Sendable, Equatable {
    let sessionDbId: Int
    let eventId: Int?
    let rules: [DriveTimeRule]
    let drivers: [DriveTimeResult]
}

struct DriveTimeRule: Codable, Sendable, Equatable, Identifiable {
    let className: String
    let rating: String?
    let minMs: Int?
    let maxMs: Int?
    let note: String?
    var id: String { "\(className)|\(rating ?? "")" }
}

struct DriveTimeResult: Codable, Sendable, Equatable, Identifiable {
    let car: String
    let driverOrder: Int
    let name: String?
    let rating: String?
    let driverId: Int?
    let className: String?
    let driveMs: Int
    let inCar: Bool
    let minMs: Int?
    let maxMs: Int?
    /// OK, UNDER_MIN, OVER_MAX, NO_RULE.
    let status: String
    let owedMs: Int?
    let remainingMs: Int?
    let overMs: Int?
    var id: String { "\(car)#\(driverOrder)" }
}

/// `GET /api/live/sessions?eventId=`.
struct LiveSessionSummary: Codable, Sendable, Equatable, Identifiable {
    let sessionDbId: Int
    let eventId: Int?
    let name: String?
    let type: String?
    let dateMs: Int?
    let laps: Int
    let cars: Int
    let current: Bool
    var id: Int { sessionDbId }
}
