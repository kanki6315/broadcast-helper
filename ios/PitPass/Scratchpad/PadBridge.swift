import PencilKit
import UIKit

/// PencilKit draws and erases; the web's `[Stroke]` stays the document.
///
/// A `PKStroke` built from a web stroke carries a private creation date as
/// its identity, so when PencilKit hands the drawing back the original
/// `Stroke` (same id, same points, same bytes) is reused for every stroke it
/// didn't touch — a round trip through the iPad never re-samples desktop
/// ink. Only strokes PencilKit created (the pen) or altered are exported
/// from their control points, thinned and rounded exactly like the web pad's
/// pointer samples.
@MainActor
struct PadBridge {
    private struct Known {
        let stroke: Stroke
        let pointCount: Int
    }

    private var known: [Date: Known] = [:]
    private var serial = 0

    // MARK: web → PencilKit

    mutating func drawing(for strokes: [Stroke]) -> PKDrawing {
        known = [:]
        return PKDrawing(strokes: strokes.map { adopt($0) })
    }

    private mutating func adopt(_ stroke: Stroke) -> PKStroke {
        serial += 1
        // Far from any real clock: PencilKit stamps new strokes with "now".
        let key = Date(timeIntervalSinceReferenceDate: Double(serial) * 0.001)
        let size = CGSize(width: stroke.size + PadBridge.sizeOffset, height: stroke.size + PadBridge.sizeOffset)
        let points = PadBridge.trace(stroke).enumerated().map { i, p in
            PadBridge.point(x: p.x, y: p.y, size: size, offset: Double(i) * 0.01)
        }
        known[key] = Known(stroke: stroke, pointCount: points.count)
        let ink = PKInk(.monoline, color: UIColor(hex: stroke.color) ?? .black)
        return PKStroke(ink: ink, path: PKStrokePath(controlPoints: points, creationDate: key))
    }

    /// The web's `traceStroke` curve — quadratics through consecutive
    /// midpoints with the samples as control points — sampled every few px.
    /// PencilKit fits a B-spline *through* its control points, so handing it
    /// the sparse samples would round every corner; handing it the curve
    /// itself makes the two renderers agree.
    static func trace(_ stroke: Stroke) -> [CGPoint] {
        let p = stroke.points
        let n = p.count / 2
        guard n > 0 else { return [] }
        func pt(_ i: Int) -> CGPoint { CGPoint(x: p[2 * i], y: p[2 * i + 1]) }
        if n == 1 {
            // A tap: the web draws a round-cap dot of the pen width.
            let c = pt(0)
            let r = max(0.5, stroke.size / 8)
            return [CGPoint(x: c.x - r, y: c.y), CGPoint(x: c.x + r, y: c.y)]
        }
        if n == 2 { return [pt(0), pt(1)] }
        var out: [CGPoint] = [pt(0)]
        var from = pt(0)
        for j in 1...(n - 2) {
            let control = pt(j)
            let next = pt(j + 1)
            let to = CGPoint(x: (control.x + next.x) / 2, y: (control.y + next.y) / 2)
            let length = hypot(control.x - from.x, control.y - from.y) + hypot(to.x - control.x, to.y - control.y)
            let steps = max(1, Int((length / 3).rounded(.up)))
            for k in 1...steps {
                let t = Double(k) / Double(steps)
                let u = 1 - t
                out.append(CGPoint(x: u * u * from.x + 2 * u * t * control.x + t * t * to.x,
                                   y: u * u * from.y + 2 * u * t * control.y + t * t * to.y))
            }
            from = to
        }
        out.append(pt(n - 1))
        return out
    }

    /// PencilKit's monoline points carry the tool width plus 2 (a 4pt pen
    /// stores 6), measured from strokes it drew itself.
    static let sizeOffset = 2.0

    private static func point(x: Double, y: Double, size: CGSize, offset: TimeInterval) -> PKStrokePoint {
        PKStrokePoint(location: CGPoint(x: x, y: y), timeOffset: offset, size: size,
                      opacity: 1, force: 1, azimuth: 0, altitude: .pi / 2)
    }

    // MARK: PencilKit → web

    /// The document after PencilKit changed the drawing. Untouched strokes
    /// come back as the very objects they were; new ones get fresh ids.
    /// Erased strokes stay known, so an undo brings the original back rather
    /// than a re-sampled copy.
    mutating func strokes(from drawing: PKDrawing, pageHeight: Int) -> [Stroke] {
        var seen = Set<Date>()
        var out: [Stroke] = []
        for pk in drawing.strokes {
            let key = pk.path.creationDate
            if let hit = known[key], hit.pointCount == pk.path.count, seen.insert(key).inserted {
                out.append(hit.stroke)
                continue
            }
            let stroke = PadBridge.export(pk, pageHeight: pageHeight)
            // Keep the identity PencilKit gave it, unless it collides.
            serial += 1
            let identity = seen.contains(key) || known[key] != nil ? Date(timeIntervalSinceReferenceDate: Double(serial) * 0.001) : key
            known[identity] = Known(stroke: stroke, pointCount: pk.path.count)
            seen.insert(identity)
            out.append(stroke)
        }
        return out
    }

    private static func export(_ pk: PKStroke, pageHeight: Int) -> Stroke {
        var points: [Double] = []
        for p in pk.path {
            let loc = p.location.applying(pk.transform)
            let x = Pad.round(min(Pad.width, max(0, loc.x)))
            let y = Pad.round(min(Double(pageHeight), max(0, loc.y)))
            Pad.thinAppend(&points, x: x, y: y)
        }
        if points.isEmpty { points = [0, 0] }
        let width = max(0.5, (pk.path.first.map { Double($0.size.width) } ?? 6) - PadBridge.sizeOffset)
        return Stroke(id: Pad.newStrokeId(), tool: "pen", color: pk.ink.color.hexString,
                      size: (width * 10).rounded() / 10, points: points)
    }
}

extension UIColor {
    /// "#rrggbb" → colour; nil for anything else.
    convenience init?(hex: String) {
        var text = hex.trimmingCharacters(in: .whitespaces)
        if text.hasPrefix("#") { text.removeFirst() }
        guard text.count == 6, let value = UInt32(text, radix: 16) else { return nil }
        self.init(red: CGFloat((value >> 16) & 0xFF) / 255, green: CGFloat((value >> 8) & 0xFF) / 255,
                  blue: CGFloat(value & 0xFF) / 255, alpha: 1)
    }

    /// The colour as "#rrggbb" in sRGB — what the web pad persists.
    var hexString: String {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        let channel = { (v: CGFloat) in Int((min(1, max(0, v)) * 255).rounded()) }
        return String(format: "#%02x%02x%02x", channel(r), channel(g), channel(b))
    }
}
