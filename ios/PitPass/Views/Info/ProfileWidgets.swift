import SwiftUI

// The driver-modal.css vocabulary, natively: the bio facts, the career chips
// and lines (CareerStats.tsx), the championship matrices (ChampSection /
// TeamChampSection over the GridTable), the read-only notes block and the
// loading skeleton. Shared by DriverProfileView and TeamProfileView so the
// two read identically.

/// `.dm-champ-head h3` / `.dm-roster-head`: a section heading with an
/// optional trailing figure.
struct ProfileSectionHead<Trailing: View>: View {
    let title: String
    @ViewBuilder var trailing: Trailing

    init(_ title: String, @ViewBuilder trailing: () -> Trailing = { EmptyView() }) {
        self.title = title
        self.trailing = trailing()
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
            Text(title).font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink)
                .lineLimit(1).truncationMode(.tail)
            Spacer(minLength: 0)
            trailing
        }
    }
}

/// `.dm-quiet`: the muted sentence that stands in for an empty section.
struct QuietText: View {
    let text: String
    var body: some View {
        Text(text).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// `.dm-facts`: label over value, wrapping.
struct FactList: View {
    let facts: [Bio.Fact]

    var body: some View {
        FlowLayout(horizontalSpacing: PP.Space.s6, verticalSpacing: PP.Space.s2) {
            ForEach(facts, id: \.self) { f in
                VStack(alignment: .leading, spacing: 1) {
                    Text(f.label).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
                    Text(f.value)
                        .font(f.mono ? PP.mono(PP.TextSize.sm, weight: 500) : PP.sans(PP.TextSize.sm, weight: 500))
                        .foregroundStyle(PP.ink)
                }
            }
        }
    }
}

/// `.dm-rating`: identity, not selection — a quiet bordered mono mark.
struct RatingMark: View {
    let rating: String
    var body: some View {
        Text(rating).font(PP.mono(PP.TextSize.xs, weight: 700)).foregroundStyle(PP.textMuted)
            .padding(.horizontal, 5).padding(.vertical, 1)
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous).strokeBorder(PP.borderStrong))
            .accessibilityLabel("Driver rating \(rating)")
    }
}

/// CareerStats.tsx: career chips plus per-series all-time and per-season lines.
struct CareerStatsView: View {
    let stats: any CareerStatsData

    var body: some View {
        let chips = CareerLines.chips(stats.career)
        let multi = CareerLines.multiSeasonSeries(stats)
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if !chips.isEmpty {
                FlowLayout(horizontalSpacing: PP.Space.s2, verticalSpacing: PP.Space.s2) {
                    ForEach(chips, id: \.self) { c in
                        VStack(spacing: 0) {
                            Text(String(c.value)).font(PP.mono(PP.TextSize.lg, weight: 700)).foregroundStyle(PP.ink)
                            Text(c.label).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
                        }
                        .frame(minWidth: 64)
                        .padding(.vertical, 6).padding(.horizontal, 10)
                        .background(PP.surface2, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
                        .accessibilityElement(children: .combine)
                    }
                }
            }
            VStack(alignment: .leading, spacing: 0) {
                ForEach(stats.bySeries.filter { multi.contains($0.seriesId) }, id: \.seriesId) { s in
                    line(when: "\(s.seriesName) all-time", badge: nil, what: CareerLines.formatSplit(s.byFormat, quali: s.quali))
                }
                ForEach(stats.seasons, id: \.self) { s in
                    line(when: "\(String(s.year)) \(s.seriesName)",
                         badge: s.qualifier ? (s.seasonLabel ?? "Qualifying") : nil,
                         what: CareerLines.formatSplit(s.byFormat, quali: s.quali))
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Career stats")
    }

    private func line(when: String, badge: String?, what: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
            HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                Text(when).font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.textMuted)
                if let badge { QualifierBadge(text: badge) }
            }
            .fixedSize()
            Text(what).font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.ink)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 3)
    }
}

/// ChampSection: one driver's start→finish and points by round.
struct DriverChampMatrixView: View {
    let champ: DriverChampMatrix

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            ProfileSectionHead(champ.title) {
                HStack(spacing: 0) {
                    Text("P\(champ.position)").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.ink)
                    Text(" · \(Points.format(champ.totalPoints)) pts").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                }
                .fixedSize()
            }
            if champ.rounds.isEmpty {
                QuietText(text: "No calendar published for this championship yet.")
            } else {
                ChampMatrixTable(rounds: champ.rounds, blocks: [
                    .init(id: "me", head: AnyView(Text("Result").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)),
                          races: champ.races(round:), points: champ.points(round:)),
                ], identWidth: 64)
                .accessibilityLabel("\(champ.title) — start and finish by round")
            }
        }
    }
}

/// TeamChampSection: one Result/Pts row pair per car.
struct TeamChampMatrixView: View {
    let champ: TeamChampMatrix

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            ProfileSectionHead(champ.title)
            if champ.rounds.isEmpty {
                QuietText(text: "No calendar published for this championship yet.")
            } else {
                ChampMatrixTable(rounds: champ.rounds, blocks: champ.entries.map { e in
                    .init(id: e.carNumber,
                          head: AnyView(VStack(alignment: .leading, spacing: 1) {
                              Text("#\(e.carNumber)").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.text)
                              Text("P\(e.position) · \(Points.format(e.totalPoints))").font(PP.mono(PP.TextSize.xs, weight: 500)).foregroundStyle(PP.textMuted)
                          }.lineLimit(1)),
                          headLines: 2,
                          races: e.races(round:), points: e.points(round:))
                }, identWidth: 96)
                .accessibilityLabel("\(champ.title) — start and finish by round")
            }
        }
    }
}

/// `.dm-matrix` over the GridTable: the row heads pin on the left, the rounds
/// scroll. Each block is a Result row and, when any points exist, a Pts row.
struct ChampMatrixTable: View {
    struct Block: Identifiable {
        let id: String
        let head: AnyView
        /// Lines the row head needs (a car head is number over standing).
        var headLines = 1
        let races: (Int) -> [RecapRace]?
        let points: (Int) -> Double?
    }

    let rounds: [RecapRound]
    let blocks: [Block]
    var identWidth: CGFloat

    var body: some View {
        let columns = rounds.map { r in
            GridColumn.round("r\(r.round)", venue: r.venue, round: r.round, width: 66, padH: 4)
        }
        var rows: [GridRowItem] = []
        for b in blocks {
            let cells = rounds.map { r -> AnyView in
                AnyView(RaceCellView(races: b.races(r.round), raceTags: RaceForm.raceTagsByOrdinal(r.races)))
            }
            let lines = rounds.map { RaceCellView.lines(b.races($0.round)) }.max() ?? 1
            rows.append(GridRowItem(id: "\(b.id)-result", ident: [b.head], cells: cells, lines: max(lines, b.headLines, 1)))
            let hasPoints = rounds.contains { b.points($0.round) != nil }
            if hasPoints {
                let pts = rounds.map { r -> AnyView in
                    b.points(r.round).map { GridCell.num(Points.format($0), muted: true) } ?? GridCell.num("—", muted: true)
                }
                rows.append(GridRowItem(id: "\(b.id)-pts",
                                        ident: [AnyView(Text("Pts").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted))],
                                        cells: pts, lines: 1))
            }
        }
        return GridTable(identColumns: [.text("head", "", width: identWidth)], dataColumns: columns,
                         sections: [GridSection(id: "m", band: nil, rows: rows)],
                         lineHeight: 20, cellPadV: 3, cellPadH: 8, headerHeight: 40,
                         separatesIdentity: true, centersCells: true)
            .padding(.bottom, -PP.Space.s5)
    }
}

/// NotesSection for a viewer: the broadcast notes, read-only. Editing stays
/// on the website.
struct NotesBlock: View {
    let notes: String?

    var body: some View {
        let text = notes?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            ProfileSectionHead("Broadcast notes")
            Text(text.isEmpty ? "No notes yet." : text)
                .font(PP.sans(PP.TextSize.sm))
                .foregroundStyle(text.isEmpty ? PP.textMuted : PP.text)
                .lineSpacing(3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, minHeight: 96, alignment: .topLeading)
                .padding(.vertical, 8).padding(.horizontal, 10)
                .background(PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.borderStrong))
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Broadcast notes")
    }
}

/// `.dm-skel-*`: the loading state, with a photo block for drivers.
struct ProfileSkeleton: View {
    var photo = false

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            HStack(spacing: PP.Space.s4) {
                if photo { SkeletonBlock(height: 84, widthFraction: 0.12) }
                SkeletonBlock(height: 24, widthFraction: 0.5)
            }
            SkeletonBlock()
            SkeletonBlock()
            SkeletonBlock(height: 64)
        }
        .padding(PP.Space.s5)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(PP.bg)
        .accessibilityLabel("Loading")
    }
}

/// The tail every profile screen shares: the source of what's shown.
struct ProfileFooter: View {
    let isStale: Bool
    let fetchedAt: Date?

    var body: some View {
        if isStale, let fetchedAt {
            Text("Saved copy from \(ConnectivityPill.age(Date.now.timeIntervalSince(fetchedAt))) ago — the server hasn't confirmed it yet.")
                .font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
        }
    }
}
