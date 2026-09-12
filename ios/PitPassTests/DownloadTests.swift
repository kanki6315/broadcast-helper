import Foundation
import os
import Testing
@testable import PitPass

/// A transport that answers by path (query included), so a job fetching four
/// at a time gets deterministic answers. Unknown paths are 404s; `offline`
/// makes every request fail at the socket.
actor RoutedTransport: HTTPTransport {
    private var routes: [String: ScriptedTransport.Scripted]
    private(set) var requested: [String] = []
    var offline = false

    init(_ routes: [String: ScriptedTransport.Scripted]) {
        self.routes = routes
    }

    func setOffline(_ value: Bool) { offline = value }

    func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        let url = request.url!
        let key = url.path() + (url.query().map { "?\($0)" } ?? "")
        requested.append(key)
        if offline { throw URLError(.notConnectedToInternet) }
        let next = routes[key] ?? .init(status: 404, body: Data("{\"message\":\"nope\"}".utf8), headers: [:])
        let response = HTTPURLResponse(url: url, statusCode: next.status, httpVersion: nil, headerFields: next.headers)!
        return (next.body, response)
    }

    func count(_ path: String) -> Int { requested.filter { $0 == path }.count }
}

private enum Fixtures {
    static let sheet = """
    {"eventId":7,"seasonId":3,"eventName":"Road America","circuitName":"Road America","eventDate":"2026-08-02",
     "year":2026,"roundOrdinal":6,"seriesName":"IMSA WeatherTech SportsCar Championship","championshipLabel":"Championship",
     "priorYearLabel":"2025","teamSheetsVersion":4,"pitAssignmentsVersion":2,"storylinesVersion":null,"formRounds":[],
     "classes":[{"className":"GTP","color":"#000","entries":[
       {"entryId":10,"carNumber":"7","teamName":"Porsche Penske","vehicle":null,"manufacturer":"Porsche","manufacturerLogoVersion":3,
        "manufacturerLogoInvert":false,"isGuest":false,"drivers":[],"qualifying":null,"startingDriver":null,"championship":null,
        "form":{},"priorYearNote":null,"priorYearAuto":false,"imageVersion":11,"teamSheetPage":2},
       {"entryId":12,"carNumber":"6","teamName":"Porsche Penske","vehicle":null,"manufacturer":"Porsche","manufacturerLogoVersion":3,
        "manufacturerLogoInvert":false,"isGuest":false,"drivers":[],"qualifying":null,"startingDriver":null,"championship":null,
        "form":{},"priorYearNote":null,"priorYearAuto":false,"imageVersion":null,"teamSheetPage":3},
       {"entryId":14,"carNumber":"31","teamName":"Action Express","vehicle":null,"manufacturer":"Cadillac","manufacturerLogoVersion":null,
        "manufacturerLogoInvert":false,"isGuest":true,"drivers":[],"qualifying":null,"startingDriver":null,"championship":null,
        "form":{},"priorYearNote":null,"priorYearAuto":false,"imageVersion":9,"teamSheetPage":null}]}]}
    """

    static let results = """
    {"eventId":7,"eventName":"Road America","circuitName":null,"eventDate":null,"roundOrdinal":6,"seasonId":3,"year":2026,
     "seriesName":"IMSA","sessions":[
       {"sessionId":70,"sessionType":"QUALIFYING","name":"Qualifying","reportMark":null,"notes":[],"hasFlags":false,"gridBasis":null,"results":[],"grid":[]},
       {"sessionId":71,"sessionType":"RACE","name":"Race","reportMark":null,"notes":[],"hasFlags":true,"gridBasis":null,"results":[],"grid":[]}]}
    """

    static let hub = """
    {"id":3,"year":2026,"seriesId":1,"seriesName":"IMSA WeatherTech SportsCar Championship","primaryKind":"DRIVERS","kind":"MAIN","label":null,
     "events":[{"id":7,"name":"Road America","circuitName":null,"eventDate":null,"roundOrdinal":6,"entryCount":30,"sessionCount":2},
               {"id":8,"name":"Indianapolis","circuitName":null,"eventDate":null,"roundOrdinal":7,"entryCount":0,"sessionCount":0}],
     "championships":[{"id":100,"title":"GTP Drivers","groupTitle":null,"className":"GTP","kind":"DRIVERS","kindLabel":null,"isCup":false,"year":2026,"seasonId":3,"seriesName":"IMSA","rowCount":12},
                      {"id":101,"title":"GTP Teams","groupTitle":null,"className":"GTP","kind":"TEAMS","kindLabel":null,"isCup":false,"year":2026,"seasonId":3,"seriesName":"IMSA","rowCount":0}],
     "entryClasses":["GTP"]}
    """

    static let images = """
    {"images":[{"id":500,"carNumber":"7","sourceFilename":"7.jpg","uploadedAt":"2026-07-01T10:00:00Z"}]}
    """

    static func decode<T: Decodable>(_ json: String) throws -> T {
        try JSONDecoder.api.decode(T.self, from: Data(json.utf8))
    }
}

struct DownloadPlanTests {
    @Test func sheetAssetsNamePitLanePdfsPhotosAndEachMarkOnce() throws {
        let sheet: Sheet = try Fixtures.decode(Fixtures.sheet)
        let assets = DownloadPlan.sheetAssets(sheet)
        #expect(assets == [
            .document("/api/events/7/pit-assignments"),
            .binary("/api/events/7/team-sheets/data?v=4"),
            .binary("/api/entries/10/image?variant=sheet&v=11"),
            .binary("/api/manufacturer-logos/porsche/data?v=3"),
            .binary("/api/entries/14/image?variant=sheet&v=9"),
        ])
    }

    @Test func seasonDocumentsCoverEveryPageAndSkipEmptyRoundsAndChampionships() throws {
        let hub: SeasonHub = try Fixtures.decode(Fixtures.hub)
        let docs = DownloadPlan.seasonDocuments(hub).map(\.path)
        #expect(docs.contains("/api/seasons/3/reference"))
        #expect(docs.contains("/api/seasons/3/lineups"))
        #expect(docs.contains("/api/seasons/3/stats"))
        #expect(docs.contains("/api/series/1/team-stats"))
        #expect(docs.contains("/api/car-images?seasonId=3"))
        #expect(docs.contains("/api/events/7/results"))
        #expect(!docs.contains("/api/events/8/results"), "a round with no sessions has no results document")
        #expect(docs.contains("/api/championships/100/recap"))
        #expect(!docs.contains("/api/championships/101/recap"), "an empty championship has no grid")
    }

    @Test func flagsOnlyForSessionsThatHaveThem() throws {
        let results: EventResults = try Fixtures.decode(Fixtures.results)
        #expect(DownloadPlan.flagDocuments(results) == [.document("/api/sessions/71/flags")])
    }
}

struct PrefetchJobTests {
    private func makeLoader(_ transport: RoutedTransport) -> DataLoader {
        let client = APIClient(baseURL: URL(string: "https://example.test")!, token: { "tok" }, transport: transport)
        let url = FileManager.default.temporaryDirectory.appending(path: "download-\(UUID().uuidString).sqlite")
        return DataLoader(client: client, store: OfflineStore(url: url))
    }

    private func eventRoutes() -> [String: ScriptedTransport.Scripted] {
        [
            "/api/events/7/sheet": .ok(Fixtures.sheet, etag: "W/\"s1\""),
            "/api/events/7/pit-assignments": .ok("{}"),
            "/api/events/7/team-sheets/data?v=4": .ok("%PDF-1.4"),
            "/api/entries/10/image?variant=sheet&v=11": .ok("jpeg"),
            "/api/manufacturer-logos/porsche/data?v=3": .ok("<svg/>"),
            // /api/entries/14/image is missing on purpose → 404
            "/api/events/7/results": .ok(Fixtures.results),
            "/api/sessions/71/flags": .ok("[]"),
            "/api/seasons/3": .ok(Fixtures.hub),
            "/api/seasons": .ok("[]"),
            "/api/series/1/class-styles": .ok("{\"styles\":[],\"unconfiguredClasses\":[]}"),
            "/api/championships/100/recap": .ok("{}"),
        ]
    }

    @Test func eventBundleStoresEverythingTheSheetCanOpenAndCountsTheMissing() async throws {
        let transport = RoutedTransport(eventRoutes())
        let loader = makeLoader(transport)
        let reports = OSAllocatedUnfairLock(initialState: [DownloadProgress]())
        let job = PrefetchJob(loader: loader) { p in reports.withLock { $0.append(p) } }

        let outcome = try await job.run(.event(7))

        #expect(outcome.title == "Road America · IMSA WeatherTech SportsCar Championship 2026")
        #expect(outcome.missing == 1)
        #expect(outcome.paths.count == 11)
        for path in ["/api/events/7/sheet", "/api/events/7/pit-assignments", "/api/events/7/team-sheets/data?v=4",
                     "/api/manufacturer-logos/porsche/data?v=3", "/api/sessions/71/flags", "/api/seasons/3",
                     "/api/championships/100/recap"] {
            #expect(await loader.store.contains(path), "expected \(path) in the store")
        }
        #expect(!(await loader.store.contains("/api/entries/14/image?variant=sheet&v=9")))
        // The mark is shared by two cars but fetched once.
        #expect(await transport.count("/api/manufacturer-logos/porsche/data?v=3") == 1)
        // Progress ends complete, with the total only ever growing.
        let seen = reports.withLock { $0 }
        let totals = seen.map(\.total)
        #expect(totals == totals.sorted())
        #expect(seen.last?.done == 12)
        #expect(seen.last?.total == 12)
    }

    @Test func secondRunRevalidatesDocumentsButNeverRefetchesBinaries() async throws {
        let transport = RoutedTransport(eventRoutes())
        let loader = makeLoader(transport)
        _ = try await PrefetchJob(loader: loader) { _ in }.run(.event(7))
        _ = try await PrefetchJob(loader: loader) { _ in }.run(.event(7))

        #expect(await transport.count("/api/events/7/sheet") == 2)
        #expect(await transport.count("/api/events/7/team-sheets/data?v=4") == 1)
        #expect(await transport.count("/api/entries/10/image?variant=sheet&v=11") == 1)
        // The 404'd photo is retried — it may have been uploaded since.
        #expect(await transport.count("/api/entries/14/image?variant=sheet&v=9") == 2)
    }

    @Test func aDroppedConnectionEndsTheJobAndAMissingRootIsItsOwnError() async throws {
        let transport = RoutedTransport(eventRoutes())
        let loader = makeLoader(transport)
        await transport.setOffline(true)
        await #expect(throws: DownloadError.self) {
            _ = try await PrefetchJob(loader: loader) { _ in }.run(.event(7))
        }

        let empty = RoutedTransport([:])
        let job = PrefetchJob(loader: makeLoader(empty)) { _ in }
        await #expect(throws: DownloadError.rootUnavailable("event sheet")) {
            _ = try await job.run(.event(7))
        }
    }

    @Test func seasonBundleReadsChildrenBackFromTheStore() async throws {
        let transport = RoutedTransport([
            "/api/seasons/3": .ok(Fixtures.hub),
            "/api/seasons": .ok("[]"),
            "/api/series/1/class-styles": .ok("{}"),
            "/api/seasons/3/reference": .ok("{}"),
            "/api/seasons/3/lineups": .ok("{}"),
            "/api/seasons/3/stats": .ok("{}"),
            "/api/seasons/3/team-stats": .ok("{}"),
            "/api/series/1/stats": .ok("{}"),
            "/api/series/1/team-stats": .ok("{}"),
            "/api/car-images?seasonId=3": .ok(Fixtures.images),
            "/api/events/7/results": .ok(Fixtures.results),
            "/api/sessions/71/flags": .ok("[]"),
            "/api/championships/100/recap": .ok("{}"),
            "/api/car-images/500/data?variant=sheet&v=2026-07-01T10:00:00Z": .ok("jpeg"),
        ])
        let loader = makeLoader(transport)

        let outcome = try await PrefetchJob(loader: loader) { _ in }.run(.season(3))

        #expect(outcome.title == "IMSA WeatherTech SportsCar Championship 2026")
        #expect(outcome.missing == 0)
        #expect(outcome.paths.count == 14)
        #expect(await loader.store.contains("/api/sessions/71/flags"))
        #expect(await loader.store.contains("/api/car-images/500/data?variant=sheet&v=2026-07-01T10:00:00Z"))
        #expect(await transport.count("/api/events/7/results") == 1, "children come from the store, not a second fetch")
    }

    @Test func aViewNoticesWhenADownloadReplacedItsDocument() async throws {
        // The sheet view adopted v1; a download stores v2; the view's own
        // revalidation gets a 304 against v2's ETag and must not conclude
        // "unchanged" — the digest tells it the store moved on.
        let transport = ScriptedTransport([
            .ok("[1]", etag: "W/\"v1\""),      // the view's first load
            .ok("[2]", etag: "W/\"v2\""),      // the download's refresh
            .notModified,                      // the view's revalidation
        ])
        let client = APIClient(baseURL: URL(string: "https://example.test")!, token: { nil }, transport: transport)
        let url = FileManager.default.temporaryDirectory.appending(path: "digest-\(UUID().uuidString).sqlite")
        let loader = DataLoader(client: client, store: OfflineStore(url: url))

        let resource = await Resource<[Int]>("/api/x")
        await resource.load(loader)
        #expect(await resource.value == [1])
        try await loader.prefetchDocument("/api/x")
        await resource.load(loader)
        #expect(await resource.value == [1], "never a silent re-render")
        #expect(await resource.pendingUpdate?.value == [2])
    }
}
