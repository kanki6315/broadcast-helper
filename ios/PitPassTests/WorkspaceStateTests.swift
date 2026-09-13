import Foundation
import Testing
@testable import PitPass

@MainActor
struct WorkspaceStateTests {
    @Test func restoresAcrossInstancesAndIsolatesAccounts() throws {
        let suite = "WorkspaceTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = WorkspaceState(scope: "server|alice", defaults: defaults)
        first.select(12, series: "IMSA")
        first.set("season.12.tab", "races")
        first.set("season.12.class", "LMP2")
        first.set("season.12.race", "72")
        first.set("event.72.tab", "scratchpad")
        first.setOffset("season.12.races", CGPoint(x: 0, y: 640))
        first.select(25, series: "Mustang")
        first.set("season.25.tab", "standings")

        let resumed = WorkspaceState(scope: "server|alice", defaults: defaults)
        #expect(resumed.snapshot.lastSeason == 25)
        #expect(resumed.snapshot.seriesSeasons["IMSA"] == 12)
        #expect(resumed.value("season.12.tab") == "races")
        #expect(resumed.value("season.12.class") == "LMP2")
        #expect(resumed.value("season.12.race") == "72")
        #expect(resumed.value("event.72.tab") == "scratchpad")
        #expect(resumed.offset("season.12.races").y == 640)
        #expect(WorkspaceState(scope: "server|bob", defaults: defaults).snapshot.lastSeason == nil)
        #expect(WorkspaceState(scope: "other-server|alice", defaults: defaults).snapshot.lastSeason == nil)
        resumed.clearLastSeason()
        #expect(resumed.value("event.72.tab") == "scratchpad")
    }

    @Test func corruptPreferencesAndInvalidPositionsRecover() throws {
        let suite = "WorkspaceTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Data("invalid".utf8), forKey: "pitpass.workspace.v1.test")
        let state = WorkspaceState(scope: "test", defaults: defaults)
        #expect(state.snapshot.lastSeason == nil)
        state.setOffset("sheet", CGPoint(x: -10, y: 120))
        #expect(state.offset("sheet") == CGPoint(x: 0, y: 120))
        state.setOffset("sheet", CGPoint(x: CGFloat.infinity, y: 20))
        #expect(state.offset("sheet").y == 120)
        state.set("class", "removed")
        state.set("class", nil)
        #expect(WorkspaceState(scope: "test", defaults: defaults).value("class") == nil)
    }
}
