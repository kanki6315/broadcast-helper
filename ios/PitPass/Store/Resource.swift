import Foundation
import Observation

/// One API document as a view sees it: paints from the store instantly, then
/// revalidates. A changed payload waits in `pendingUpdate` until the person
/// taps Refresh — the web app's DataNudge rule, kept on purpose.
@MainActor
@Observable
final class Resource<T: Decodable & Sendable> {
    private(set) var path: String
    private(set) var value: T?
    private(set) var fetchedAt: Date?
    /// True while what's shown came from disk and the server hasn't confirmed it.
    private(set) var isStale = false
    private(set) var isLoading = false
    private(set) var error: String?
    private(set) var pendingUpdate: Loaded<T>?
    /// Digest of the bytes behind `value` (see `Loaded.digest`).
    private var digest: Int?

    init(_ path: String) {
        self.path = path
    }

    /// Point at another document (a model built before its id was known).
    func retarget(_ path: String) {
        guard path != self.path else { return }
        self.path = path
        value = nil
        fetchedAt = nil
        isStale = false
        error = nil
        pendingUpdate = nil
        digest = nil
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
                // 304 against the *store's* ETag: if a download replaced the
                // stored body since this view adopted it, the server has just
                // confirmed bytes we aren't showing — surface them as an update.
                if let digest, digest != loaded.digest {
                    pendingUpdate = loaded
                } else {
                    fetchedAt = loaded.fetchedAt
                    isStale = false
                }
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

    /// A write just returned the document's new state: show it as current.
    func replace(_ newValue: T) {
        value = newValue
        fetchedAt = .now
        isStale = false
        pendingUpdate = nil
        error = nil
        digest = nil
    }

    func applyPendingUpdate() {
        if let pendingUpdate { adopt(pendingUpdate) }
    }

    private func adopt(_ loaded: Loaded<T>) {
        value = loaded.value
        fetchedAt = loaded.fetchedAt
        isStale = loaded.fromCache
        pendingUpdate = nil
        digest = loaded.digest
    }
}
