import Foundation
import Testing
@testable import PitPass

/// A scripted transport: each call pops the next response; records requests.
actor ScriptedTransport: HTTPTransport {
    struct Scripted: Sendable {
        let status: Int
        let body: Data
        let headers: [String: String]

        static func ok(_ json: String, etag: String? = nil) -> Scripted {
            Scripted(status: 200, body: Data(json.utf8), headers: etag.map { ["ETag": $0] } ?? [:])
        }

        static let notModified = Scripted(status: 304, body: Data(), headers: [:])
    }

    private var queue: [Scripted]
    private(set) var requests: [URLRequest] = []

    init(_ responses: [Scripted]) {
        queue = responses
    }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        requests.append(request)
        guard !queue.isEmpty else { throw URLError(.notConnectedToInternet) }
        let next = queue.removeFirst()
        let response = HTTPURLResponse(url: request.url!, statusCode: next.status, httpVersion: nil,
                                       headerFields: next.headers)!
        return (next.body, response)
    }

    func header(_ index: Int, _ name: String) -> String? {
        requests[index].value(forHTTPHeaderField: name)
    }
}

struct DataLoaderTests {
    private func makeLoader(_ transport: ScriptedTransport, token: String? = "tok") -> DataLoader {
        let client = APIClient(baseURL: URL(string: "https://example.test")!, token: { token }, transport: transport)
        let url = FileManager.default.temporaryDirectory.appending(path: "loader-\(UUID().uuidString).sqlite")
        return DataLoader(client: client, store: OfflineStore(url: url))
    }

    @Test func firstFetchStoresAndSendsBearer() async throws {
        let transport = ScriptedTransport([.ok("[1]", etag: "W/\"v1\"")])
        let loader = makeLoader(transport)

        let result: DataLoader.Refresh<[Int]> = try await loader.refresh("/api/x")
        guard case let .updated(loaded) = result else { Issue.record("expected updated"); return }
        #expect(loaded.value == [1])
        #expect(!loaded.fromCache)
        #expect(await transport.header(0, "Authorization") == "Bearer tok")
        #expect(await transport.header(0, "If-None-Match") == nil)

        let cached: Loaded<[Int]>? = await loader.cached("/api/x")
        #expect(cached?.value == [1])
        #expect(cached?.fromCache == true)
    }

    @Test func revalidationSendsEtagAndA304IsUnchanged() async throws {
        let transport = ScriptedTransport([.ok("[1]", etag: "W/\"v1\""), .notModified])
        let loader = makeLoader(transport)
        _ = try await loader.refresh("/api/x", as: [Int].self)

        let result: DataLoader.Refresh<[Int]> = try await loader.refresh("/api/x")
        guard case let .unchanged(loaded) = result else { Issue.record("expected unchanged"); return }
        #expect(loaded.value == [1])
        #expect(await transport.header(1, "If-None-Match") == "W/\"v1\"")
    }

    @Test func identicalBodyWithoutEtagIsUnchangedButAChangedBodyIsUpdated() async throws {
        let transport = ScriptedTransport([.ok("[1]"), .ok("[1]"), .ok("[2]")])
        let loader = makeLoader(transport)
        _ = try await loader.refresh("/api/x", as: [Int].self)

        guard case .unchanged = try await loader.refresh("/api/x", as: [Int].self) else {
            Issue.record("same bytes should read as unchanged"); return
        }
        guard case let .updated(loaded) = try await loader.refresh("/api/x", as: [Int].self) else {
            Issue.record("new bytes should read as updated"); return
        }
        #expect(loaded.value == [2])
    }

    @Test func networkFirstFallsBackToTheStoreOffline() async throws {
        let transport = ScriptedTransport([.ok("{\"authEnabled\":true,\"email\":\"a@b\",\"isAdmin\":false}")])
        let loader = makeLoader(transport)
        let live: Loaded<Me> = try await loader.networkFirst("/api/me")
        #expect(!live.fromCache)

        // Script exhausted → transport error → served from disk.
        let offline: Loaded<Me> = try await loader.networkFirst("/api/me")
        #expect(offline.fromCache)
        #expect(offline.value.email == "a@b")

        await #expect(throws: LoadError.self) {
            let _: Loaded<Me> = try await loader.networkFirst("/api/never-seen")
        }
    }

    @Test func unauthorizedIsNotMaskedByTheCache() async throws {
        let transport = ScriptedTransport([.ok("[1]"), .init(status: 401, body: Data(), headers: [:])])
        let loader = makeLoader(transport)
        _ = try await loader.refresh("/api/x", as: [Int].self)
        await #expect(throws: APIError.unauthorized) {
            let _: Loaded<[Int]> = try await loader.networkFirst("/api/x")
        }
    }
}
