import SwiftUI

/// Native season navigation with a shared model and independent tab content.
struct SeasonView: View {
    @Environment(AppSession.self) private var session
    @State private var model: SeasonModel
    @State private var page: Page = .overview

    enum Page: String, CaseIterable, Identifiable {
        case overview, races, standings, stats, more
        var id: String { rawValue }
        var label: String { rawValue.prefix(1).uppercased() + rawValue.dropFirst() }
    }

    init(seasonId: Int) {
        _model = State(initialValue: SeasonModel(seasonId: seasonId))
    }

    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        TabView(selection: $page) {
            Tab("Overview", systemImage: "house", value: Page.overview) { section(.overview) }
            Tab("Races", systemImage: "flag.checkered", value: Page.races) { section(.races) }
            Tab("Standings", systemImage: "trophy", value: Page.standings) { section(.standings) }
            Tab("Stats", systemImage: "chart.bar", value: Page.stats) { section(.stats) }
            Tab("More", systemImage: "ellipsis", value: Page.more) { section(.more) }
        }
        // Keep the agreed bottom navigation on iPad; restore the real size
        // class inside each tab so its tables and adaptive layouts stay wide.
        .environment(\.horizontalSizeClass, .compact)
        .tint(PP.accentInk)
        .navigationTitle(model.hub.value?.seriesName ?? "Season")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                if let hub = model.hub.value { seasonMenu(hub) }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if model.hub.value != nil { StatusDownloadButton(target: .season(model.seasonId), noun: "season") }
            }
        }
        .task(id: model.seasonId) { await model.load(session) }
        .environment(model)
    }

    private func section(_ selected: Page) -> some View {
        ScrollView {
            PageContainer {
                VStack(alignment: .leading, spacing: PP.Space.s2) {
                    if model.hub.value != nil {
                        if selected != .more { classPicker }
                        content(selected)
                    } else if let error = model.hub.error {
                        ErrorPanel(message: error)
                    } else {
                        SkeletonLines()
                    }
                }
            }
        }
        .background(PP.bg)
        .refreshable { await model.load(session) }
        .environment(\.horizontalSizeClass, sizeClass)
    }

    @ViewBuilder private var classPicker: some View {
        @Bindable var model = model
        if model.classes.count > 1 {
            ScrollView(.horizontal) {
                ColoredClassFilter(classes: model.classes, selection: $model.classFilter)
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, PP.Space.s1)
        }
    }

    private func seasonMenu(_ hub: SeasonHub) -> some View {
        let seasons = (model.seasons.value ?? []).filter { $0.seriesName == hub.seriesName }
        let byYear = Dictionary(grouping: seasons, by: \.year)
        let years = byYear.keys.sorted(by: >)
        let stages = (byYear[hub.year] ?? []).sorted {
            if $0.isQualifier != $1.isQualifier { return !$0.isQualifier }
            return ($0.label ?? "") < ($1.label ?? "")
        }
        return Menu {
            Picker("Year", selection: Binding(
                get: { hub.year },
                set: { year in
                    guard year != hub.year, let options = byYear[year],
                          let target = options.first(where: { !$0.isQualifier }) ?? options.first else { return }
                    switchSeason(target.id)
                }
            )) {
                ForEach(years, id: \.self) { Text(String($0)).tag($0) }
            }
            if stages.count > 1 {
                Picker("Stage", selection: Binding(
                    get: { hub.id },
                    set: { if $0 != hub.id { switchSeason($0) } }
                )) {
                    ForEach(stages) { stage in
                        Text(stage.isQualifier ? (stage.label ?? "Qualifying") : "Main season").tag(stage.id)
                    }
                }
            }
        } label: {
            VStack(spacing: 2) {
                Text(hub.seriesName).font(.headline)
                HStack(spacing: 4) {
                    Text(String(hub.year) + (hub.isQualifier ? " · " + (hub.label ?? "Qualifying") : ""))
                    Image(systemName: "chevron.down").font(.caption2)
                }
                .font(.subheadline).foregroundStyle(.secondary)
            }
        }
        .foregroundStyle(PP.ink)
        .accessibilityLabel("\(hub.seriesName), \(hub.year), choose season")
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

    @ViewBuilder private func content(_ selected: Page) -> some View {
        switch selected {
        case .overview: HubView()
        case .races: RacesView().id(model.seasonId)
        case .standings: ChampionshipGridView(mode: .points)
        case .stats: StatsView()
        case .more:
            VStack(spacing: 0) {
                NavigationLink {
                    secondaryPage("Entries") { EntriesView() }
                } label: { moreRow("Entries", symbol: "person.3") }
                Divider()
                NavigationLink {
                    secondaryPage("Photos") { PhotosView() }
                } label: { moreRow("Photos", symbol: "photo.on.rectangle") }
            }
        }
    }

    private func moreRow(_ title: String, symbol: String) -> some View {
        HStack {
            Label(title, systemImage: symbol)
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(.secondary)
        }
        .font(.body)
        .padding(.vertical, PP.Space.s4)
        .contentShape(Rectangle())
    }

    private func secondaryPage<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        ScrollView {
            PageContainer {
                VStack(alignment: .leading, spacing: PP.Space.s4) {
                    classPicker
                    content()
                }
            }
        }
        .background(PP.bg)
        .navigationTitle(title)
        .environment(model)
    }
}
