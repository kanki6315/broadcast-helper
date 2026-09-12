import Foundation
import Observation

/// "Is what's on screen live or cached, and how old?" — the web pill's second
/// half (`lib/connectivity.ts` dataAsOf). Resources report when they paint
/// from the store and clear themselves once the server confirms them; the
/// oldest cached document on screen sets the age. Reset per screen.
@MainActor
@Observable
final class Freshness {
    private var cachedAt: [String: Date] = [:]

    /// Oldest cache-served document currently on screen, if any.
    var dataAsOf: Date? { cachedAt.values.min() }

    func report(cachedAt date: Date, for path: String) {
        cachedAt[path] = date
    }

    func confirmed(_ path: String) {
        cachedAt[path] = nil
    }

    func reset() {
        cachedAt = [:]
    }
}
