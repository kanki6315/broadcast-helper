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

    /// What the header clock shows, as on the web (liveTiming.ts sessionClock).
    struct ClockReading: Equatable {
        let time: String
        let note: String
        let stopped: Bool
        let laps: String?
    }

    /// Time-limited sessions count down: the time run is now (or the stop)
    /// less the start and the time stopped. A lap-limited one shows the
    /// leader's lap. nil for no clock, a MANUAL session or a finished one.
    /// "Now" is the wall clock while the feed is live and the scheduled end
    /// is still ahead, else the feed's own newest time, so a replay shows the
    /// clock as it stood. A practice red flag does not stop the clock; only
    /// the feed's isSessionRunning does.
    static func sessionClock(_ tower: Tower, wallMs: Int) -> ClockReading? {
        guard let session = tower.session, let c = session.clock, !session.finished else { return nil }
        var laps: String?
        if let final = c.finalLaps, c.finalType == "BY_LAPS" || c.finalType == "BY_LAPS_WITH_MAX_TIME" {
            laps = c.currentLap.map { "Lap \(min($0, final)) of \(final)" } ?? "\(final) laps"
        }
        guard let finalMs = c.finalMs, c.finalType != "BY_LAPS", c.finalType != "MANUAL" else {
            return laps.map { ClockReading(time: $0, note: "", stopped: false, laps: nil) }
        }
        guard let start = c.startMs else { return ClockReading(time: duration(finalMs), note: "Not started", stopped: false, laps: laps) }
        let scheduledEnd = start + finalMs + c.stoppedMs
        let now = c.stopMs ?? (tower.state == "LIVE" && wallMs < scheduledEnd ? wallMs
            : feedNow(state: tower.state, finished: session.finished, feedClockMs: tower.feedClockMs, wallMs: wallMs))
        guard let now else { return nil }
        let left = max(0, finalMs - (now - start - c.stoppedMs))
        return ClockReading(time: duration(left), note: c.stopMs != nil ? "Clock stopped" : "to go",
                            stopped: c.stopMs != nil, laps: laps)
    }

    /// Time of day at the track (24 h, h:mm:ss) from the feed's UTC offset.
    static func trackTime(wallMs: Int, utcOffsetHours: Double?) -> String? {
        guard let offset = utcOffsetHours else { return nil }
        let secs = ((wallMs / 1000 + Int(offset * 3600)) % 86_400 + 86_400) % 86_400
        return "\(secs / 3600):\(pad((secs % 3600) / 60)):\(pad(secs % 60))"
    }

    /// When a race control message was shown: the track's time of day when its
    /// offset is known, else the device's own clock (24 h, h:mm:ss).
    static func messageTime(dayTimeMs: Int?, utcOffsetHours: Double?) -> String? {
        guard let ms = dayTimeMs else { return nil }
        if let atTrack = trackTime(wallMs: ms, utcOffsetHours: utcOffsetHours) { return atTrack }
        let offset = TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: Double(ms) / 1000))
        return trackTime(wallMs: ms, utcOffsetHours: Double(offset) / 3600)
    }

    /// A temperature in the feed's units, to a tenth: "38.3°". Nil when the station sent none.
    static func temperature(c: Double?, f: Double?, us: Bool) -> String? {
        guard let v = us ? f : c else { return nil }
        return String(format: "%.1f°", v)
    }

    private static let compassPoints = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"]

    /// Degrees as a 16-point compass direction: 225 → "SW".
    static func compass(_ degrees: Int?) -> String? {
        guard let d = degrees else { return nil }
        let norm = Double(((d % 360) + 360) % 360)
        return compassPoints[Int((norm / 22.5).rounded()) % 16]
    }

    /// Wind in the feed's units: "14 km/h SW", or just the speed or direction the station sent.
    static func wind(_ r: WeatherReading, us: Bool) -> String? {
        let speed = (us ? r.windMph : r.windKmh).map { "\(Int($0.rounded())) \(us ? "mph" : "km/h")" }
        let parts = [speed, compass(r.windDirection)].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    /// Pressure in the feed's units: "29.90 inHg" or "1012 mbar".
    static func pressure(_ r: WeatherReading, us: Bool) -> String? {
        if us { return r.pressureInHg.map { String(format: "%.2f inHg", $0) } }
        return r.pressureMbar.map { "\(Int($0.rounded())) mbar" }
    }

    enum LapMark { case classBest, personalBest }

    /// How a last lap reads: it set the class's fastest lap, or it was the
    /// car's own best — timing screens' purple and green.
    static func lastLapMark(_ car: TowerCar, classBest: Int?) -> LapMark? {
        guard let last = car.lastLapMs, last > 0, last == car.bestLapMs else { return nil }
        return last == classBest ? .classBest : .personalBest
    }

    /// How a sector time reads, as a lap does: the class's fastest there, or the car's own best.
    static func sectorMark(_ ms: Int?, carBest: Int?, classBest: Int?) -> LapMark? {
        guard let ms, ms > 0 else { return nil }
        if ms == classBest { return .classBest }
        return ms == carBest ? .personalBest : nil
    }

    /// The car's best valid lap and each driver's (by driver order): who set
    /// the time is the question asked of it on air. The first lap to a time holds it.
    static func bestLaps(_ laps: [LapRow]) -> (car: LapRow?, byDriver: [Int: LapRow]) {
        var car: LapRow?
        var byDriver: [Int: LapRow] = [:]
        for l in laps where l.valid != false {
            guard let ms = l.lapTimeMs, ms > 0 else { continue }
            if car.map({ ms < $0.lapTimeMs! }) ?? true { car = l }
            if let order = l.driverOrder, byDriver[order].map({ ms < $0.lapTimeMs! }) ?? true { byDriver[order] = l }
        }
        return (car, byDriver)
    }

    /// The tower overall: every car in the feed's overall order, a car it has
    /// not placed yet last, in class order. Nil until the overall standings arrive.
    static func overallOrder(_ tower: Tower) -> [(car: TowerCar, cls: TowerClass)]? {
        let all = tower.classes.enumerated().flatMap { ci, cls in cls.cars.map { (car: $0, cls: cls, ci: ci) } }
        guard all.contains(where: { $0.car.overallPosition != nil }) else { return nil }
        return all.sorted { a, b in
            let pa = a.car.overallPosition ?? .max, pb = b.car.overallPosition ?? .max
            if pa != pb { return pa < pb }
            if a.ci != b.ci { return a.ci < b.ci }
            return a.car.position < b.car.position
        }
        .map { (car: $0.car, cls: $0.cls) }
    }

    /// Places gained in class since the start: positive = up; nil without a start position.
    static func placesGained(_ car: TowerCar) -> Int? {
        car.startPosition.map { $0 - car.position }
    }

    struct FieldCounts: Equatable {
        let onTrack: Int
        let inPit: Int
        /// nil without participant details: only they say a car has stopped on track.
        let stopped: Int?
        let retired: Int

        var line: String {
            ["\(onTrack) on track", "\(inPit) in pit", stopped.map { "\($0) stopped" }, "\(retired) retired"]
                .compactMap { $0 }.joined(separator: " · ")
        }
    }

    /// The field at a glance, as the web's fieldCounts.
    static func fieldCounts(_ tower: Tower) -> FieldCounts {
        let cars = tower.classes.flatMap(\.cars)
        let stopped: (TowerCar) -> Bool = { $0.trackStatus == "STOPPED" }
        return FieldCounts(
            onTrack: cars.filter { $0.running && !$0.inPit && !stopped($0) }.count,
            inPit: cars.filter { $0.running && $0.inPit }.count,
            stopped: cars.contains { $0.trackStatus != nil } ? cars.filter { $0.running && !$0.inPit && stopped($0) }.count : nil,
            retired: cars.filter { $0.status == "RETIRED" }.count)
    }

    /// Cars whose class or place changed between two towers (the first tower seen has nothing to compare with).
    static func moved(from before: Tower?, to after: Tower) -> Set<String> {
        guard let before else { return [] }
        func places(_ t: Tower) -> [String: String] {
            Dictionary(t.classes.flatMap { c in c.cars.map { ($0.carNumber, "\(c.className)|\($0.position)") } },
                       uniquingKeysWith: { a, _ in a })
        }
        let was = places(before)
        return Set(places(after).compactMap { car, place in was[car].map { $0 == place ? nil : car } ?? nil })
    }

    /// The class's fastest best lap, to mark in the tower.
    static func classBest(_ cars: [TowerCar]) -> Int? {
        cars.compactMap(\.bestLapMs).filter { $0 > 0 }.min()
    }

    /// A car's gap on one lap, as the gap chart's readout says it: "Leader",
    /// "+12.345", or "+1 lap" when lapped; nil where the lap was not timed.
    static func gapAt(_ car: GapCar, lap: Int) -> String? {
        guard let down = car.down(at: lap) else { return nil }
        if down > 0 { return "+\(down) \(down == 1 ? "lap" : "laps")" }
        guard let ms = car.gap(at: lap) else { return nil }
        return ms == 0 ? "Leader" : "+" + lapTime(ms)
    }

    /// A car's best time in each sector over its valid laps (index 0 = S1),
    /// nil where it has none — the web car panel's personal-best marks.
    static func bestSectors(_ laps: [LapRow], count: Int) -> [Int?] {
        var best = [Int?](repeating: nil, count: count)
        for l in laps where l.valid != false {
            for (i, ms) in (l.sectorMs ?? []).enumerated() where i < count {
                if let ms, ms > 0, best[i].map({ ms < $0 }) ?? true { best[i] = ms }
            }
        }
        return best
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
