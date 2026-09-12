import AuthenticationServices
import UIKit

/// Runs the Google login in the system browser sheet. Google refuses OAuth in
/// embedded web views, and the sheet's cookies never reach the app, so the
/// backend ends the flow by redirecting to `pitpass://auth?code=…`
/// (DeviceLoginSuccessHandler) — the sheet closes on that URL and the code is
/// all we take from it. Ephemeral so nothing from the sheet lingers in Safari.
@MainActor
final class WebSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    enum Error: Swift.Error, LocalizedError {
        case cancelled
        case noCode

        var errorDescription: String? {
            switch self {
            case .cancelled: "Sign-in cancelled"
            case .noCode: "The sign-in finished without a code — try again"
            }
        }
    }

    private var session: ASWebAuthenticationSession?

    func run(server: URL, deviceName: String) async throws -> String {
        var components = URLComponents(url: server.appending(path: "api/auth/device/start"),
                                       resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "name", value: deviceName)]
        let start = components.url!

        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: start, callback: .customScheme("pitpass")) { url, error in
                if let error {
                    let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                    continuation.resume(throwing: cancelled ? Error.cancelled : error)
                    return
                }
                let code = url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false) }?
                    .queryItems?.first(where: { $0.name == "code" })?.value
                if let code, !code.isEmpty {
                    continuation.resume(returning: code)
                } else {
                    continuation.resume(throwing: Error.noCode)
                }
            }
            session.prefersEphemeralWebBrowserSession = true
            session.presentationContextProvider = self
            self.session = session
            if !session.start() {
                continuation.resume(throwing: Error.noCode)
            }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
