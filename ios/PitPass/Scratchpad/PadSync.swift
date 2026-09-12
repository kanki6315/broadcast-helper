import BackgroundTasks
import Foundation
import Observation

/// App-level replay of offline scratchpad ink — scratchpadSync.ts natively.
/// On start and every time the heartbeat flips back to 'live', dirty local
/// pads are PUT whole, even if their sheet is never reopened; a pad whose
/// sheet is open is left to its own `PadModel`. A 409 is never resolved
/// here: the mirror is flagged `conflict` and the next open shows the banner.
///
/// Also the FAB badge's source: `attention` says which pads hold unsynced
/// ink (amber) or a conflict (red), kept current by whoever writes a mirror.
@MainActor
@Observable
final class PadSyncer {
    enum Attention: Equatable {
        case dirty, conflict
    }

    /// BGAppRefresh identifier (also in Info.plist's permitted list).
    static let backgroundTaskId = "com.arjunakankipati.pitpass.padsync"

    private(set) var attention: [String: Attention] = [:]
    private var active: Set<String> = []
    private var syncing = false
    private var store: OfflineStore?
    private var client: APIClient?
    private var wasLive = true

    func attention(eventId: Int, owner: String) -> Attention? {
        attention[LocalPad.key(eventId: eventId, owner: owner)]
    }

    var hasDirtyPads: Bool { !attention.isEmpty }

    func start(store: OfflineStore, client: APIClient, connectivity: Connectivity) async {
        self.store = store
        self.client = client
        wasLive = connectivity.status == .live
        for pad in await store.allPads() { note(pad) }
        watch(connectivity)
        // Catch ink stranded by a previous session (app killed while offline).
        await syncDirtyPads()
    }

    func forget() {
        attention = [:]
    }

    // MARK: the open pad

    func registerActive(eventId: Int, owner: String) {
        active.insert(LocalPad.key(eventId: eventId, owner: owner))
    }

    func unregisterActive(eventId: Int, owner: String) {
        active.remove(LocalPad.key(eventId: eventId, owner: owner))
    }

    /// A mirror was written (by the open pad or by this syncer).
    func noteChanged(_ pad: LocalPad) {
        note(pad)
    }

    private func note(_ pad: LocalPad) {
        attention[pad.key] = pad.conflict ? .conflict : (pad.dirty ? .dirty : nil)
    }

    // MARK: replay

    func syncDirtyPads() async {
        guard !syncing, let store, let client else { return }
        syncing = true
        defer { syncing = false }
        for pad in await store.allPads() where pad.dirty && !pad.conflict && !active.contains(pad.key) {
            await push(pad, store: store, client: client)
        }
    }

    private func push(_ pad: LocalPad, store: OfflineStore, client: APIClient) async {
        let request = PadSaveRequest(baseRevision: pad.baseRevision, pageHeight: pad.pageHeight, strokes: pad.strokes)
        do {
            let saved: PadSaveResponse = try await client.putJSON("/api/events/\(pad.eventId)/scratchpad", body: request)
            var next = pad
            next.dirty = false
            next.baseRevision = saved.revision
            await store.savePad(next)
            note(next)
        } catch {
            if case .http(409, _) = error {
                var next = pad
                next.conflict = true
                await store.savePad(next)
                note(next)
            }
            // 401, 413 and 5xx stay dirty for the next 'live' flip; so does a
            // dead network.
        }
    }

    private func watch(_ connectivity: Connectivity) {
        withObservationTracking {
            _ = connectivity.status
        } onChange: {
            Task { @MainActor [weak self] in
                guard let self else { return }
                let live = connectivity.status == .live
                if live, !self.wasLive { await self.syncDirtyPads() }
                self.wasLive = live
                self.watch(connectivity)
            }
        }
    }

    // MARK: background refresh

    /// iOS may wake the app for a short while after it's been backgrounded
    /// with unsynced ink; the request is only worth submitting when there is.
    func scheduleBackgroundSyncIfNeeded() {
        guard hasDirtyPads else { return }
        let request = BGAppRefreshTaskRequest(identifier: PadSyncer.backgroundTaskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
