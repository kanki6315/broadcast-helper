import Foundation
import Testing
@testable import PitPass

struct OfflineStoreTests {
    private func temporaryStore() -> OfflineStore {
        let url = FileManager.default.temporaryDirectory
            .appending(path: "pitpass-test-\(UUID().uuidString).sqlite")
        return OfflineStore(url: url)
    }

    @Test func roundTripsBodyEtagAndStamp() async {
        let store = temporaryStore()
        let when = Date(timeIntervalSince1970: 1_700_000_000)
        await store.save("/api/series", etag: "W/\"abc\"", body: Data("[1,2]".utf8), at: when)

        let entry = await store.load("/api/series")
        #expect(entry?.etag == "W/\"abc\"")
        #expect(entry?.body == Data("[1,2]".utf8))
        #expect(entry?.fetchedAt == when)
        #expect(await store.load("/api/other") == nil)
    }

    @Test func saveReplacesAndTouchMovesOnlyTheStamp() async {
        let store = temporaryStore()
        await store.save("/p", etag: nil, body: Data("a".utf8), at: Date(timeIntervalSince1970: 1))
        await store.save("/p", etag: "e2", body: Data("b".utf8), at: Date(timeIntervalSince1970: 2))
        #expect(await store.load("/p")?.body == Data("b".utf8))
        #expect(await store.load("/p")?.etag == "e2")

        await store.touch("/p", at: Date(timeIntervalSince1970: 3))
        let entry = await store.load("/p")
        #expect(entry?.fetchedAt == Date(timeIntervalSince1970: 3))
        #expect(entry?.body == Data("b".utf8))
    }

    @Test func statsAndRemoveAll() async {
        let store = temporaryStore()
        await store.save("/a", etag: nil, body: Data(repeating: 0, count: 10))
        await store.save("/b", etag: nil, body: Data(repeating: 0, count: 5))
        #expect(await store.stats() == OfflineStore.Stats(entries: 2, bytes: 15))
        await store.removeAll()
        #expect(await store.stats() == OfflineStore.Stats(entries: 0, bytes: 0))
    }

    @Test func unavailableStorageDegradesToNoCache() async {
        let store = OfflineStore(url: nil)
        await store.save("/a", etag: nil, body: Data("x".utf8))
        #expect(await store.load("/a") == nil)
        #expect(await store.stats() == OfflineStore.Stats(entries: 0, bytes: 0))
    }
}
