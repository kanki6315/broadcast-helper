import SwiftUI

struct RootView: View {
    @Environment(AppSession.self) private var session

    var body: some View {
        Group {
            switch session.phase {
            case .checking:
                ConnectionLoadingView(host: session.serverURL.host() ?? "server")
            case let .signedOut(reason):
                SignInView(reason: reason)
            case let .ready(me):
                HomeView(me: me)
            }
        }
        .preferredColorScheme(session.theme.colorScheme)
    }
}

/// Lives only while bootstrap is pending; it never delays the ready/sign-in transition.
private struct ConnectionLoadingView: View {
    let host: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var started = Date()
    @State private var hint = ""

    var body: some View {
        VStack(spacing: PP.Space.s5) {
            BrandMark(size: 56)
            TimelineView(.animation(minimumInterval: 1.0 / 30,
                                    paused: reduceMotion || scenePhase != .active)) { context in
                let elapsed = max(0, context.date.timeIntervalSince(started))
                VStack(spacing: PP.Space.s3) {
                    HStack(spacing: PP.Space.s4) {
                        ForEach(0..<5) { index in
                            let phase = elapsed * .pi - Double(index) * 0.5
                            Circle()
                                .fill(PP.accent)
                                .opacity(reduceMotion ? 1 : 0.2 + 0.8 * pow((sin(phase) + 1) / 2, 3))
                                .frame(width: 16, height: 16)
                        }
                    }
                    Rectangle()
                        .fill(PP.border)
                        .frame(width: 144, height: 2)
                        .overlay(alignment: .leading) {
                            Rectangle()
                                .fill(PP.accent)
                                .frame(width: 48, height: 2)
                                .offset(x: reduceMotion ? 48 : elapsed.truncatingRemainder(dividingBy: 2) / 2 * 192 - 48)
                        }
                        .clipped()
                }
            }
            .accessibilityHidden(true)

            VStack(spacing: PP.Space.s2) {
                Text("Connecting to \(host)…")
                    .font(PP.sans(PP.TextSize.base, weight: 600))
                    .foregroundStyle(PP.ink)
                Text(hint.isEmpty ? " " : hint)
                    .font(PP.sans(PP.TextSize.sm))
                    .foregroundStyle(PP.textMuted)
                    .accessibilityHidden(hint.isEmpty)
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: 380)
        }
        .padding(PP.Space.s5)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PP.bg.ignoresSafeArea())
        .task {
            // SwiftUI cancels this task as soon as the connection screen disappears.
            do {
                try await Task.sleep(for: .seconds(3))
                hint = "The server may be waking up. The first connection can take a few seconds."
                try await Task.sleep(for: .seconds(12))
                hint = "Still waiting for the server. Pit Pass will open when it responds."
            } catch { /* The connection finished or the view was dismissed. */ }
        }
    }
}
