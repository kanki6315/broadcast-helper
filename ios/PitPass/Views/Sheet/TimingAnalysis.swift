import Charts
import SwiftUI

/// The Timing tab's analysis views — gap to the class leader lap by lap, best
/// sectors, pit stops — over one recorded session: the web's
/// `pages/TimingAnalysis.tsx` on the iPad. The backend recomputes all three
/// from the stored laps and stints on every poll (LiveAnalysis.java), so they
/// fill in as the session runs.

/// Every 15 s while the session is live, every 2 min for a finished one.
private func pollEvery(_ s: LiveSessionSummary) -> Duration { s.current ? .seconds(15) : .seconds(120) }

/// The car a row opens, handed up to the sheet that presents `LiveCarSheet`.
struct AnalysisOpen {
    let car: AnalysisCar
    let color: String?
}

// MARK: - Gaps

/// Categorical slots 1–8 of the data-viz reference palette in its fixed order,
/// stepped per theme and validated adjacent-pairs against the page background
/// (as timing.css). Aqua, yellow and magenta sit under 3:1 on white, so every
/// followed line carries a direct label.
enum GapSeries {
    static let colors: [Color] = [
        PP.dynamicPublic(0x2A78D6, 0x3987E5),
        PP.dynamicPublic(0xEB6834, 0xD95926),
        PP.dynamicPublic(0x1BAF7A, 0x199E70),
        PP.dynamicPublic(0xEDA100, 0xC98500),
        PP.dynamicPublic(0xE87BA4, 0xD55181),
        PP.dynamicPublic(0x008300, 0x008300),
        PP.dynamicPublic(0x4A3AA7, 0x9085E9),
        PP.dynamicPublic(0xE34948, 0xE66767),
    ]
    /// Followed on first open: the class's top three, so the chart starts uncluttered.
    static let defaultPicks = 3
}

struct GapsSection: View {
    @Environment(AppSession.self) private var session
    let chosen: LiveSessionSummary
    let open: (AnalysisOpen) -> Void
    @State private var feed = LiveFeed<GapsResponse>()
    @State private var className: String?

    var body: some View {
        Group {
            if let value = feed.value {
                let classes = value.classes.filter { $0.gaps.contains { !$0.gapMs.isEmpty } }
                if let cls = classes.first(where: { $0.className == className }) ?? classes.first {
                    VStack(alignment: .leading, spacing: PP.Space.s3) {
                        if let error = feed.error { StaleLine(error: error) }
                        if classes.count > 1 {
                            Picker("Class", selection: Binding(get: { cls.className }, set: { className = $0 })) {
                                ForEach(classes) { Text($0.className).tag($0.className) }
                            }
                            .pickerStyle(.segmented)
                            .frame(maxWidth: 360)
                        }
                        GapPanel(cls: cls, open: open).id(cls.className)
                    }
                } else {
                    EmptyState(message: "No timed laps recorded for this session yet.")
                }
            } else if let error = feed.error {
                ErrorPanel(message: "Could not load gaps: \(error)")
            } else {
                SkeletonLines()
            }
        }
        .task(id: chosen.sessionDbId) {
            await feed.run(session.client, path: "/api/live/gaps?session=\(chosen.sessionDbId)", every: pollEvery(chosen))
        }
    }
}

private struct GapPanel: View {
    let cls: GapClass
    let open: (AnalysisOpen) -> Void
    /// Followed cars keep their colour slot until unfollowed: colour follows the car, not its rank.
    @State private var picked: [(car: String, slot: Int)] = []
    @State private var seeded = false
    @State private var asTable = false

    private var timed: [GapCar] { cls.gaps.filter { !$0.gapMs.isEmpty } }

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            HStack(alignment: .firstTextBaseline) {
                Text("Gap to the \(cls.className) leader after each lap. Follow up to eight cars; the rest stay in grey.")
                    .font(.subheadline).foregroundStyle(PP.textMuted)
                Spacer(minLength: PP.Space.s3)
                Picker("Show as", selection: $asTable) {
                    Text("Chart").tag(false)
                    Text("Table").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 180)
            }
            FlowLayout(horizontalSpacing: 6, verticalSpacing: 6) {
                ForEach(timed) { g in chip(g) }
            }
            if asTable {
                GapTable(cls: cls, cars: followedCars, open: open)
            } else {
                GapChart(gaps: timed, picked: picked)
            }
        }
        .onAppear {
            guard !seeded else { return }
            seeded = true
            picked = timed.prefix(GapSeries.defaultPicks).enumerated().map { ($1.carNumber, $0) }
        }
    }

    private var followedCars: [GapCar] {
        let order = picked.isEmpty ? timed.prefix(GapSeries.defaultPicks).map(\.carNumber) : picked.map(\.car)
        return order.compactMap { c in timed.first { $0.carNumber == c } }
    }

    private func toggle(_ car: String) {
        if picked.contains(where: { $0.car == car }) {
            picked.removeAll { $0.car == car }
            return
        }
        if picked.count >= GapSeries.colors.count { picked.removeFirst() }
        let used = Set(picked.map(\.slot))
        let slot = (0..<GapSeries.colors.count).first { !used.contains($0) } ?? 0
        picked.append((car, slot))
    }

    /// The chips double as the legend: a followed car shows its line colour.
    private func chip(_ g: GapCar) -> some View {
        let slot = picked.first { $0.car == g.carNumber }?.slot
        let color = slot.map { GapSeries.colors[$0] }
        return Button { toggle(g.carNumber) } label: {
            HStack(spacing: 6) {
                RoundedRectangle(cornerRadius: 1).fill(color ?? PP.borderStrong).frame(width: 10, height: slot == nil ? 2 : 3)
                Text("#\(g.carNumber)").font(PP.mono(PP.TextSize.sm, weight: 600))
                    .foregroundStyle(slot == nil ? PP.textMuted : PP.ink)
            }
            .padding(.horizontal, 10).frame(minHeight: 32)
            .background(PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.sm))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.sm).strokeBorder(color ?? PP.borderStrong))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Car \(g.carNumber)\(cls.cars.first { $0.carNumber == g.carNumber }?.teamName.map { ", \($0)" } ?? "")")
        .accessibilityAddTraits(slot != nil ? .isSelected : [])
    }
}

/// One plotted point: a car's gap in seconds on a lap. `segment` breaks the
/// line wherever the car was lapped or a lap went untimed.
private struct GapPoint: Identifiable {
    let car: String
    let segment: String
    let lap: Int
    /// The gap in seconds, negated (see `GapChart.points`).
    let value: Double
    var id: String { "\(segment)@\(lap)" }
}

private struct GapChart: View {
    let gaps: [GapCar]
    let picked: [(car: String, slot: Int)]
    @State private var hoverLap: Int?

    /// A car's points split into unbroken runs. Context lines past `limit`
    /// points are thinned by stride, keeping every run's ends — they are
    /// shape, not numbers. `value` is the gap negated: the chart's y runs up,
    /// and the leader belongs at the top.
    private func points(_ g: GapCar, limit: Int? = nil) -> [GapPoint] {
        var out: [GapPoint] = []
        var run = 0
        let step = limit.map { max(1, g.gapMs.count / $0) } ?? 1
        for (i, ms) in g.gapMs.enumerated() {
            guard let ms else { continue }
            let starts = i == 0 || g.gapMs[i - 1] == nil
            let ends = i == g.gapMs.count - 1 || g.gapMs[i + 1] == nil
            if starts { run += 1 }
            guard step == 1 || starts || ends || i % step == 0 else { continue }
            out.append(GapPoint(car: g.carNumber, segment: "\(g.carNumber)#\(run)", lap: g.firstLap + i, value: -Double(ms) / 1000))
        }
        return out
    }

    var body: some View {
        let focus = picked.compactMap { p in gaps.first { $0.carNumber == p.car }.map { (g: $0, slot: p.slot) } }
        let context = gaps.filter { g in !focus.contains { $0.g.carNumber == g.carNumber } }
        let firstLap = gaps.map(\.firstLap).min() ?? 1
        let lastLap = max(firstLap + 1, gaps.map(\.lastLap).max() ?? 1)
        // Scale to the followed cars, so one car a minute down after a long stop
        // does not flatten the fight; lines past the edge are pinned to it
        // (not clipped: clipping the plot would clip the direct labels too).
        let scaleOn = focus.isEmpty ? gaps : focus.map(\.g)
        let maxGap = scaleOn.flatMap { $0.gapMs.compactMap { $0 } }.max().map { Double($0) / 1000 } ?? 1
        let yMax = Self.niceCeil(maxGap * 1.05)

        VStack(alignment: .leading, spacing: PP.Space.s2) {
            Chart {
                ForEach(context) { g in
                    ForEach(points(g, limit: 300)) { p in
                        LineMark(x: .value("Lap", p.lap), y: .value("Gap", max(p.value, -yMax)), series: .value("Run", p.segment))
                            .foregroundStyle(PP.borderStrong)
                            .lineStyle(StrokeStyle(lineWidth: 1))
                    }
                }
                ForEach(focus, id: \.g.carNumber) { f in
                    let color = GapSeries.colors[f.slot]
                    ForEach(points(f.g)) { p in
                        LineMark(x: .value("Lap", p.lap), y: .value("Gap", max(p.value, -yMax)), series: .value("Run", p.segment))
                            .foregroundStyle(color)
                            .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                    }
                    ForEach(f.g.pitLaps, id: \.self) { lap in
                        if let ms = f.g.gap(at: lap) {
                            PointMark(x: .value("Lap", lap), y: .value("Gap", max(-Double(ms) / 1000, -yMax)))
                                .symbol { Circle().strokeBorder(color, lineWidth: 2).background(Circle().fill(PP.bg)).frame(width: 9, height: 9) }
                        }
                    }
                    if let last = points(f.g).last {
                        PointMark(x: .value("Lap", last.lap), y: .value("Gap", max(last.value, -yMax)))
                            .symbolSize(30)
                            .foregroundStyle(color)
                    }
                }
                if let hoverLap {
                    RuleMark(x: .value("Lap", hoverLap))
                        .foregroundStyle(PP.textMuted)
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [2, 3]))
                        .annotation(position: .top, spacing: 0, overflowResolution: .init(x: .fit(to: .chart), y: .fit(to: .chart))) {
                            readout(hoverLap, focus: focus)
                        }
                }
            }
            // The leader at the top, further behind further down (gaps are plotted negated).
            .chartYScale(domain: -yMax...0)
            .chartXScale(domain: firstLap...lastLap)
            .chartYAxis {
                AxisMarks(position: .leading, values: stride(from: 0, through: yMax, by: yMax / 4).map { -$0 }) { v in
                    AxisGridLine().foregroundStyle(PP.border)
                    AxisValueLabel {
                        if let raw = v.as(Double.self) {
                            let s = abs(raw)
                            Text(s == 0 ? "Leader" : "+\(s.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(s)) : String(format: "%.1f", s))s")
                                .font(PP.mono(11)).foregroundStyle(PP.textMuted)
                        }
                    }
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 8)) { v in
                    AxisValueLabel {
                        if let l = v.as(Int.self) { Text(String(l)).font(PP.mono(11)).foregroundStyle(PP.textMuted) }
                    }
                }
            }
            .chartXAxisLabel(position: .bottomTrailing) { Text("Lap").font(PP.mono(11)).foregroundStyle(PP.textMuted) }
            // Tap a lap for its readout, tap it again to clear. Not chartXSelection:
            // inside the tab's ScrollView the scroll gesture takes the drag first.
            .chartOverlay { proxy in
                GeometryReader { geo in
                    ZStack(alignment: .topLeading) {
                        Rectangle().fill(.clear).contentShape(Rectangle())
                            .onTapGesture { location in
                                guard let frame = proxy.plotFrame else { return }
                                let x = location.x - geo[frame].origin.x
                                guard let lap: Int = proxy.value(atX: x) else { return }
                                let clamped = min(max(lap, firstLap), lastLap)
                                hoverLap = hoverLap == clamped ? nil : clamped
                            }
                        if let frame = proxy.plotFrame {
                            ForEach(labels(focus, proxy: proxy, plot: geo[frame], yMax: yMax)) { l in
                                HStack(spacing: 4) {
                                    Circle().fill(GapSeries.colors[l.slot]).frame(width: 8, height: 8)
                                    Text("#\(l.car)").font(PP.mono(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.ink)
                                }
                                .fixedSize()
                                .frame(width: 0, height: 0, alignment: .leading)
                                .position(x: l.x + 8, y: l.y)
                                .allowsHitTesting(false)
                            }
                        }
                    }
                }
            }
            .frame(height: 340)
            .padding(.trailing, 44)
            .accessibilityLabel("Gap to the class leader by lap. The table view has the numbers.")

            HStack(spacing: 6) {
                Circle().strokeBorder(PP.textMuted, lineWidth: 2).frame(width: 9, height: 9)
                Text("Pit stop, on the lap the car came in. A line breaks where the car was a lap or more down. Tap the chart for a lap's gaps.")
                    .font(.caption).foregroundStyle(PP.textMuted)
            }
        }
    }

    private struct GapLabel: Identifiable {
        let car: String
        let slot: Int
        let x: CGFloat
        var y: CGFloat
        var id: String { car }
    }

    /// Direct labels at each followed line's last point, nudged apart so they
    /// never overlap, then backed up from the bottom edge so a bunch of
    /// trailing cars never spills under the axis (as the web chart).
    private func labels(_ focus: [(g: GapCar, slot: Int)], proxy: ChartProxy, plot: CGRect, yMax: Double) -> [GapLabel] {
        let gap: CGFloat = 15
        var out = focus.compactMap { f -> GapLabel? in
            guard let last = points(f.g).last,
                  let x = proxy.position(forX: last.lap),
                  let y = proxy.position(forY: max(last.value, -yMax)) else { return nil }
            return GapLabel(car: f.g.carNumber, slot: f.slot, x: plot.minX + x, y: plot.minY + y)
        }
        .sorted { $0.y < $1.y }
        for i in out.indices.dropFirst() { out[i].y = max(out[i].y, out[i - 1].y + gap) }
        for i in out.indices.reversed() {
            out[i].y = min(out[i].y, i == out.count - 1 ? plot.maxY : out[i + 1].y - gap)
        }
        return out
    }

    private func readout(_ lap: Int, focus: [(g: GapCar, slot: Int)]) -> some View {
        let rows = focus.compactMap { f in TimingFormat.gapAt(f.g, lap: lap).map { (f, $0) } }
            .sorted { ($0.0.g.gap(at: lap) ?? .max) < ($1.0.g.gap(at: lap) ?? .max) }
        return VStack(alignment: .leading, spacing: 3) {
            Text("Lap \(String(lap))").font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink)
            ForEach(rows, id: \.0.g.carNumber) { f, text in
                HStack(spacing: 8) {
                    Circle().fill(GapSeries.colors[f.slot]).frame(width: 8, height: 8)
                    Text("#\(f.g.carNumber)").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.ink)
                    Spacer(minLength: 8)
                    Text(text).font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.ink)
                    if f.g.pitLaps.contains(lap) {
                        Text("Pit").font(PP.sans(PP.TextSize.xs, weight: 700)).foregroundStyle(PP.accentInk)
                    }
                }
            }
        }
        .padding(.horizontal, PP.Space.s3).padding(.vertical, PP.Space.s2)
        .frame(minWidth: 150)
        .background(PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.md))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md).strokeBorder(PP.borderStrong))
    }

    /// Rounds up to a tidy axis maximum (seconds): 1, 2, 5 × 10ⁿ.
    static func niceCeil(_ v: Double) -> Double {
        guard v > 0 else { return 1 }
        let p = pow(10, floor(log10(v)))
        for m in [1.0, 2, 5, 10] where v <= m * p { return m * p }
        return 10 * p
    }
}

private struct GapTable: View {
    let cls: GapClass
    let cars: [GapCar]
    let open: (AnalysisOpen) -> Void

    var body: some View {
        let first = cars.map(\.firstLap).min() ?? 1
        let last = cars.map(\.lastLap).max() ?? 0
        let columns = cars.map { g in
            GridColumn(id: g.carNumber, width: 110, align: .trailing) {
                Text("#\(g.carNumber)").font(PP.mono(PP.TextSize.xs, weight: 700)).foregroundStyle(PP.ink)
            }
        }
        let rows = stride(from: last, through: first, by: -1).map { lap in
            GridRowItem(id: String(lap),
                        ident: [GridCell.num(String(lap), bold: true)],
                        cells: cars.map { g in
                            AnyView(HStack(spacing: 6) {
                                Text(TimingFormat.gapAt(g, lap: lap) ?? "").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
                                if g.pitLaps.contains(lap) {
                                    Text("Pit").font(PP.sans(PP.TextSize.xs, weight: 700)).foregroundStyle(PP.accentInk)
                                }
                            })
                        },
                        lines: 1)
        }
        GridTable(identColumns: [.text("lap", "Lap", width: 56, align: .trailing)], dataColumns: columns,
                  sections: [GridSection(id: "gaps", rows: rows)],
                  cellPadV: 4, cellPadH: 8, headerHeight: 32, separatesIdentity: true, centersCells: true)
    }
}

// MARK: - Sectors

struct SectorsSection: View {
    @Environment(AppSession.self) private var session
    let chosen: LiveSessionSummary
    let open: (AnalysisOpen) -> Void
    @State private var feed = LiveFeed<SectorsResponse>()

    var body: some View {
        Group {
            if let value = feed.value {
                let classes = value.classes.filter { $0.bests.sectors > 0 }
                if classes.isEmpty {
                    EmptyState(message: "No sector times recorded for this session yet.")
                } else {
                    VStack(alignment: .leading, spacing: PP.Space.s3) {
                        if let error = feed.error { StaleLine(error: error) }
                        table(classes)
                        Text("Invalid laps (track limits) count for nothing. Fastest in class on the violet tint. Tap a car for its laps.")
                            .font(.caption).foregroundStyle(PP.textMuted)
                    }
                }
            } else if let error = feed.error {
                ErrorPanel(message: "Could not load sectors: \(error)")
            } else {
                SkeletonLines()
            }
        }
        .task(id: chosen.sessionDbId) {
            await feed.run(session.client, path: "/api/live/sectors?session=\(chosen.sessionDbId)", every: pollEvery(chosen))
        }
    }

    private func table(_ classes: [SectorClass]) -> some View {
        let sectors = classes.map(\.bests.sectors).max() ?? 0
        var data: [GridColumn] = [.text("team", "Team", width: 190)]
        data += (0..<sectors).map { .text("s\($0)", "S\($0 + 1)", width: 84, align: .trailing) }
        data += [.text("theo", "Theoretical", width: 104, align: .trailing),
                 .text("best", "Best lap", width: 100, align: .trailing),
                 GridColumn(id: "left", width: 84, align: .trailing, growthWeight: 1) {
                     Text("Left").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
                 }]
        let sections = classes.map { cls in
            let info = Dictionary(cls.cars.map { ($0.carNumber, $0) }, uniquingKeysWith: { a, _ in a })
            let classTheoretical: Int? = cls.bests.classBestSectorMs.contains(where: { $0 == nil }) ? nil
                : cls.bests.classBestSectorMs.compactMap { $0 }.reduce(0, +)
            let bestLap = cls.bests.cars.compactMap(\.bestLapMs).min()
            let bestTheoretical = cls.bests.cars.compactMap(\.theoreticalMs).min()
            var ideal: [AnyView] = [GridCell.text("Best in class", muted: true)]
            ideal += (0..<sectors).map { i in
                GridCell.num(TimingFormat.lapTime(cls.bests.classBestSectorMs.indices.contains(i) ? cls.bests.classBestSectorMs[i] : nil), muted: true)
            }
            ideal += [GridCell.num(TimingFormat.lapTime(classTheoretical), muted: true), GridCell.num(TimingFormat.lapTime(bestLap), muted: true), GridCell.empty()]
            let idealRow = GridRowItem(id: "ideal", ident: [GridCell.empty()], cells: ideal, lines: 1)

            let rows = cls.bests.cars.map { c -> GridRowItem in
                let car = info[c.carNumber] ?? AnalysisCar(carNumber: c.carNumber, teamName: nil, className: cls.className)
                var cells: [AnyView] = [GridCell.text(car.teamName ?? "—", muted: true)]
                cells += (0..<sectors).map { i in
                    let ms = c.bestSectorMs.indices.contains(i) ? c.bestSectorMs[i] : nil
                    let classBest = cls.bests.classBestSectorMs.indices.contains(i) ? cls.bests.classBestSectorMs[i] : nil
                    let lap = c.bestSectorLap.indices.contains(i) ? c.bestSectorLap[i] : nil
                    return fastest(TimingFormat.lapTime(ms), marked: ms != nil && ms == classBest,
                                   note: lap.map { "lap \(String($0))" })
                }
                let left = c.bestLapMs.flatMap { b in c.theoreticalMs.map { b - $0 } }
                cells += [GridCell.num(TimingFormat.lapTime(c.theoreticalMs), bold: c.theoreticalMs != nil && c.theoreticalMs == bestTheoretical),
                          fastest(TimingFormat.lapTime(c.bestLapMs), marked: c.bestLapMs != nil && c.bestLapMs == bestLap,
                                  note: c.bestLap.map { "lap \(String($0))" }),
                          GridCell.num(left.map { $0 > 0 ? "+" + TimingFormat.lapTime($0) : "" } ?? "", muted: true)]
                return GridRowItem(id: c.carNumber, ident: [GridCell.car(c.carNumber)], cells: cells, lines: 1,
                                   onTap: { open(AnalysisOpen(car: car, color: cls.color)) },
                                   tapLabel: "Car \(c.carNumber)\(car.teamName.map { ", \($0)" } ?? ""). Opens laps and stints.")
            }
            return GridSection(id: cls.className, band: (label: cls.className, color: cls.color ?? ""), rows: [idealRow] + rows)
        }
        return GridTable(identColumns: [.text("car", "#", width: 56, align: .trailing)], dataColumns: data, sections: sections,
                         lineHeight: 20, cellPadV: 5, cellPadH: 8, headerHeight: 34, separatesIdentity: true, centersCells: true)
    }
}

/// A time on the violet "fastest" tint when marked — the tower's class-best vocabulary.
func fastest(_ text: String, marked: Bool, note: String? = nil) -> AnyView {
    AnyView(Text(text)
        .font(PP.mono(PP.TextSize.sm, weight: marked ? 700 : 400))
        .foregroundStyle(marked ? PP.ink : PP.text)
        .padding(.horizontal, marked ? 4 : 0)
        .background(marked ? ResultTint.top5 : .clear, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
        .accessibilityLabel([text, marked ? "fastest in class" : nil, note].compactMap { $0 }.joined(separator: ", ")))
}

// MARK: - Pits

struct PitsSection: View {
    @Environment(AppSession.self) private var session
    let chosen: LiveSessionSummary
    let open: (AnalysisOpen) -> Void
    @State private var feed = LiveFeed<PitsResponse>()
    @State private var expanded: Set<String> = []

    var body: some View {
        Group {
            if let value = feed.value {
                let classes = value.classes.filter { $0.pits.contains { !$0.stops.isEmpty } }
                if classes.isEmpty {
                    EmptyState(message: "No pit stops recorded for this session yet.")
                } else {
                    VStack(alignment: .leading, spacing: PP.Space.s3) {
                        if let error = feed.error { StaleLine(error: error) }
                        table(value, classes)
                        Text("Pit-lane time is Al Kamel's pit stint, entry to exit. Penalty and safety-car stops are marked. Tap a car for its laps.")
                            .font(.caption).foregroundStyle(PP.textMuted)
                    }
                }
            } else if let error = feed.error {
                ErrorPanel(message: "Could not load pit stops: \(error)")
            } else {
                SkeletonLines()
            }
        }
        .task(id: chosen.sessionDbId) {
            await feed.run(session.client, path: "/api/live/pits?session=\(chosen.sessionDbId)", every: pollEvery(chosen))
        }
    }

    private func table(_ value: PitsResponse, _ classes: [PitClass]) -> some View {
        let data: [GridColumn] = [
            .text("team", "Team", width: 190),
            .text("stops", "Stops", width: 60, align: .trailing),
            .text("lane", "Pit lane", width: 84, align: .trailing),
            .text("avg", "Average", width: 80, align: .trailing),
            .text("last", "Last stop", width: 170, align: .trailing),
            .text("change", "Driver change", width: 190),
            .text("since", "Since", width: 64, align: .trailing),
            GridColumn(id: "all", width: 90, growthWeight: 1) { Text("").accessibilityHidden(true) },
        ]
        let sections = classes.map { cls in
            let info = Dictionary(cls.cars.map { ($0.carNumber, $0) }, uniquingKeysWith: { a, _ in a })
            let rows = cls.pits.flatMap { p -> [GridRowItem] in
                let car = info[p.carNumber] ?? AnalysisCar(carNumber: p.carNumber, teamName: nil, className: cls.className)
                let isOpen = expanded.contains(p.carNumber)
                let since: AnyView = p.inPit ? pitMark() : GridCell.num(p.lapsSinceStop.map { "\($0) L" } ?? "")
                let head = GridRowItem(
                    id: p.carNumber,
                    ident: [GridCell.car(p.carNumber)],
                    cells: [GridCell.text(car.teamName ?? "—", muted: true),
                            GridCell.num(String(p.stops.count)),
                            GridCell.num(p.stops.isEmpty ? "" : TimingFormat.duration(p.totalMs)),
                            GridCell.num(p.averageMs.map { TimingFormat.duration($0) } ?? ""),
                            p.stops.last.map { stopCell($0) } ?? GridCell.empty(),
                            p.stops.last.map { change($0, car: p.carNumber, value) } ?? GridCell.empty(),
                            since,
                            p.stops.count > 1 ? AnyView(Button(isOpen ? "Hide stops" : "All \(p.stops.count)") {
                                if isOpen { expanded.remove(p.carNumber) } else { expanded.insert(p.carNumber) }
                            }.font(PP.sans(PP.TextSize.xs, weight: 600)).buttonStyle(.bordered).controlSize(.mini)) : GridCell.empty()],
                    lines: 1,
                    onTap: { open(AnalysisOpen(car: car, color: cls.color)) },
                    tapLabel: "Car \(p.carNumber), \(p.stops.count) stops. Opens laps and stints.")
                guard isOpen else { return [head] }
                let stops = p.stops.reversed().map { s in
                    GridRowItem(id: "\(p.carNumber)-\(s.number)",
                                ident: [GridCell.empty()],
                                cells: [GridCell.text("Stop \(s.number)", muted: true), GridCell.empty(), GridCell.empty(), GridCell.empty(),
                                        stopCell(s), change(s, car: p.carNumber, value), GridCell.empty(), GridCell.empty()],
                                lines: 1)
                }
                return [head] + stops
            }
            return GridSection(id: cls.className, band: (label: cls.className, color: cls.color ?? ""), rows: rows)
        }
        return GridTable(identColumns: [.text("car", "#", width: 56, align: .trailing)], dataColumns: data, sections: sections,
                         lineHeight: 22, cellPadV: 5, cellPadH: 8, headerHeight: 34, separatesIdentity: true, centersCells: true)
    }

    private func stopCell(_ s: PitStop) -> AnyView {
        AnyView(HStack(spacing: PP.Space.s2) {
            if let lap = s.lap { Text("L\(String(lap))").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted) }
            Text(s.durationMs.map { TimingFormat.duration($0) } ?? "In pit").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
            if let type = s.pitType {
                Text(type.lowercased().replacingOccurrences(of: "_", with: " "))
                    .font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.accentInk)
            }
        })
    }

    private func change(_ s: PitStop, car: String, _ value: PitsResponse) -> AnyView {
        if s.driverChange {
            let text = "\(value.driverName(car: car, order: s.driverIn) ?? "—") → \(value.driverName(car: car, order: s.driverOut) ?? "—")"
            return AnyView(Text(text).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.ink).lineLimit(1))
        }
        return s.driverOut == nil ? GridCell.empty() : GridCell.text("No change", muted: true)
    }

    private func pitMark() -> AnyView {
        AnyView(Text("Pit").font(PP.sans(PP.TextSize.xs, weight: 700)).foregroundStyle(PP.ink)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(PP.accentTint, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.xs).strokeBorder(PP.accent.opacity(0.45))))
    }
}

/// Every car's energy use side by side: green use over the last 10 and 5 green
/// laps, what that rests on, caution use, and green laps left on the energy it
/// has now. Tapping a car shows each lap and whether it counted.
struct EnergySection: View {
    @Environment(AppSession.self) private var session
    let chosen: LiveSessionSummary
    let open: (AnalysisOpen) -> Void
    @State private var feed = LiveFeed<EnergyResponse>()
    // The finish scenario's inputs, as typed; blank is "not given".
    @State private var reserve = ""
    @State private var fromStop = false
    @State private var cautionUse = ""
    @State private var cautionLapSec = ""

    private var path: String {
        TimingFormat.energyPath(session: chosen.sessionDbId, reserve: reserve, fromStop: fromStop,
                                cautionUse: cautionUse, cautionLapSec: cautionLapSec)
    }

    var body: some View {
        Group {
            if let value = feed.value {
                if value.classes.isEmpty {
                    EmptyState(message: "No energy telemetry recorded for this session. Only IMSA WeatherTech cars send it.")
                } else {
                    VStack(alignment: .leading, spacing: PP.Space.s3) {
                        if let error = feed.error { StaleLine(error: error) }
                        if let finish = value.finish { controls(finish) }
                        table(value)
                        if let finish = value.finish {
                            Text(finish.type == "TIME"
                                 ? "The flag falls on the overall leader's first crossing after the clock runs out, projected at its green pace; each car finishes on its next crossing. A caution lap uses less energy and more of the clock, so fewer laps fit before the flag. Not counted: the time a stop takes, so a stop now is given the laps as if it had not — the cautious side."
                                 : "The leader's laps left, less the laps a car is down. A caution lap replaces a green one and uses less. Not counted: the time a stop takes — the cautious side.")
                                .font(.caption).foregroundStyle(PP.textMuted)
                        }
                        Text("A lap counts when it is run under one flag — green, or full-course yellow for caution — clear of the pits, with IMSA's energy reading seen at the line before and after it. Green use averages the last 10 green laps of the session across pit stops; under 10 it is underlined, under 3 there is none. A car with fewer than 3 caution laps of its own uses its class's. Tap a car for every lap and why it counted.")
                            .font(.caption).foregroundStyle(PP.textMuted)
                    }
                }
            } else if let error = feed.error {
                ErrorPanel(message: "Could not load energy: \(error)")
            } else {
                SkeletonLines()
            }
        }
        .task(id: path) {
            // Typing settles before it asks: a new keystroke cancels this wait.
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            await feed.run(session.client, path: path, every: pollEvery(chosen))
        }
    }

    private func controls(_ finish: EnergyFinishInfo) -> some View {
        let refill = String(format: "%.1f%%", finish.refillPct)
            + (finish.refillSource == "ASSUMED" ? ", assumed: no refill seen yet" : ", where refills have landed")
        return VStack(alignment: .leading, spacing: PP.Space.s2) {
            Group {
                if finish.type == "TIME" {
                    Text("Clock: **\(TimingFormat.duration(finish.clockLeftMs ?? 0))** left · leader #\(finish.leader) takes the flag in about **\(TimingFormat.duration(finish.flagInMs ?? 0))**")
                } else {
                    Text("Leader #\(finish.leader): **\(finish.leaderLapsLeft ?? 0)** laps left")
                }
            }
            .font(.subheadline)
            if finish.inputs.fromStop {
                Text("Every car from a stop now, at \(refill)").font(.caption).foregroundStyle(PP.textMuted)
            }
            HStack(spacing: PP.Space.s4) {
                HStack(spacing: 4) {
                    Text("Reserve")
                    TextField("0", text: $reserve).keyboardType(.decimalPad).frame(width: 56)
                        .textFieldStyle(.roundedBorder).multilineTextAlignment(.trailing)
                    Text("%")
                }
                Picker("Start from", selection: $fromStop) {
                    Text("Energy now").tag(false)
                    Text("A stop now").tag(true)
                }
                .pickerStyle(.segmented).frame(width: 240)
            }
            .font(.subheadline)
            // A row of its own: beside the reserve and start it overflows a portrait iPad.
            HStack(spacing: PP.Space.s4) {
                Text("Caution, where a car and its class have none:").foregroundStyle(PP.textMuted)
                HStack(spacing: 4) {
                    TextField("—", text: $cautionUse).keyboardType(.decimalPad).frame(width: 56)
                        .textFieldStyle(.roundedBorder).multilineTextAlignment(.trailing)
                    Text("% a lap")
                }
                HStack(spacing: 4) {
                    TextField("—", text: $cautionLapSec).keyboardType(.numberPad).frame(width: 56)
                        .textFieldStyle(.roundedBorder).multilineTextAlignment(.trailing)
                    Text("s a lap")
                }
            }
            .font(.subheadline)
        }
    }

    private func perLap(_ a: EnergyAverage?) -> String { a.map { String(format: "%.2f%%", $0.perLapPct) } ?? "" }

    private func table(_ value: EnergyResponse) -> some View {
        var data: [GridColumn] = [.text("team", "Team", width: 170)]
        if value.live { data.append(.text("now", "Energy", width: 70, align: .trailing)) }
        data += [
            .text("green", "Green / lap", width: 96, align: .trailing),
            .text("short", "Last 5", width: 74, align: .trailing),
            .text("based", "Based on", width: 150),
        ]
        if value.live { data.append(.text("left", "Green laps left", width: 120, align: .trailing)) }
        data += [
            .text("caution", "Caution / lap", width: 120, align: .trailing),
            .text("since", "Since green", width: 96, align: .trailing),
            GridColumn(id: "out", width: 220, growthWeight: 1) {
                Text("Left out").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
            },
        ]
        if value.finish != nil {
            data += [
                .text("toflag", "To flag", width: 72, align: .trailing),
                .text("margin", "Green margin", width: 110, align: .trailing),
                .text("make", "To make it", width: 230),
            ]
        }
        let sections = value.classes.map { cls in
            let info = Dictionary(cls.cars.map { ($0.carNumber, $0) }, uniquingKeysWith: { a, _ in a })
            let rows = cls.energy.map { e -> GridRowItem in
                let car = info[e.carNumber] ?? AnalysisCar(carNumber: e.carNumber, teamName: nil, className: cls.className)
                let thin = (e.green?.laps ?? TimingFormat.energyFullLaps) < TimingFormat.energyFullLaps
                var cells: [AnyView] = [GridCell.text(car.teamName ?? "—", muted: true)]
                if value.live { cells.append(GridCell.num(e.energyPct.map { "\(Int($0.rounded()))%" } ?? "")) }
                cells.append(AnyView(Text(perLap(e.green)).font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
                    .underline(thin, pattern: .dot)))
                cells.append(GridCell.num(perLap(e.greenShort), muted: true))
                if let g = e.green {
                    cells.append(GridCell.text("\(g.laps) \(g.laps == 1 ? "lap" : "laps")" + (g.driverChange ? " · 2+ drivers" : "")))
                } else {
                    cells.append(GridCell.text("Needs 3 green laps", muted: true))
                }
                if value.live { cells.append(GridCell.num(e.greenLapsLeft.map { "\(Int($0)) L" } ?? "")) }
                cells.append(GridCell.num(perLap(e.caution) + (e.cautionSource == "CLASS" ? " class" : "")))
                cells.append(GridCell.num(e.lapsSinceGreen.map { "\($0) L" } ?? ""))
                cells.append(GridCell.text(TimingFormat.energyLeftOut(e.laps), muted: true))
                if value.finish != nil {
                    if let f = e.finish {
                        cells.append(GridCell.num(String(format: "%.1f", f.result.lapsToFlag)))
                        cells.append(AnyView(Text(TimingFormat.marginLabel(f.result.marginPct)).font(PP.mono(PP.TextSize.sm))
                            .foregroundStyle(f.result.marginPct >= 0 ? PP.success : PP.error)))
                        let source: String? = switch f.cautionSource {
                        case "CAR": "own caution use"
                        case "CLASS": "class caution use"
                        case "MANUAL": "your caution figure"
                        default: nil
                        }
                        let showSource = (f.result.cautionLaps ?? 0) > 0 && source != nil
                        cells.append(GridCell.text(TimingFormat.cautionLapsLabel(f) + (showSource ? " · \(source!)" : "")))
                    } else {
                        cells += [GridCell.empty(), GridCell.empty(), GridCell.text(e.green == nil ? "Needs 3 green laps" : "", muted: true)]
                    }
                }
                return GridRowItem(id: e.carNumber, ident: [GridCell.car(e.carNumber)], cells: cells, lines: 1,
                                   onTap: { open(AnalysisOpen(car: car, color: cls.color)) },
                                   tapLabel: "Car \(e.carNumber), green use \(perLap(e.green)) a lap. Opens laps and stints.")
            }
            return GridSection(id: cls.className, band: (label: cls.className, color: cls.color ?? ""), rows: rows,
                               bandDetail: cls.caution.map { "Caution \(perLap($0)) a lap over \($0.laps) laps" })
        }
        // Wide enough that the pinned band label reads "GTDPRO", not "GT…".
        return GridTable(identColumns: [.text("car", "#", width: 84, align: .trailing)], dataColumns: data, sections: sections,
                         lineHeight: 22, cellPadV: 5, cellPadH: 8, headerHeight: 34, separatesIdentity: true, centersCells: true)
    }
}

private struct StaleLine: View {
    let error: String
    var body: some View {
        Text("Not updating: \(error)").font(.caption).foregroundStyle(PP.error).lineLimit(1)
    }
}
