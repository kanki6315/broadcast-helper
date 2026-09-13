import SwiftUI

/// ChampionshipGrid: the filter bar (championship family, competitor kind,
/// points view, show-teams), the legend, and one ClassGrid per selected
/// championship. `recap` shows start→finish chips per round; `points` the
/// earnings per round with points and gaps pinned beside the entry.
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
            } else {
                if let ptsFlags, model.pointsView == .breakdown {
                    PointsLegend(flags: ptsFlags)
                }
                Legend(items: [LegendItem(text: "Gap to leader (previous position)")])
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
    @State private var availableWidth: CGFloat = 0
    @Environment(\.infoModals) private var modals
    @ScaledMetric private var entryLineHeight: CGFloat = 24

    // The same entry layout in every orientation; reserve two rounds in narrow windows.
    private var entryWidth: CGFloat { min(280, max(140, availableWidth - 132)) }

    var body: some View {
        let resource = model.recap(for: champ)
        Group {
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
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { availableWidth = $0 }
    }

    private func grid(_ recap: Recap) -> some View {
        let rounds = recap.rounds.filter { $0.hasParticipation(in: recap.rows) }
        let drivers = recap.championship.kind == "DRIVERS"
        let leader = recap.rows.map(\.totalPoints).max() ?? 0
        let color = model.classColor(champ.className)
        let breakdown = mode == .points && model.pointsView == .breakdown
        let raceTags: [Int: [Int: String?]] = Dictionary(uniqueKeysWithValues: rounds.map { ($0.round, RaceForm.raceTagsByOrdinal($0.races)) })
        let sessionTags: [Int: [Int: String?]] = Dictionary(uniqueKeysWithValues: rounds.map { ($0.round, RaceForm.sessionTags($0.sessions)) })
        let marksWidth: Int = breakdown
            ? recap.rows.flatMap { $0.sessionPoints.values }.map { Points.marks($0).map(\.glyph).joined(separator: " ").count }.max() ?? 0
            : 0

        let identColumns = [GridColumn(id: "entry", width: entryWidth, padH: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text(drivers ? "Driver" : "Team").font(PP.sans(PP.TextSize.xs, weight: 600))
                Text("Position · car · points").font(PP.sans(PP.TextSize.xs))
            }
            .foregroundStyle(PP.textMuted)
            .lineLimit(1)
        }]
        let tightRounds = mode == .recap && availableWidth < 890
        var dataColumns: [GridColumn] = rounds.map { round in
            let padH: CGFloat = tightRounds ? 2 : 4
            let contentWidth = recap.rows.map {
                RaceCellView.contentWidth($0.races(round: round.round),
                                          raceTags: raceTags[round.round] ?? [:], chipPadding: padH)
            }.max() ?? 0
            return .round("r\(round.round)", venue: round.venue, round: round.round,
                          width: max(tightRounds ? 60 : 66, contentWidth + padH * 2),
                          padH: padH,
                          current: model.currentEventId != nil && round.eventId == model.currentEventId)
        }
        var rows: [GridRowItem] = []
        var prevPoints: Double?
        for row in recap.rows {
            let back = leader - row.totalPoints
            // Entrant standings can be keyed by a team name rather than a car.
            let keyIsCar = row.cells.values.joined().contains { $0.carNumber == row.carNumber }
            let teamKeyed = !drivers && !keyIsCar
                && (row.carNumber == nil || row.carNumber?.contains(where: \.isLetter) == true)
            let name = drivers ? (row.competitorName ?? row.competitorKey)
                : (row.teamName?.isEmpty == false ? row.teamName! : (teamKeyed ? row.competitorKey : ""))
            let carNumber = teamKeyed ? nil : row.carNumber
            let teamNames: [String] = drivers ? (row.teamNames?.isEmpty == false ? row.teamNames! : (row.teamName.map { [$0] } ?? [])) : []
            let shownTeams = mode == .recap && model.showTeams ? teamNames : []
            // The primary label opens the championship competitor's profile;
            // team sub-lines open the team's (Privateer stays plain).
            let target: InfoTarget? = drivers ? InfoTarget.driver(named: name) : InfoTarget.team(named: name)
            let entry = RecapEntryDetail(id: row.competitorKey, position: row.position,
                                         carNumber: carNumber, name: name,
                                         points: row.totalPoints, back: back, teams: teamNames,
                                         gap: mode == .points ? prevPoints.map { $0 - row.totalPoints } : nil)
            let label = RecapEntryLabel(entry: entry, shownTeams: shownTeams)
            let ident: [AnyView]
            if let target, let modals {
                ident = [AnyView(Button {
                    modals.open(target)
                } label: {
                    label
                }
                .buttonStyle(.plain)
                .accessibilityLabel(entry.accessibilityLabel)
                .accessibilityHint("Opens the profile"))]
            } else {
                ident = [AnyView(label.accessibilityLabel(entry.accessibilityLabel))]
            }

            var cells: [AnyView] = []
            var lines = shownTeams.isEmpty ? 2 : 3
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
                    cells.append(AnyView(RaceCellView(races: races, raceTags: raceTags[r.round] ?? [:], chipPadding: tightRounds ? 2 : 4).frame(maxWidth: .infinity)))
                }
            }
            rows.append(GridRowItem(id: row.competitorKey, ident: ident, cells: cells, lines: lines))
            prevPoints = row.totalPoints
        }
        // Tag (16) + gap + a four-digit value (~34) + padding, plus the marks gutter.
        let minimumPointsWidth: CGFloat = breakdown ? CGFloat(88 + marksWidth * 7 + (marksWidth > 0 ? 6 : 0)) : 60
        let pointsColumnWidth = max(minimumPointsWidth, (availableWidth - entryWidth) / CGFloat(max(1, rounds.count)))
        dataColumns = dataColumns.map { col in
            col.id.hasPrefix("r") && mode == .points
                ? GridColumn(id: col.id, width: pointsColumnWidth, align: .trailing, title: { col.title })
                : col
        }
        return VStack(alignment: .trailing, spacing: 0) {
            GridTable(identColumns: identColumns, dataColumns: dataColumns,
                         sections: [GridSection(id: String(champ.id),
                                                band: ("\(champ.className ?? "") · \(Champs.champKindLabel(champ))", color),
                                                rows: rows)],
                         lineHeight: entryLineHeight,
                         cellPadV: 3,
                         headerHeight: entryLineHeight * 2,
                         separatesIdentity: true,
                         centersCells: true)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("\(champ.className ?? "") \(Champs.champKindLabel(champ)) — \(mode == .recap ? "season recap" : "championship points by round")")
            if dataColumns.reduce(0, { $0 + $1.width }) > availableWidth - entryWidth + 1 {
                Label("Swipe for more rounds", systemImage: "arrow.right")
                    .font(PP.sans(PP.TextSize.xs))
                    .foregroundStyle(PP.textMuted)
                    .padding(.bottom, PP.Space.s3)
            }
        }
    }
}

private struct RecapEntryDetail: Identifiable {
    let id: String
    let position: Int
    let carNumber: String?
    let name: String
    let points: Double
    let back: Double
    let teams: [String]
    var gap: Double? = nil

    var accessibilityLabel: String {
        let car = carNumber.flatMap { $0.isEmpty ? nil : "Car \($0), " } ?? ""
        return "Position \(position), \(car)\(name), \(Points.format(points)) points"
            + (back > 0 ? ", \(Points.format(back)) behind leader" : ", championship leader")
            + (gap.map { ", \(Points.format($0)) behind previous position" } ?? "")
    }
}

private struct RecapEntryLabel: View {
    let entry: RecapEntryDetail
    let shownTeams: [String]
    @ScaledMetric private var positionWidth: CGFloat = 32
    @ScaledMetric private var positionSize: CGFloat = 36
    @ScaledMetric private var numberSize: CGFloat = 14
    @ScaledMetric private var pointsSize: CGFloat = 13

    var body: some View {
        // Trade gutter spacing for numeral width, keeping the entry text's left edge.
        HStack(alignment: .center, spacing: 4) {
            Text(String(entry.position))
                .font(.system(size: positionSize, weight: .medium))
                .fontWidth(.compressed)
                .monospacedDigit()
                .foregroundStyle(PP.text)
                .lineLimit(1)
                .minimumScaleFactor(0.65)
                .frame(width: positionWidth, alignment: .center)
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    if let car = entry.carNumber, !car.isEmpty {
                        Text("#\(car)").font(PP.mono(numberSize, weight: 500))
                            .foregroundStyle(PP.text).fixedSize()
                    }
                    Text(entry.name).font(PP.sans(PP.TextSize.sm, weight: 500))
                        .foregroundStyle(PP.ink).lineLimit(1)
                }
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(Points.format(entry.points))
                            .font(PP.mono(pointsSize, weight: 600))
                            .foregroundStyle(PP.ink)
                        Text("pts").font(PP.sans(PP.TextSize.xs))
                            .foregroundStyle(PP.text)
                    }
                    .layoutPriority(1)
                    if entry.back > 0 {
                        Text("−\(Points.format(entry.back))")
                            .font(PP.mono(PP.TextSize.xs))
                            .foregroundStyle(PP.textMuted)
                    }
                    if let gap = entry.gap {
                        Text("(\(gap == 0 ? "0" : "−\(Points.format(gap))"))")
                            .font(PP.mono(PP.TextSize.xs))
                            .foregroundStyle(PP.textMuted)
                    }
                }
                .lineLimit(1)
                if !shownTeams.isEmpty {
                    Text(shownTeams.joined(separator: " · "))
                        .font(PP.sans(PP.TextSize.xs))
                        .foregroundStyle(PP.textMuted).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
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
