import SwiftUI

/// The website's `.topbar`: wordmark left; heartbeat pill, theme toggle and
/// settings right. Sits above every screen in place of a system nav bar.
struct TopBar: View {
    let onSettings: () -> Void

    var body: some View {
        HStack(spacing: PP.Space.s4) {
            Wordmark()
            Spacer(minLength: PP.Space.s4)
            HStack(spacing: PP.Space.s3) {
                ConnectivityPill()
                ThemeToggle()
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(PP.textMuted)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Settings")
            }
        }
        .padding(.vertical, PP.Space.s2)
    }
}

/// `.container`: the page shell — capped at 1800pt, 24pt side gutters.
struct PageContainer<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .frame(maxWidth: 1800)
            .padding(.top, PP.Space.s4)
            .padding(.horizontal, PP.Space.s5)
            .padding(.bottom, PP.Space.s7)
            .frame(maxWidth: .infinity)
    }
}
