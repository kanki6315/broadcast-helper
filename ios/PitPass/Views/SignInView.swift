import SwiftUI

/// The website's `.login-screen`: brand + wordmark, one amber button.
struct SignInView: View {
    @Environment(AppSession.self) private var session
    let reason: String?
    @State private var showSettings = false

    var body: some View {
        VStack(spacing: PP.Space.s4) {
            Spacer()
            HStack(spacing: PP.Space.s3) {
                BrandMark(size: 56)
                (Text("Pit ").foregroundColor(PP.ink) + Text("Pass").foregroundColor(PP.accentInk))
                    .font(PP.sans(PP.TextSize.xxl, weight: 650))
                    .tracking(PP.TextSize.xxl * -0.02)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Text(session.serverURL.host() ?? session.serverURL.absoluteString)
                .font(PP.mono(PP.TextSize.sm))
                .foregroundStyle(PP.textMuted)
            if let reason {
                Text(reason)
                    .font(PP.sans(PP.TextSize.sm))
                    .foregroundStyle(PP.text)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 440)
            }
            Button {
                Task { await session.signIn() }
            } label: {
                HStack(spacing: PP.Space.s2) {
                    if session.isSigningIn {
                        ProgressView().tint(PP.onAccent).controlSize(.small)
                    }
                    Text(session.isSigningIn ? "Signing in…" : "Sign in with Google")
                }
                .frame(minWidth: 200)
            }
            .buttonStyle(PPPrimaryButtonStyle())
            .disabled(session.isSigningIn)
            .padding(.top, PP.Space.s2)
            if let error = session.signInError {
                ErrorPanel(message: error).frame(maxWidth: 440)
            }
            Button("Retry connection") { Task { await session.bootstrap() } }
                .buttonStyle(PPQuietButtonStyle())
            Spacer()
            Button("Server settings") { showSettings = true }
                .buttonStyle(PPQuietButtonStyle())
                .padding(.bottom, PP.Space.s5)
        }
        .padding(PP.Space.s6)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(PP.bg.ignoresSafeArea())
        .sheet(isPresented: $showSettings) { SettingsView() }
    }
}
