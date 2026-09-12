import Foundation
import Observation
import UIKit

/// App-wide state: the server, who is signed in, and the shared data plumbing
/// (API client, offline store, connectivity heartbeat). One instance lives in
/// the SwiftUI environment.
///
/// Sign-in mirrors the web app's contract: `/api/me` is public and says
/// whether auth is on and who the caller is. With a device token attached, a
/// null email means the token is gone (revoked from Manage → Sessions), which
/// is the only "signed out" signal the server ever sends — bearer requests
/// never get a redirect.
@MainActor
@Observable
final class AppSession {
    enum Phase: Equatable {
        case checking
        case signedOut(reason: String?)
        case ready(Me)
    }

    private(set) var phase: Phase = .checking
    private(set) var serverURL: URL
    private(set) var client: APIClient
    private(set) var loader: DataLoader
    let store: OfflineStore
    let connectivity = Connectivity()
    let freshness = Freshness()
    /// "Download this event / season" jobs and what they last completed.
    let downloads = DownloadManager()
    var theme: ThemePreference = ThemePreference.stored {
        didSet { ThemePreference.stored = theme }
    }
    private(set) var isSigningIn = false
    var signInError: String?

    init() {
        let url = ServerConfig.current
        let store = OfflineStore.open()
        let client = AppSession.makeClient(url)
        serverURL = url
        self.store = store
        self.client = client
        loader = DataLoader(client: client, store: store)
    }

    private static func makeClient(_ url: URL) -> APIClient {
        APIClient(baseURL: url, token: { Keychain.deviceToken })
    }

    /// Point the app at another server. Drops the token and cache: they belong
    /// to the old one.
    func setServer(_ url: URL) async {
        guard url != serverURL else { return }
        ServerConfig.current = url
        serverURL = url
        Keychain.deviceToken = nil
        await clearOfflineData()
        client = AppSession.makeClient(url)
        loader = DataLoader(client: client, store: store)
        await bootstrap()
    }

    /// Wipe the store and everything that describes it.
    func clearOfflineData() async {
        downloads.forget()
        await store.removeAll()
    }

    func bootstrap() async {
        phase = .checking
        connectivity.start(client: client)
        await downloads.load(from: store)
        do {
            let me: Loaded<Me> = try await loader.networkFirst("/api/me")
            resolve(me.value, fromCache: me.fromCache)
        } catch {
            phase = .signedOut(reason: "Can't reach \(serverURL.host() ?? "the server"): \(error.localizedDescription)")
        }
    }

    private func resolve(_ me: Me, fromCache: Bool) {
        if !me.authEnabled || me.email != nil {
            phase = .ready(me)
        } else if Keychain.deviceToken != nil, !fromCache {
            Keychain.deviceToken = nil
            phase = .signedOut(reason: "This iPad's sign-in was revoked. Sign in again.")
        } else {
            phase = .signedOut(reason: nil)
        }
    }

    /// The whole native login: Google in a system browser sheet → one-time
    /// code → device token in the Keychain → `/api/me` again.
    func signIn() async {
        guard !isSigningIn else { return }
        isSigningIn = true
        signInError = nil
        defer { isSigningIn = false }
        do {
            let code = try await WebSignIn().run(server: serverURL, deviceName: UIDevice.current.name)
            let issued: DeviceIssued = try await client.postJSON("/api/auth/device/exchange",
                                                                 body: ["code": code])
            Keychain.deviceToken = issued.token
            await bootstrap()
        } catch WebSignIn.Error.cancelled {
            // The person closed the sheet; nothing to report.
        } catch {
            signInError = error.localizedDescription
        }
    }

    /// Revoke this device's token (best effort) and forget everything local.
    func signOut() async {
        try? await client.send("DELETE", "/api/auth/device")
        Keychain.deviceToken = nil
        await clearOfflineData()
        await bootstrap()
    }
}
