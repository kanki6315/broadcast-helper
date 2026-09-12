import SwiftUI

/// The toolbar's "Download this event / season" control, in four states:
/// not yet downloaded (Download), in flight (a determinate ring, "12 of 40",
/// tap to stop), downloaded ("Downloaded · 2h", tap to bring it up to date —
/// cheap, every document revalidates by ETag) and failed (tap to retry).
/// Amber stays reserved for the primary action; this reads in the muted
/// toolbar ink like the connectivity pill beside it.
struct DownloadButton: View {
    @Environment(AppSession.self) private var session
    let target: DownloadTarget
    /// "event" / "season" — for the accessibility label.
    let noun: String

    var body: some View {
        let downloads = session.downloads
        switch downloads.activity(for: target) {
        case let .running(progress):
            Button {
                downloads.cancel(target)
            } label: {
                HStack(spacing: PP.Space.s2) {
                    Ring(fraction: progress.total > 0 ? Double(progress.done) / Double(progress.total) : 0)
                    Text(progress.total > 0 ? "\(progress.done) of \(progress.total)" : "Starting…")
                        .font(PP.mono(PP.TextSize.xs, weight: 500))
                        .monospacedDigit()
                }
                .foregroundStyle(PP.textMuted)
            }
            .accessibilityLabel("Downloading \(noun): \(progress.phase), \(progress.done) of \(progress.total). Double-tap to stop.")
        case let .failed(message):
            Button {
                downloads.dismissFailure(target)
                start()
            } label: {
                caption("Download failed", systemImage: "exclamationmark.circle", color: PP.error)
            }
            .accessibilityLabel("Download failed: \(message). Double-tap to try again.")
            .help(message)
        case nil:
            if let record = downloads.record(for: target) {
                TimelineView(.periodic(from: .now, by: 60)) { context in
                    Button(action: start) {
                        caption("Downloaded · \(ConnectivityPill.age(context.date.timeIntervalSince(record.completedAt)))",
                                systemImage: "checkmark.circle", color: PP.textMuted)
                    }
                    .accessibilityLabel("Downloaded \(record.completedAt.formatted(date: .abbreviated, time: .shortened)). Double-tap to download again.")
                }
            } else {
                Button(action: start) {
                    caption("Download", systemImage: "arrow.down.circle", color: PP.accentInk)
                }
                .accessibilityLabel("Download this \(noun) for offline use")
            }
        }
    }

    /// Icon + text as a plain stack: a toolbar collapses `Label` to its icon.
    private func caption(_ text: String, systemImage: String, color: Color) -> some View {
        HStack(spacing: PP.Space.s1) {
            Image(systemName: systemImage).font(.system(size: 13, weight: .medium))
            Text(text).font(PP.sans(PP.TextSize.xs, weight: 500))
        }
        .foregroundStyle(color)
        .lineLimit(1)
        .fixedSize()
    }

    private func start() {
        session.downloads.start(target, loader: session.loader, store: session.store)
    }

    /// A 14pt determinate ring: hairline track, ink arc. Animates the arc
    /// between reports; static under Reduce Motion.
    private struct Ring: View {
        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        let fraction: Double

        var body: some View {
            ZStack {
                Circle().stroke(PP.borderStrong, lineWidth: 2)
                Circle()
                    .trim(from: 0, to: max(0.02, min(1, fraction)))
                    .stroke(PP.text, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(reduceMotion ? nil : PP.Motion.medium, value: fraction)
            }
            .frame(width: 14, height: 14)
            .accessibilityHidden(true)
        }
    }
}

/// Compact toolbar entry point; detailed status and download actions live in
/// the popover so neither timestamps nor progress counts crowd the title.
struct StatusDownloadButton: View {
    @Environment(AppSession.self) private var session
    @State private var showingStatus = false
    let target: DownloadTarget
    let noun: String

    var body: some View {
        Button { showingStatus = true } label: {
            HStack(spacing: 10) {
                Image(systemName: connectionIcon)
                    .foregroundStyle(connectionColor)
                if case .running = session.downloads.activity(for: target) {
                    ProgressView().controlSize(.mini)
                } else {
                    Image(systemName: downloadIcon)
                }
            }
            .frame(minHeight: 32)
        }
        .accessibilityLabel("\(connectionLabel). \(downloadLabel). Show status and downloads")
        .popover(isPresented: $showingStatus) {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("Status & downloads").font(.headline)
                    Spacer()
                    Button("Done") { showingStatus = false }
                }
                Label(connectionLabel, systemImage: connectionIcon)
                    .foregroundStyle(connectionColor)
                if let checked = session.connectivity.lastChecked {
                    Text("Connection checked \(checked.formatted(date: .omitted, time: .shortened))")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if let cached = session.freshness.dataAsOf {
                    Text("Cached data from \(cached.formatted(date: .abbreviated, time: .shortened))")
                        .font(.subheadline)
                }
                Divider()
                downloadDetails
            }
            .padding(20)
            .frame(width: 340)
            .tint(PP.accentInk)
            .presentationCompactAdaptation(.popover)
        }
    }

    @ViewBuilder private var downloadDetails: some View {
        if let record = session.downloads.record(for: target) {
            Label("\(noun.capitalized) saved for offline use", systemImage: "checkmark.circle")
            Text("Downloaded \(record.completedAt.formatted(date: .abbreviated, time: .shortened))")
                .font(.caption).foregroundStyle(.secondary)
        }
        switch session.downloads.activity(for: target) {
        case let .running(progress):
            Text(progress.phase).font(.subheadline)
            if progress.total > 0 {
                ProgressView(value: Double(progress.done), total: Double(progress.total))
                Text("\(progress.done) of \(progress.total)").font(.caption).monospacedDigit()
            } else {
                ProgressView("Starting download…")
            }
            Button("Stop download", role: .cancel) { session.downloads.cancel(target) }
                .buttonStyle(.bordered)
        case let .failed(message):
            Label("Download failed", systemImage: "exclamationmark.circle").foregroundStyle(PP.error)
            Text(message).font(.subheadline)
            Button("Retry download") {
                session.downloads.dismissFailure(target)
                start()
            }
            .buttonStyle(.bordered)
        case nil:
            if session.downloads.record(for: target) == nil {
                Text("This \(noun) hasn’t been downloaded for offline use.")
                    .font(.subheadline)
            }
            Button(session.downloads.record(for: target) == nil ? "Download \(noun)" : "Update download", action: start)
                .buttonStyle(.bordered)
        }
    }

    private func start() {
        session.downloads.start(target, loader: session.loader, store: session.store)
    }

    private var connectionLabel: String {
        switch session.connectivity.status {
        case .live: "Connected"
        case .degraded: "Slow connection"
        case .offline: "Offline"
        }
    }

    private var connectionIcon: String {
        switch session.connectivity.status {
        case .live: "wifi"
        case .degraded: "wifi.exclamationmark"
        case .offline: "wifi.slash"
        }
    }

    private var connectionColor: Color {
        switch session.connectivity.status {
        case .live: PP.textMuted
        case .degraded: PP.accentInk
        case .offline: PP.error
        }
    }

    private var downloadIcon: String {
        if case .failed = session.downloads.activity(for: target) { return "exclamationmark.arrow.triangle.2.circlepath" }
        return session.downloads.record(for: target) == nil ? "arrow.down.circle" : "checkmark.circle"
    }

    private var downloadLabel: String {
        switch session.downloads.activity(for: target) {
        case .running: "Downloading \(noun)"
        case .failed: "Download failed"
        case nil: session.downloads.record(for: target) == nil ? "Not downloaded" : "Downloaded"
        }
    }
}
