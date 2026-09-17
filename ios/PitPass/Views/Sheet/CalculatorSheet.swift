import SwiftUI

/// Event-scoped simulation. Own resources and local state keep actual standings isolated.
struct CalculatorSheet: View {
    @Environment(AppSession.self) private var session
    let eventId: Int
    @State private var hub: Resource<SeasonHub>

    init(seasonId: Int, eventId: Int) {
        self.eventId = eventId
        _hub = State(initialValue: Resource<SeasonHub>("/api/seasons/\(seasonId)"))
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PP.Space.s3) {
                Text("Championship calculator").ppTitle()
                Text("If the race finished like this. Compare selected teams using the latest imported points.")
                    .font(.subheadline).foregroundStyle(PP.textMuted)
                if let value = hub.value {
                    CalculatorClassWorkspace(championships: value.championships.filter(ChampionshipCalculator.supported), eventId: eventId)
                } else if let error = hub.error {
                    ErrorPanel(message: error)
                    Button("Retry") { Task { await hub.load(session.loader) } }
                } else { ProgressView("Loading championships…") }
            }
            .padding(PP.Space.s5)
        }
        .background(PP.bg)
        .task { await hub.load(session.loader, connectivity: session.connectivity, freshness: session.freshness) }
    }
}

/// Class panels keep drafts above their visibility boundary, so hiding a class
/// cannot discard work or alter another class's simulated positions.
struct CalculatorClassWorkspace: View {
    let championships: [ChampionshipSummary]
    let eventId: Int
    var initialRecaps: [Int: Recap] = [:]
    @State private var cup = false
    @State private var enabled = Set<String>()
    @State private var drafts: [Int: [String: ChampionshipCalculator.Entry]] = [:]
    @State private var width: CGFloat = 0

    init(championships: [ChampionshipSummary], eventId: Int, initialRecaps: [Int: Recap] = [:],
         initiallyShown: Set<String> = [], initialScenarios: [Int: [String: ChampionshipCalculator.Entry]] = [:]) {
        self.championships = championships
        self.eventId = eventId
        self.initialRecaps = initialRecaps
        _enabled = State(initialValue: initiallyShown)
        _drafts = State(initialValue: initialScenarios)
    }
    private var available: [ChampionshipSummary] {
        let family = championships.contains { $0.isCup == cup } ? cup : !cup
        var seen = Set<String>()
        return Array(championships.filter { $0.isCup == family && seen.insert($0.className ?? "").inserted }.prefix(4))
    }
    private var shown: Set<String> {
        let valid = enabled.intersection(available.map { $0.className ?? "" })
        return valid.isEmpty ? Set(available.prefix(1).map { $0.className ?? "" }) : valid
    }
    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if available.isEmpty {
                EmptyState(message: "Import IMSA WeatherTech team standings to calculate a scenario.")
            } else {
                Picker("Championship", selection: $cup) {
                    if championships.contains(where: { !$0.isCup }) { Text("WeatherTech Championship").tag(false) }
                    if championships.contains(where: { $0.isCup }) { Text("Michelin Endurance Cup").tag(true) }
                }.pickerStyle(.menu)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: PP.Space.s2) { classButtons }
                    VStack(alignment: .leading, spacing: PP.Space.s1) { classButtons }
                }
                Text("\(shown.count) \(shown.count == 1 ? "class" : "classes") shown. Hiding a class keeps its scenario while you stay on this page.")
                    .font(.caption).foregroundStyle(PP.textMuted)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), alignment: .topLeading), count: width >= 1100 && shown.count > 1 ? 2 : 1), alignment: .leading, spacing: PP.Space.s5) {
                    ForEach(available.filter { shown.contains($0.className ?? "") }) { champ in
                        VStack(alignment: .leading, spacing: PP.Space.s3) {
                            Divider()
                            Text(champ.className ?? champ.title).font(.headline)
                            CalculatorChampionshipView(championship: champ, eventId: eventId, scenario: Binding(
                                get: { drafts[champ.id] ?? [:] },
                                set: { drafts[champ.id] = $0 }
                            ), initialRecap: initialRecaps[champ.id])
                            .id(champ.id)
                        }
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .accessibilityElement(children: .contain)
                        .accessibilityLabel("\(champ.className ?? champ.title) calculator")
                    }
                }
            }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .onChange(of: cup) { _, _ in drafts = [:] }
        .onChange(of: eventId) { _, _ in drafts = [:] }
        .onAppear { if !championships.contains(where: { !$0.isCup }) { cup = true } }
        .tint(PP.accentInk)
    }
    @ViewBuilder private var classButtons: some View {
        ForEach(available) { champ in
            let name = champ.className ?? ""
            let active = shown.contains(name)
            Button {
                var next = shown
                if active { next.remove(name) } else { next.insert(name) }
                enabled = next
            } label: {
                Label(name.isEmpty ? champ.title : name, systemImage: active ? "checkmark.circle.fill" : "circle")
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.bordered)
            .tint(active ? PP.accentInk : PP.textMuted)
            .disabled(active && shown.count == 1)
            .accessibilityLabel("Show \(name)")
            .accessibilityValue(active ? "Shown" : "Hidden")
        }
    }
}

private struct CalculatorChampionshipView: View {
    @Environment(AppSession.self) private var session
    let championship: ChampionshipSummary
    let eventId: Int
    @State private var resource: Resource<Recap>
    @State private var revision = 0
    @Binding var scenario: [String: ChampionshipCalculator.Entry]
    private let initialRecap: Recap?
    init(championship: ChampionshipSummary, eventId: Int, scenario: Binding<[String: ChampionshipCalculator.Entry]>, initialRecap: Recap? = nil) {
        self.championship = championship
        self.initialRecap = initialRecap
        self.eventId = eventId
        _scenario = scenario
        let resource = Resource<Recap>("/api/championships/\(championship.id)/calculator")
        if let initialRecap { resource.replace(initialRecap) }
        _resource = State(initialValue: resource)
    }
    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if let recap = resource.value {
                if resource.isStale { Text("Using cached imported standings.").foregroundStyle(PP.textMuted) }
                if resource.pendingUpdate != nil {
                    Button("Use updated standings and reset scenario") { resource.applyPendingUpdate(); scenario = [:]; revision += 1 }
                }
                if let issue = ChampionshipCalculator.baselineIssue(recap, eventId: eventId) {
                    ErrorPanel(message: issue)
                } else {
                    CalculatorEditor(recap: recap, eventId: eventId, scenario: $scenario).id(revision)
                }
            } else if let error = resource.error {
                ErrorPanel(message: error)
                Button("Retry") { Task { await resource.load(session.loader) } }
            } else { ProgressView("Loading imported standings…") }
        }
        .task { if initialRecap == nil { await resource.load(session.loader, connectivity: session.connectivity, freshness: session.freshness) } }
    }
}

struct CalculatorEditor: View {
    let recap: Recap
    let eventId: Int
    @Binding var scenario: [String: ChampionshipCalculator.Entry]
    init(recap: Recap, eventId: Int, scenario: Binding<[String: ChampionshipCalculator.Entry]>) {
        self.recap = recap
        self.eventId = eventId
        _scenario = scenario
    }
    private var phases: [String] {
        recap.championship.isCup ? (recap.rounds.first { $0.eventId == eventId }?.sessions.map(\.name) ?? []) : ["Qualifying", "Race"]
    }
    private var rows: [ChampionshipCalculator.Projection] {
        ChampionshipCalculator.project(recap, scenario: scenario, cup: recap.championship.isCup, phaseCount: phases.count)
    }
    private var remaining: [RecapRow] { recap.rows.filter { scenario[$0.competitorKey] == nil } }
    private func number(_ value: Double) -> String { value.formatted(.number.precision(.fractionLength(0...2))) }
    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            Text(recap.championship.isCup ? "Baseline: latest imported totals. Checkpoints for this unscored event are simulated below." : "Baseline: latest imported totals. Qualifying for this unscored weekend is added below.")
                .font(.subheadline).foregroundStyle(PP.textMuted)
            HStack {
                Menu {
                    ForEach(remaining, id: \.competitorKey) { row in
                        Button(ChampionshipCalculator.name(row)) {
                            scenario[row.competitorKey] = .init(positions: phases.map { _ in 0 })
                        }
                    }
                } label: { Label("Add team", systemImage: "plus") }
                .buttonStyle(.bordered).disabled(remaining.isEmpty)
                Spacer()
                Button("Reset scenario") { scenario = [:] }.disabled(scenario.isEmpty)
            }
            Text("Rank and gap compare selected teams only. Blank positions add zero. Choosing an occupied position swaps the teams. Scroll the table for position controls.")
                .font(.caption).foregroundStyle(PP.textMuted)
            if rows.isEmpty { EmptyState(message: "Select the teams you want to compare, then assign positions in class.") }
            else { table }
            DisclosureGroup("Points calculation and assumptions") {
                VStack(alignment: .leading, spacing: PP.Space.s2) {
                    Text("Projected = imported + simulated points + adjustment. Use a negative adjustment for a points penalty. Guest eligibility and official tie-breaks are not applied; equal totals remain tied.")
                    Text(recap.championship.isCup
                         ? "Each imported checkpoint: P1 = 5, P2 = 4, P3 = 3, P4 onward = 2. Every checkpoint here is simulated."
                         : "Qualifying: 35, 32, 30, 28, 26, then 25 down to 1; P30 onward = 1. Race points are ten times qualifying points. Enter qualifying positions manually, including after qualifying has happened.")
                    ForEach(rows) { row in
                        Text("\(ChampionshipCalculator.name(row.row)): \(number(row.row.totalPoints)) + \(row.awards.map(number).joined(separator: " + ")) + (\(number(scenario[row.id]?.adjustment ?? 0))) = \(number(row.total))")
                    }
                    Link("2026 IMSA scoring regulations", destination: ChampionshipCalculator.sourceURL)
                }.font(.caption).padding(.top, PP.Space.s2)
            }
            Text("Temporary scenario on this screen. Actual standings and recap tables are unchanged.")
                .font(.caption).foregroundStyle(PP.textMuted)
        }.tint(PP.accentInk)
    }
    private var table: some View {
        let identity = [GridColumn.text("rank", "Rank*", width: 64), GridColumn.text("team", "Team", width: 200)]
        let data = [GridColumn.text("total", "Projected", width: 94, align: .trailing),
                    GridColumn.text("gap", "Gap*", width: 66, align: .trailing),
                    GridColumn.text("base", "Imported", width: 82, align: .trailing)]
            + phases.enumerated().map { GridColumn.text("phase\($0.offset)", $0.element, width: 108, align: .center) }
            + [GridColumn.text("adjust", "Adjustment", width: 108, align: .trailing),
               GridColumn.text("added", "Added", width: 76, align: .trailing),
               GridColumn.text("remove", "", width: 64)]
        let items = rows.map { row in
            GridRowItem(id: row.id, ident: [AnyView(Text("\(row.tied ? "=" : "")\(row.rank)")), AnyView(Text(ChampionshipCalculator.name(row.row)).font(.subheadline).lineLimit(2))],
                        cells: cells(row), lines: 2)
        }
        return GridTable(identColumns: identity, dataColumns: data, sections: [GridSection(id: "scenario", rows: items)], lineHeight: 30, cellPadV: 6)
    }
    private func cells(_ row: ChampionshipCalculator.Projection) -> [AnyView] {
        var result = [AnyView(Text(number(row.total)).bold().foregroundStyle(PP.accentInk)), AnyView(Text(number(row.gap))), AnyView(Text(number(row.row.totalPoints)))]
        for (i, phase) in phases.enumerated() {
            result.append(AnyView(VStack(spacing: 0) {
                Menu {
                    Picker(phase, selection: Binding(
                        get: { scenario[row.id]?.positions[i] ?? 0 },
                        set: { scenario = ChampionshipCalculator.assign(scenario, key: row.id, phase: i, position: $0) }
                    )) {
                        Text("—").tag(0)
                        ForEach(1...max(40, recap.rows.count), id: \.self) { Text("P\($0)").tag($0) }
                    }
                } label: {
                    HStack(spacing: 4) {
                        let position = scenario[row.id]?.positions[i] ?? 0
                        Text(position == 0 ? "—" : "P\(position)")
                        Image(systemName: "chevron.down").font(.caption2)
                    }
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .accessibilityLabel("\(phase) position for \(ChampionshipCalculator.name(row.row))")
                Text("+\(number(row.awards[i]))").font(.caption).foregroundStyle(PP.textMuted)
            }))
        }
        result.append(AnyView(TextField("Adjustment", value: Binding(
            get: { scenario[row.id]?.adjustment ?? 0 },
            set: { if $0.isFinite { scenario[row.id]?.adjustment = $0 } }
        ), format: .number).textFieldStyle(.roundedBorder).multilineTextAlignment(.trailing)
            .accessibilityLabel("Points adjustment for \(ChampionshipCalculator.name(row.row))")))
        result.append(AnyView(Text("\(row.added > 0 ? "+" : "")\(number(row.added))")))
        result.append(AnyView(Button { scenario.removeValue(forKey: row.id) } label: { Image(systemName: "minus.circle").frame(width: 44, height: 44).contentShape(Rectangle()) }
            .accessibilityLabel("Remove \(ChampionshipCalculator.name(row.row))")))
        return result
    }
}
