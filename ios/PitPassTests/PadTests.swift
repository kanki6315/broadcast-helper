import Foundation
import PencilKit
import Testing
@testable import PitPass

struct PadDocumentTests {
    @Test func strokesRoundTripTheWebJsonWithTenths() throws {
        let json = ##"[{"id":"m1abc","tool":"pen","color":"#dc2626","size":4,"points":[10,20.5,11.3,22]}]"##
        let strokes = try JSONDecoder().decode([Stroke].self, from: Data(json.utf8))
        #expect(strokes[0].size == 4)
        #expect(strokes[0].points == [10, 20.5, 11.3, 22])
        let out = String(decoding: try JSONEncoder().encode(strokes), as: UTF8.self)
        #expect(out.contains("\"size\":4"), "whole numbers stay integers on the wire: \(out)")
        #expect(out.contains("20.5"))
        #expect(out.contains("11.3"))
    }

    @Test func thinAppendDropsNearPointsAndRoundsToTenths() {
        var points: [Double] = []
        #expect(Pad.thinAppend(&points, x: 1, y: 1))
        #expect(!Pad.thinAppend(&points, x: 1.5, y: 1.5), "within 1.5px of the last kept point")
        #expect(Pad.thinAppend(&points, x: 3, y: 1))
        #expect(points == [1, 1, 3, 1])
        #expect(Pad.round(12.3456) == 12.3)
        #expect(Pad.newStrokeId().count >= 11)
    }

    @Test func localPadMirrorRoundTrips() async {
        let url = FileManager.default.temporaryDirectory.appending(path: "pad-\(UUID().uuidString).sqlite")
        let store = OfflineStore(url: url)
        let pad = LocalPad(eventId: 7, owner: "a@b", strokes: [Stroke(id: "x", tool: "pen", color: "#111827", size: 2, points: [1, 2])],
                           pageHeight: 3000, baseRevision: 4, dirty: true, conflict: false, updatedAt: 1, backup: nil)
        await store.savePad(pad)
        #expect(await store.loadPad(eventId: 7, owner: "a@b") == pad)
        #expect(await store.allPads() == [pad])
        await store.removeAll()
        #expect(await store.allPads() == [pad], "clearing documents keeps possibly-unsynced ink")
        await store.removeAll(includingPads: true)
        #expect(await store.allPads().isEmpty)
    }
}

@MainActor
struct PadBridgeTests {
    private let web = [
        Stroke(id: "w1", tool: "pen", color: "#2563eb", size: 4, points: [10, 10, 20.5, 30.2, 40, 50]),
        Stroke(id: "w2", tool: "pen", color: "#111827", size: 2, points: [100, 100]),
    ]

    @Test func desktopInkSurvivesTheRoundTripUntouched() {
        var bridge = PadBridge()
        let drawing = bridge.drawing(for: web)
        #expect(drawing.strokes.count == 2)
        #expect(drawing.strokes[0].ink.inkType == .monoline)
        #expect(drawing.strokes[0].path.count > 3, "the web's curve is sampled densely for PencilKit's B-spline")
        #expect(drawing.strokes[1].path.count == 2, "a tap becomes a short stroke so PencilKit draws the dot")
        let traced = PadBridge.trace(web[0])
        #expect(traced.first == CGPoint(x: 10, y: 10))
        #expect(traced.last == CGPoint(x: 40, y: 50))

        let back = bridge.strokes(from: drawing, pageHeight: 2000)
        #expect(back == web, "the same objects come back — no re-sampling")
    }

    @Test func aPencilStrokeIsExportedThinnedRoundedAndKeepsItsIdAfterwards() {
        var bridge = PadBridge()
        var drawing = bridge.drawing(for: web)
        let size = CGSize(width: 10, height: 10) // PencilKit stores width + 2
        let samples = [(0.0, 0.0), (0.4, 0.3), (5.123, 5.987), (900.0, 2500.0)]
        let points = samples.enumerated().map { i, s in
            PKStrokePoint(location: CGPoint(x: s.0, y: s.1), timeOffset: Double(i) * 0.01, size: size,
                          opacity: 1, force: 1, azimuth: 0, altitude: .pi / 2)
        }
        let pencil = PKStroke(ink: PKInk(.monoline, color: UIColor(hex: "#16a34a")!),
                              path: PKStrokePath(controlPoints: points, creationDate: .now))
        drawing.strokes.append(pencil)

        let first = bridge.strokes(from: drawing, pageHeight: 2000)
        #expect(first.count == 3)
        #expect(Array(first.prefix(2)) == web)
        let new = first[2]
        #expect(new.tool == "pen")
        #expect(new.color == "#16a34a")
        #expect(new.size == 8)
        #expect(new.points == [0, 0, 5.1, 6, 800, 2000], "thinned to 1.5px, tenths, clamped to the page")
        #expect(new.id != nil)

        // The same PencilKit stroke handed back again keeps its id.
        let second = bridge.strokes(from: drawing, pageHeight: 2000)
        #expect(second == first)

        // Erasing the first web stroke drops just it; undoing brings back
        // the original, not a re-sampled copy.
        let removed = drawing.strokes.remove(at: 0)
        let erased = bridge.strokes(from: drawing, pageHeight: 2000)
        #expect(erased == [web[1], new])
        drawing.strokes.insert(removed, at: 0)
        #expect(bridge.strokes(from: drawing, pageHeight: 2000) == [web[0], web[1], new])
    }

    @Test func colorsRoundTripAsHex() {
        #expect(UIColor(hex: "#dc2626")?.hexString == "#dc2626")
        #expect(UIColor(hex: "dc2626")?.hexString == "#dc2626")
        #expect(UIColor(hex: "#zz0000") == nil)
    }
}

struct PadSyncerTests {
    private func makeParts(_ transport: RoutedTransport) -> (APIClient, OfflineStore) {
        let client = APIClient(baseURL: URL(string: "https://example.test")!, token: { "tok" }, transport: transport)
        let url = FileManager.default.temporaryDirectory.appending(path: "sync-\(UUID().uuidString).sqlite")
        return (client, OfflineStore(url: url))
    }

    private func pad(_ eventId: Int, dirty: Bool = true, conflict: Bool = false) -> LocalPad {
        LocalPad(eventId: eventId, owner: "a@b", strokes: [], pageHeight: 2000, baseRevision: 3,
                 dirty: dirty, conflict: conflict, updatedAt: 0, backup: nil)
    }

    @Test @MainActor func dirtyPadsArePutAndConflictsAreFlaggedNotResolved() async {
        let transport = RoutedTransport([
            "/api/events/1/scratchpad": .ok(#"{"revision":4,"updatedAt":"2026-09-12T10:00:00Z"}"#),
            "/api/events/2/scratchpad": .init(status: 409, body: Data(#"{"message":"moved on"}"#.utf8), headers: [:]),
        ])
        let (client, store) = makeParts(transport)
        await store.savePad(pad(1))
        await store.savePad(pad(2))
        await store.savePad(pad(3, dirty: false))
        let syncer = PadSyncer()
        await syncer.start(store: store, client: client, connectivity: Connectivity())

        let one = await store.loadPad(eventId: 1, owner: "a@b")
        #expect(one?.dirty == false)
        #expect(one?.baseRevision == 4)
        #expect(syncer.attention(eventId: 1, owner: "a@b") == nil)
        let two = await store.loadPad(eventId: 2, owner: "a@b")
        #expect(two?.dirty == true)
        #expect(two?.conflict == true)
        #expect(syncer.attention(eventId: 2, owner: "a@b") == .conflict)
        #expect(await transport.count("/api/events/3/scratchpad") == 0, "clean pads are never sent")
    }

    @Test @MainActor func anOpenPadIsLeftToItsModel() async {
        let transport = RoutedTransport(["/api/events/1/scratchpad": .ok(#"{"revision":4}"#)])
        let (client, store) = makeParts(transport)
        await store.savePad(pad(1))
        let syncer = PadSyncer()
        syncer.registerActive(eventId: 1, owner: "a@b")
        await syncer.start(store: store, client: client, connectivity: Connectivity())
        #expect(await transport.count("/api/events/1/scratchpad") == 0)
        #expect(syncer.attention(eventId: 1, owner: "a@b") == .dirty)
    }
}
