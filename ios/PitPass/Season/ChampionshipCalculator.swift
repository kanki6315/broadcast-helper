import Foundation

/// Pure scenario arithmetic. Does not mutate Recap, SeasonModel or the offline store.
enum ChampionshipCalculator {
    static let sourceURL = URL(string: "https://www.imsa.com/wp-content/uploads/sites/32/2026/05/20/2026-IMSA-SPORTING-REGULATIONS-and-SSR-IWSC-Blackline-031126.pdf")!
    enum Phase { case qualifying, race, checkpoint }
    struct Entry {
        var positions: [Int]
        var adjustment: Double = 0
    }
    struct Projection: Identifiable {
        let row: RecapRow
        let awards: [Double]
        let added: Double
        let total: Double
        var rank: Int = 0
        var tied = false
        var gap: Double = 0
        var id: String { row.competitorKey }
    }
    static func supported(_ c: ChampionshipSummary) -> Bool {
        let imsa = c.seriesName.localizedCaseInsensitiveContains("weathertech") || c.seriesName.uppercased() == "IMSA"
        let family = (c.groupTitle ?? c.title).lowercased()
        return imsa && c.kind == "TEAMS" && c.rowCount > 0 && (!c.isCup || family.contains("endurance") || family.contains("imec"))
    }
    static func points(_ position: Int, phase: Phase) -> Double {
        guard position > 0 else { return 0 }
        if phase == .checkpoint { return Double(position == 1 ? 5 : position == 2 ? 4 : position == 3 ? 3 : 2) }
        let first = [35, 32, 30, 28, 26]
        let q = position <= 5 ? first[position - 1] : max(1, 31 - position)
        return Double(q * (phase == .race ? 10 : 1))
    }
    static func assign(_ scenario: [String: Entry], key: String, phase: Int, position: Int) -> [String: Entry] {
        var result = scenario
        guard let entry = scenario[key], entry.positions.indices.contains(phase) else { return result }
        let old = entry.positions[phase]
        for (id, entry) in scenario where entry.positions.indices.contains(phase) {
            if id == key { result[id]?.positions[phase] = position }
            else if position > 0 && entry.positions[phase] == position { result[id]?.positions[phase] = old }
        }
        return result
    }
    static func project(_ recap: Recap, scenario: [String: Entry], cup: Bool, phaseCount: Int) -> [Projection] {
        var rows = recap.rows.compactMap { row -> Projection? in
            guard let entry = scenario[row.competitorKey] else { return nil }
            let awards = (0..<phaseCount).map { i in
                points(entry.positions.indices.contains(i) ? entry.positions[i] : 0,
                       phase: cup ? .checkpoint : i == 0 ? .qualifying : .race)
            }
            let added = awards.reduce(0, +) + entry.adjustment
            return Projection(row: row, awards: awards, added: added, total: row.totalPoints + added)
        }.sorted {
            if $0.total != $1.total { return $0.total > $1.total }
            if $0.row.position != $1.row.position { return $0.row.position < $1.row.position }
            return $0.id < $1.id
        }
        let totals = rows.map(\.total)
        for i in rows.indices {
            rows[i].rank = 1 + totals.filter { $0 > rows[i].total }.count
            rows[i].tied = totals.filter { $0 == rows[i].total }.count > 1
            rows[i].gap = (totals.first ?? 0) - rows[i].total
        }
        return rows
    }
    static func baselineIssue(_ recap: Recap, eventId: Int) -> String? {
        guard let target = recap.rounds.first(where: { $0.eventId == eventId }) else {
            return "This event is not mapped to the imported championship calendar yet."
        }
        let covered = recap.rounds.contains { round in
            round.round >= target.round && recap.rows.contains { row in
                row.points(round: round.round) != nil || round.sessions.contains { row.sessionPoints(index: $0.sessionIndex)?.contested == true }
            }
        }
        return covered ? "The imported standings already include this event or a later round. Choose an unscored event to avoid counting points twice." : nil
    }
    static func name(_ row: RecapRow) -> String {
        (row.carNumber.map { "#\($0) · " } ?? "") + (row.teamName ?? row.competitorName ?? row.competitorKey)
    }
}
