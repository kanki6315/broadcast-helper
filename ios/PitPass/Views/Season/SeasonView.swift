import SwiftUI

/// SeasonLayout: series title + year strip, the class chips, the selected
/// year's qualifying stages, and the sub-page segmented nav. The year strip
/// swaps the model in place so the sub-page and filters survive the switch.
struct SeasonView: View {
    @Environment(AppSession.self) private var session
    @State private var model: SeasonModel
    @State private var page: Page? = .overview

    enum Page: String, CaseIterable, Identifiable {
        case overview, schedule, standings, stats, results, entries, photos
        var id: String { rawValue }
        var label: String { rawValue.prefix(1).uppercased() + rawValue.dropFirst() }
    }

    init(seasonId: Int) {
        _model = State(initialValue: SeasonModel(seasonId: seasonId))
    }

    var body: some View {
        @Bindable var model = model
        ScrollView {
            PageContainer {
                VStack(alignment: .leading, spacing: 0) {
                    if let hub = model.hub.value {
                        header(hub)
                        content(hub)
                    } else if let error = model.hub.error {
                        ErrorPanel(message: error)
                    } else {
                        SkeletonLines()
                    }
                }
            }
        }
        .background(PP.bg.ignoresSafeArea())
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(PP.bg, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { ConnectivityPill() }
        }
        .task(id: model.seasonId) { await model.load(session) }
        .refreshable { await model.load(session) }
        .environment(model)
    }

    private var title: String {
        guard let hub = model.hub.value else { return "" }
        let stage = hub.isQualifier ? " · \(hub.label ?? "Qualifying")" : ""
        return "\(String(hub.year))\(stage) · \(hub.seriesName)"
    }

    // MARK: header

    private struct SeasonRef: Hashable {
        let id: Int
        let year: Int
        let isQualifier: Bool
        let label: String?
    }

    private func header(_ hub: SeasonHub) -> some View {
        @Bindable var model = model
        let known: [SeasonRef] = {
            let same = (model.seasons.value ?? []).filter { $0.seriesName == hub.seriesName }
            if same.isEmpty { return [SeasonRef(id: hub.id, year: hub.year, isQualifier: hub.isQualifier, label: hub.label)] }
            return same.map { SeasonRef(id: $0.id, year: $0.year, isQualifier: $0.isQualifier, label: $0.label) }
        }()
        let byYear = Dictionary(grouping: known, by: \.year)
        let years = byYear.keys.sorted(by: >)
        let stages = (byYear[hub.year] ?? []).filter(\.isQualifier).sorted { ($0.label ?? "") < ($1.label ?? "") }
        let main = (byYear[hub.year] ?? []).first { !$0.isQualifier }
        let yearSelection = Binding<Int?>(
            get: { hub.year },
            set: { year in
                guard let year, year != hub.year, let list = byYear[year] else { return }
                let target = list.first { !$0.isQualifier } ?? list.sorted { ($0.label ?? "") < ($1.label ?? "") }[0]
                switchSeason(target.id)
            })
        let stageSelection = Binding<Int?>(get: { hub.id }, set: { id in if let id, id != hub.id { switchSeason(id) } })

        return VStack(alignment: .leading, spacing: PP.Space.s3) {
            // `.season-toolbar` wraps: title + years first, the chips on their
            // own line when the row can't hold both.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: PP.Space.s4) {
                    titleRow(hub, years: years, selection: yearSelection)
                    Spacer(minLength: PP.Space.s4)
                    if model.classes.count > 1 { ClassChipRow(classes: model.classes, selection: $model.classFilter).fixedSize() }
                }
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    titleRow(hub, years: years, selection: yearSelection)
                    if model.classes.count > 1 { ClassChipRow(classes: model.classes, selection: $model.classFilter) }
                }
            }
            if !stages.isEmpty {
                var options: [Segmented<Int>.Option] = []
                let _ = { if let main { options.append(.init(id: main.id, label: "Season")) } }()
                let _ = { options.append(contentsOf: stages.map { .init(id: $0.id, label: $0.label ?? "Qualifying") }) }()
                Segmented(options: options, selection: stageSelection, size: .stage)
            }
            Segmented(options: Page.allCases.map { .init(id: $0, label: $0.label) }, selection: $page)
        }
        .padding(.bottom, PP.Space.s4)
    }

    private func titleRow(_ hub: SeasonHub, years: [Int], selection: Binding<Int?>) -> some View {
        HStack(spacing: PP.Space.s3) {
            Text(hub.seriesName).ppHeadline().lineLimit(1).fixedSize()
            Segmented(options: years.map { .init(id: $0, label: String($0)) }, selection: selection, size: .year)
        }
    }

    private func switchSeason(_ id: Int) {
        let next = SeasonModel(seasonId: id)
        next.classFilter = model.classFilter
        next.family = model.family
        next.kind = model.kind
        next.pointsView = model.pointsView
        next.showTeams = model.showTeams
        model = next
    }

    // MARK: content

    @ViewBuilder private func content(_ hub: SeasonHub) -> some View {
        switch page ?? .overview {
        case .overview: HubView()
        case .schedule: ScheduleView()
        case .standings: ChampionshipGridView(mode: .points)
        case .stats: StatsView()
        case .results: ResultsView()
        case .entries: EntriesView()
        case .photos: PhotosView()
        }
    }
}
