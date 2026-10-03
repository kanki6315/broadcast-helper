import Foundation

// The live timing page's documents (docs/LIVE_TIMING.md "Timing page API"):
// mirrors of /api/live/timing, /cars/{car}, /drive-time and /sessions. Like
// every live document they are polled through `LiveFeed`, never stored — a
// tower that is not current is not a tower.

/// `GET /api/live/timing`. `eventId` is the connection's binding;
/// `filedEventId` the event the session on track is filed under — teams,
/// colours and drivers come from it, and nil means the feed's own.
struct Tower: Codable, Sendable, Equatable {
    let state: String
    let eventId: Int?
    let eventName: String?
    var filedEventId: Int? = nil
    var filedEventName: String? = nil
    let session: LiveSession?
    let sessionDbId: Int?
    /// The newest time the feed itself reported: what a stint counts up to in
    /// a replay or a finished session.
    let feedClockMs: Int?
    let classes: [TowerClass]
    let matched: Int
    let total: Int
    /// Race control's screen now and its newest message; nil when the feed sends no race control.
    var raceControl: RaceControlNow? = nil
    /// "mph" or "km/h", from the feed's unit of measure; nil before the feed says.
    var speedUnit: String? = nil
    /// The track's weather station, latest reading; nil when the feed sends no weather.
    var weather: WeatherReading? = nil
}

/// One weather station reading, in both unit systems (the backend converts
/// whichever the station did not send). `windDirection` is degrees as the
/// station sends it — taken as where the wind comes from, unverified.
struct WeatherReading: Codable, Sendable, Equatable {
    let dayTimeMs: Int?
    let airC: Double?
    let airF: Double?
    let trackC: Double?
    let trackF: Double?
    let humidityPct: Double?
    let pressureMbar: Double?
    let pressureInHg: Double?
    let windDirection: Int?
    let windKmh: Double?
    let windMph: Double?
}

/// `GET /api/live/weather?session=`, one a minute, oldest first.
struct WeatherLog: Codable, Sendable, Equatable {
    let sessionDbId: Int
    let readings: [WeatherReading]
}

/// One race control message. `dayTimeMs` is when it was shown (nil on the
/// screen's lines); colours are race control's own, "#rrggbb" or nil.
struct RaceControlMessage: Codable, Sendable, Equatable, Identifiable {
    let key: String
    let dayTimeMs: Int?
    let text: String
    /// Usually a class name, when race control addresses one.
    let group: String?
    let line: Int?
    let foreground: String?
    let background: String?
    let blink: Bool
    var id: String { key }
}

struct RaceControlNow: Codable, Sendable, Equatable {
    /// What race control's screen shows now, in line order. Often empty.
    let lines: [RaceControlMessage]
    let latest: RaceControlMessage?
}

/// `GET /api/live/race-control?session=`, newest first.
struct RaceControlLog: Codable, Sendable, Equatable {
    let sessionDbId: Int
    let messages: [RaceControlMessage]
}

struct TowerClass: Codable, Sendable, Equatable, Identifiable {
    let className: String
    let feedClass: String
    /// The series' class_style colour ("#rrggbb"), or nil.
    let color: String?
    let cars: [TowerCar]
    /// Per sector, the class's fastest time, its car and driver; empty without participant details.
    var bestSectors: [ClassSector]? = nil
    /// The class's best sectors summed.
    var idealMs: Int? = nil
    var id: String { className }
}

/// A class's fastest time in one sector, the car that holds it and its driver's surname.
struct ClassSector: Codable, Sendable, Equatable {
    let ms: Int?
    let car: String?
    var driver: String? = nil
}

/// A sector's newest time. `currentLap` false = the previous lap's, until the car runs that sector again.
struct SectorTime: Codable, Sendable, Equatable {
    let ms: Int
    let valid: Bool?
    let currentLap: Bool
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
    /// Participant details' BOX / OUT_LAP / TRACK / STOPPED; nil when that channel is off.
    var trackStatus: String? = nil
    /// Its place in its class on the starting grid, in a race; nil off the grid.
    var startPosition: Int? = nil
    /// Pit stops so far, as Al Kamel counts them; nil with no session recorded.
    var pitStops: Int? = nil
    /// Pit-lane time of the newest finished stop.
    var lastPitMs: Int? = nil
    /// Sector 1 first, as they are run; nil where the car has no time yet.
    var sectors: [SectorTime?]? = nil
    var bestSectorMs: [Int?]? = nil
    /// Place and gaps across every class, from the feed's overall standings; nil until they arrive.
    var overallPosition: Int? = nil
    var overallGapMs: Int? = nil
    var overallGapLaps: Int? = nil
    var overallIntervalMs: Int? = nil
    var overallIntervalLaps: Int? = nil
    /// Has taken the chequered flag.
    var checkered: Bool? = nil
    /// Full name of the driver who set `bestLapMs`.
    var bestLapDriver: String? = nil
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

/// `GET /api/live/sessions?eventId=` or `?feedEvent=`.
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

// MARK: - Analysis: /gaps, /sectors, /pits (backend LiveAnalysisService)

struct AnalysisCar: Codable, Sendable, Equatable, Identifiable {
    let carNumber: String
    let teamName: String?
    let className: String
    var id: String { carNumber }
}

/// One car's gap to its class leader after each lap, laps `firstLap`… in
/// order. `gapMs` is nil where the car was lapped (`lapsDown` > 0) or the lap
/// untimed; `lapsDown` is nil only where the lap was untimed.
struct GapCar: Codable, Sendable, Equatable, Identifiable {
    let carNumber: String
    let firstLap: Int
    let gapMs: [Int?]
    let lapsDown: [Int?]
    let pitLaps: [Int]
    var id: String { carNumber }
    var lastLap: Int { firstLap + gapMs.count - 1 }

    func gap(at lap: Int) -> Int? {
        let i = lap - firstLap
        return gapMs.indices.contains(i) ? gapMs[i] : nil
    }

    func down(at lap: Int) -> Int? {
        let i = lap - firstLap
        return lapsDown.indices.contains(i) ? lapsDown[i] : nil
    }
}

struct GapClass: Codable, Sendable, Equatable, Identifiable {
    let className: String
    let color: String?
    let cars: [AnalysisCar]
    /// Running order: most laps, then earliest to finish the last.
    let gaps: [GapCar]
    var id: String { className }
}

/// `GET /api/live/gaps?session=`.
struct GapsResponse: Codable, Sendable, Equatable {
    let sessionDbId: Int
    let classes: [GapClass]
}

/// By sector, index 0 = S1; nil where the car has no valid time.
struct SectorCar: Codable, Sendable, Equatable, Identifiable {
    let carNumber: String
    let bestSectorMs: [Int?]
    let bestSectorLap: [Int?]
    let bestLap: Int?
    let bestLapMs: Int?
    let theoreticalMs: Int?
    var id: String { carNumber }
}

struct SectorBests: Codable, Sendable, Equatable {
    let sectors: Int
    let classBestSectorMs: [Int?]
    let cars: [SectorCar]
}

struct SectorClass: Codable, Sendable, Equatable, Identifiable {
    let className: String
    let color: String?
    let cars: [AnalysisCar]
    let bests: SectorBests
    var id: String { className }
}

/// `GET /api/live/sectors?session=`.
struct SectorsResponse: Codable, Sendable, Equatable {
    let sessionDbId: Int
    let classes: [SectorClass]
}

struct PitStop: Codable, Sendable, Equatable, Identifiable {
    let number: Int
    let startTimeMs: Int
    /// Pit-lane time; nil while the car is still in.
    let durationMs: Int?
    let lap: Int?
    let pitType: String?
    let driverIn: Int?
    let driverOut: Int?
    let driverChange: Bool
    var id: Int { startTimeMs }
}

struct PitCar: Codable, Sendable, Equatable, Identifiable {
    let carNumber: String
    let stops: [PitStop]
    let totalMs: Int
    let averageMs: Int?
    let inPit: Bool
    let lapsSinceStop: Int?
    var id: String { carNumber }
}

struct PitClass: Codable, Sendable, Equatable, Identifiable {
    let className: String
    let color: String?
    let cars: [AnalysisCar]
    let pits: [PitCar]
    var id: String { className }
}

/// `GET /api/live/pits?session=`.
struct PitsResponse: Codable, Sendable, Equatable {
    let sessionDbId: Int
    /// Car → driver order (as a string key) → surname.
    let drivers: [String: [String: String?]]
    let classes: [PitClass]

    func driverName(car: String, order: Int?) -> String? {
        guard let order else { return nil }
        return (drivers[car]?[String(order)] ?? nil) ?? "Driver \(order)"
    }
}

// MARK: - Series weekends: /weekends, /feed-events/{id} (backend LiveWeekends)

/// An event an admin could file a series weekend under.
struct EventOption: Codable, Sendable, Equatable, Identifiable {
    let id: Int
    let name: String
    let seriesName: String
    let date: String?
}

/// One series at one weekend: Al Kamel's feed event. `boundBy`: nil = not
/// filed yet (automatic filing keeps trying), AUTO, ADMIN, ADMIN_NONE (an
/// admin said it is not in Pit Pass).
struct WeekendChampionship: Codable, Sendable, Equatable, Identifiable {
    let feedEventDbId: Int
    let champDbId: Int?
    let champName: String?
    let feedEventName: String?
    let track: String?
    let eventId: Int?
    let eventName: String?
    let boundBy: String?
    let firstSessionMs: Int?
    let lastSessionMs: Int?
    let sessions: [LiveSessionSummary]
    /// Events an admin could file it under: any series, dated near the weekend, its own series first.
    let candidates: [EventOption]
    var id: Int { feedEventDbId }

    /// What to call it: the feed's championship, else its event id.
    var title: String { champName ?? "Feed event \(feedEventDbId)" }

    var filedUnder: String {
        if let eventName { return eventName }
        return boundBy == "ADMIN_NONE" ? "Not in Pit Pass" : "Not filed under a Pit Pass event"
    }
}

/// `PUT /api/live/feed-events/{id}/event`: exactly one of an event, none
/// ("not in Pit Pass") or auto (back to automatic filing). Admin-only.
struct FeedEventBinding: Codable, Sendable {
    var eventId: Int? = nil
    var none: Bool? = nil
    var auto: Bool? = nil
}

/// `GET /api/live/weekends`: every series at one track within a few days.
struct LiveWeekend: Codable, Sendable, Equatable, Identifiable {
    let track: String?
    let fromMs: Int?
    let toMs: Int?
    let championships: [WeekendChampionship]
    var id: String { championships.map { String($0.feedEventDbId) }.joined(separator: "-") }
}
