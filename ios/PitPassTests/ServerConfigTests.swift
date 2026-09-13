import Foundation
import Testing
@testable import PitPass

struct ServerConfigTests {
    @Test func defaultAndOverride() {
        #expect(ServerConfig.resolve(environment: [:]) == ServerConfig.defaultURL)
        #expect(ServerConfig.resolve(environment: ["PITPASS_SERVER_URL": ""]) == ServerConfig.defaultURL)
        let override = ServerConfig.resolve(environment: ["PITPASS_SERVER_URL": "http://localhost:8731/"])
        #if DEBUG
        #expect(override.absoluteString == "http://localhost:8731")
        #else
        #expect(override == ServerConfig.production)
        #expect(ServerConfig.resolve(environment: ["PITPASS_SERVER_URL": "invalid"]) == ServerConfig.production)
        #endif
    }

    @Test func platformDefault() {
        #if DEBUG && targetEnvironment(simulator)
        #expect(ServerConfig.defaultURL == ServerConfig.local)
        #else
        #expect(ServerConfig.defaultURL == ServerConfig.production)
        #endif
    }

    @Test func buildOverrideAndLaunchPrecedence() {
        let built = "http://localhost:8732"
        let launch = ["PITPASS_SERVER_URL": "http://localhost:8733"]
        #if DEBUG
        #expect(ServerConfig.resolve(environment: [:], builtServerURL: built).absoluteString == built)
        #expect(ServerConfig.resolve(environment: launch, builtServerURL: built).absoluteString == "http://localhost:8733")
        #expect(ServerConfig.resolve(environment: ["PITPASS_SERVER_URL": " "], builtServerURL: built).absoluteString == built)
        #expect(ServerConfig.resolve(environment: [:], builtServerURL: " ") == ServerConfig.defaultURL)
        #expect(ServerConfig.resolve(environment: ["PITPASS_SERVER_URL": ServerConfig.production.absoluteString],
                                     builtServerURL: built) == ServerConfig.production)
        #else
        #expect(ServerConfig.resolve(environment: launch, builtServerURL: built) == ServerConfig.production)
        #expect(ServerConfig.resolve(environment: [:], builtServerURL: "invalid") == ServerConfig.production)
        #endif
    }

    @Test func originsOnly() {
        #expect(ServerConfig.parse(" http://My-Mac.local:8731/ ")?.absoluteString == "http://my-mac.local:8731")
        for raw in ["", "localhost:8731", "ftp://example.com", "https://example.com/api",
                    "https://user:secret@example.com", "https://example.com?x=1",
                    "https://example.com#fragment", "http://localhost:99999"] {
            #expect(ServerConfig.parse(raw) == nil)
        }
    }

    @Test func migrationAndEndpointChanges() throws {
        let name = "ServerConfigTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let local = try #require(URL(string: "http://localhost:8731"))
        #expect(!ServerConfig.needsReset(for: ServerConfig.production, defaults: defaults))
        #expect(ServerConfig.needsReset(for: local, defaults: defaults))
        defaults.set(local.absoluteString, forKey: "pitpass.serverURL")
        #expect(ServerConfig.needsReset(for: ServerConfig.production, defaults: defaults))
        #expect(!ServerConfig.needsReset(for: local, defaults: defaults))
        ServerConfig.recordServer(ServerConfig.production, defaults: defaults)
        #expect(defaults.string(forKey: "pitpass.serverURL") == nil)
        #expect(!ServerConfig.needsReset(for: ServerConfig.production, defaults: defaults))
        #expect(ServerConfig.needsReset(for: local, defaults: defaults))
    }
}
