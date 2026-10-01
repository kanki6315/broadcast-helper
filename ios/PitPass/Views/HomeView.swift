import SwiftUI

/// Series selection establishes the root workspace; event routes push within it.
struct HomeView: View {
    @Environment(AppSession.self) private var session
    let me: Me
    @State private var workspace: WorkspaceState
    @State private var selectedSeason: Int?
    @State private var showLibrary = false
    @State private var path = NavigationPath()
    @State private var showSettings = false

    init(me: Me, scope: String) {
        self.me = me
        let workspace = WorkspaceState(scope: scope)
        _workspace = State(initialValue: workspace)
        _selectedSeason = State(initialValue: workspace.snapshot.lastSeason)
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if !showLibrary, let selectedSeason {
                    SeasonView(seasonId: selectedSeason, chooseSeason: select, unavailableSeason: {
                        workspace.clearLastSeason()
                        self.selectedSeason = nil
                        showLibrary = true
                    })
                        .id(selectedSeason)
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button { showLibrary = true } label: {
                                    Label("All series", systemImage: "square.grid.2x2")
                                }
                            }
                            ToolbarItem(placement: .topBarTrailing) { timingButton }
                        }
                } else {
                    ScrollView {
                        PageContainer {
                            SeriesDirectoryView(select: select)
                        }
                    }
                    .background(PP.bg)
                    .navigationTitle("")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Wordmark(markSize: 32, textSize: PP.TextSize.lg)
                                .fixedSize()
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel("Pit Pass")
                        }
                        .sharedBackgroundVisibility(.hidden)
                        ToolbarItem(placement: .topBarTrailing) { timingButton }
                        ToolbarItem(placement: .topBarTrailing) {
                            Button { showSettings = true } label: {
                                Label("Settings", systemImage: "gearshape")
                            }
                        }
                    }
                }
            }
            .navigationDestination(for: SheetRoute.self) { route in
                SheetView(eventId: route.eventId)
            }
            .navigationDestination(for: TimingRoute.self) { route in
                switch route {
                case .home: TimingHomeView()
                case let .weekend(id):
                    TimingSheet(scope: .weekend(id))
                        .navigationTitle("Timing")
                        .navigationBarTitleDisplayMode(.inline)
                }
            }
        }
        // Driver / team profiles open from any name in the stack. On the
        // stack itself, not its root content: pushed screens inherit the
        // stack's environment, not the root view's modifiers.
        .infoModalHost()
        .environment(workspace)
        .tint(PP.accentInk)
        .sheet(isPresented: $showSettings) { SettingsView() }
    }

    /// Live timing for every series on track, filed under a Pit Pass event or not.
    private var timingButton: some View {
        NavigationLink(value: TimingRoute.home) {
            Label("Timing", systemImage: "stopwatch")
        }
    }

    private func select(_ id: Int) {
        path = NavigationPath()
        workspace.select(id)
        selectedSeason = id
        showLibrary = false
    }
}
