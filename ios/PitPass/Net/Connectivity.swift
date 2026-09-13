import Foundation
import Observation
import UIKit

/// Port of the web app's `lib/connectivity.ts`: a heartbeat (HEAD /api/me
/// every 30s while active) proves the backend is reachable right now — the OS's "online"
/// flag is not trusted because it says yes on unusably bad paddock Wi-Fi.
/// Foregrounding pings immediately so the badge is honest within a beat.
@MainActor
@Observable
final class Connectivity {
    enum Status: Equatable {
        case live
        case degraded
        case offline
    }

    private(set) var status: Status = .live
    private(set) var lastChecked: Date?

    private static let interval: TimeInterval = 30
    private static let timeout: TimeInterval = 4
    /// Answers slower than this read as degraded.
    private static let slow: TimeInterval = 2.5

    private var client: APIClient?
    private var task: Task<Void, Never>?

    func start(client: APIClient) {
        self.client = client
        pause()
        resume()
    }

    func pause() {
        task?.cancel()
        task = nil
    }

    func resume() {
        guard client != nil, task == nil,
              UIApplication.shared.applicationState == .active else { return }
        task = Task { [weak self] in
            while !Task.isCancelled {
                await self?.ping()
                do {
                    try await Task.sleep(for: .seconds(Connectivity.interval))
                } catch {
                    return
                }
            }
        }
    }

    /// Something else already learned the network is gone (a failed fetch).
    func noteOffline() {
        status = .offline
    }

    func noteReachable() {
        if status == .offline { status = .live }
    }

    func ping() async {
        guard !Task.isCancelled, UIApplication.shared.applicationState == .active,
              let client else { return }
        do {
            let elapsed = try await client.head("/api/me", timeout: Connectivity.timeout)
            guard !Task.isCancelled else { return }
            status = elapsed > Connectivity.slow ? .degraded : .live
        } catch {
            guard !Task.isCancelled else { return }
            status = .offline
        }
        lastChecked = .now
    }
}
