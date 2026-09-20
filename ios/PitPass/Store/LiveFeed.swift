import Foundation
import Observation

/// A document that is only worth having while it is current: polled, never
/// stored. The opposite contract to `Resource` on purpose — no offline copy,
/// and a changed payload is shown at once rather than waiting behind a nudge,
/// because a running order that waits for a tap is not live.
///
/// Polling is cheap by construction: the live endpoints carry no timestamps,
/// so the API's content ETag answers 304 until the order actually changes.
@MainActor
@Observable
final class LiveFeed<T: Decodable & Sendable & Equatable> {
    private(set) var value: T?
    /// Why the last poll failed; cleared by the next one that succeeds.
    private(set) var error: String?
    /// When the server last answered at all (200 or 304).
    private(set) var confirmedAt: Date?
    private var etag: String?

    /// Polls until the surrounding task is cancelled — run it from `.task`,
    /// so leaving the screen stops the requests.
    func run(_ client: APIClient, path: String, every interval: Duration = .seconds(3)) async {
        while !Task.isCancelled {
            await poll(client, path: path)
            try? await Task.sleep(for: interval)
        }
    }

    func poll(_ client: APIClient, path: String) async {
        do {
            switch try await client.get(path, ifNoneMatch: etag) {
            case .notModified:
                break
            case let .ok(data, etag):
                let decoded: T = try client.decode(data)
                if decoded != value { value = decoded }
                self.etag = etag
            }
            error = nil
            confirmedAt = .now
        } catch {
            // Keep the last value: the caller decides how to present stale.
            self.error = error.localizedDescription
        }
    }

    /// A write just returned the document's new state.
    func replace(_ newValue: T) {
        value = newValue
        etag = nil
        error = nil
        confirmedAt = .now
    }
}
