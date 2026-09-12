import SwiftUI

/// ChampionshipGrid: the filter bar (championship family, competitor kind,
/// points view, show-teams), the legend, and one ClassGrid per selected
/// championship. `recap` shows start→finish chips per round; `points` the
/// earnings per round with Total and Back.
struct ChampionshipGridView: View {
    enum Mode { case recap, points }

    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model
    let mode: Mode

    var body: some View {
        @Bindable var model = model
        let shown = model.shownChamps
        if model.families.isEmpty {
            EmptyState(message: "No standings imported yet — bring in a standings file on the website and the season recap builds itself.")
        } else {
            VStack(alignment: .leading, spacing: PP.Space.s3) {
                filterBar
                if shown.isEmpty {
                    EmptyState(message: "No \(model.classFilter ?? "") standings in this championship — pick another class or championship.")
                } else {
                    ForEach(shown) { champ in
                        ClassGridView(champ: champ, mode: mode)
                    }
                }
            }
            .padding(.top, PP.Space.s4)
            .task(id: shown.map(\.id)) { await model.loadRecaps(session, for: shown) }
        }
    }

    private var loadedRecaps: [Recap] {
        model.shownChamps.compactMap { model.recap(for: $0).value }
    }

    private var filterBar: some View {
        @Bindable var model = model
        let familySel = Binding<String?>(get: { model.selectedFamily }, set: { if let f = $0 { model.switchFamily(f) } })
        let kindSel = Binding<String?>(get: { model.selectedKind }, set: { model.kind = $0 })
        let viewSel = Binding<SeasonModel.PointsView?>(get: { model.pointsView }, set: { if let v = $0 { model.pointsView = v } })
        let recaps = loadedRecaps
        let ptsFlags = mode == .points ? PointsLegendFlags(recaps) : nil
        return FlowLayout(horizontalSpacing: PP.Space.s3, verticalSpacing: PP.Space.s2) {
            if model.families.count > 1 {
                Segmented(options: model.families.map { .init(id: $0.family, label: $0.label) }, selection: familySel)
            }
            if model.kinds.count > 1 {
                Segmented(options: model.kinds.map { .init(id: $0, label: model.kindLabel($0)) }, selection: kindSel)
            }
            if mode == .points, ptsFlags != nil {
                Segmented(options: SeasonModel.PointsView.allCases.map { .init(id: $0, label: $0.label) }, selection: viewSel)
            }
            if mode == .recap, model.selectedKind == "DRIVERS" {
                SegmentedToggles(options: [.init(id: "teams", label: "Show teams")],
                                 on: Binding(get: { model.showTeams ? ["teams"] : [] }, set: { _ in }),
                                 toggle: { _ in model.showTeams.toggle() })
            }
            if mode == .recap {
                RecapLegend(recaps: recaps)
            } else if let ptsFlags, model.pointsView == .breakdown {
                PointsLegend(flags: ptsFlags)
            }
        }
    }
}

// MARK: - legends

struct PointsLegendFlags {
    var tags: [(String, String)] = []
    var pole = false, fl = false, bonus = false, penalty = false

    init?(_ recaps: [Recap]) {
        var seen = Set<String>()
        for recap in recaps {
            for r in recap.rounds where r.sessions.count > 1 {
                let tags = RaceForm.sessionTags(r.sessions)
                for s in r.sessions {
                    let letter = ((tags[s.sessionIndex] ?? nil) ?? "").trimmingCharacters(in: .decimalDigits)
                    let word = s.name.trimmingCharacters(in: .whitespaces).split(separator: " ").first.map { $0.lowercased() } ?? ""
                    if !letter.isEmpty, !seen.contains(letter) { seen.insert(letter); self.tags.append((letter, word)) }
                }
            }
            for row in recap.rows {
                for sp in row.sessionPoints.values {
                    if sp.pole > 0 { pole = true }
                    if sp.fastestLap > 0 { fl = true }
                    if sp.bonus > 0 { bonus = true }
                    if sp.penalty > 0 { penalty = true }
                }
            }
        }
        if tags.isEmpty, !pole, !fl, !bonus, !penalty { return nil }
    }
}

struct PointsLegend: View {
    let flags: PointsLegendFlags
    var body: some View {
        var items: [LegendItem] = flags.tags.map { LegendItem(text: "\($0.0) = \($0.1)") }
        let _ = {
            if flags.pole { items.append(LegendItem(text: "+nP = pole", accent: true)) }
            if flags.fl { items.append(LegendItem(text: "+nF = fastest lap")) }
            if flags.bonus { items.append(LegendItem(text: "+n = bonus")) }
            if flags.penalty { items.append(LegendItem(text: "−n = penalty", error: true)) }
            items.append(LegendItem(text: "— = no entry"))
        }()
        Legend(items: items)
    }
}

struct RecapLegend: View {
    let recaps: [Recap]

    var body: some View {
        let scope: String = {
            if recaps.isEmpty || recaps.allSatisfy({ !$0.championship.isOverall }) { return "start/finish in class" }
            if recaps.allSatisfy({ $0.championship.isOverall }) { return "start/finish overall" }
            return "start/finish in class · overall standings: whole field"
        }()
        var tags: [(String, String)] = []
        let _ = {
            var seen = Set<String>()
            for recap in recaps {
                for r in recap.rounds where r.races.count > 1 {
                    let byOrdinal = RaceForm.raceTagsByOrdinal(r.races)
                    for race in r.races {
                        let letter = ((byOrdinal[race.ordinal] ?? nil) ?? "").trimmingCharacters(in: .decimalDigits)
                        let word = (race.name ?? "").trimmingCharacters(in: .whitespaces).split(separator: " ").first.map { $0.lowercased() } ?? ""
                        if !letter.isEmpty, !word.isEmpty, !seen.contains(letter) { seen.insert(letter); tags.append((letter, word)) }
                    }
                }
            }
        }()
        let items: [LegendItem] = [
            LegendItem(swatch: .win, text: "Win"), LegendItem(swatch: .top3, text: "Top 3"),
            LegendItem(swatch: .top5, text: "Top 5"), LegendItem(swatch: .dnf, text: "DNF"),
            LegendItem(swatch: .sample, text: scope),
        ] + tags.map { LegendItem(text: "\($0.0) = \($0.1)") } + [
            LegendItem(text: "P = pole", accent: true), LegendItem(text: "ᴿ = retired"), LegendItem(text: "· = no entry"),
        ]
        Legend(items: items)
    }
}

// MARK: - one class

struct ClassGridView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model
    let champ: ChampionshipSummary
    let mode: ChampionshipGridView.Mode

    var body: some View {
        let resource = model.recap(for: champ)
        if let recap = resource.value {
            grid(recap)
        } else if let error = resource.error {
            HStack(spacing: PP.Space.s2) {
                Text("Couldn’t load the \(champ.className ?? "") \(Champs.champKindLabel(champ).lowercased()) recap. \(error)")
                RetryButton { Task { await resource.load(session.loader) } }
            }
            .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.error)
        } else {
            SkeletonLines()
        }
    }

    private func grid(_ recap: Recap) -> some View {
        let rounds = recap.rounds
        let drivers = recap.championship.kind == "DRIVERS"
        let leader = recap.rows.map(\.totalPoints).max() ?? 0
        let color = model.classColor(champ.className)
        let breakdown = mode == .points && model.pointsView == .breakdown
        let raceTags: [Int: [Int: String?]] = Dictionary(uniqueKeysWithValues: rounds.map { ($0.round, RaceForm.raceTagsByOrdinal($0.races)) })
        let sessionTags: [Int: [Int: String?]] = Dictionary(uniqueKeysWithValues: rounds.map { ($0.round, RaceForm.sessionTags($0.sessions)) })
        let marksWidth: Int = breakdown
            ? recap.rows.flatMap { $0.sessionPoints.values }.map { Points.marks($0).map(\.glyph).joined(separator: " ").count }.max() ?? 0
            : 0

        var identColumns: [GridColumn] = mode == .recap
            ? [.text("pos", "Pos", width: 48, align: .trailing), .text("pts", "Pts", width: 112, align: .trailing),
               .text("car", "#", width: 52, align: .trailing), .text("name", drivers ? "Driver" : "Team", width: 216)]
            : [.text("pos", "Pos", width: 52, align: .trailing), .text("car", "#", width: 60, align: .trailing),
               .text("name", drivers ? "Driver" : "Team", width: 236)]
        identColumns = identColumns.map { $0 }
        var dataColumns: [GridColumn] = rounds.map { .round("r\($0.round)", venue: $0.venue, round: $0.round) }
        if mode == .points {
            dataColumns.append(.text("total", "Total", width: 72, align: .trailing))
            dataColumns.append(.text("back", "Back", width: 110, align: .trailing))
        }

        var rows: [GridRowItem] = []
        var prevPoints: Double?
        for row in recap.rows {
            let back = leader - row.totalPoints
            let name = drivers ? (row.competitorName ?? row.competitorKey) : (row.teamName ?? "")
            let teamNames: [String] = drivers ? (row.teamNames?.isEmpty == false ? row.teamNames! : (row.teamName.map { [$0] } ?? [])) : []
            let shownTeams = model.showTeams ? teamNames : []
            var ident: [AnyView] = [GridCell.pos(row.position)]
            if mode == .recap {
                ident.append(AnyView(HStack(alignment: .firstTextBaseline, spacing: 3) {
                    Text(Points.format(row.totalPoints)).font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
                    if back > 0 { Text("(-\(Points.format(back)))").font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.textMuted.opacity(0.75)) }
                }))
            }
            ident.append(GridCell.car(row.carNumber ?? ""))
            ident.append(GridCell.name(name, sub: shownTeams))

            var cells: [AnyView] = []
            var lines = shownTeams.isEmpty ? 1 : 2
            for r in rounds {
                if mode == .points {
                    guard let pts = row.points(round: r.round) else { cells.append(GridCell.skip()); continue }
                    let ran = breakdown ? Points.contestedSessions(r, row) : []
                    if !breakdown || ran.isEmpty {
                        cells.append(AnyView(PtsLine(tag: nil, value: pts, marks: [], marksWidth: 0, zero: pts == 0)))
                    } else {
                        lines = max(lines, ran.count)
                        let tags = sessionTags[r.round] ?? [:]
                        cells.append(AnyView(VStack(alignment: .trailing, spacing: 2) {
                            ForEach(Array(ran.enumerated()), id: \.0) { _, entry in
                                let marks = Points.marks(entry.points)
                                PtsLine(tag: tags[entry.session.sessionIndex] ?? nil,
                                        value: marks.isEmpty ? entry.points.total : entry.points.race,
                                        marks: marks, marksWidth: marksWidth, zero: entry.points.total == 0)
                            }
                        }))
                    }
                } else {
                    let races = row.races(round: r.round)
                    lines = max(lines, RaceCellView.lines(races))
                    cells.append(AnyView(RaceCellView(races: races, raceTags: raceTags[r.round] ?? [:]).frame(maxWidth: .infinity)))
                }
            }
            if mode == .points {
                cells.append(GridCell.num(Points.format(row.totalPoints), bold: true))
                let gap: Double? = prevPoints.map { $0 - row.totalPoints }
                cells.append(AnyView(HStack(alignment: .firstTextBaseline, spacing: 3) {
                    if back == 0 {
                        Text("—").foregroundStyle(PP.textMuted)
                    } else {
                        Text("-\(Points.format(back))").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                        if let gap { Text("(\(gap == 0 ? "0" : "-\(Points.format(gap))"))").font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.textMuted.opacity(0.75)) }
                    }
                }))
            }
            rows.append(GridRowItem(id: row.competitorKey, ident: ident, cells: cells, lines: lines))
            prevPoints = row.totalPoints
        }
        // Tag (16) + gap + a four-digit value (~34) + padding, plus the marks gutter.
        let pointsColumnWidth: CGFloat = breakdown ? CGFloat(88 + marksWidth * 7 + (marksWidth > 0 ? 6 : 0)) : 64
        dataColumns = dataColumns.map { col in
            col.id.hasPrefix("r") && mode == .points
                ? GridColumn(id: col.id, width: pointsColumnWidth, align: .trailing, title: { col.title })
                : col
        }
        return GridTable(identColumns: identColumns, dataColumns: dataColumns,
                         sections: [GridSection(id: String(champ.id),
                                                band: ("\(champ.className ?? "") · \(Champs.champKindLabel(champ))", color),
                                                rows: rows)])
            .accessibilityLabel("\(champ.className ?? "") \(Champs.champKindLabel(champ)) — \(mode == .recap ? "season recap" : "championship points by round")")
    }
}

/// `.pts-line`: tag left, value right, marks in a shared gutter.
struct PtsLine: View {
    let tag: String?
    let value: Double
    let marks: [Points.Mark]
    let marksWidth: Int
    let zero: Bool

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 6) {
            if let tag {
                Text(tag).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted).frame(minWidth: 16, alignment: .leading)
            }
            Text(Points.format(value)).font(PP.mono(PP.TextSize.sm)).foregroundStyle(zero ? PP.textMuted : PP.text)
                .lineLimit(1).fixedSize()
                .frame(maxWidth: .infinity, alignment: .trailing)
            if marksWidth > 0 {
                HStack(spacing: 3) {
                    ForEach(marks, id: \.self) { m in
                        Text(m.glyph).foregroundStyle(markColor(m.kind)).accessibilityLabel(m.text)
                    }
                }
                .font(PP.mono(PP.TextSize.xs, weight: 700))
                .frame(minWidth: CGFloat(marksWidth) * 7, alignment: .leading)
            }
        }
    }

    private func markColor(_ kind: Points.Mark.Kind) -> Color {
        switch kind {
        case .pole: PP.accentInk
        case .fastestLap: PP.text
        case .bonus: PP.textMuted
        case .penalty: PP.error
        }
    }
}
