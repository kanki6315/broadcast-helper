import Foundation
import Observation
import PencilKit

/// One open scratchpad: the document, its server revision, the local mirror
/// and the save loop — ScratchpadModal.tsx's state without the canvas
/// plumbing (PencilKit owns that, see `PadBridge`). Rules kept from the web:
///
/// - every completed mutation is mirrored to the store first (durability);
///   the debounced PUT is merely sync;
/// - a 409 flags a conflict and stops saving until a person picks a side;
///   both choices stash the loser in the one-slot backup;
/// - offline is calm news, not an error — the ink is on the device and the
///   next 'live' flip (or the background syncer) sends it.
@MainActor
@Observable
final class PadModel {
    enum Phase: Equatable {
        case loading
        case error(String)
        case ready
    }

    enum SaveStatus: Equatable {
        case idle, saving, saved, error, full, conflict, offline
    }

    let eventId: Int
    let owner: String
    private let session: AppSession

    private(set) var phase: Phase = .loading
    private(set) var status: SaveStatus = .idle
    private(set) var strokes: [Stroke] = []
    private(set) var pageHeight = Pad.defaultPageHeight
    private(set) var revision = 0
    private(set) var dirty = false
    private(set) var conflict = false
    private var backup: PadBackup?

    /// What the canvas should show. `drawingVersion` bumps whenever the
    /// document was replaced from outside PencilKit (load, conflict choice),
    /// which is the canvas's cue to reload it and drop its undo history.
    private(set) var drawing = PKDrawing()
    private(set) var drawingVersion = 0
    private(set) var canUndo = false
    private(set) var canRedo = false
    /// Installed by the canvas: PencilKit's own undo manager.
    var undoHandle: (() -> Void)?
    var redoHandle: (() -> Void)?

    private var bridge = PadBridge()
    private var saving = false
    private var debounce: Task<Void, Never>?
    private var statusReset: Task<Void, Never>?
    private var wasLive: Bool

    var path: String { "/api/events/\(eventId)/scratchpad" }

    init(eventId: Int, session: AppSession) {
        self.eventId = eventId
        self.session = session
        owner = session.padOwner
        wasLive = session.connectivity.status == .live
        session.pads.registerActive(eventId: eventId, owner: owner)
        watchConnectivity()
    }

    /// The sheet is going away: last flush, hand the pad back to the syncer.
    func close() {
        debounce?.cancel()
        session.pads.unregisterActive(eventId: eventId, owner: owner)
        if dirty, !conflict { Task { await flush() } }
    }

    // MARK: load

    func load() async {
        phase = .loading
        let local = await session.store.loadPad(eventId: eventId, owner: owner)
        backup = local?.backup
        // Network first; a transport failure is "offline", anything else the
        // backend refusing (a real error when there's nothing local).
        var live: PadResponse?
        var refused: String?
        do {
            if case let .ok(data, _) = try await session.client.get(path) {
                live = try session.client.decode(data)
                session.connectivity.noteReachable()
            }
        } catch {
            if case .transport = error { session.connectivity.noteOffline() } else { refused = error.localizedDescription }
        }

        if let live, local == nil || local?.dirty == false {
            // Nothing unsent: the server is the document.
            dirty = false
            conflict = false
            status = .idle
            adopt(live.strokes, revision: live.revision, height: live.pageHeight)
            await persistLocal()
        } else if let live, let local {
            // Unsent local ink. Same base revision = server + strokes the
            // network never got: adopt and sync. A moved-on server is the
            // conflict case — show THIS device's ink with the banner.
            dirty = true
            conflict = local.conflict || live.revision != local.baseRevision
            status = conflict ? .conflict : .idle
            adopt(local.strokes, revision: local.baseRevision, height: local.pageHeight)
            if !conflict { scheduleFlush(after: .milliseconds(400)) }
        } else if let local {
            dirty = local.dirty
            conflict = local.conflict
            status = conflict ? .conflict : (dirty ? .offline : .idle)
            adopt(local.strokes, revision: local.baseRevision, height: local.pageHeight)
        } else if refused == nil {
            // Offline, no mirror: the last cached copy if any, else an empty
            // pad rather than a dead end — the revision guard is the net.
            if let cached: Loaded<PadResponse> = await session.loader.cached(path) {
                adopt(cached.value.strokes, revision: cached.value.revision, height: cached.value.pageHeight)
            } else {
                adopt([], revision: 0, height: Pad.defaultPageHeight)
            }
            dirty = false
            conflict = false
            status = .offline
        } else {
            phase = .error(refused ?? "Failed to load the scratchpad.")
        }
    }

    private func adopt(_ strokes: [Stroke], revision: Int, height: Int) {
        self.strokes = strokes
        self.revision = revision
        pageHeight = Pad.clampedHeight(height)
        drawing = bridge.drawing(for: strokes)
        drawingVersion += 1
        canUndo = false
        canRedo = false
        phase = .ready
    }

    // MARK: mutations (from the canvas)

    /// PencilKit finished a stroke, an erase, an undo or a redo.
    func canvasChanged(_ drawing: PKDrawing, canUndo: Bool, canRedo: Bool) {
        self.canUndo = canUndo
        self.canRedo = canRedo
        let next = bridge.strokes(from: drawing, pageHeight: pageHeight)
        guard next != strokes else { return }
        strokes = next
        markDirty()
    }

    func noteUndoState(canUndo: Bool, canRedo: Bool) {
        self.canUndo = canUndo
        self.canRedo = canRedo
    }

    func extendPage() {
        let next = min(Pad.maxPageHeight, pageHeight + Pad.pageExtendStep)
        guard next != pageHeight else { return }
        pageHeight = next
        markDirty()
    }

    var canExtend: Bool { pageHeight < Pad.maxPageHeight }

    private func markDirty() {
        dirty = true
        Task { await persistLocal() }
        scheduleFlush(after: Pad.saveDebounce)
    }

    // MARK: save

    private func scheduleFlush(after delay: Duration) {
        debounce?.cancel()
        debounce = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.flush()
        }
    }

    private func persistLocal() async {
        let pad = LocalPad(eventId: eventId, owner: owner, strokes: strokes, pageHeight: pageHeight,
                           baseRevision: revision, dirty: dirty, conflict: conflict,
                           updatedAt: Date.now.timeIntervalSince1970 * 1000, backup: backup)
        await session.store.savePad(pad)
        session.pads.noteChanged(pad)
    }

    func flush() async {
        guard dirty, !saving, !conflict else { return }
        dirty = false
        saving = true
        status = .saving
        let request = PadSaveRequest(baseRevision: revision, pageHeight: pageHeight, strokes: strokes)
        do {
            let saved: PadSaveResponse = try await session.client.putJSON(path, body: request)
            saving = false
            revision = saved.revision
            session.connectivity.noteReachable()
            await persistLocal()
            if dirty {
                await flush()
            } else {
                showSaved()
            }
        } catch {
            saving = false
            switch error {
            case .http(409, _):
                conflict = true
                status = .conflict
                await persistLocal()
            case .http(413, _):
                dirty = true
                status = .full
            case .transport:
                dirty = true
                session.connectivity.noteOffline()
                status = .offline
            default:
                dirty = true
                status = session.connectivity.status == .live ? .error : .offline
            }
        }
    }

    private func showSaved() {
        status = .saved
        statusReset?.cancel()
        statusReset = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1600))
            guard !Task.isCancelled, self?.status == .saved else { return }
            self?.status = .idle
        }
    }

    /// Both choices fetch the server copy first — the loser is stashed in the
    /// one-slot backup, never destroyed.
    func resolveConflict(keepMine: Bool) async {
        let server: PadResponse
        do {
            guard case let .ok(data, _) = try await session.client.get(path) else { return }
            server = try session.client.decode(data)
        } catch {
            status = .conflict // still unreachable; resolving needs the other copy
            return
        }
        let now = Date.now.timeIntervalSince1970 * 1000
        if keepMine {
            backup = PadBackup(strokes: server.strokes, pageHeight: server.pageHeight, savedAt: now,
                               reason: "overwritten-by-this-device")
            revision = server.revision
            conflict = false
            dirty = true
            status = .idle
            await persistLocal()
            await flush()
        } else {
            backup = PadBackup(strokes: strokes, pageHeight: pageHeight, savedAt: now, reason: "replaced-by-other")
            conflict = false
            dirty = false
            status = .idle
            adopt(server.strokes, revision: server.revision, height: server.pageHeight)
            await persistLocal()
        }
    }

    // MARK: connectivity

    /// While the pad is open its ink is this model's to sync (the syncer
    /// skips registered pads), so retry on reconnect here.
    private func watchConnectivity() {
        let connectivity = session.connectivity
        withObservationTracking {
            _ = connectivity.status
        } onChange: {
            Task { @MainActor [weak self] in
                guard let self else { return }
                let live = connectivity.status == .live
                if live, !self.wasLive, self.dirty { await self.flush() }
                self.wasLive = live
                self.watchConnectivity()
            }
        }
    }
}
