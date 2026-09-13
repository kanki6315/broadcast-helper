import Testing
import SwiftUI
import UIKit
@testable import PitPass

@MainActor
struct RecapLayoutTests {
    private let tags: [Int: String?] = [1: "R1", 2: "R2"]

    private func races(cars: [String], retired: Bool = false) -> [RecapRace] {
        cars.flatMap { car in
            (1...2).map { ordinal in
                RecapRace(race: ordinal, name: "Race \(ordinal)", carNumber: car,
                          start: 15, finish: 18, status: retired ? "retired" : nil,
                          notFinished: retired)
            }
        }
    }

    @Test func everyCarRaceHasVerticalSpace() {
        #expect(RaceCellView.lines(nil) == 1)
        #expect(RaceCellView.lines([]) == 1)
        #expect(RaceCellView.lines(races(cars: ["22"])) == 2)
        #expect(RaceCellView.lines(races(cars: ["22", "23", "24", "25"])) == 8)
    }

    @Test func dnsChipsFitIncludingTheirPadding() {
        let results = [RecapRace(race: 1, name: "Race 1", carNumber: "22",
                                 start: nil, finish: nil, status: "not started", notFinished: true)]
        let host = UIHostingController(rootView: RaceCellView(races: results, raceTags: tags).fixedSize())
        let size = host.sizeThatFits(in: CGSize(width: 1000, height: 1000))
        #expect(size.width <= RaceCellView.contentWidth(results, raceTags: tags))
    }

    @Test func columnsAndRowsFitUncompressedResults() {
        for cars in [["22"], ["22", "1234"], ["22", "23", "24", "25"]] {
            for retired in [false, true] {
                let results = races(cars: cars, retired: retired)
                for padding: CGFloat in [2, 4] {
                    let host = UIHostingController(rootView:
                        RaceCellView(races: results, raceTags: tags, chipPadding: padding).fixedSize())
                    let size = host.sizeThatFits(in: CGSize(width: 1000, height: 1000))
                    #expect(size.width <= RaceCellView.contentWidth(results, raceTags: tags, chipPadding: padding))
                    #expect(size.height <= CGFloat(RaceCellView.lines(results)) * 24)
                }
            }
        }
    }
}
