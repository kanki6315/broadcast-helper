import Foundation

/// Live mode of the championship calculator: the scenario is not typed in, it
/// is where everyone is running. Same scales, same `project` — a live position
/// is scored exactly as one set by hand, so the two modes can never disagree.
///
/// Unlike a hand-built scenario, every standings row takes part (a leader who
/// is not running still belongs in the table, adding nothing), and the three
/// kinds are all covered: the server has already turned the running order into
/// each kind's scoring position (in class for teams and drivers, among makes
/// for manufacturers).
extension ChampionshipCalculator {
    static let liveKinds = ["TEAMS", "DRIVERS", "MANUFACTURERS"]

    /// The season championships live mode can score. Same series as the manual
    /// calculator — the scales are IMSA's — but all three kinds, and no cups:
    /// the Endurance Cup scores at checkpoints, which live mode does not model yet.
    static func supportedLive(_ c: ChampionshipSummary) -> Bool {
        let imsa = c.seriesName.localizedCaseInsensitiveContains("weathertech") || c.seriesName.uppercased() == "IMSA"
        return (imsa || isPilotChallenge(c.seriesName)) && liveKinds.contains(c.kind ?? "")
            && !c.isCup && c.rowCount > 0 && !(c.className ?? "").isEmpty
    }

    /// The scoring columns of a weekend: qualifying pays in WeatherTech, not in Pilot Challenge.
    static func phases(seriesName: String) -> [String] {
        isPilotChallenge(seriesName) ? ["Race"] : ["Qualifying", "Race"]
    }

    /// Every standings row's positions for the weekend's scoring columns.
    /// During a race: the live race position, plus the imported qualifying
    /// position (official standings leave it out until after the weekend).
    /// During qualifying: the live qualifying position, no race yet.
    /// During anything else nothing scores.
    static func liveScenario(_ live: LiveChampionship, seriesName: String) -> [String: Entry] {
        let columns = phases(seriesName: seriesName)
        var scenario: [String: Entry] = [:]
        for row in live.rows {
            let positions = columns.map { column -> Int in
                switch (column, live.livePhase) {
                case ("Race", "RACE"): row.live?.position ?? 0
                case ("Qualifying", "RACE"): row.qualifyingPosition ?? 0
                case ("Qualifying", "QUALIFYING"): row.live?.position ?? 0
                default: 0
                }
            }
            scenario[row.competitorKey] = Entry(positions: positions)
        }
        return scenario
    }

    /// One line of the live table: the projection, where the row is running,
    /// and how many places it moves against the imported standings.
    struct LiveLine: Identifiable {
        let projection: Projection
        let running: LiveRunning?
        let qualifyingPosition: Int?
        /// The position scored in each column of `phases`; 0 = none.
        let positions: [Int]
        /// Positive = places gained on the imported standings.
        let movement: Int
        var id: String { projection.id }
    }

    static func liveLines(_ recap: Recap, live: LiveChampionship) -> [LiveLine] {
        let series = recap.championship.seriesName
        let byKey = Dictionary(live.rows.map { ($0.competitorKey, $0) }, uniquingKeysWith: { first, _ in first })
        let scenario = liveScenario(live, seriesName: series)
        // Movement compares like with like: both ranks count the rows strictly
        // ahead on points. The standings source's own `position` cannot be used
        // — it ranks tied co-drivers 1, 1, 2, 2 where this ranks them 1, 1, 3, 3,
        // which would show every crew behind a tie as having lost places.
        let imported = recap.rows.map(\.totalPoints)
        return project(recap, scenario: scenario, cup: false, phaseCount: phases(seriesName: series).count)
            .map { projection in
                let row = byKey[projection.id]
                return LiveLine(projection: projection, running: row?.live,
                                qualifyingPosition: row?.qualifyingPosition,
                                positions: scenario[projection.id]?.positions ?? [],
                                movement: 1 + imported.filter { $0 > projection.row.totalPoints }.count - projection.rank)
            }
    }

    /// Who a row is. A drivers recap row carries the car and team it last
    /// raced with, so `name(_:)` would call Felipe Nasr "#7 · Porsche Penske".
    static func liveName(_ row: RecapRow, kind: String?) -> String {
        switch kind {
        case "DRIVERS", "MANUFACTURERS": row.competitorName.flatMap { $0.isEmpty ? nil : $0 } ?? row.competitorKey
        default: name(row)
        }
    }

    /// "Leader", "+1.830", "+1:02.4", "+2 laps".
    static func liveGap(_ running: LiveRunning) -> String {
        if let laps = running.gapToLeaderLaps, laps > 0 { return "+\(laps) \(laps == 1 ? "lap" : "laps")" }
        guard let ms = running.gapToLeaderMs, ms > 0 else { return running.position == 1 ? "Leader" : "" }
        let seconds = Double(ms) / 1000
        if seconds < 60 { return "+" + seconds.formatted(.number.precision(.fractionLength(3))) }
        let minutes = Int(seconds) / 60
        let rest = seconds - Double(minutes * 60)
        return "+\(minutes):" + (rest < 10 ? "0" : "") + rest.formatted(.number.precision(.fractionLength(1)))
    }
}
