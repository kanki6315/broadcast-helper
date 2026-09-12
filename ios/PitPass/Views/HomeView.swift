import SwiftUI

/// The shell: the website's topbar over a scrolling page. Read-only by
/// design — editing stays on the website (docs/IOS.md). Season screens push
/// onto the stack; until they exist a placeholder names the season.
struct HomeView: View {
    @Environment(AppSession.self) private var session
    let me: Me
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            ScrollView {
                PageContainer {
                    VStack(alignment: .leading, spacing: 0) {
                        TopBar { showSettings = true }
                        SeriesDirectoryView()
                    }
                }
            }
            .background(PP.bg.ignoresSafeArea())
            // No nav bar, so nothing backs the status bar: paint that strip in
            // the page ground so scrolled content never shows through it.
            .overlay(alignment: .top) {
                Color.clear.frame(height: 0).background(PP.bg.ignoresSafeArea(edges: .top))
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: SeasonSummary.self) { season in
                SeasonView(seasonId: season.id)
            }
            .navigationDestination(for: SheetRoute.self) { route in
                SheetView(eventId: route.eventId)
            }
        }
        .tint(PP.accentInk)
        .sheet(isPresented: $showSettings) { SettingsView() }
    }
}
