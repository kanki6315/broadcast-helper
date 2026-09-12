import Foundation
import Observation

/// One season's state: the hub, class styles, the class filter, the
/// championship selection, and a per-championship recap cache — the native
/// counterpart of SeasonLayout's outlet context plus ChampionshipGrid's caches.
/// Every document goes through `Resource`, so each paints from the store first.
@MainActor
@Observable
final class SeasonModel {
    let seasonId: Int
    let hub = Resource<SeasonHub>("")
    let seasons = Resource<[SeasonSummary]>("/api/seasons")
    private(set) var styles: Resource<ClassStylesResponse>?
    private(set) var reference: Resource<ReferenceTable>?
    private(set) var lineups: Resource<Lineups>?
    private var recaps: [Int: Resource<Recap>] = [:]

    var classFilter: String?
    var family: String?
    var kind: String?
    var pointsView: PointsView = .breakdown
    var showTeams = false

    enum PointsView: String, CaseIterable, Identifiable {
        case breakdown, total
        var id: String { rawValue }
        var label: String { self == .breakdown ? "Breakdown" : "Round total" }
    }

    init(seasonId: Int) {
        self.seasonId = seasonId
        hub.retarget("/api/seasons/\(seasonId)")
    }

    var classes: [ClassInfo] {
        guard let hub = hub.value else { return [] }
        return SeasonClasses.of(hub: hub, styles: styles?.value)
    }

    func classColor(_ name: String?) -> String {
        guard let name, let c = classes.first(where: { $0.name == name }) else { return ClassInfo.defaultColor }
        return c.color
    }

    /// Championships with rows, the families/kinds among them, and the current pick.
    var withRows: [ChampionshipSummary] { (hub.value?.championships ?? []).filter { $0.rowCount > 0 } }

    var families: [ChampFamily] {
        guard let hub = hub.value else { return [] }
        return Champs.families(withRows, seriesName: hub.seriesName)
    }

    var selectedFamily: String? {
        if let family, families.contains(where: { $0.family == family }) { return family }
        return families.first?.family
    }

    var kinds: [String] { Champs.kinds(withRows, family: selectedFamily, primaryKind: hub.value?.primaryKind) }

    var selectedKind: String? {
        if let kind, kinds.contains(kind) { return kind }
        return kinds.first
    }

    func kindLabel(_ k: String) -> String { Champs.kindLabel(among: withRows, kind: k) }

    var selectedChamps: [ChampionshipSummary] {
        Champs.selected(withRows, family: selectedFamily, kind: selectedKind, classes: classes)
    }

    /// The selection narrowed by the class filter.
    var shownChamps: [ChampionshipSummary] {
        guard let classFilter else { return selectedChamps }
        return selectedChamps.filter { $0.className == classFilter }
    }

    /// Switching championship keeps the Teams/Drivers choice when offered.
    func switchFamily(_ f: String) {
        family = f
        let nextKinds = Set(withRows.filter { $0.family == f }.compactMap(\.kind))
        if let kind, !nextKinds.contains(kind) { self.kind = nil }
    }

    func recap(for champ: ChampionshipSummary) -> Resource<Recap> {
        if let r = recaps[champ.id] { return r }
        let r = Resource<Recap>("/api/championships/\(champ.id)/recap")
        recaps[champ.id] = r
        return r
    }

    // MARK: loading

    func load(_ session: AppSession) async {
        session.freshness.reset()
        async let a: Void = hub.load(session.loader, connectivity: session.connectivity, freshness: session.freshness)
        async let b: Void = seasons.load(session.loader)
        _ = await (a, b)
        guard let hub = hub.value else { return }
        if styles == nil { styles = Resource<ClassStylesResponse>("/api/series/\(hub.seriesId)/class-styles") }
        if reference == nil { reference = Resource<ReferenceTable>("/api/seasons/\(hub.id)/reference") }
        if lineups == nil { lineups = Resource<Lineups>("/api/seasons/\(hub.id)/lineups") }
        await styles?.load(session.loader)
    }

    func loadHubExtras(_ session: AppSession) async {
        async let a: Void = reference?.load(session.loader, freshness: session.freshness) ?? ()
        async let b: Void = lineups?.load(session.loader, freshness: session.freshness) ?? ()
        _ = await (a, b)
    }

    func loadRecaps(_ session: AppSession, for champs: [ChampionshipSummary]) async {
        // Main-actor resources; fire the loads together and await them in turn.
        let resources = champs.map { recap(for: $0) }
        let loader = session.loader
        let freshness = session.freshness
        let tasks = resources.map { r in Task { await r.load(loader, freshness: freshness) } }
        for t in tasks { await t.value }
    }
}
