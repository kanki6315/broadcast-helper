import Foundation

// The scratchpad's wire format and local mirror — ports of
// frontend/src/lib/scratchpad.ts and scratchpadStore.ts. The pad's
// coordinate space is a fixed 800-wide logical column (y grows to
// pageHeight); a stroke's points are a flat [x0,y0,x1,y1,…] rounded to a
// tenth of a logical px. The desktop web pad reads iPad ink and vice versa,
// so nothing here may drift from the TypeScript.

/// One pen stroke as the server stores it (opaque JSONB on the backend).
struct Stroke: Codable, Sendable, Equatable {
    /// Client-generated, unique per stroke; absent on pads persisted before
    /// offline writes shipped.
    var id: String?
    /// Only "pen" exists today.
    var tool: String
    var color: String
    var size: Double
    var points: [Double]

    var pointCount: Int { points.count / 2 }
}

enum Pad {
    static let width = 800.0
    /// Points closer than this to the last kept one are dropped — a 30–60%
    /// payload cut with no visual difference at pen widths ≥ 2.
    static let minPointGap = 1.5
    static let eraserRadius = 12.0
    static let defaultPageHeight = 2000
    static let pageExtendStep = 1000
    static let maxPageHeight = 50000
    static let saveDebounce: Duration = .milliseconds(2500)

    /// Compact unique stroke id (~11 chars beats a 36-char UUID against the
    /// pad's 2 MB serialized cap) — same construction as the web.
    static func newStrokeId() -> String {
        let millis = Int(Date.now.timeIntervalSince1970 * 1000)
        let random = (0..<6).map { _ in "0123456789abcdefghijklmnopqrstuvwxyz".randomElement()! }
        return String(millis, radix: 36) + String(random)
    }

    /// Tenth-of-a-px precision: whole-px rounding staircases slow handwriting.
    static func round(_ v: Double) -> Double { (v * 10).rounded() / 10 }

    /// Append a point unless it's within `minPointGap` of the last kept one.
    @discardableResult
    static func thinAppend(_ points: inout [Double], x: Double, y: Double) -> Bool {
        let n = points.count
        if n >= 2 {
            let dx = x - points[n - 2]
            let dy = y - points[n - 1]
            if dx * dx + dy * dy < minPointGap * minPointGap { return false }
        }
        points.append(x)
        points.append(y)
        return true
    }

    static func clampedHeight(_ h: Int) -> Int { min(maxPageHeight, max(500, h)) }
}

// MARK: wire

/// `GET /api/events/{id}/scratchpad`.
struct PadResponse: Codable, Sendable {
    let eventId: Int
    let revision: Int
    let pageHeight: Int
    let strokes: [Stroke]
}

struct PadSaveRequest: Encodable, Sendable {
    let baseRevision: Int
    let pageHeight: Int
    let strokes: [Stroke]
}

struct PadSaveResponse: Decodable, Sendable {
    let revision: Int
}

// MARK: local mirror

/// One-slot stash of whichever copy lost a conflict choice — nothing is ever
/// destroyed, but only the most recent loser is kept.
struct PadBackup: Codable, Sendable, Equatable {
    let strokes: [Stroke]
    let pageHeight: Int
    /// Milliseconds since 1970, like the web record.
    let savedAt: Double
    /// "replaced-by-other" | "overwritten-by-this-device"
    let reason: String
}

/// The device's copy of one (event, owner) pad. `dirty` = ink the server
/// hasn't accepted; `conflict` = the server moved on while we were dirty and
/// a person has to pick a side.
struct LocalPad: Codable, Sendable, Equatable {
    let eventId: Int
    let owner: String
    var strokes: [Stroke]
    var pageHeight: Int
    /// Server revision this local state builds on (what PUT sends as baseRevision).
    var baseRevision: Int
    var dirty: Bool
    var conflict: Bool
    var updatedAt: Double
    var backup: PadBackup?

    static func key(eventId: Int, owner: String) -> String { "\(eventId) \(owner)" }
    var key: String { LocalPad.key(eventId: eventId, owner: owner) }
}

extension OfflineStore {
    func loadPad(eventId: Int, owner: String) async -> LocalPad? {
        guard let data = await loadPadBody(LocalPad.key(eventId: eventId, owner: owner)) else { return nil }
        return try? JSONDecoder().decode(LocalPad.self, from: data)
    }

    func savePad(_ pad: LocalPad) async {
        guard let data = try? JSONEncoder().encode(pad) else { return }
        await savePadBody(pad.key, body: data)
    }

    func allPads() async -> [LocalPad] {
        await allPadBodies().compactMap { try? JSONDecoder().decode(LocalPad.self, from: $0) }
    }
}
