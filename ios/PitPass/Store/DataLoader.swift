import Foundation

/// A decoded API document plus where it came from.
struct Loaded<T: Sendable>: Sendable {
    let value: T
    /// When the server last confirmed this content (a fresh 200 or a 304).
    let fetchedAt: Date
    let fromCache: Bool
}

enum LoadError: Error, LocalizedError {
    /// Network failed and nothing was stored for that path.
    case offlineNoCache(underlying: APIError)

    var errorDescription: String? {
        switch self {
        case let .offlineNoCache(underlying): underlying.localizedDescription
        }
    }
}

/// Read-through between the API and the offline store — the native stand-in
/// for the service worker's stale-while-revalidate route, with the same rule
/// the web app settled on: the cached copy paints instantly, a background
/// revalidation follows, and a *changed* payload is surfaced as a nudge, never
/// silently re-rendered under a broadcaster mid-sentence (see `Resource`).
struct DataLoader: Sendable {
    enum Refresh<T: Sendable>: Sendable {
        /// Server said 304 (or the body was byte-identical).
        case unchanged(Loaded<T>)
        case updated(Loaded<T>)
    }

    let client: APIClient
    let store: OfflineStore

    /// What is on disk for this path, decoded; nil if nothing or undecodable
    /// (e.g. a stale shape from an older app build — treated as absent).
    func cached<T: Decodable & Sendable>(_ path: String, as type: T.Type = T.self) async -> Loaded<T>? {
        guard let entry = await store.load(path),
              let value: T = try? client.decode(entry.body) else { return nil }
        return Loaded(value: value, fetchedAt: entry.fetchedAt, fromCache: true)
    }

    /// Ask the server, storing what comes back. A 304 refreshes the stamp only.
    func refresh<T: Decodable & Sendable>(_ path: String, as type: T.Type = T.self) async throws(APIError) -> Refresh<T> {
        let entry = await store.load(path)
        let result = try await client.get(path, ifNoneMatch: entry?.etag)
        let now = Date.now
        switch result {
        case .notModified:
            guard let entry, let value: T = try? client.decode(entry.body) else {
                // The server honoured an ETag we can't decode any more: refetch clean.
                return try await refreshUnconditionally(path, as: type)
            }
            await store.touch(path, at: now)
            return .unchanged(Loaded(value: value, fetchedAt: now, fromCache: false))
        case let .ok(data, etag):
            let value: T = try client.decode(data)
            let same = entry?.body == data
            await store.save(path, etag: etag, body: data, at: now)
            let loaded = Loaded(value: value, fetchedAt: now, fromCache: false)
            return same ? .unchanged(loaded) : .updated(loaded)
        }
    }

    private func refreshUnconditionally<T: Decodable & Sendable>(_ path: String, as type: T.Type) async throws(APIError) -> Refresh<T> {
        guard case let .ok(data, etag) = try await client.get(path) else {
            throw APIError.decoding("unconditional GET answered 304")
        }
        let value: T = try client.decode(data)
        await store.save(path, etag: etag, body: data)
        return .updated(Loaded(value: value, fetchedAt: .now, fromCache: false))
    }

    /// Cache-first bytes for immutable binaries (logos and photos carry a
    /// `?v=` stamp, so a stored copy can never be stale — the service worker's
    /// `api-images` CacheFirst route). Nil when offline with nothing stored.
    func bytes(_ path: String) async -> Data? {
        if let entry = await store.load(path) { return entry.body }
        guard case let .ok(data, etag)? = try? await client.get(path) else { return nil }
        await store.save(path, etag: etag, body: data)
        return data
    }

    /// Network, falling back to the store when the network fails. For
    /// documents that must be fresh when online (identity, the scratchpad).
    func networkFirst<T: Decodable & Sendable>(_ path: String, as type: T.Type = T.self) async throws -> Loaded<T> {
        do {
            switch try await refresh(path, as: type) {
            case let .unchanged(loaded), let .updated(loaded):
                return loaded
            }
        } catch let error as APIError {
            switch error {
            case .transport, .http:
                if let cached: Loaded<T> = await cached(path) { return cached }
                throw LoadError.offlineNoCache(underlying: error)
            default:
                throw error
            }
        }
    }
}
