import CoreLocation
import Foundation
import Observation

/// One live position stream for as long as something needs it (a guidance
/// target, an anchor capture); stopped on dismiss so the sheet never drains
/// a battery in a pocket. `CLLocationUpdate.liveUpdates` prompts for
/// when-in-use permission itself (NSLocationWhenInUseUsageDescription).
@MainActor
@Observable
final class LocationWatcher {
    struct Fix: Sendable, Equatable {
        let lat: Double
        let lng: Double
        /// Horizontal accuracy radius, metres.
        let accuracy: Double
    }

    private(set) var fix: Fix?
    private(set) var error: String?
    /// Everything received since `start`, for anchor averaging.
    private(set) var samples: [PitLaneGeo.FixSample] = []
    private var task: Task<Void, Never>?

    var isRunning: Bool { task != nil }

    func start() {
        guard task == nil else { return }
        fix = nil
        error = nil
        samples = []
        task = Task { [weak self] in
            do {
                for try await update in CLLocationUpdate.liveUpdates(.otherNavigation) {
                    guard let self, !Task.isCancelled else { break }
                    if update.authorizationDenied {
                        self.error = "Location is blocked — allow it for Pit Pass in Settings to get guidance."
                        continue
                    }
                    if update.locationUnavailable { self.error = "No GPS fix yet — step away from the garage overhang." }
                    guard let loc = update.location else { continue }
                    let f = Fix(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude,
                                accuracy: max(0, loc.horizontalAccuracy))
                    self.error = nil
                    self.fix = f
                    self.samples.append(.init(lat: f.lat, lng: f.lng, accuracy: f.accuracy))
                }
            } catch {
                self?.error = "Location isn't available: \(error.localizedDescription)"
            }
        }
    }

    func stop() {
        task?.cancel()
        task = nil
    }
}
