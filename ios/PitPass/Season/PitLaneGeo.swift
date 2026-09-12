import Foundation

// Pit-lane geometry from GPS anchors — a port of lib/pitLaneGeo.ts. Anchors
// are fixes captured standing at the centre of a numbered box; boxes between
// two anchors interpolate linearly on box number, so the lane is a piecewise
// polyline through the anchors. Planar maths on an equirectangular projection
// around the anchor centroid — millimetre-honest at pit-lane scale.
// Pure functions: the sheet feeds fixes in, so this is unit-testable.

struct GeoFix: Sendable, Equatable {
    let lat: Double
    let lng: Double
}

struct Guidance: Sendable, Equatable {
    enum Direction: Sendable { case pitIn, pitOut }
    /// Fractional box the fix projects to ("you're near box 12.4").
    let currentBox: Double
    /// Walking distance along the lane to the target box, in feet.
    let feet: Double
    /// Numeric box-count gap (what the wall numbers say), not distance/25'.
    let boxesAway: Int
    /// Which way to walk; box numbers increase toward pit in. Nil on arrival.
    let direction: Direction?
    /// Within about one box of the target.
    let arrived: Bool
}

enum PitLaneGeo {
    static let metersPerDegLat = 111_320.0
    static let boxMeters = 7.62           // 25 feet
    static let feetPerMeter = 3.28084

    private struct Pt { let x: Double; let y: Double }

    private struct Lane {
        let boxes: [Double]   // anchor box numbers, ascending
        let points: [Pt]      // projected anchor positions
        let arc: [Double]     // cumulative along-lane metres at each anchor
    }

    private static func project(_ anchors: [PitAnchor], _ fix: GeoFix) -> (lane: Lane, here: Pt)? {
        guard anchors.count >= 2 else { return nil }
        let sorted = anchors.sorted { $0.boxNumber < $1.boxNumber }
        let lat0 = sorted.map(\.lat).reduce(0, +) / Double(sorted.count)
        let lng0 = sorted.map(\.lng).reduce(0, +) / Double(sorted.count)
        let mPerDegLng = metersPerDegLat * cos(lat0 * .pi / 180)
        func toPt(_ lat: Double, _ lng: Double) -> Pt { Pt(x: (lng - lng0) * mPerDegLng, y: (lat - lat0) * metersPerDegLat) }
        let points = sorted.map { toPt($0.lat, $0.lng) }
        var arc = [0.0]
        for i in 1..<points.count {
            arc.append(arc[i - 1] + hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y))
        }
        // Two anchors on the same spot describe no line — refuse rather than NaN.
        guard let total = arc.last, total >= 1 else { return nil }
        return (Lane(boxes: sorted.map { Double($0.boxNumber) }, points: points, arc: arc), toPt(fix.lat, fix.lng))
    }

    /// Along-lane metres for a (possibly fractional) box; linear per segment,
    /// extrapolated along the end segments beyond the outermost anchors.
    private static func arcAtBox(_ lane: Lane, _ box: Double) -> Double {
        let boxes = lane.boxes, arc = lane.arc
        let last = boxes.count - 1
        let i: Int
        if box <= boxes[0] { i = 0 }
        else if box >= boxes[last] { i = last - 1 }
        else { i = boxes.indices.first { boxes[$0 + 1] >= box } ?? last - 1 }
        let t = (box - boxes[i]) / (boxes[i + 1] - boxes[i])
        return arc[i] + t * (arc[i + 1] - arc[i])
    }

    /// Nearest point on the polyline → fractional box + along-lane metres.
    /// The end segments extend past their anchors.
    private static func locate(_ lane: Lane, _ here: Pt) -> (box: Double, arc: Double) {
        var best = (box: lane.boxes[0], arc: 0.0, dist: Double.infinity)
        for i in 0..<(lane.points.count - 1) {
            let a = lane.points[i], b = lane.points[i + 1]
            let dx = b.x - a.x, dy = b.y - a.y
            let lenSq = dx * dx + dy * dy
            var t = lenSq == 0 ? 0 : ((here.x - a.x) * dx + (here.y - a.y) * dy) / lenSq
            let lo = i == 0 ? -Double.infinity : 0
            let hi = i == lane.points.count - 2 ? Double.infinity : 1
            t = min(hi, max(lo, t))
            let px = a.x + t * dx, py = a.y + t * dy
            let dist = hypot(here.x - px, here.y - py)
            if dist < best.dist {
                best = (lane.boxes[i] + t * (lane.boxes[i + 1] - lane.boxes[i]),
                        lane.arc[i] + t * (lane.arc[i + 1] - lane.arc[i]), dist)
            }
        }
        return (best.box, best.arc)
    }

    /// Nil when fewer than two distinct anchors exist.
    static func guide(anchors: [PitAnchor], fix: GeoFix, targetBox: Int) -> Guidance? {
        guard let (lane, here) = project(anchors, fix) else { return nil }
        let at = locate(lane, here)
        let meters = abs(arcAtBox(lane, Double(targetBox)) - at.arc)
        let arrived = meters <= boxMeters
        return Guidance(currentBox: at.box, feet: meters * feetPerMeter,
                        boxesAway: abs(targetBox - Int(at.box.rounded())),
                        direction: arrived ? nil : (Double(targetBox) > at.box ? .pitIn : .pitOut),
                        arrived: arrived)
    }

    /// "~8 boxes (200 ft)" — boxes say what the wall numbers will, feet say
    /// how far the walk is.
    static func guidanceText(_ g: Guidance) -> String {
        let feet = max(10, Int((g.feet / 10).rounded()) * 10)
        let boxes = g.boxesAway == 1 ? "1 box" : "\(g.boxesAway) boxes"
        return "~\(boxes) (\(feet) ft)"
    }

    struct FixSample: Sendable {
        let lat: Double
        let lng: Double
        /// Reported 1-sigma-ish radius in metres; smaller = trusted more.
        let accuracy: Double
    }

    struct AveragedFix: Sendable, Equatable {
        let lat: Double
        let lng: Double
        /// The worse of the mean reported accuracy and the samples' scatter.
        let accuracyM: Double
        let used: Int
    }

    /// Inverse-variance weighted average of a sampling window; outliers past
    /// 2.5× the best accuracy (min 25 m) dropped unless that leaves < 3.
    static func averageFixes(_ samples: [FixSample]) -> AveragedFix? {
        guard !samples.isEmpty else { return nil }
        let best = samples.map(\.accuracy).min() ?? 0
        let cutoff = max(25, best * 2.5)
        let kept0 = samples.filter { $0.accuracy <= cutoff }
        let kept = (kept0.count >= 3 || kept0.count == samples.count) ? kept0 : samples
        var wSum = 0.0, lat = 0.0, lng = 0.0, accSum = 0.0
        for s in kept {
            let w = 1 / max(1, s.accuracy * s.accuracy)
            wSum += w; lat += s.lat * w; lng += s.lng * w; accSum += s.accuracy * w
        }
        lat /= wSum; lng /= wSum
        let mPerDegLng = metersPerDegLat * cos(lat * .pi / 180)
        var scatterSq = 0.0
        for s in kept {
            let dx = (s.lng - lng) * mPerDegLng, dy = (s.lat - lat) * metersPerDegLat
            scatterSq += dx * dx + dy * dy
        }
        let rms = sqrt(scatterSq / Double(kept.count))
        return AveragedFix(lat: lat, lng: lng, accuracyM: max(accSum / wSum, rms), used: kept.count)
    }
}
