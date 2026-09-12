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
