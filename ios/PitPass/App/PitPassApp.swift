import BackgroundTasks
import SwiftUI

@main
struct PitPassApp: App {
    @State private var session: AppSession
    @Environment(\.scenePhase) private var scenePhase

    init() {
        let session = AppSession()
        _session = State(initialValue: session)
        // Offline scratchpad ink gets a second chance to sync after the app
        // is backgrounded; registration has to happen before launch ends.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: PadSyncer.backgroundTaskId, using: nil) { task in
            let handle = Unchecked(task)
            let work = Task { @MainActor in
                await session.pads.syncDirtyPads()
                handle.value.setTaskCompleted(success: true)
            }
            handle.value.expirationHandler = { work.cancel() }
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(session)
                .task { await session.bootstrap() }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { session.pads.scheduleBackgroundSyncIfNeeded() }
        }
    }
}

/// BGTask isn't Sendable; the scheduler hands it to us on its own queue and
/// we only touch it from the one task that completes it.
private struct Unchecked<T>: @unchecked Sendable {
    let value: T
    init(_ value: T) { self.value = value }
}
