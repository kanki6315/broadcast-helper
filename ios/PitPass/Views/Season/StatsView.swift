import SwiftUI

/// StatsPage: per-driver or per-team tallies by race format, plus qualifying.
/// Season or all-time scope, format groups toggle off, columns sort within
/// each class band. Non-zero wins/podiums/top-5s wear the result tints.
struct StatsView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model
    @State private var mode: Mode? = .drivers
    @State private var scope: Scope? = .season
    @State private var hidden: Set<String> = []
    @State private var sort: Sort?
    @State private var drivers: Resource<StatsTable>?
    @State private var teams: Resource<TeamStatsTable>?

    enum Mode: String, Identifiable { case drivers, teams; var id: String { rawValue } }
    enum Scope: String, Identifiable { case season, alltime; var id: String { rawValue } }
    struct Sort: Equatable { let key: String; let dir: Dir; enum Dir { case asc, desc } }

    struct Row: Identifiable {
        let id: Int
        let name: String
        let car: String?
        let className: String
        let byFormat: [FormatLine]
        let quali: QualiLine
    }

    private struct RaceCol { let key: String; let label: String; let rated: Bool }
    private static let raceCols = [
        RaceCol(key: "starts", label: "St", rated: false), RaceCol(key: "wins", label: "W", rated: true),
        RaceCol(key: "podiums", label: "P3", rated: true), RaceCol(key: "top5s", label: "T5", rated: true),
        RaceCol(key: "dnfs", label: "DNF", rated: false),
    ]

    private var path: String {
        guard let hub = model.hub.value else { return "" }
        let leaf = mode == .drivers ? "stats" : "team-stats"
        return scope == .season ? "/api/seasons/\(hub.id)/\(leaf)" : "/api/series/\(hub.seriesId)/\(leaf)"
    }

    private var data: (formats: [FormatInfo], rows: [Row])? {
        if mode == .drivers, let t = drivers?.value {
            return (t.formats, t.rows.map { Row(id: $0.driverId, name: $0.driverName, car: $0.carNumber, className: $0.className, byFormat: $0.byFormat, quali: $0.quali) })
        }
        if mode == .teams, let t = teams?.value {
            return (t.formats, t.rows.map { Row(id: $0.teamId, name: $0.teamName, car: $0.carNumbers, className: $0.className, byFormat: $0.byFormat, quali: $0.quali) })
        }
        return nil
    }

    private var error: String? { mode == .drivers ? drivers?.error : teams?.error }

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            filterBar
            if let error, data == nil {
                ErrorPanel(message: error)
            } else if let data {
                table(data.formats, data.rows)
            } else {
                SkeletonBlock(height: 192)
            }
        }
        .padding(.top, PP.Space.s4)
        .task(id: path) {
            guard !path.isEmpty else { return }
            hidden = []
            sort = nil
            if mode == .drivers {
                let r = Resource<StatsTable>(path); drivers = r
                await r.load(session.loader, freshness: session.freshness)
            } else {
                let r = Resource<TeamStatsTable>(path); teams = r
                await r.load(session.loader, freshness: session.freshness)
            }
        }
    }

    private var filterBar: some View {
        let formats = data?.formats ?? []
        let hasQuali = (data?.rows ?? []).contains { $0.quali.sessions > 0 }
        let groupCount = formats.count + (hasQuali ? 1 : 0)
        var toggles: [Segmented<String>.Option] = formats.map { .init(id: $0.key, label: $0.name) }
        let _ = { if hasQuali { toggles.append(.init(id: "quali", label: "Qualifying")) } }()
        let on = Set(toggles.map(\.id)).subtracting(hidden)
        return FlowLayout(horizontalSpacing: PP.Space.s3, verticalSpacing: PP.Space.s2) {
            Segmented(options: [.init(id: Mode.drivers, label: "Drivers"), .init(id: .teams, label: "Teams")], selection: $mode)
            Segmented(options: [.init(id: Scope.season, label: "\(String(model.hub.value?.year ?? 0)) season"), .init(id: .alltime, label: "All-time")], selection: $scope)
            if scope == .alltime {
                Text("Main seasons only — qualifying stages excluded.").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
            }
            if groupCount > 1 {
                SegmentedToggles(options: toggles, on: .constant(on)) { key in
                    if hidden.contains(key) { hidden.remove(key) }
                    else if on.count > 1 {
                        hidden.insert(key)
                        if let s = sort, key == "quali" ? s.key.hasPrefix("quali:") : s.key.hasPrefix("fmt:\(key):") { sort = nil }
                    }
                }
            }
            Legend(items: [LegendItem(text: "St = starts"), LegendItem(text: "W = wins"), LegendItem(text: "P3 = podiums"),
                           LegendItem(text: "T5 = top 5s"), LegendItem(text: "% = share of starts")]
                   + (hasQuali ? [LegendItem(text: "poles from qualifying only", accent: true)] : []))
        }
    }

    // MARK: table

    private func table(_ allFormats: [FormatInfo], _ rows: [Row]) -> some View {
        let formats = allFormats.filter { !hidden.contains($0.key) }
        let hasQuali = rows.contains { $0.quali.sessions > 0 }
        let showQuali = hasQuali && !hidden.contains("quali")
        let order = Dictionary(uniqueKeysWithValues: model.classes.enumerated().map { ($1.name, $0) })
        var groups: [(String, [Row])] = []
        for row in rows where model.classFilter == nil || row.className == model.classFilter {
            if let i = groups.firstIndex(where: { $0.0 == row.className }) { groups[i].1.append(row) } else { groups.append((row.className, [row])) }
        }
        groups.sort { (order[$0.0] ?? 99) < (order[$1.0] ?? 99) }

        let ident: [GridColumn] = [
            sortableColumn("name", label: mode == .drivers ? "Driver" : "Team", width: 200, align: .leading),
            sortableColumn("car", label: "#", width: 64, align: .trailing),
        ]
        var data: [GridColumn] = []
        for f in formats {
            for (ci, c) in Self.raceCols.enumerated() {
                data.append(sortableColumn("fmt:\(f.key):\(c.key)", label: c.label, group: ci == 0 ? f.name : nil, width: c.rated ? 78 : 50, align: .center, groupStart: ci == 0))
            }
        }
        if showQuali {
            data.append(sortableColumn("quali:poles", label: "Pole", group: "Qualifying", width: 56, align: .center, groupStart: true))
            data.append(sortableColumn("quali:top5s", label: "T5", width: 50, align: .center))
        }

        let sections: [GridSection] = groups.map { name, rows in
            let sorted = sortRows(rows)
            let band: (String, String)? = (groups.count > 1 || model.classFilter == nil) ? (name, model.classColor(name)) : nil
            return GridSection(id: name, band: band, rows: sorted.map { row in
                let byFormat = Dictionary(row.byFormat.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
                var cells: [AnyView] = []
                for f in formats {
                    let line = byFormat[f.key]
                    for c in Self.raceCols {
                        if c.rated { cells.append(AnyView(StatPair(col: c.key, line: line))) }
                        else { cells.append(AnyView(StatValue(col: c.key, value: line.map { statValue($0, c.key) }))) }
                    }
                }
                if showQuali {
                    cells.append(AnyView(StatValue(col: "poles", value: row.quali.sessions > 0 ? row.quali.poles : nil)))
                    cells.append(AnyView(StatValue(col: "qualiTop5s", value: row.quali.sessions > 0 ? row.quali.top5s : nil)))
                }
                return GridRowItem(id: "\(row.id)-\(row.className)", ident: [GridCell.name(row.name), GridCell.car(row.car ?? "")], cells: cells, lines: 1)
            })
        }
        return GridTable(identColumns: ident, dataColumns: data, sections: sections, cellPadH: 5, headerHeight: 46)
    }

    private func sortableColumn(_ key: String, label: String, group: String? = nil, width: CGFloat, align: GridColumn.Align, groupStart: Bool = false) -> GridColumn {
        let active = sort?.key == key
        let dir = active ? sort!.dir : defaultDir(key)
        return GridColumn(id: key, width: width, align: align, padH: 5) {
            Button { toggleSort(key) } label: {
                VStack(spacing: 1) {
                    Text(group ?? " ").font(PP.sans(PP.TextSize.xs, weight: 700)).foregroundStyle(PP.text).lineLimit(1).fixedSize()
                        .frame(maxWidth: .infinity, alignment: .leading)
                    HStack(spacing: 2) {
                        Text(label).font(PP.sans(PP.TextSize.xs, weight: active ? 600 : 500)).foregroundStyle(active ? PP.ink : PP.textMuted)
                        if active { Text(dir == .asc ? "▲" : "▼").font(.system(size: 7)).foregroundStyle(PP.accentInk) }
                    }
                }
            }
            .buttonStyle(.plain)
            .overlay(alignment: .leading) { if groupStart { Rectangle().fill(PP.border).frame(width: 1).padding(.leading, -5) } }
            .accessibilityLabel("\(group.map { $0 + " " } ?? "")\(label), sort")
        }
    }

    private func defaultDir(_ key: String) -> Sort.Dir { key == "name" || key == "car" ? .asc : .desc }

    private func toggleSort(_ key: String) {
        withAnimation(PP.Motion.fast) {
            if sort?.key != key { sort = Sort(key: key, dir: defaultDir(key)) }
            else if sort!.dir == defaultDir(key) { sort = Sort(key: key, dir: sort!.dir == .asc ? .desc : .asc) }
            else { sort = nil }
        }
    }

    private func statValue(_ line: FormatLine, _ key: String) -> Int {
        switch key {
        case "starts": line.starts
        case "wins": line.wins
        case "podiums": line.podiums
        case "top5s": line.top5s
        default: line.dnfs
        }
    }

    /// Never-contested sorts last in both directions; ties keep the backend's ranking.
    private func sortRows(_ rows: [Row]) -> [Row] {
        guard let sort else { return rows }
        let flip: Double = sort.dir == .asc ? 1 : -1
        func value(_ r: Row) -> (num: Double?, text: String?) {
            switch sort.key {
            case "name": return (nil, r.name.lowercased())
            case "car": return (nil, r.car)
            case "quali:poles": return (r.quali.sessions > 0 ? Double(r.quali.poles) : nil, nil)
            case "quali:top5s": return (r.quali.sessions > 0 ? Double(r.quali.top5s) : nil, nil)
            default:
                let parts = sort.key.split(separator: ":").map(String.init)
                guard parts.count == 3, let line = r.byFormat.first(where: { $0.key == parts[1] }) else { return (nil, nil) }
                return (Double(statValue(line, parts[2])), nil)
            }
        }
        return rows.enumerated().sorted { a, b in
            let va = value(a.element), vb = value(b.element)
            let na = va.num == nil && va.text == nil, nb = vb.num == nil && vb.text == nil
            if na || nb { return na == nb ? a.offset < b.offset : nb }
            if let ta = va.text, let tb = vb.text {
                let cmp = sort.key == "car" ? compareCar(ta, tb) : ta.compare(tb)
                return cmp == .orderedSame ? a.offset < b.offset : (flip > 0 ? cmp == .orderedAscending : cmp == .orderedDescending)
            }
            let d = (va.num ?? 0) - (vb.num ?? 0)
            return d == 0 ? a.offset < b.offset : (d * flip < 0)
        }.map(\.element)
    }

    private func compareCar(_ a: String, _ b: String) -> ComparisonResult {
        guard let na = Int(a), let nb = Int(b) else { return a.compare(b) }
        return na != nb ? (na < nb ? .orderedAscending : .orderedDescending) : a.compare(b)
    }
}

/// A count in the result-tint chip when non-zero; a receded "·" for never contested.
private struct StatValue: View {
    let col: String
    let value: Int?
    var body: some View {
        if let value { StatFigure(col: col, value: value) }
        else { Text("·").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted.opacity(0.55)) }
    }
}

private struct StatFigure: View {
    let col: String
    let value: Int
    var body: some View {
        let tint: Color? = value > 0 ? (col == "wins" || col == "poles" ? ResultTint.win : col == "podiums" ? ResultTint.top3 : (col == "top5s" || col == "qualiTop5s") ? ResultTint.top5 : nil) : nil
        Text(String(value))
            .font(PP.mono(PP.TextSize.sm, weight: tint != nil ? 600 : 400))
            .foregroundStyle(value == 0 ? PP.textMuted.opacity(0.55) : PP.text)
            .frame(minWidth: 22).padding(.horizontal, 4)
            .background(tint ?? .clear, in: RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous))
    }
}

/// A count beside its share of starts; the rate slot is reserved even when empty.
private struct StatPair: View {
    let col: String
    let line: FormatLine?
    var body: some View {
        let value: Int? = line.map { l in col == "wins" ? l.wins : col == "podiums" ? l.podiums : l.top5s }
        let rate: Int? = (line != nil && (value ?? 0) > 0 && line!.starts > 0) ? Int((Double(value!) / Double(line!.starts) * 100).rounded()) : nil
        HStack(alignment: .firstTextBaseline, spacing: 5) {
            StatValue(col: col, value: value).frame(width: 30, alignment: .trailing)
            Text(rate.map { "\($0)%" } ?? "").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).frame(width: 34, alignment: .leading)
        }
    }
}
