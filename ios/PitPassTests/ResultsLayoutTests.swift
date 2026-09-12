import SwiftUI
import XCTest
@testable import PitPass

@MainActor
final class ResultsLayoutTests: XCTestCase {
    func testLapDeficitsHaveAnExplicitPlusSign() {
        XCTAssertEqual(ResultGaps.displayGap("2 Laps"), "+ 2 laps")
        XCTAssertEqual(ResultGaps.displayGap("1 Lap"), "+ 1 lap")
        XCTAssertEqual(ResultGaps.displayGap("+2 Laps"), "+ 2 laps")
        XCTAssertEqual(ResultGaps.displayGap("+ 2 laps"), "+ 2 laps")
        XCTAssertEqual(ResultGaps.displayGap("+1.960"), "+1.960")
        XCTAssertEqual(ResultGaps.displayGap("6:00:45.052"), "6:00:45.052")
        XCTAssertEqual(ResultGaps.displayGap("-"), "-")
        XCTAssertNil(ResultGaps.displayGap(nil))
    }

    func testPopulatedClassificationFitsBothIPadOrientations() async throws {
        let session = try JSONDecoder().decode(SessionResults.self, from: Data(#"""
        {
          "sessionId": 1, "sessionType": "RACE", "name": "Race",
          "notes": [], "hasFlags": false,
          "results": [
            {"posOverall":1,"posInClass":1,"carNumber":"10","className":"GTP",
             "teamName":"Cadillac Wayne Taylor Racing","drivers":"Ricky Taylor, Filipe Albuquerque",
             "vehicle":"Cadillac V-Series.R","laps":151,"pitStops":5,
             "fastestLapTime":"1:12.345","fastestLapNumber":142,"elapsedTime":"2:40:12.345","status":"Classified"},
            {"posOverall":2,"posInClass":1,"carNumber":"04","className":"LMP2",
             "teamName":"Crowdstrike Racing by APR","drivers":"George Kurtz, Alex Quinn, Toby Sowery",
             "vehicle":"ORECA LMP2 07","laps":149,"pitStops":6,
             "fastestLapTime":"1:14.987","fastestLapNumber":128,"gapFirst":"2 Laps","status":"Classified"},
            {"posOverall":3,"posInClass":1,"carNumber":"033","className":"GTDPRO",
             "teamName":"Triarsi Competizione","drivers":"James Calado, Riccardo Agostini",
             "vehicle":"Ferrari 296 GT3","laps":148,"pitStops":5,
             "fastestLapTime":"1:20.456","fastestLapNumber":104,"gapFirst":"3 Laps","status":"Classified"},
            {"posOverall":4,"posInClass":2,"carNumber":"43","className":"LMP2",
             "teamName":"Inter Europol Competition","drivers":"Jeremy Clarke, Bijoy Garg, Tom Dillmann",
             "vehicle":"ORECA LMP2 07","laps":125,"pitStops":4,
             "fastestLapTime":"1:15.678","fastestLapNumber":98,"gapFirst":"26 Laps","status":"DNF"}
          ],
          "grid": [
            {"carNumber":"10","posOverall":7}, {"carNumber":"04","posOverall":9},
            {"carNumber":"033","posOverall":15}, {"carNumber":"43","posOverall":1}
          ]
        }
        """#.utf8))

        try await verifyOrientations(session, name: "Results")
    }

    /// Values transcribed from the user's website reference, including a
    /// six-hour winning time, a sub-two-second gap and double-digit pit counts.
    func testWebsiteReferenceFitsBothIPadOrientations() async throws {
        let session = try JSONDecoder().decode(SessionResults.self, from: Data(#"""
        {
          "sessionId": 2, "sessionType": "RACE", "name": "Race",
          "notes": [], "hasFlags": false,
          "results": [
            {"posOverall":1,"posInClass":1,"carNumber":"10","className":"GTP",
             "teamName":"Cadillac Wayne Taylor Racing","drivers":"Ricky Taylor, Filipe Albuquerque",
             "vehicle":"Cadillac V-Series.R","laps":151,"pitStops":6,
             "fastestLapTime":"1:52.939","fastestLapNumber":88,"elapsedTime":"6:00:45.052","status":"Classified"},
            {"posOverall":2,"posInClass":2,"carNumber":"5","className":"GTP",
             "teamName":"JDC-Miller MotorSports","drivers":"Tijmen van der Helm, Laurin Heinrich, Kaylen Frederick",
             "vehicle":"Porsche 963","laps":151,"pitStops":11,
             "fastestLapTime":"1:53.585","fastestLapNumber":34,"gapFirst":"+1.960","status":"Classified"},
            {"posOverall":8,"posInClass":1,"carNumber":"43","className":"LMP2",
             "teamName":"Inter Europol Competition","drivers":"Jeremy Clarke, Bijoy Garg, Tom Dillmann",
             "vehicle":"ORECA LMP2 07","laps":149,"pitStops":10,
             "fastestLapTime":"1:54.778","fastestLapNumber":143,"gapFirst":"2 Laps","status":"Classified"},
            {"posOverall":10,"posInClass":3,"carNumber":"11","className":"LMP2",
             "teamName":"TDS Racing","drivers":"Tobi Lutke, Mathias Beche, David Heinemeier Hansson",
             "vehicle":"ORECA LMP2 07","laps":149,"pitStops":9,
             "fastestLapTime":"1:55.838","fastestLapNumber":116,"gapFirst":"2 Laps","status":"Classified"}
          ],
          "grid": [
            {"carNumber":"10","posOverall":7}, {"carNumber":"5","posOverall":11},
            {"carNumber":"43","posOverall":14}, {"carNumber":"11","posOverall":17}
          ]
        }
        """#.utf8))
        try await verifyOrientations(session, name: "Website reference")
    }

    private func verifyOrientations(_ session: SessionResults, name: String) async throws {
        var heights: [CGFloat] = []
        for size in [CGSize(width: 834, height: 1194), CGSize(width: 1194, height: 834)] {
            let view = ResultsTable(session: session)
                .environment(SeasonModel(seasonId: 1))
                .padding(24)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(PP.bg)
                .environment(\.colorScheme, .dark)
            let host = UIHostingController(rootView: view)
            let window = UIWindow(frame: CGRect(origin: .zero, size: size))
            window.rootViewController = host
            window.isHidden = false
            host.view.frame = window.bounds
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            // Allow SwiftUI's available-width observation to complete its layout.
            try await Task.sleep(for: .milliseconds(250))
            host.view.layoutIfNeeded()

            let scroll = try XCTUnwrap(scrollViews(in: host.view).first)
            XCTAssertGreaterThan(scroll.contentSize.height, 200, "Wrapped rows must retain their natural height")
            XCTAssertLessThanOrEqual(scroll.contentSize.width, scroll.bounds.width + 1,
                                     "Populated statistics must fit in the full-width iPad view")
            heights.append(scroll.contentSize.height)

            let image = UIGraphicsImageRenderer(size: size).image { _ in
                host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = name + (size.width < size.height ? " portrait" : " landscape")
            attachment.lifetime = .keepAlways
            add(attachment)
            window.isHidden = true
        }
        XCTAssertGreaterThan(heights[0], heights[1], "Portrait should wrap crew names into taller rows")
    }

    private func scrollViews(in view: UIView) -> [UIScrollView] {
        (view as? UIScrollView).map { [$0] } ?? view.subviews.flatMap { scrollViews(in: $0) }
    }
}
