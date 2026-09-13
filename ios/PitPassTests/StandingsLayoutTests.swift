import SwiftUI
import XCTest
@testable import PitPass

@MainActor
final class StandingsLayoutTests: XCTestCase {
    func testStandingsFitIPadAndScrollInNarrowWindows() async throws {
        let champ = ChampionshipSummary(id: 1, title: "IMSA", groupTitle: nil, className: "GTP",
                                        kind: "TEAMS", kindLabel: nil, isCup: false, year: 2026,
                                        seasonId: 1, seriesName: "IMSA", rowCount: 3)
        let rounds = (1...11).map { n in
            RecapRound(round: n, venue: ["DAY", "SEB", "LBH", "LAG", "DET", "WGI", "CTMP", "RDA", "VIR", "IMS", "ATL"][n - 1],
                       eventId: n, raceCount: 1,
                       sessions: [RecapSession(sessionIndex: n * 2, name: "Qualifying"),
                                  RecapSession(sessionIndex: n * 2 + 1, name: "Race")], races: [])
        }
        let rows: [RecapRow] = (1...3).map { position in
            let points = Dictionary(uniqueKeysWithValues: (1...8).map { (String($0), Double(350 - position * 10)) })
            var sessions: [String: RecapSessionPoints] = [:]
            for n in 1...8 {
                sessions[String(n * 2)] = RecapSessionPoints(total: 30, race: 30, pole: 0, fastestLap: 0, penalty: 0, bonus: 0, contested: true)
                let race = Double(320 - position * 10)
                sessions[String(n * 2 + 1)] = RecapSessionPoints(total: race, race: race, pole: 0, fastestLap: 0, penalty: 0, bonus: 0, contested: true)
            }
            return RecapRow(position: position, competitorKey: String(position), competitorName: nil,
                     carNumber: ["31", "93", "7"][position - 1],
                     teamName: ["Cadillac Whelen", "Acura Meyer Shank Racing w/Curb Agajanian", "Porsche Penske Motorsport"][position - 1],
                     teamNames: nil, totalPoints: Double(2500 - position * 100),
                     pointsByRound: points, sessionPoints: sessions, cells: [:])
        }
        let recap = Recap(championship: RecapChampionship(id: 1, title: "IMSA", className: "GTP", kind: "TEAMS",
                                                         family: "IMSA", isCup: false, isOverall: false,
                                                         seasonId: 1, year: 2026, seriesName: "IMSA"), rounds: rounds, rows: rows)
        let model = SeasonModel(seasonId: 1)
        model.recap(for: champ).replace(recap)
        for mode in SeasonModel.PointsView.allCases {
            model.pointsView = mode
            let configurations: [(CGFloat, ColorScheme, DynamicTypeSize)] = [
                (1194, .dark, .large), (834, .dark, .large), (507, .dark, .large),
                (834, .light, .large), (834, .dark, .xxxLarge)
            ]
            for (width, scheme, textSize) in configurations {
                let size = CGSize(width: width, height: width == 834 ? 1194 : 834)
                let view = ClassGridView(champ: champ, mode: .points)
                    .environment(model).environment(AppSession())
                    .padding(24)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    .background(PP.bg).environment(\.colorScheme, scheme)
                    .environment(\.dynamicTypeSize, textSize)
                let host = UIHostingController(rootView: view)
                let window = UIWindow(frame: CGRect(origin: .zero, size: size))
                window.rootViewController = host
                window.isHidden = false
                host.view.frame = window.bounds
                host.view.layoutIfNeeded()
                try await Task.sleep(for: .milliseconds(250))
                host.view.layoutIfNeeded()
                let scroll = try XCTUnwrap(scrollViews(in: host.view).first)
                XCTAssertGreaterThan(scroll.bounds.width, 130, "Pinned identity must leave room for rounds")
                if width == 1194 || (width == 834 && mode == .total) {
                    XCTAssertLessThanOrEqual(scroll.contentSize.width, scroll.bounds.width + 1,
                                             "Completed rounds should fit; empty future rounds must not consume width")
                } else {
                    XCTAssertGreaterThan(scroll.contentSize.width, scroll.bounds.width, "Narrow windows must scroll without shrinking points")
                }
                let image = UIGraphicsImageRenderer(size: size).image { _ in
                    host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
                }
                let attachment = XCTAttachment(image: image)
                attachment.name = "Standings \(mode.rawValue) \(Int(width)) \(scheme) \(textSize)"
                attachment.lifetime = .keepAlways
                add(attachment)
                try image.pngData()?.write(to: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("standings-\(mode.rawValue)-\(Int(width))-\(scheme)-\(textSize).png"))
                window.isHidden = true
            }
        }
    }

    private func scrollViews(in view: UIView) -> [UIScrollView] {
        (view as? UIScrollView).map { [$0] } ?? view.subviews.flatMap { scrollViews(in: $0) }
    }
}
