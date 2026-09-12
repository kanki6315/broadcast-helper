import Foundation
import Observation

/// One API document as a view sees it: paints from the store instantly, then
/// revalidates. A changed payload waits in `pendingUpdate` until the person
/// taps Refresh — the web app's DataNudge rule, kept on purpose.
@MainActor
@Observable
final class Resource<T: Decodable & Sendable> {
    let path: String
    private(set) var value: T?
    private(set) var fetchedAt: Date?
    /// True while what's shown came from disk and the server hasn't confirmed it.
    private(set) var isStale = false
    private(set) var isLoading = false
    private(set) var error: String?
    private(set) var pendingUpdate: Loaded<T>?

    init(_ path: String) {
        self.path = path
    }

    func load(_ loader: DataLoader, connectivity: Connectivity? = nil, freshness: Freshness? = nil) async {
        if value == nil, let cached: Loaded<T> = await loader.cached(path) {
            adopt(cached)
            freshness?.report(cachedAt: cached.fetchedAt, for: path)
        }
        isLoading = true
        defer { isLoading = false }
        do {
            let result: DataLoader.Refresh<T> = try await loader.refresh(path)
            error = nil
            connectivity?.noteReachable()
            switch result {
            case let .unchanged(loaded):
                fetchedAt = loaded.fetchedAt
                isStale = false
                freshness?.confirmed(path)
            case let .updated(loaded):
                if value == nil {
                    adopt(loaded)
                } else {
                    pendingUpdate = loaded
                }
            }
        } catch {
            if case .transport = error { connectivity?.noteOffline() }
            if let fetchedAt, isStale { freshness?.report(cachedAt: fetchedAt, for: path) }
            if value == nil { self.error = error.localizedDescription }
        }
    }

    func applyPendingUpdate() {
        if let pendingUpdate { adopt(pendingUpdate) }
    }

    private func adopt(_ loaded: Loaded<T>) {
        value = loaded.value
        fetchedAt = loaded.fetchedAt
        isStale = loaded.fromCache
        pendingUpdate = nil
    }
}
