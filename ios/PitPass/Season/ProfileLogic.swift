import Foundation

// Pure derivations behind the driver and team profiles — ports of
// CareerStats.tsx, DriverModal.tsx's bio helpers, InfoModalProvider's
// name→driver rule and ResultsPage's crew splitting. No SwiftUI here so the
// facts can be unit-tested and read the same on both surfaces.

enum CareerLines {
    /// "Sprint 3W 4P3 · Main 2W 2P3 · 2 poles" — a format's part only names
    /// the numbers it has; a season of zero wins still shows its formats so
    /// the line reads as participation, not absence.
    static func formatSplit(_ byFormat: [NamedFormatLine], quali: QualiLine) -> String {
        var parts: [String] = byFormat.filter { $0.starts > 0 }.map { l in
            var bits = [l.formatName, "\(l.wins)W"]
            if l.podiums > 0 { bits.append("\(l.podiums)P3") }
            return bits.joined(separator: " ")
        }
        if quali.poles > 0 { parts.append("\(quali.poles) pole\(quali.poles == 1 ? "" : "s")") }
        return parts.joined(separator: " · ")
    }

    /// Series whose all-time line earns its place: more than one main season
    /// here. Qualifying stages don't count toward the span — their lines are
    /// badged extras, not seasons the rollup covers.
    static func multiSeasonSeries(_ stats: some CareerStatsData) -> Set<Int> {
        Set(stats.bySeries.filter { s in
            stats.seasons.filter { $0.seriesName == s.seriesName && !$0.qualifier }.count > 1
        }.map(\.seriesId))
    }

    struct Chip: Hashable { let label: String; let value: Int }

    /// Career totals as chips, in the web's order. Empty when there are no
    /// starts (a driver seen only in qualifying stages).
    static func chips(_ career: CareerTotals) -> [Chip] {
        guard career.starts > 0 else { return [] }
        return [
            Chip(label: "Starts", value: career.starts), Chip(label: "Wins", value: career.wins),
            Chip(label: "Podiums", value: career.podiums), Chip(label: "Top 5s", value: career.top5s),
            Chip(label: "Poles", value: career.poles), Chip(label: "DNFs", value: career.dnfs),
        ]
    }

    /// Nothing to draw when the career is empty and no season lines exist.
    static func isEmpty(_ stats: some CareerStatsData) -> Bool {
        stats.career.starts == 0 && stats.seasons.isEmpty
    }
}

enum Bio {
    private static let calendar = Calendar(identifier: .gregorian)

    static func parseISODate(_ iso: String) -> DateComponents? {
        let parts = iso.split(separator: "-")
        guard parts.count == 3, let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), (1...31).contains(d) else { return nil }
        return DateComponents(year: y, month: m, day: d)
    }

    /// "4 May 2001" (the web's en-GB day-month-year); the raw string when it
    /// isn't an ISO date.
    static func formatDob(_ iso: String) -> String {
        guard let c = parseISODate(iso), let date = calendar.date(from: c) else { return iso }
        let f = DateFormatter()
        f.calendar = calendar
        f.locale = Locale(identifier: "en_GB")
        f.dateFormat = "d MMM yyyy"
        return f.string(from: date)
    }

    /// Whole years between the birthday and `now`, nil when the date is
    /// unparseable or implausible (negative or 130+).
    static func age(from iso: String, now: Date = .now) -> Int? {
        guard let birth = parseISODate(iso), let by = birth.year, let bm = birth.month, let bd = birth.day else { return nil }
        let today = calendar.dateComponents([.year, .month, .day], from: now)
        guard let ty = today.year, let tm = today.month, let td = today.day else { return nil }
        var age = ty - by
        if tm < bm || (tm == bm && td < bd) { age -= 1 }
        return (0..<130).contains(age) ? age : nil
    }

    struct Fact: Hashable { let label: String; let value: String; let mono: Bool }

    /// The bio strip's facts in the web's order; empty means "No bio yet."
    static func facts(_ p: DriverProfile, now: Date = .now) -> [Fact] {
        var out: [Fact] = []
        if let dob = p.dateOfBirth, !dob.isEmpty {
            out.append(Fact(label: "Born", value: formatDob(dob), mono: true))
            if let age = age(from: dob, now: now) { out.append(Fact(label: "Age", value: String(age), mono: true)) }
        }
        if let h = p.hometown, !h.isEmpty { out.append(Fact(label: "Hometown", value: h, mono: false)) }
        if let b = p.placeOfBirth, !b.isEmpty { out.append(Fact(label: "Birthplace", value: b, mono: false)) }
        return out
    }

    static func isPrivateer(_ teamName: String?) -> Bool {
        teamName?.trimmingCharacters(in: .whitespaces).lowercased() == "privateer"
    }
}

enum DriverLookup {
    /// The search path the website's `openDriverByName` uses.
    static func searchPath(_ name: String) -> String {
        let q = name.trimmingCharacters(in: .whitespaces)
        let encoded = q.addingPercentEncoding(withAllowedCharacters: .urlQueryValueAllowed) ?? q
        return "/api/drivers/search?q=\(encoded)&limit=5"
    }

    /// Names that never resolve: blank and TBD seats stay plain labels.
    static func isLookupable(_ name: String) -> Bool {
        let n = name.trimmingCharacters(in: .whitespaces).lowercased()
        return !n.isEmpty && n != "tbd"
    }

    /// The exact (case- and whitespace-insensitive) match, else the top hit.
    static func pick(_ hits: [DriverSearchHit], wanted name: String) -> DriverSearchHit? {
        let wanted = name.trimmingCharacters(in: .whitespaces).lowercased()
        return hits.first { $0.name.trimmingCharacters(in: .whitespaces).lowercased() == wanted } ?? hits.first
    }
}

enum CrewNames {
    /// The backend joins crew names with ", " — split them back into links.
    static func split(_ names: String?) -> [String] {
        guard let names, !names.isEmpty else { return [] }
        return names.components(separatedBy: ", ")
    }
}

extension CharacterSet {
    /// RFC 3986 unreserved characters — what a query *value* may carry raw.
    static let urlQueryValueAllowed: CharacterSet = {
        var set = CharacterSet.alphanumerics
        set.insert(charactersIn: "-._~")
        return set
    }()
}
