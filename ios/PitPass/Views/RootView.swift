import SwiftUI

struct RootView: View {
    @Environment(AppSession.self) private var session

    var body: some View {
        Group {
            switch session.phase {
            case .checking:
                VStack(spacing: PP.Space.s4) {
                    BrandMark(size: 56)
                    Text("Connecting to \(session.serverURL.host() ?? "server")…")
                        .font(PP.sans(PP.TextSize.sm))
                        .foregroundStyle(PP.textMuted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(PP.bg.ignoresSafeArea())
            case let .signedOut(reason):
                SignInView(reason: reason)
            case let .ready(me):
                HomeView(me: me)
            }
        }
        .preferredColorScheme(session.theme.colorScheme)
    }
}
