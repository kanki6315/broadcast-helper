import SwiftUI

/// HubPage: the four live facts as ONE hairline-divided instrument (next or
/// latest round, championship leaders, latest winners, entries), then the
/// season recap grid. The strip is the recap's status line, not four cards.
struct HubView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HubStrip()
            PageTitle(text: "Season recap")
            ChampionshipGridView(mode: .recap)
        }
        .task(id: model.seasonId) { await model.loadHubExtras(session) }
    }
}

private struct HubStrip: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model

    var body: some View {
        let hub = model.hub.value
        let reference = model.reference?.value
        let lineups = model.lineups?.value
        let classFilter = model.classFilter
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 0) { cells(hub, reference, lineups, classFilter, dividers: .vertical) }
                .frame(minWidth: 1000)
            // Below ~1100px the web pairs the cells up; the dividers follow the wrap.
            VStack(spacing: 0) {
                HStack(alignment: .top, spacing: 0) {
                    roundCell(hub, reference, lineups, classFilter)
                    divider(.vertical)
                    leadersCell(classFilter)
                }
                .fixedSize(horizontal: false, vertical: true)
                divider(.horizontal)
                HStack(alignment: .top, spacing: 0) {
                    winnersCell(reference, lineups, classFilter)
                    divider(.vertical)
                    entriesCell(lineups, classFilter)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            .frame(minWidth: 560)
            VStack(alignment: .leading, spacing: 0) { cells(hub, reference, lineups, classFilter, dividers: .horizontal) }
        }
        .background(PP.surface)
        .clipShape(RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous).strokeBorder(PP.border))
        .padding(.top, PP.Space.s5)
        .padding(.bottom, PP.Space.s6)
    }

    private enum Dividers { case vertical, horizontal }

    @ViewBuilder
    private func cells(_ hub: SeasonHub?, _ reference: ReferenceTable?, _ lineups: Lineups?, _ classFilter: String?, dividers: Dividers) -> some View {
        roundCell(hub, reference, lineups, classFilter)
        divider(dividers)
        leadersCell(classFilter)
        divider(dividers)
        winnersCell(reference, lineups, classFilter)
        divider(dividers)
        entriesCell(lineups, classFilter)
    }

    @ViewBuilder private func divider(_ d: Dividers) -> some View {
        if d == .vertical { Rectangle().fill(PP.border).frame(width: 1) } else { Rectangle().fill(PP.border).frame(height: 1) }
    }

    // MARK: round

    private func roundCell(_ hub: SeasonHub?, _ reference: ReferenceTable?, _ lineups: Lineups?, _ classFilter: String?) -> some View {
        let events = hub?.events ?? []
        let today = Dates.today
        let upcoming = events.first { ($0.eventDate ?? "") >= today && $0.eventDate != nil }
        let shown = upcoming ?? events.last
        let heading = upcoming != nil ? "Next round" : "Latest round"
        let awaiting: Bool = {
            guard upcoming == nil, let shown, let reference,
                  let round = reference.rounds.first(where: { $0.eventId == shown.id }) else { return false }
            return !reference.classes.contains { cls in
                cls.entries.contains { e in (e.races(round: round.ordinal) ?? []).contains { $0.finish != nil } }
            }
        }()
        let note = lineupChangeNote(lineups, classFilter)
        let roundEntries: String? = {
            guard let shown else { return nil }
            if classFilter == nil { return shown.entryCount > 0 ? "\(shown.entryCount) entries" : nil }
            let n = roundCarCount(lineups, shown.id, classFilter!)
            return n > 0 ? "\(n) \(classFilter!) entries" : nil
        }()
        return StripCell(title: shown?.roundOrdinal != nil ? "\(heading) · Rd \(shown!.roundOrdinal!)" : heading) {
            if let shown {
                VStack(alignment: .leading, spacing: 1) {
                    Text(shown.name).font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink).lineLimit(1)
                    Text([shown.circuitName, shown.eventDate.map(Dates.short), roundEntries].compactMap { $0 }.joined(separator: " · "))
                        .font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).lineLimit(1)
                    if awaiting { Text("Results not imported yet").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted) }
                    if let note { Text(note).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.text).padding(.top, PP.Space.s1) }
                }
            } else {
                Text("No events imported yet.").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            }
        }
    }

    // MARK: leaders

    private func leadersCell(_ classFilter: String?) -> some View {
        let champs = model.shownChamps
        let familyLabel = model.families.first { $0.family == model.selectedFamily }?.label
        let scope = (familyLabel != nil && familyLabel != "Championship") ? "\(familyLabel!) leaders" : "Championship leaders"
        let title = model.selectedKind.map { "\(scope) · \(model.kindLabel($0))" } ?? scope
        let resources = champs.map { model.recap(for: $0) }
        let loading = resources.contains { $0.value == nil && $0.error == nil }
        let failed = resources.contains { $0.value == nil && $0.error != nil }
        struct Leader: Identifiable { let id: Int; let className: String?; let car: String; let name: String; let points: String }
        let rows: [Leader] = resources.compactMap { r in
            guard let recap = r.value, let leader = recap.rows.first(where: { $0.position == 1 }) ?? recap.rows.first else { return nil }
            let drivers = recap.championship.kind == "DRIVERS"
            return Leader(id: recap.championship.id, className: recap.championship.className,
                          car: leader.carNumber ?? "",
                          name: (drivers ? (leader.competitorName ?? leader.competitorKey) : leader.teamName) ?? "",
                          points: "\(Points.format(leader.totalPoints)) pts")
        }
        let emptyCopy: String = model.selectedChamps.isEmpty ? "No standings imported yet."
            : classFilter != nil ? "No \(classFilter!) standings in this \(familyLabel == "Championship" ? "championship" : (familyLabel ?? "championship"))."
            : "No standings imported yet."
        return StripCell(title: title) {
            if failed {
                CellError(what: "standings") { Task { await model.loadRecaps(session, for: champs) } }
            } else if champs.isEmpty {
                Text(emptyCopy).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            } else if loading, rows.isEmpty {
                SkeletonLines()
            } else {
                StripRows(rows: rows.prefix(6).map { r in
                    StripRow(id: String(r.id), className: r.className, car: r.car, primary: r.name, secondary: nil, tag: nil, value: r.points)
                }, more: rows.count > 6 ? "\(rows.count - 6) more championships →" : nil)
            }
        }
        .task(id: champs.map(\.id)) { await model.loadRecaps(session, for: champs) }
    }

    // MARK: winners

    private func winnersCell(_ reference: ReferenceTable?, _ lineups: Lineups?, _ classFilter: String?) -> some View {
        struct Winner { let className: String; let car: String; let team: String?; let race: Int }
        var roundOrdinal: Int?
        var venue = ""
        var raceCount = 1
        var winners: [Winner] = []
        if let reference {
            for round in reference.rounds.reversed() {
                let hasFinish = reference.classes.contains { cls in cls.entries.contains { ($0.races(round: round.ordinal) ?? []).contains { $0.finish != nil } } }
                if hasFinish { roundOrdinal = round.ordinal; venue = round.venue; raceCount = round.raceCount; break }
            }
            if let ord = roundOrdinal {
                for (ci, cls) in reference.classes.enumerated() {
                    if let classFilter, cls.className != classFilter { continue }
                    for e in cls.entries {
                        for r in e.races(round: ord) ?? [] where r.finish == 1 {
                            winners.append(Winner(className: cls.className, car: e.carNumber, team: e.team, race: r.raceOrdinal))
                        }
                    }
                    _ = ci
                }
                let order = Dictionary(uniqueKeysWithValues: reference.classes.enumerated().map { ($1.className, $0) })
                winners.sort { a, b in
                    let ca = order[a.className] ?? 0, cb = order[b.className] ?? 0
                    return ca != cb ? ca < cb : a.race < b.race
                }
            }
        }
        let tagWinners = raceCount > 1
        let shown = Array(winners.prefix(4))
        let missingAll = model.classes.filter { classFilter == nil || $0.name == classFilter }
            .filter { c in !winners.contains { $0.className == c.name } }.map(\.name)
        let missing = missingAll.count > 3 ? "\(missingAll.count) classes have" : "\(missingAll.joined(separator: ", ")) \(missingAll.count == 1 ? "has" : "have")"
        let failed = model.reference?.error != nil && reference == nil
        return StripCell(title: roundOrdinal != nil ? "Winners · \(venue)" : "Winners") {
            if failed {
                CellError(what: "results") { Task { await model.loadHubExtras(session) } }
            } else if reference == nil {
                SkeletonLines()
            } else if winners.isEmpty {
                Text(roundOrdinal != nil && classFilter != nil ? "No \(classFilter!) winner at \(venue)." : "No race results imported yet.")
                    .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            } else {
                StripRows(rows: shown.map { w in
                    let crew = crewOf(lineups, roundOrdinal, w.className, w.car)
                    let primary = crew.count == 1 ? crew[0] : (w.team ?? "")
                    let secondary = subLine(primary, crew.count == 1 ? w.team : crew.joined(separator: " · "))
                    return StripRow(id: "\(w.className)-\(w.car)-\(w.race)", className: w.className, car: w.car,
                                    primary: primary, secondary: secondary, tag: tagWinners ? "R\(w.race)" : nil, value: nil)
                }, more: winners.count > shown.count ? "\(winners.count - shown.count) more winners at \(venue) →" : nil,
                   note: missingAll.isEmpty ? nil : "\(missing) no winner at \(venue)")
            }
        }
    }

    // MARK: entries

    private func entriesCell(_ lineups: Lineups?, _ classFilter: String?) -> some View {
        let counts = (lineups?.classes ?? []).filter { classFilter == nil || $0.className == classFilter }
            .map { ($0.className, $0.cars.count) }
        let failed = model.lineups?.error != nil && lineups == nil
        return StripCell(title: "Entries") {
            if failed {
                CellError(what: "lineups") { Task { await model.loadHubExtras(session) } }
            } else if lineups == nil {
                SkeletonLines()
            } else if counts.isEmpty {
                Text(classFilter != nil && !(lineups!.classes.isEmpty) ? "No \(classFilter!) entries this season." : "No entry lists imported yet.")
                    .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            } else {
                StripRows(rows: counts.prefix(6).map { c in
                    StripRow(id: c.0, className: c.0, car: nil, primary: "\(c.1) cars", secondary: nil, tag: nil, value: nil)
                }, more: counts.count > 6 ? "\(counts.count - 6) more classes →" : nil)
            }
        }
    }

    // MARK: derivations (HubPage.tsx)

    private func roundCarCount(_ lineups: Lineups?, _ eventId: Int, _ className: String) -> Int {
        guard let lineups, let round = lineups.rounds.first(where: { $0.eventId == eventId }),
              let cls = lineups.classes.first(where: { $0.className == className }) else { return 0 }
        return cls.cars.filter { !($0.crew(round: round.ordinal) ?? []).isEmpty }.count
    }

    private func lineupChangeNote(_ lineups: Lineups?, _ classFilter: String?) -> String? {
        guard let lineups, let last = lineups.rounds.last?.ordinal else { return nil }
        let ordinals = lineups.rounds.map(\.ordinal)
        var changed = 0
        for cls in lineups.classes {
            if let classFilter, cls.className != classFilter { continue }
            for car in cls.cars {
                guard let now = car.crew(round: last) else { continue }
                guard let prev = ordinals.reversed().first(where: { $0 < last && car.crew(round: $0) != nil }) else { continue }
                let a = Set(now.map(\.name)), b = Set((car.crew(round: prev) ?? []).map(\.name))
                if a != b { changed += 1 }
            }
        }
        guard changed > 0 else { return nil }
        let venue = lineups.rounds.first { $0.ordinal == last }?.venue ?? "Rd \(last)"
        return "\(changed) lineup change\(changed == 1 ? "" : "s") at \(venue)"
    }

    private func crewOf(_ lineups: Lineups?, _ round: Int?, _ className: String, _ car: String) -> [String] {
        guard let lineups, let round, let cls = lineups.classes.first(where: { $0.className == className }),
              let c = cls.cars.first(where: { $0.carNumber == car }) else { return [] }
        return (c.crew(round: round) ?? []).filter { !$0.isTbd }.map { Names.short($0.name) }
    }

    private func subLine(_ primary: String?, _ secondary: String?) -> String? {
        guard let secondary, !secondary.isEmpty else { return nil }
        guard let primary, !primary.isEmpty else { return secondary }
        func norm(_ v: String) -> String { v.lowercased().filter { $0.isLetter || $0.isNumber } }
        if norm(secondary) == norm(primary) { return nil }
        if norm(Names.short(secondary)) == norm(primary) { return nil }
        return secondary
    }
}

// MARK: strip pieces (.hs-*)

private struct StripCell<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            Text(title).font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink).lineLimit(1)
            content
        }
        .padding(.vertical, PP.Space.s3)
        .padding(.horizontal, PP.Space.s4)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private struct StripRow: Identifiable {
    let id: String
    let className: String?
    let car: String?
    let primary: String
    let secondary: String?
    let tag: String?
    let value: String?
}

/// `.hs-rows`: pill · car · name · value, edges shared straight down the list.
private struct StripRows: View {
    @Environment(SeasonModel.self) private var model
    let rows: [StripRow]
    var more: String?
    var note: String?

    var body: some View {
        let hasCar = rows.contains { $0.car != nil }
        let hasTag = rows.contains { $0.tag != nil }
        let hasValue = rows.contains { $0.value != nil }
        Grid(alignment: .leadingFirstTextBaseline, horizontalSpacing: PP.Space.s2, verticalSpacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.1.id) { i, row in
                GridRow {
                    if let cls = row.className { ClassTag(name: cls, color: model.classColor(cls)) } else { Color.clear.frame(width: 1, height: 1) }
                    if hasCar { Text(row.car ?? "").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.text).gridColumnAlignment(.trailing) }
                    if hasTag { Text(row.tag ?? "").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted) }
                    VStack(alignment: .leading, spacing: 0) {
                        Text(row.primary).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.ink).lineLimit(1)
                        if let s = row.secondary { Text(s).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).lineLimit(1) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if hasValue { Text(row.value ?? "").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text).gridColumnAlignment(.trailing) }
                }
                .padding(.vertical, 3)
                .overlay(alignment: .top) { if i > 0 { Rectangle().fill(PP.border).frame(height: 1) } }
            }
        }
        .font(PP.sans(PP.TextSize.sm))
        if let more { Text(more).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.accentInk).padding(.top, PP.Space.s1) }
        if let note { Text(note).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.text).padding(.top, PP.Space.s1) }
    }
}

private struct CellError: View {
    let what: String
    let retry: () -> Void
    var body: some View {
        HStack(spacing: PP.Space.s2) {
            Text("Couldn’t load \(what).").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            RetryButton(action: retry)
        }
    }
}
