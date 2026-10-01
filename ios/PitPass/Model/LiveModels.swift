import Foundation

// Live timing (docs/LIVE_TIMING.md). Mirrors of /api/live/*; only the fields
// the app reads. None of these documents is ever stored offline — a running
// order is worthless the moment it stops being current.

/// `GET /api/live/status`.
struct LiveStatus: Codable, Sendable, Equatable {
    /// NOT_CONFIGURED, OFF, STANDBY, CONNECTING, LIVE, BACKING_OFF.
    let state: String
    let configured: Bool
    let replaying: Bool
    let desiredConnected: Bool
    /// The Pit Pass event the connection is bound to: only a hint for filing
    /// (docs/LIVE_TIMING.md "Which event a session belongs to"); nil when an
    /// admin connected for every series.
    let eventId: Int?
    let eventName: String?
    /// The event the session on track is filed under: where its Pit Pass
    /// rows (teams, colours, drivers, championships) come from. Nil = filed
    /// nowhere — a series Pit Pass does not follow, shown as the feed has it.
    var filedEventId: Int? = nil
    var filedEventName: String? = nil
    let requestedBy: String?
    let lastError: String?
    let lastWarning: String?
    /// What the feed itself says is on track — shown so a mismatch with the
    /// bound event (another series' session) is visible.
    let session: LiveSession?

    /// A running order exists: current (LIVE) or last known (BACKING_OFF).
    var hasOrder: Bool { state == "LIVE" || state == "BACKING_OFF" }
}

struct LiveSession: Codable, Sendable, Equatable {
    let championship: String?
    let event: String?
    let name: String?
    let type: String?
    let flag: String?
    let running: Bool
    let finished: Bool
    /// Al Kamel's event id: the series weekend this session belongs to.
    var feedEventDbId: Int? = nil
}

/// `POST /api/live/connect`. A nil event connects for every series: sessions
/// are filed by championship, and any earlier binding is cleared.
struct LiveConnectRequest: Codable, Sendable {
    let eventId: Int?
}

/// `GET /api/live/championships/{id}`: one class championship's rows against
/// the running order. Positions only — the points scales live in
/// `ChampionshipCalculator`, applied exactly as to positions set by hand.
struct LiveChampionship: Codable, Sendable, Equatable {
    let state: String
    let eventId: Int?
    let session: LiveSession?
    let championshipId: Int
    let kind: String
    let className: String
    /// RACE or QUALIFYING: the column live positions belong to. Nil for a
    /// session that scores nothing (practice).
    let livePhase: String?
    /// Whether this weekend's qualifying result is imported — official
    /// standings leave qualifying points out until after the race, so a race
    /// projection without it is short by them.
    let qualifyingImported: Bool
    let rows: [LiveRow]
    let newcomers: [LiveNewcomer]
}

struct LiveRow: Codable, Sendable, Equatable {
    let competitorKey: String
    let live: LiveRunning?
    let qualifyingPosition: Int?
}

/// The car a row is scoring with. `position` is already by the championship's
/// rule: in class for teams and drivers, among makes for manufacturers.
struct LiveRunning: Codable, Sendable, Equatable {
    let position: Int
    let carNumber: String
    let teamName: String?
    let status: String?
    let laps: Int?
    let gapToLeaderMs: Int?
    let gapToLeaderLaps: Int?
}

/// Scoring right now without a standings row (a late entry, an endurance-only driver).
struct LiveNewcomer: Codable, Sendable, Equatable, Identifiable {
    let name: String
    let carNumber: String
    let position: Int
    var id: String { "\(carNumber)|\(name)" }
}
