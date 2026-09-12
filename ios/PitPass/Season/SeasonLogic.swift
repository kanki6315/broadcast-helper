import Foundation

// Pure derivations ported from the website (lib/names.ts, lib/raceForm.ts,
// pages/season/venue.ts, ChampionshipGrid.tsx, SeasonLayout.tsx, HubPage.tsx).
// Keep these in step with the web: they are what makes a fact read the same
// on both surfaces.

enum Names {
    /// "Jack Aitken" → "J. Aitken". Single-word names pass through.
    static func short(_ name: String) -> String {
        let parts = name.split(whereSeparator: \.isWhitespace).map(String.init)
        guard parts.count >= 2, let first = parts[0].first else { return name }
        return "\(first). \(parts.dropFirst().joined(separator: " "))"
    }
}

enum RaceForm {
    static func statusAbbr(_ status: String?) -> String {
        guard let status else { return "" }
        let s = status.lowercased()
        if s.contains("not start") || s == "dns" || s == "dnp" { return "DNS" }
        if s.contains("disqual") || s == "dsq" { return "DSQ" }
        if s == "dnf" || s.contains("did not finish") || s.contains("retire") || s.contains("crash")
            || s.contains("accident") || s.contains("mechanical") {
            return "DNF"
        }
        return ""
    }

    /// The app-wide result tier: win / top-3 / top-5 tints, DNF inverted chip.
    enum Tier {
        case none, win, top3, top5, dnf
    }

    static func positionTier(finish: Int?, nonResult: Bool) -> Tier {
        if nonResult { return .dnf }
        guard let finish else { return .none }
        if finish == 1 { return .win }
        if finish <= 3 { return .top3 }
        if finish <= 5 { return .top5 }
        return .none
    }

    /// Compact tags for the sessions of one round, in source order: the
    /// initial, numbered only where that word repeats inside the round.
    /// A single-session round gets no tags at all.
    static func sessionTagList(_ names: [String]) -> [String?] {
        guard names.count > 1 else { return names.map { _ in nil } }
        func initial(_ name: String) -> String {
            let trimmed = name.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { return "R" }
            if trimmed.lowercased().hasPrefix("qual") { return "Q" }
            return String(trimmed.prefix(1)).uppercased()
        }
        var counts: [String: Int] = [:]
        for n in names { counts[initial(n), default: 0] += 1 }
        var seen: [String: Int] = [:]
        return names.map { name in
            let i = initial(name)
            let n = (seen[i] ?? 0) + 1
            seen[i] = n
            return (counts[i] ?? 0) > 1 ? "\(i)\(n)" : i
        }
    }

    static func raceTagsByOrdinal(_ races: [RecapRaceRef]) -> [Int: String?] {
        let list = sessionTagList(races.map { $0.name ?? "" })
        return Dictionary(uniqueKeysWithValues: zip(races.map(\.ordinal), list))
    }

    static func sessionTags(_ sessions: [RecapSession]) -> [Int: String?] {
        let list = sessionTagList(sessions.map(\.name))
        return Dictionary(uniqueKeysWithValues: zip(sessions.map(\.sessionIndex), list))
    }
}

enum Venue {
    /// Client-side mirror of SheetController.venueAbbrev — keep in sync.
    static func of(eventName: String?, circuitName: String?) -> String {
        let haystack = "\(eventName ?? "") \(circuitName ?? "")".lowercased()
        let table: [(String, [String])] = [
            ("DAY", ["daytona"]), ("SEB", ["sebring"]), ("LBH", ["long beach"]),
            ("LAG", ["laguna", "monterey", "weathertech raceway", "wrls"]),
            ("DET", ["detroit"]), ("WGI", ["watkins", "glen"]),
            ("CTMP", ["canadian tire", "bowmanville", "ctmp", "mosport"]),
            ("RDA", ["road america"]), ("VIR", ["virginia"]), ("IMS", ["indianapolis"]),
            ("ATL", ["road atlanta", "michelin raceway"]), ("COTA", ["cota", "circuit of the americas"]),
        ]
        for (code, needles) in table where needles.contains(where: haystack.contains) {
            return code
        }
        let base = circuitName ?? eventName ?? "???"
        return String(base.filter(\.isLetter).uppercased().prefix(3))
    }
}

enum Points {
    /// NUMERIC totals: drop a trailing .0, keep real half-points ("19.5").
    static func format(_ points: Double) -> String {
        if points == points.rounded() { return String(Int(points)) }
        return String(points)
    }

    struct Mark: Hashable {
        enum Kind { case pole, fastestLap, bonus, penalty }
        let glyph: String
        let kind: Kind
        let text: String
    }

    /// Bonus/penalty marks with values printed in full ("+1P").
    static func marks(_ sp: RecapSessionPoints) -> [Mark] {
        var out: [Mark] = []
        if sp.pole > 0 { out.append(Mark(glyph: "+\(format(sp.pole))P", kind: .pole, text: "pole +\(format(sp.pole))")) }
        if sp.fastestLap > 0 { out.append(Mark(glyph: "+\(format(sp.fastestLap))F", kind: .fastestLap, text: "fastest lap +\(format(sp.fastestLap))")) }
        if sp.bonus > 0 { out.append(Mark(glyph: "+\(format(sp.bonus))", kind: .bonus, text: "bonus +\(format(sp.bonus))")) }
        if sp.penalty > 0 { out.append(Mark(glyph: "−\(format(sp.penalty))", kind: .penalty, text: "penalty −\(format(sp.penalty))")) }
        return out
    }

    /// The sessions of one round this competitor actually ran, in calendar order.
    static func contestedSessions(_ round: RecapRound, _ row: RecapRow) -> [(session: RecapSession, points: RecapSessionPoints)] {
        round.sessions.compactMap { session in
            guard let sp = row.sessionPoints(index: session.sessionIndex), sp.contested else { return nil }
            return (session, sp)
        }
    }
}

// MARK: - Classes and championships (SeasonLayout.tsx / ChampionshipGrid.tsx)

struct ClassInfo: Hashable, Identifiable {
    let name: String
    let color: String
    var id: String { name }

    static let defaultColor = "#5e626e"
}

enum SeasonClasses {
    /// Configured order first, then any class with data the config doesn't
    /// know — but only classes that actually have data this season.
    static func of(hub: SeasonHub, styles: ClassStylesResponse?) -> [ClassInfo] {
        var present = Set(hub.entryClasses ?? [])
        for c in hub.championships { if let n = c.className { present.insert(n) } }
        var known: [String: String] = [:]
        for st in styles?.styles ?? [] { known[st.classCode] = st.color }
        var seen = Set<String>()
        var out: [ClassInfo] = []
        for st in styles?.styles ?? [] where present.contains(st.classCode) {
            out.append(ClassInfo(name: st.classCode, color: st.color))
            seen.insert(st.classCode)
        }
        // Unknown classes in a stable order (the web iterates a Set; sort here).
        for name in present.sorted() where !seen.contains(name) {
            out.append(ClassInfo(name: name, color: known[name] ?? ClassInfo.defaultColor))
        }
        return out
    }
}

struct ChampFamily: Hashable, Identifiable {
    let family: String
    let label: String
    let isCup: Bool
    var id: String { family }
}

enum Champs {
    /// "Championship" for the series' own; cups drop the series-name prefix.
    static func familyLabel(_ family: String, seriesName: String) -> String {
        if family == seriesName { return "Championship" }
        var label = family
        if label.hasPrefix(seriesName) {
            label = String(label.dropFirst(seriesName.count)).trimmingCharacters(in: .whitespaces)
        }
        let firstWord = seriesName.split(separator: " ").first.map(String.init) ?? ""
        if label == family, !firstWord.isEmpty, label.hasPrefix(firstWord + " ") {
            label = String(label.dropFirst(firstWord.count + 1))
        }
        return label.isEmpty ? family : label
    }

    static func kindLabel(_ kind: String) -> String {
        let lower = kind.lowercased()
        return lower.prefix(1).uppercased() + lower.dropFirst()
    }

    static func champKindLabel(_ c: ChampionshipSummary) -> String {
        c.kindLabel ?? kindLabel(c.kind ?? "")
    }

    static func kindLabel(among champs: [ChampionshipSummary], kind: String) -> String {
        if let c = champs.first(where: { $0.kind == kind && $0.kindLabel != nil }) { return champKindLabel(c) }
        return kindLabel(kind)
    }

    static func families(_ withRows: [ChampionshipSummary], seriesName: String) -> [ChampFamily] {
        var seen: [String] = []
        var out: [ChampFamily] = []
        for c in withRows where !seen.contains(c.family) {
            seen.append(c.family)
            out.append(ChampFamily(family: c.family, label: familyLabel(c.family, seriesName: seriesName), isCup: c.isCup))
        }
        return out
    }

    static func kinds(_ withRows: [ChampionshipSummary], family: String?, primaryKind: String?) -> [String] {
        var seen: [String] = []
        for c in withRows where c.family == family {
            if let k = c.kind, !seen.contains(k) { seen.append(k) }
        }
        return seen.sorted { rank($0, primaryKind) < rank($1, primaryKind) }
    }

    private static func rank(_ kind: String, _ primaryKind: String?) -> Int {
        if let primaryKind, kind == primaryKind { return -1 }
        switch kind {
        case "TEAMS": return 0
        case "DRIVERS": return 1
        default: return 2
        }
    }

    static func selected(_ withRows: [ChampionshipSummary], family: String?, kind: String?,
                         classes: [ClassInfo]) -> [ChampionshipSummary] {
        let order = Dictionary(uniqueKeysWithValues: classes.enumerated().map { ($1.name, $0) })
        return withRows
            .filter { $0.family == family && $0.kind == kind }
            .sorted { (order[$0.className ?? ""] ?? 99) < (order[$1.className ?? ""] ?? 99) }
    }
}

// MARK: - Results helpers (ResultsPage.tsx)

enum ResultGaps {
    /// A published gap as whole milliseconds; "-" is zero, lap gaps are nil.
    static func gapMs(_ gap: String?) -> Int? {
        guard let gap else { return nil }
        if gap == "-" { return 0 }
        let pattern = #"^\+?(?:(\d+):)?(\d+)\.(\d{1,3})$"#
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: gap.trimmingCharacters(in: .whitespaces), range: NSRange(gap.startIndex..., in: gap)) else { return nil }
        let text = gap.trimmingCharacters(in: .whitespaces)
        func group(_ i: Int) -> String? {
            guard let r = Range(m.range(at: i), in: text) else { return nil }
            return String(text[r])
        }
        let min = Int(group(1) ?? "0") ?? 0
        let sec = Int(group(2) ?? "0") ?? 0
        let frac = (group(3) ?? "0").padding(toLength: 3, withPad: "0", startingAt: 0)
        return (min * 60 + sec) * 1000 + (Int(frac) ?? 0)
    }

    static func format(_ ms: Int) -> String {
        let sign = ms < 0 ? "-" : "+"
        let t = abs(ms)
        let frac = String(format: "%03d", t % 1000)
        let whole = t / 1000
        if whole < 60 { return "\(sign)\(whole).\(frac)" }
        return "\(sign)\(whole / 60):\(String(format: "%02d", whole % 60)).\(frac)"
    }

    /// Gap to the class leader by exact subtraction, keyed by car number.
    static func classGaps(_ rows: [ResultRow]) -> [String: String] {
        var leader: [String: Int] = [:]
        for r in rows {
            guard let cls = r.className, let ms = gapMs(r.gapFirst) else { continue }
            if let best = leader[cls] { leader[cls] = min(best, ms) } else { leader[cls] = ms }
        }
        var out: [String: String] = [:]
        for r in rows {
            guard let ms = gapMs(r.gapFirst), let cls = r.className, let lead = leader[cls] else { continue }
            out[r.carNumber] = ms == lead ? "-" : format(ms - lead)
        }
        return out
    }

    static func isClassified(_ status: String?) -> Bool {
        let s = status?.lowercased()
        return s == "classified" || s == "running"
    }
}

enum Dates {
    /// "Jul 12" reads faster on air than 2026-07-12.
    static func short(_ iso: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        guard let d = f.date(from: iso) else { return iso }
        let out = DateFormatter()
        out.locale = Locale(identifier: "en_US")
        out.dateFormat = "MMM d"
        return out.string(from: d)
    }

    static var today: String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: .now)
    }
}
