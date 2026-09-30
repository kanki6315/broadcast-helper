import Foundation

/// How every cell of the timing page reads — a port of the web's
/// `frontend/src/lib/liveTiming.ts`, so a lap time, a gap or a drive time says
/// the same thing on the iPad as in the browser beside it.
enum TimingFormat {

    /// 1:38.765, or 38.765 under a minute; h:mm:ss.sss past an hour. "—" for nothing.
    static func lapTime(_ ms: Int?) -> String {
        guard let ms, ms > 0 else { return "—" }
        let h = ms / 3_600_000
        let m = (ms % 3_600_000) / 60_000
        let s = (ms % 60_000) / 1000
        let frac = pad(ms % 1000, 3)
        if h > 0 { return "\(h):\(pad(m)):\(pad(s)).\(frac)" }
        if m > 0 { return "\(m):\(pad(s)).\(frac)" }
        return "\(s).\(frac)"
    }

    /// +4.200, +1:02.345, +1 lap, +2 laps; empty for the leader.
    static func gap(ms: Int?, laps: Int?) -> String {
        if let laps, laps != 0 { return "+\(abs(laps)) \(abs(laps) == 1 ? "lap" : "laps")" }
        guard let ms, ms != 0 else { return "" }
        return "+" + lapTime(abs(ms))
    }

    /// 2:04:09, or 4:09 under an hour; negative clamps to zero.
    static func duration(_ ms: Int?) -> String {
        guard let ms else { return "—" }
        let total = max(0, ms / 1000)
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? "\(h):\(pad(m)):\(pad(s))" : "\(m):\(pad(s))"
    }

    /// A rule bound, always with hours so a column of them aligns: 1:30:00.
    static func ruleTime(_ ms: Int?) -> String {
        guard let ms else { return "" }
        let total = max(0, ms / 1000)
        return "\(total / 3600):\(pad((total % 3600) / 60)):\(pad(total % 60))"
    }

    /// GREEN → "Green", FULL_YELLOW → "Full yellow".
    static func flagLabel(_ flag: String?) -> String? {
        guard let flag else { return nil }
        let words = flag.lowercased().split(whereSeparator: { $0 == "_" || $0 == " " }).map(String.init)
        guard let first = words.first else { return nil }
        return ([first.prefix(1).uppercased() + first.dropFirst()] + words.dropFirst()).joined(separator: " ")
    }

    enum FlagTone { case green, yellow, red, neutral }

    static func flagTone(_ flag: String?) -> FlagTone {
        let f = (flag ?? "").uppercased()
        if f.contains("RED") { return .red }
        if f.contains("YELLOW") || f.contains("SC") || f.contains("CODE") { return .yellow }
        if f.contains("GREEN") { return .green }
        return .neutral
    }

    static func ratingName(_ code: String?) -> String? {
        guard let code, let first = code.uppercased().first else { return nil }
        return ["B": "Bronze", "S": "Silver", "G": "Gold", "P": "Platinum"][String(first)] ?? code
    }

    /// "Now" for a stint's running time: the wall clock while the feed is live
    /// and its newest time is recent, otherwise that newest time — a replay or
    /// a finished session must not count against today's clock.
    static func feedNow(state: String, finished: Bool, feedClockMs: Int?, wallMs: Int) -> Int? {
        guard let clock = feedClockMs, clock > 0 else { return state == "LIVE" ? wallMs : nil }
        let live = state == "LIVE" && !finished && wallMs >= clock && wallMs - clock < 10 * 60_000
        return live ? wallMs : clock
    }

    /// The class's fastest best lap, to mark in the tower.
    static func classBest(_ cars: [TowerCar]) -> Int? {
        cars.compactMap(\.bestLapMs).filter { $0 > 0 }.min()
    }

    /// "62.4%", or empty.
    static func pct(_ value: Double?, digits: Int = 1) -> String {
        guard let value else { return "" }
        return String(format: "%.\(digits)f%%", value)
    }

    private static func pad(_ n: Int, _ width: Int = 2) -> String {
        let s = String(n)
        return s.count >= width ? s : String(repeating: "0", count: width - s.count) + s
    }
}
