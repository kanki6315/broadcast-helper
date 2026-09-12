import Foundation

/// Which Pit Pass backend this iPad talks to. Production by default; the
/// Settings screen can point it at a local dev server (`http://localhost:8731`,
/// which runs with auth off).
enum ServerConfig {
    static let production = URL(string: "https://pitpass.arjunakankipati.com")!
    static let localDev = URL(string: "http://localhost:8731")!

    private static let key = "pitpass.serverURL"

    static var current: URL {
        get {
            guard let raw = UserDefaults.standard.string(forKey: key), let url = URL(string: raw) else {
                return production
            }
            return url
        }
        set { UserDefaults.standard.set(newValue.absoluteString, forKey: key) }
    }

    /// Accepts what a person types: adds https:// when missing, drops a trailing slash.
    static func parse(_ text: String) -> URL? {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if !trimmed.contains("://") { trimmed = "https://" + trimmed }
        while trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard let url = URL(string: trimmed), let scheme = url.scheme, ["http", "https"].contains(scheme),
              url.host() != nil else { return nil }
        return url
    }
}
