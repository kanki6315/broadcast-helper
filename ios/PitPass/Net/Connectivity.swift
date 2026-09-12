import Foundation
import Observation
import UIKit

/// Port of the web app's `lib/connectivity.ts`: a heartbeat (HEAD /api/me
/// every 30s) proves the backend is reachable right now — the OS's "online"
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
    private var foregroundObserver: (any NSObjectProtocol)?

    func start(client: APIClient) {
        self.client = client
        task?.cancel()
        task = Task { [weak self] in
            while !Task.isCancelled {
                await self?.ping()
                try? await Task.sleep(for: .seconds(Connectivity.interval))
            }
        }
        if foregroundObserver == nil {
            foregroundObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in await self?.ping() }
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
        guard let client else { return }
        do {
            let elapsed = try await client.head("/api/me", timeout: Connectivity.timeout)
            status = elapsed > Connectivity.slow ? .degraded : .live
        } catch {
            status = .offline
        }
        lastChecked = .now
    }
}
