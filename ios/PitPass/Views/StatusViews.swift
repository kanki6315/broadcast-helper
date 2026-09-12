import SwiftUI

/// The topbar pill (`.conn-pill`): heartbeat dot + Live / Slow / Offline, and
/// "· cached Xm" while the screen shows data the store answered.
struct ConnectivityPill: View {
    @Environment(AppSession.self) private var session

    var body: some View {
        let status = session.connectivity.status
        TimelineView(.periodic(from: .now, by: 30)) { context in
            HStack(spacing: PP.Space.s1) {
                Circle().fill(dotColor(status)).frame(width: 8, height: 8)
                Text(label(status))
                if let asOf = session.freshness.dataAsOf {
                    Text("· cached \(ConnectivityPill.age(context.date.timeIntervalSince(asOf)))")
                        .font(PP.sans(PP.TextSize.xs, weight: 400))
                }
            }
            .font(PP.sans(PP.TextSize.xs, weight: 500))
            .foregroundStyle(status == .offline ? PP.error : PP.textMuted)
            .lineLimit(1)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(accessibilityText(status, asOf: session.freshness.dataAsOf))
        }
    }

    private func dotColor(_ status: Connectivity.Status) -> Color {
        switch status {
        case .live: PP.success
        case .degraded: PP.accent
        case .offline: PP.error
        }
    }

    private func label(_ status: Connectivity.Status) -> String {
        switch status {
        case .live: "Live"
        case .degraded: "Slow"
        case .offline: "Offline"
        }
    }

    private func accessibilityText(_ status: Connectivity.Status, asOf: Date?) -> String {
        let base = switch status {
        case .live: "Backend reachable"
        case .degraded: "Backend responding slowly"
        case .offline: "Backend unreachable"
        }
        guard let asOf else { return base }
        return "\(base). This screen includes cached data last fetched \(asOf.formatted(date: .abbreviated, time: .shortened))."
    }

    /// Rounded minutes / hours / days, floored at 1m — same wording as the web.
    static func age(_ seconds: TimeInterval) -> String {
        let minutes = Int((seconds / 60).rounded())
        if minutes < 60 { return "\(max(1, minutes))m" }
        let hours = Int((Double(minutes) / 60).rounded())
        return hours < 24 ? "\(hours)h" : "\(Int((Double(hours) / 24).rounded()))d"
    }
}

/// "Newer data is available — Refresh / Dismiss": the DataNudge, a
/// bottom-centre toast on a raised surface. Content never reshuffles under the
/// broadcaster until they ask.
struct UpdateNudge: View {
    let refresh: () -> Void
    let dismiss: () -> Void

    var body: some View {
        HStack(spacing: PP.Space.s4) {
            Text("Newer data is available")
                .font(PP.sans(PP.TextSize.sm, weight: 500))
                .foregroundStyle(PP.text)
            HStack(spacing: PP.Space.s2) {
                Button("Refresh", action: refresh).buttonStyle(PPPrimaryButtonStyle(compact: true))
                Button("Dismiss", action: dismiss).buttonStyle(PPQuietButtonStyle())
            }
        }
        .padding(.vertical, PP.Space.s2)
        .padding(.leading, PP.Space.s4)
        .padding(.trailing, PP.Space.s3)
        .background(PP.surface2, in: RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous).strokeBorder(PP.borderStrong))
        .shadow(color: .black.opacity(0.14), radius: 6, y: 4)
        .shadow(color: .black.opacity(0.18), radius: 20, y: 12)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

/// `.error-panel`: a tinted, hairlined message in the error hue.
struct ErrorPanel: View {
    let message: String

    var body: some View {
        Text(message)
            .font(PP.sans(PP.TextSize.sm))
            .foregroundStyle(PP.error)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, PP.Space.s3)
            .padding(.horizontal, PP.Space.s4)
            .background(PP.errorTint, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous)
                .strokeBorder(PP.error.opacity(0.35)))
    }
}

/// `.empty-state`: dashed hairline box, muted, teaching what would fill it.
struct EmptyState: View {
    let message: String

    var body: some View {
        Text(message)
            .font(PP.sans(PP.TextSize.sm))
            .foregroundStyle(PP.textMuted)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.vertical, PP.Space.s6)
            .padding(.horizontal, PP.Space.s4)
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous)
                .strokeBorder(PP.borderStrong, style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
    }
}

/// `.skeleton`: a raised block pulsing at 1.4s; static under Reduce Motion.
struct SkeletonBlock: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var height: CGFloat = 16
    var widthFraction: CGFloat = 1
    @State private var dim = false

    var body: some View {
        GeometryReader { geo in
            RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous)
                .fill(PP.surface2)
                .frame(width: geo.size.width * widthFraction, height: height)
                .opacity(dim ? 0.55 : 1)
        }
        .frame(height: height)
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) { dim = true }
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Buttons (one vocabulary, every screen)

/// `.login-button` / `.update-banner-reload`: solid amber, on-accent ink,
/// radius md. Pressed = amber mixed 12% toward ink.
struct PPPrimaryButtonStyle: ButtonStyle {
    var compact = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PP.sans(compact ? PP.TextSize.sm : PP.TextSize.base, weight: 600))
            .foregroundStyle(PP.onAccent)
            .padding(.vertical, compact ? PP.Space.s1 : 10)
            .padding(.horizontal, compact ? PP.Space.s3 : 20)
            .background(PP.accent, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous)
                    .fill(PP.ink.opacity(configuration.isPressed ? 0.12 : 0))
            }
            .animation(PP.Motion.fast, value: configuration.isPressed)
    }
}

/// `.button-secondary`: panel surface, body ink, hairline.
struct PPSecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PP.sans(PP.TextSize.sm, weight: 500))
            .foregroundStyle(PP.text)
            .padding(.vertical, 6)
            .padding(.horizontal, 14)
            .background(configuration.isPressed ? PP.surface2 : PP.surface,
                        in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
            .animation(PP.Motion.fast, value: configuration.isPressed)
    }
}

/// `.update-banner-dismiss`: text only, muted, ink on press.
struct PPQuietButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(PP.sans(PP.TextSize.sm, weight: 500))
            .foregroundStyle(configuration.isPressed ? PP.text : PP.textMuted)
            .padding(.vertical, PP.Space.s1)
            .padding(.horizontal, PP.Space.s2)
            .animation(PP.Motion.fast, value: configuration.isPressed)
    }
}

/// Auto / Light / Dark — the website's segmented theme toggle.
struct ThemeToggle: View {
    @Environment(AppSession.self) private var session

    var body: some View {
        @Bindable var session = session
        HStack(spacing: 2) {
            ForEach(ThemePreference.allCases) { option in
                let active = session.theme == option
                Button(option.label) {
                    withAnimation(PP.Motion.fast) { session.theme = option }
                }
                .font(PP.sans(PP.TextSize.xs, weight: active ? 600 : 500))
                .foregroundStyle(active ? PP.ink : PP.textMuted)
                .padding(.vertical, 4)
                .padding(.horizontal, 10)
                .background {
                    if active {
                        RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous)
                            .fill(PP.bg)
                            .shadow(color: .black.opacity(0.08), radius: 1, y: 1)
                            .shadow(color: .black.opacity(0.06), radius: 4, y: 2)
                    }
                }
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .padding(2)
        .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Theme")
    }
}
