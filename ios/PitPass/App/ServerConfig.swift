import Foundation

/// Release always uses production. Debug simulators default to the local backend.
enum ServerConfig {
    static let production = URL(string: "https://pitpass.arjunakankipati.com")!

    static let local = URL(string: "http://localhost:8731")!

    static var current: URL {
        resolve(environment: ProcessInfo.processInfo.environment,
                builtServerURL: Bundle.main.object(forInfoDictionaryKey: "PitPassServerURL") as? String)
    }

    static var defaultURL: URL {
        #if DEBUG && targetEnvironment(simulator)
        return local
        #else
        return production
        #endif
    }

    static func resolve(environment: [String: String], builtServerURL: String? = nil) -> URL {
        #if DEBUG
        // A launch override wins; the bundled setting survives ordinary relaunches.
        for raw in [environment["PITPASS_SERVER_URL"], builtServerURL].compactMap({ $0 }) {
            if raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            guard let url = parse(raw) else {
                fatalError("PITPASS_SERVER_URL must be an http(s) origin, e.g. http://localhost:8731")
            }
            return url
        }
        #endif
        return defaultURL
    }

    /// API paths are absolute: endpoints must be origins, without credentials or query strings.
    static func parse(_ text: String) -> URL? {
        let raw = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var parts = URLComponents(string: raw),
              let scheme = parts.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.path.isEmpty || parts.path == "/",
              parts.port == nil || (1...65535).contains(parts.port!) else { return nil }
        parts.scheme = scheme
        parts.host = host.lowercased()
        parts.path = ""
        return parts.url
    }

    /// The legacy preference is consulted only to identify data from an old server.
    static func needsReset(for url: URL, defaults: UserDefaults = .standard) -> Bool {
        let previous = defaults.string(forKey: "pitpass.lastServerURL")
            ?? defaults.string(forKey: "pitpass.serverURL")
            ?? production.absoluteString
        return parse(previous) != url
    }

    static func recordServer(_ url: URL, defaults: UserDefaults = .standard) {
        defaults.set(url.absoluteString, forKey: "pitpass.lastServerURL")
        defaults.removeObject(forKey: "pitpass.serverURL")
    }
}
