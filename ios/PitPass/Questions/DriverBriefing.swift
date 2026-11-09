import Foundation

/// Everything the question model may know about one driver, as numbered lines
/// it can cite. The app chooses the context; the model only writes questions.
/// Facts are `F…`, storyline paragraphs `S…`, earlier conversations `C…`.
struct DriverBriefing: Sendable, Equatable {
    struct Line: Sendable, Equatable, Identifiable {
        let id: String
        let text: String
    }

    let driverName: String
    let eventName: String
    var facts: [Line]
    var conversations: [Line]
    /// Relevant paragraphs first, then the rest of the pack in document order.
    var storylines: [Line]
    /// Storyline ids that mention this driver, car or team — the rest is general context.
    var relevantStorylines: Set<String>
    var plannedTopic: String?
    var moment: String?

    /// Every citable line, for resolving the ids a question comes back with.
    var lines: [String: Line] {
        Dictionary(uniqueKeysWithValues: (facts + conversations + storylines).map { ($0.id, $0) })
    }

    /// The prompt body, trimmed to `budget` characters by dropping general
    /// storylines first, then relevant ones from the end. Facts always fit.
    func prompt(budget: Int, generalStorylines: Bool = true) -> String {
        var head = "Driver: \(driverName)\nEvent: \(eventName)\n"
        if let moment { head += "When: \(moment)\n" }
        if let plannedTopic, !plannedTopic.isEmpty { head += "The commentator wants to talk about: \(plannedTopic)\n" }
        head += "\nFACTS (from timing and results data)\n" + facts.map { "\($0.id): \($0.text)" }.joined(separator: "\n")
        if !conversations.isEmpty {
            head += "\n\nEARLIER CONVERSATIONS WITH THIS DRIVER THIS WEEKEND\n"
                + conversations.map { "\($0.id): \($0.text)" }.joined(separator: "\n")
        }
        var remaining = budget - head.count - 60
        var kept: [Line] = []
        for line in storylines where generalStorylines || relevantStorylines.contains(line.id) {
            let cost = line.id.count + line.text.count + 3
            if cost > remaining { continue }
            kept.append(line)
            remaining -= cost
        }
        guard !kept.isEmpty else { return head }
        let order = Dictionary(uniqueKeysWithValues: storylines.enumerated().map { ($1.id, $0) })
        kept.sort { order[$0.id]! < order[$1.id]! }
        let marked = kept.map { "\($0.id)\(relevantStorylines.contains($0.id) ? "*" : ""): \($0.text)" }
        return head + "\n\nSTORYLINES PACK (lines marked * mention this driver, car or team)\n" + marked.joined(separator: "\n")
    }
}

enum BriefingBuilder {
    struct Inputs: Sendable {
        var sheet: Sheet
        var entry: SheetEntry
        var driver: SheetDriver
        var profile: DriverProfile?
        var stats: DriverStats?
        var storylineParagraphs: [String] = []
        var conversations: [Conversation] = []
        var planned: Conversation?
    }

    static func build(_ input: Inputs) -> DriverBriefing {
        let sheet = input.sheet, entry = input.entry
        let className = sheet.classes.first { $0.entries.contains { $0.entryId == entry.entryId } }?.className
        var facts: [String] = []

        var car = "Drives the #\(entry.carNumber) \(entry.teamName)"
        if let vehicle = entry.vehicle { car += " \(vehicle)" }
        if let className { car += " in \(className)" }
        facts.append(car)
        let crew = entry.drivers.filter { !$0.isTbd && $0.name != input.driver.name }.map(\.name)
        if !crew.isEmpty { facts.append("Co-drivers this weekend: \(crew.joined(separator: ", "))") }
        if entry.isStartingDriver(input.driver) { facts.append("Is the car's starting driver for the race") }
        if let rating = input.driver.rating ?? input.profile?.rating, let label = ratingLabel(rating) {
            facts.append("FIA driver rating: \(label)")
        }
        if let p = input.profile {
            var home = [String]()
            if let h = p.hometown, !h.isEmpty { home.append("from \(h)") }
            else if let c = p.country { home.append("nationality \(c)") }
            if let dob = p.dateOfBirth, let age = age(dob, on: sheet.eventDate) { home.append("age \(age)") }
            if !home.isEmpty { facts.append("Background: " + home.joined(separator: ", ")) }
            if let notes = p.notes?.trimmingCharacters(in: .whitespacesAndNewlines), !notes.isEmpty {
                facts.append("Commentator's notes on this driver: \(notes)")
            }
        }
        if let q = entry.qualifying, !q.isEmpty { facts.append("Qualified \(q) in class for this race") }
        if let c = entry.championship, !c.isEmpty, c != "GUEST" {
            facts.append("\(className.map { "\($0) " } ?? "")team championship going into this round: \(c)")
        }
        let formLines = sheet.formRounds.compactMap { round -> String? in
            let races = entry.races(round: round.ordinal)
            guard !races.isEmpty else { return nil }
            return "\(round.venue) " + races.map(raceText).joined(separator: "; ")
        }
        if !formLines.isEmpty { facts.append("This season so far (the car): " + formLines.joined(separator: " | ")) }
        if let prior = entry.priorYearNote, !prior.isEmpty {
            facts.append("This car's class finish at this event last year: \(prior)")
        }

        if let p = input.profile { facts += history(p, sheet: sheet) }
        if let s = input.stats { facts += careerLines(s, before: sheet.year) }

        let people = input.conversations
            .filter { $0.id != input.planned?.id }
            .sorted { $0.date < $1.date }
            .suffix(4)
            .compactMap(conversationText)

        let paragraphs = input.storylineParagraphs
        let terms = matchTerms(driver: input.driver.name, entry: entry)
        let scored = paragraphs.enumerated().map { ($0, score($1, terms)) }
        let relevant = scored.filter { $0.1 > 0 }.sorted { $0.1 != $1.1 ? $0.1 > $1.1 : $0.0 < $1.0 }.map(\.0)
        let general = scored.filter { $0.1 == 0 }.map(\.0)
        let ordered = (relevant + general).map { DriverBriefing.Line(id: "S\($0 + 1)", text: paragraphs[$0]) }

        var moment: String?
        if let planned = input.planned { moment = "\(planned.session), \(planned.context.lowercased())" }

        return DriverBriefing(
            driverName: input.driver.name,
            eventName: "\(sheet.year) \(sheet.eventName)\(sheet.circuitName.map { " at \($0)" } ?? "") (\(sheet.seriesName)\(sheet.roundOrdinal.map { ", round \($0)" } ?? ""))",
            facts: facts.enumerated().map { .init(id: "F\($0 + 1)", text: $1) },
            conversations: people.enumerated().map { .init(id: "C\($0 + 1)", text: $1) },
            storylines: ordered,
            relevantStorylines: Set(relevant.map { "S\($0 + 1)" }),
            plannedTopic: input.planned?.topic.trimmingCharacters(in: .whitespacesAndNewlines),
            moment: moment)
    }

    // MARK: facts

    static func ratingLabel(_ rating: String) -> String? {
        switch rating.uppercased() {
        case "P": "Platinum"
        case "G": "Gold"
        case "S": "Silver"
        case "B": "Bronze"
        default: nil
        }
    }

    static func age(_ dob: String, on eventDate: String?) -> Int? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        guard let born = f.date(from: String(dob.prefix(10))) else { return nil }
        let on = eventDate.flatMap { f.date(from: String($0.prefix(10))) } ?? .now
        return Calendar(identifier: .gregorian).dateComponents([.year], from: born, to: on).year
    }

    static func raceText(_ r: FormRace) -> String {
        let finish = r.finish.map(ordinal) ?? (r.status ?? "no result")
        let dnf = r.status.map { !$0.isEmpty && $0 != "Classified" && r.finish != nil ? " (\($0))" : "" } ?? ""
        return r.start.map { "started \(ordinal($0)), finished \(finish)\(dnf)" } ?? "finished \(finish)\(dnf)"
    }

    static func ordinal(_ n: Int) -> String {
        let suffix = (11...13).contains(n % 100) ? "th" : ["th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th"][n % 10]
        return "\(n)\(suffix)"
    }

    /// Final positions of past seasons and this driver's results at this
    /// venue, strictly before this event (the sheet is prepared pre-race).
    static func history(_ p: DriverProfile, sheet: Sheet) -> [String] {
        let venue = p.championships.lazy.flatMap(\.rounds).first { $0.eventId == sheet.eventId }?.venue
        var out: [String] = []
        var seenVenue = Set<String>()
        var venueLines: [(Int, String)] = []
        for m in p.championships.sorted(by: { $0.year > $1.year }) where m.year <= sheet.year {
            let current = m.rounds.firstIndex { $0.eventId == sheet.eventId }
            for (i, round) in m.rounds.enumerated() where venue != nil && round.venue == venue {
                // This season: only an earlier visit to the same track counts.
                if m.year == sheet.year, current.map({ i >= $0 }) ?? true { continue }
                guard let races = m.races(round: round.round), !races.isEmpty else { continue }
                let key = "\(m.year)|\(round.venue)"
                guard seenVenue.insert(key).inserted else { continue }
                let text = races.map { r in
                    let car = r.carNumber.map { "#\($0) " } ?? ""
                    let start = r.start.map { "started \(ordinal($0)), " } ?? ""
                    let finish = r.finish.map { "finished \(ordinal($0))" } ?? (r.status ?? "no result")
                    return "\(car)\(start)\(finish)\(r.notFinished ? " (\(r.status ?? "DNF"))" : "")"
                }.joined(separator: "; ")
                venueLines.append((m.year, "\(m.year) \(m.className ?? "") at \(round.venue): \(text)"))
            }
        }
        if !venueLines.isEmpty {
            out.append("Record at this track: " + venueLines.sorted { $0.0 > $1.0 }.prefix(6).map(\.1).joined(separator: " | "))
        }
        let finals = p.championships
            .filter { $0.year < sheet.year && !$0.title.localizedCaseInsensitiveContains("cup") }
            .sorted { $0.year > $1.year }
            .prefix(5)
            .map { "\($0.year) \($0.className ?? $0.seriesName): \(ordinal($0.position))" }
        if !finals.isEmpty { out.append("Past championship finishes (\(p.seriesName ?? "series") drivers): " + finals.joined(separator: ", ")) }
        return out
    }

    /// Past seasons only: the current season's rollup would include this
    /// event's own result once it's imported.
    static func careerLines(_ s: DriverStats, before year: Int) -> [String] {
        let past = s.seasons.filter { $0.year < year && !$0.qualifier }
        guard !past.isEmpty else { return [] }
        let races = past.flatMap(\.byFormat)
        let starts = races.reduce(0) { $0 + $1.starts }, wins = races.reduce(0) { $0 + $1.wins }
        let podiums = races.reduce(0) { $0 + $1.podiums }, poles = past.reduce(0) { $0 + $1.quali.poles }
        var out = ["Career before this season: \(starts) starts, \(wins) class wins, \(podiums) podiums, \(poles) poles"]
        let years = Set(past.map(\.year)).sorted(by: >).prefix(4).map { y in
            let lines = past.filter { $0.year == y }
            let st = lines.flatMap(\.byFormat).reduce(0) { $0 + $1.starts }
            let w = lines.flatMap(\.byFormat).reduce(0) { $0 + $1.wins }
            let pd = lines.flatMap(\.byFormat).reduce(0) { $0 + $1.podiums }
            return "\(y) \(lines.map(\.className).joined(separator: "/")): \(st) starts, \(w) wins, \(pd) podiums"
        }
        out.append("Recent seasons: " + years.joined(separator: " | "))
        return out
    }

    static func conversationText(_ c: Conversation) -> String? {
        var parts: [String] = []
        let when = "\(c.session), \(c.context.lowercased())"
        if !c.topic.isEmpty { parts.append("topic \(c.topic)") }
        if !c.takeaway.isEmpty { parts.append("takeaway: \(c.takeaway)") }
        let said = c.transcript.map(\.text).joined(separator: " ")
        if !said.isEmpty {
            let words = said.split(separator: " ")
            parts.append("they said: \"\(words.prefix(120).joined(separator: " "))\(words.count > 120 ? "…" : "")\"")
        }
        guard !parts.isEmpty else { return nil }
        return "\(c.kind == .planned ? "Planned" : "Spoke") (\(when)): " + parts.joined(separator: "; ")
    }

    // MARK: storylines

    struct Terms: Sendable {
        var strong: [String] = []   // the driver
        var car: [String] = []      // #6 but not #60
        var medium: [String] = []   // team
        var weak: [String] = []     // co-drivers
    }

    static func matchTerms(driver: String, entry: SheetEntry) -> Terms {
        var t = Terms()
        let surname = driver.split(separator: " ").last.map(String.init) ?? driver
        t.strong = [driver, surname].filter { $0.count >= 3 }
        t.car = [entry.carNumber]
        let generic: Set<String> = ["racing", "motorsport", "motorsports", "team", "the", "and", "with", "by", "autosport", "competition"]
        t.medium = entry.teamName.split(separator: " ").map(String.init)
            .filter { $0.count >= 4 && !generic.contains($0.lowercased()) }
        t.weak = entry.drivers.filter { !$0.isTbd && $0.name != driver }
            .compactMap { $0.name.split(separator: " ").last.map(String.init) }
            .filter { $0.count >= 3 }
        return t
    }

    static func score(_ paragraph: String, _ t: Terms) -> Int {
        var s = 0
        if t.strong.contains(where: { containsWord(paragraph, $0) }) { s += 6 }
        if t.car.contains(where: { containsCarNumber(paragraph, $0) }) { s += 4 }
        if t.medium.contains(where: { containsWord(paragraph, $0) }) { s += 2 }
        if t.weak.contains(where: { containsWord(paragraph, $0) }) { s += 1 }
        return s
    }

    static func containsWord(_ text: String, _ word: String) -> Bool {
        let pattern = "(?<![\\p{L}\\p{N}])" + NSRegularExpression.escapedPattern(for: word) + "(?![\\p{L}\\p{N}])"
        return text.range(of: pattern, options: [.regularExpression, .caseInsensitive, .diacriticInsensitive]) != nil
    }

    /// "#6" and "No. 6" match; "#60" and "6th" don't.
    static func containsCarNumber(_ text: String, _ number: String) -> Bool {
        let n = NSRegularExpression.escapedPattern(for: number)
        return text.range(of: "(#|No\\.\\s?)\(n)(?!\\d)", options: .regularExpression) != nil
    }
}
