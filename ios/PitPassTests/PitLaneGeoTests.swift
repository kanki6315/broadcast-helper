import Foundation
import Testing
@testable import PitPass

/// The lane geometry, checked against the numbers the web module produces.
struct PitLaneGeoTests {
    // A straight north–south lane: box 1 at the origin, box 21 ~152 m north
    // (20 boxes × 7.62 m). Longitude 0 keeps the projection trivial.
    private let anchors = [
        PitAnchor(boxNumber: 1, lat: 43.0, lng: -87.99, accuracyM: 3),
        PitAnchor(boxNumber: 21, lat: 43.0 + 152.4 / PitLaneGeo.metersPerDegLat, lng: -87.99, accuracyM: 3),
    ]

    @Test func standingAtBoxElevenGuidesTowardPitIn() {
        let fix = GeoFix(lat: 43.0 + 76.2 / PitLaneGeo.metersPerDegLat, lng: -87.99)
        let g = PitLaneGeo.guide(anchors: anchors, fix: fix, targetBox: 19)!
        #expect(abs(g.currentBox - 11) < 0.05)
        #expect(g.boxesAway == 8)
        #expect(g.direction == .pitIn)
        #expect(!g.arrived)
        #expect(abs(g.feet - 8 * 25) < 1)
        #expect(PitLaneGeo.guidanceText(g) == "~8 boxes (200 ft)")
    }

    @Test func withinOneBoxIsArrival() {
        let fix = GeoFix(lat: 43.0 + 30.48 / PitLaneGeo.metersPerDegLat, lng: -87.99)   // box 5
        let g = PitLaneGeo.guide(anchors: anchors, fix: fix, targetBox: 5)!
        #expect(g.arrived)
        #expect(g.direction == nil)
    }

    @Test func endSegmentsExtrapolatePastTheAnchors() {
        let fix = GeoFix(lat: 43.0 - 15.24 / PitLaneGeo.metersPerDegLat, lng: -87.99)   // two boxes before box 1
        let g = PitLaneGeo.guide(anchors: anchors, fix: fix, targetBox: 1)!
        #expect(abs(g.currentBox - (-1)) < 0.05)
        #expect(g.direction == .pitIn)
    }

    @Test func coincidentAnchorsRefuse() {
        let same = [anchors[0], PitAnchor(boxNumber: 9, lat: 43.0, lng: -87.99, accuracyM: 3)]
        #expect(PitLaneGeo.guide(anchors: same, fix: GeoFix(lat: 43, lng: -87.99), targetBox: 3) == nil)
        #expect(PitLaneGeo.guide(anchors: [anchors[0]], fix: GeoFix(lat: 43, lng: -87.99), targetBox: 3) == nil)
    }

    @Test func averagingDropsOutliersAndFoldsScatterIn() {
        let samples = [
            PitLaneGeo.FixSample(lat: 43.0, lng: -87.99, accuracy: 4),
            PitLaneGeo.FixSample(lat: 43.0, lng: -87.99, accuracy: 5),
            PitLaneGeo.FixSample(lat: 43.0, lng: -87.99, accuracy: 4),
            PitLaneGeo.FixSample(lat: 43.001, lng: -87.99, accuracy: 80),   // multipath junk
        ]
        let a = PitLaneGeo.averageFixes(samples)!
        #expect(a.used == 3)
        #expect(abs(a.lat - 43.0) < 1e-9)
        #expect(a.accuracyM >= 4 && a.accuracyM <= 5)
        #expect(PitLaneGeo.averageFixes([]) == nil)
    }
}
