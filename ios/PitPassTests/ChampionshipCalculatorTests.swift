import SwiftUI
import XCTest
@testable import PitPass

@MainActor
final class ChampionshipCalculatorTests: XCTestCase {
    private var recap: Recap {
        Recap(championship: RecapChampionship(id: 1, title: "WeatherTech · GTP Teams", className: "GTP", kind: "TEAMS", family: "IMSA", isCup: false, isOverall: false, seasonId: 1, year: 2026, seriesName: "IMSA"),
              rounds: [RecapRound(round: 1, venue: "DAY", eventId: 11, raceCount: 1, sessions: [], races: []),
                       RecapRound(round: 2, venue: "SEB", eventId: 22, raceCount: 1, sessions: [], races: [])],
              rows: [("6", 1000.0), ("7", 980.0), ("31", 970.0)].enumerated().map { i, entry in
                  RecapRow(position: i + 1, competitorKey: entry.0, competitorName: nil, carNumber: entry.0,
                           teamName: i < 2 ? "Porsche Penske Motorsport" : "Cadillac Whelen", teamNames: nil, totalPoints: entry.1,
                           pointsByRound: ["1": entry.1], sessionPoints: [:], cells: [:])
              })
    }
    private var scenario: [String: ChampionshipCalculator.Entry] {
        ["6": .init(positions: [1, 5], adjustment: -10), "7": .init(positions: [2, 1])]
    }
    func testScoringAndSelectedOnlyProjection() {
        XCTAssertEqual([0, 1, 2, 5, 6, 29, 30, 40].map { ChampionshipCalculator.points($0, phase: .qualifying) }, [0, 35, 32, 26, 25, 2, 1, 1])
        XCTAssertEqual(ChampionshipCalculator.points(40, phase: .race), 10)
        let rows = ChampionshipCalculator.project(recap, scenario: scenario, cup: false, phaseCount: 2)
        XCTAssertEqual(rows.map(\.id), ["7", "6"])
        XCTAssertEqual(rows.map(\.total), [1362, 1285])
        XCTAssertEqual(rows.map(\.gap), [0, 77])
        XCTAssertEqual(recap.rows.map(\.totalPoints), [1000, 980, 970])
        XCTAssertEqual(ChampionshipCalculator.project(recap, scenario: ["6": .init(positions: [1, 3, 4, 2])], cup: true, phaseCount: 4).first?.added, 14)
    }
    func testSwapsTiesAndBaselineGuard() {
        let changed = ChampionshipCalculator.assign(scenario, key: "6", phase: 1, position: 1)
        XCTAssertEqual(changed["7"]?.positions, [2, 5])
        let tied = ChampionshipCalculator.project(recap, scenario: ["6": .init(positions: [0, 0]), "7": .init(positions: [0, 0], adjustment: 20)], cup: false, phaseCount: 2)
        XCTAssertTrue(tied.allSatisfy { $0.rank == 1 && $0.tied && $0.gap == 0 })
        XCTAssertNotNil(ChampionshipCalculator.baselineIssue(recap, eventId: 11))
        XCTAssertNil(ChampionshipCalculator.baselineIssue(recap, eventId: 22))
        XCTAssertNotNil(ChampionshipCalculator.baselineIssue(recap, eventId: 99))
    }
    func testClassWorkspaceLayouts() async throws {
        let names = ["GTP", "LMP2", "GTD PRO", "GTD"]
        let championships = names.enumerated().map { i, name in
            ChampionshipSummary(id: i + 1, title: "\(name) Teams", groupTitle: "IMSA", className: name,
                                kind: "TEAMS", kindLabel: nil, isCup: false, year: 2026, seasonId: 1, seriesName: "IMSA", rowCount: 3)
        }
        let recaps = Dictionary(uniqueKeysWithValues: championships.map { ($0.id, recap) })
        let drafts = Dictionary(uniqueKeysWithValues: championships.map { ($0.id, scenario) })
        for (count, width) in [(1, 1194), (2, 1194), (3, 1194), (4, 1194), (4, 834), (4, 507)] {
            let size = CGSize(width: width, height: 1400)
            let view = ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Championship calculator").ppTitle()
                    CalculatorClassWorkspace(championships: championships, eventId: 22, initialRecaps: recaps,
                                             initiallyShown: Set(names.prefix(count)), initialScenarios: drafts)
                }.padding(24)
            }.background(PP.bg).environment(AppSession()).environment(\.colorScheme, width == 834 ? .light : .dark)
            let host = UIHostingController(rootView: view)
            let window = UIWindow(frame: CGRect(origin: .zero, size: size))
            window.rootViewController = host
            window.isHidden = false
            host.view.frame = window.bounds
            host.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(300))
            host.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: size).image { _ in
                host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = "Calculator classes \(count) \(width)"
            attachment.lifetime = .keepAlways
            add(attachment)
            try image.pngData()?.write(to: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("calculator-classes-\(count)-\(width).png"))
            window.isHidden = true
        }
    }
    func testCalculatorLayouts() async throws {
        for (width, scheme) in [(CGFloat(1194), ColorScheme.dark), (CGFloat(834), ColorScheme.light), (CGFloat(507), ColorScheme.dark)] {
            let size = CGSize(width: width, height: 834)
            let view = ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Championship calculator").ppTitle()
                    Text("WeatherTech · GTP Teams / Sebring").font(.headline)
                    CalculatorEditor(recap: recap, eventId: 22, scenario: .constant(scenario))
                }.padding(24)
            }.background(PP.bg).environment(\.colorScheme, scheme)
            let host = UIHostingController(rootView: view)
            let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
            let window = UIWindow(windowScene: scene)
            window.frame = CGRect(origin: .zero, size: size)
            window.rootViewController = host
            window.isHidden = false
            host.view.frame = window.bounds
            host.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(300))
            host.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: size).image { _ in
                host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
            }
            let attachment = XCTAttachment(image: image)
            attachment.name = "Calculator \(Int(width)) \(scheme)"
            attachment.lifetime = .keepAlways
            add(attachment)
            try image.pngData()?.write(to: URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("calculator-\(Int(width))-\(scheme).png"))
            window.isHidden = true
        }
    }
}
