import SwiftUI

/// EntriesPage: crew per car per round. A tinted cell is a lineup change from
/// the car's previous entered round; "—" means the car skipped the round.
struct EntriesView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model

    var body: some View {
        let resource = model.lineups
        Group {
            if let lineups = resource?.value {
                content(lineups)
            } else if let error = resource?.error {
                ErrorPanel(message: error)
            } else {
                SkeletonLines()
            }
        }
        .task(id: model.seasonId) { await model.lineups?.load(session.loader, freshness: session.freshness) }
    }

    private func content(_ lineups: Lineups) -> some View {
        let classes = lineups.classes.filter { model.classFilter == nil || $0.className == model.classFilter }
        return Group {
            if lineups.rounds.isEmpty || classes.isEmpty {
                EmptyState(message: "No entry lists imported yet — driver lineups appear here per round once entry lists come in.")
            } else {
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    Text("Crew per car per round. A highlighted cell is a lineup change from the car’s previous round; “—” means the car skipped the round.")
                        .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                        .padding(.vertical, PP.Space.s3)
                    GridTable(identColumns: [.text("car", "#", width: 72, align: .trailing), .text("team", "Team", width: 240)],
                              dataColumns: lineups.rounds.map { .round("r\($0.ordinal)", venue: $0.venue, round: $0.ordinal,
                                                                       width: roundWidth($0.ordinal, classes)) },
                              sections: classes.map { section($0, ordinals: lineups.rounds.map(\.ordinal)) },
                              lineHeight: 17.5)
                }
            }
        }
    }

    /// Auto-width like a table cell: the longest "I. Surname RATING" line in
    /// the column, in 12pt Inter (~6.4pt per character), plus the change dot.
    private func roundWidth(_ ordinal: Int, _ classes: [LineupClass]) -> CGFloat {
        var longest = 0
        for cls in classes {
            for car in cls.cars {
                for d in car.crew(round: ordinal) ?? [] {
                    let line = (d.isTbd ? "TBD" : Names.short(d.name)) + (d.rating.map { " " + $0 } ?? "")
                    longest = max(longest, line.count)
                }
            }
        }
        return min(220, max(66, CGFloat(longest) * 6.4 + 30))
    }

    private func section(_ cls: LineupClass, ordinals: [Int]) -> GridSection {
        GridSection(id: cls.className, band: (cls.className, model.classColor(cls.className)), rows: cls.cars.map { car in
            var cells: [AnyView] = []
            var lines = 1
            for (idx, ord) in ordinals.enumerated() {
                guard let crew = car.crew(round: ord), !crew.isEmpty else { cells.append(GridCell.skip()); continue }
                let prev = ordinals[..<idx].reversed().first { !(car.crew(round: $0) ?? []).isEmpty }
                let changed = prev.map { !sameCrew(crew, car.crew(round: $0) ?? []) } ?? false
                lines = max(lines, crew.count)
                cells.append(AnyView(LineupCell(crew: crew, changed: changed)))
            }
            let ident: [AnyView] = [
                AnyView(HStack(spacing: 4) {
                    Text(car.carNumber).font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.text)
                    if car.isGuest { Badge(text: "G") }
                }),
                GridCell.name(car.teamName ?? ""),
            ]
            return GridRowItem(id: "\(cls.className)-\(car.carNumber)", ident: ident, cells: cells, lines: lines)
        })
    }

    private func sameCrew(_ a: [LineupDriver], _ b: [LineupDriver]) -> Bool {
        a.count == b.count && Set(a.map(\.name)) == Set(b.map(\.name))
    }
}

/// `.lineup-cell`: one driver per line, rating muted, the change dot leading.
private struct LineupCell: View {
    let crew: [LineupDriver]
    let changed: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(crew.enumerated()), id: \.0) { i, d in
                HStack(spacing: 4) {
                    if i == 0, changed { Circle().fill(PP.accent).frame(width: 6, height: 6) }
                    Text(d.isTbd ? "TBD" : Names.short(d.name)).foregroundStyle(PP.text)
                    if let rating = d.rating { Text(rating).foregroundStyle(PP.textMuted) }
                }
                .font(PP.sans(PP.TextSize.xs))
                .lineLimit(1)
                .frame(height: 17.5)
            }
        }
        .padding(.horizontal, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(changed ? PP.accentTint : .clear)
        .accessibilityLabel(changed ? "Lineup changed vs previous round" : "")
    }
}
