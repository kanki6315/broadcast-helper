import SwiftUI

/// Navigation preferences only. Cached API data and scratchpad documents keep
/// their existing lifetimes. Scope by server and user so numeric IDs cannot collide.
@MainActor @Observable
final class WorkspaceState {
    struct Snapshot: Codable {
        var lastSeason: Int?
        var seriesSeasons: [String: Int] = [:]
        var values: [String: String] = [:]
        var offsets: [String: [Double]] = [:]
    }
    private(set) var snapshot: Snapshot
    private let defaults: UserDefaults
    private let storageKey: String

    init(scope: String, defaults: UserDefaults = .standard) {
        self.defaults = defaults
        storageKey = "pitpass.workspace.v1." + scope
        snapshot = defaults.data(forKey: storageKey)
            .flatMap { try? JSONDecoder().decode(Snapshot.self, from: $0) } ?? Snapshot()
    }

    func value(_ key: String) -> String? { snapshot.values[key] }
    func set(_ key: String, _ value: String?) {
        guard snapshot.values[key] != value else { return }
        snapshot.values[key] = value
        save()
    }
    func select(_ season: Int, series: String? = nil) {
        snapshot.lastSeason = season
        if let series { snapshot.seriesSeasons[series] = season }
        save()
    }
    func clearLastSeason() { snapshot.lastSeason = nil; save() }
    func offset(_ key: String) -> CGPoint {
        guard let v = snapshot.offsets[key], v.count == 2,
              v.allSatisfy({ $0.isFinite }) else { return .zero }
        return CGPoint(x: max(0, v[0]), y: max(0, v[1]))
    }
    func setOffset(_ key: String, _ point: CGPoint) {
        guard point.x.isFinite, point.y.isFinite else { return }
        let value = [Double(max(0, point.x)), Double(max(0, point.y))]
        guard snapshot.offsets[key] != value else { return }
        snapshot.offsets[key] = value
        save()
    }
    private func save() {
        if let data = try? JSONEncoder().encode(snapshot) { defaults.set(data, forKey: storageKey) }
    }
}

/// Restore only once real content is ready; loading placeholders must never
/// replace a remembered position. Save at gesture end, disappearance/background.
private struct ResumeScroll: ViewModifier {
    @Environment(WorkspaceState.self) private var workspace: WorkspaceState?
    @Environment(\.scenePhase) private var scenePhase
    let key: String
    let ready: Bool
    @State private var position = ScrollPosition()
    @State private var offset = CGPoint.zero
    @State private var touched = false
    @State private var restored = false

    func body(content: Content) -> some View {
        content
            .scrollPosition($position)
            .onScrollGeometryChange(for: CGPoint.self) { geometry in
                CGPoint(x: geometry.contentOffset.x + geometry.contentInsets.leading,
                        y: geometry.contentOffset.y + geometry.contentInsets.top)
            } action: { _, value in offset = value }
            .onScrollGeometryChange(for: CGSize.self) { $0.contentSize } action: { _, _ in
                // Recap grids load after the hub. Retry as the real content grows.
                if ready && restored && !touched { position.scrollTo(point: workspace?.offset(key) ?? .zero) }
            }
            .onScrollPhaseChange { _, phase in
                if phase == .interacting || phase == .tracking { touched = true }
                if phase == .idle { save() }
            }
            .task(id: ready) {
                guard ready, !restored else { return }
                await Task.yield()
                guard !Task.isCancelled else { return }
                restored = true
                if !touched { position.scrollTo(point: workspace?.offset(key) ?? .zero) }
            }
            .onDisappear { save() }
            .onChange(of: scenePhase) { _, phase in if phase != .active { save() } }
    }
    private func save() {
        if ready && restored && touched { workspace?.setOffset(key, offset) }
    }
}

extension View {
    @ViewBuilder func resumeScrollIfNeeded(_ key: String?) -> some View {
        if let key { resumeScroll(key) } else { self }
    }
    func resumeScroll(_ key: String, ready: Bool = true) -> some View {
        modifier(ResumeScroll(key: key, ready: ready))
    }
}
