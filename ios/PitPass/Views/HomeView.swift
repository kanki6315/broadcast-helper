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
                SeasonPlaceholderView(season: season)
            }
        }
        .tint(PP.accentInk)
        .sheet(isPresented: $showSettings) { SettingsView() }
    }
}

/// Stand-in until the season slice lands: the page title in the website's
/// headline style so the navigation already reads right.
struct SeasonPlaceholderView: View {
    let season: SeasonSummary

    var body: some View {
        ScrollView {
            PageContainer {
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                        Text(season.seriesName).ppHeadline()
                        Text(String(season.year)).font(PP.mono(PP.TextSize.xl, weight: 700)).foregroundStyle(PP.ink)
                        if season.isQualifier { QualifierBadge(text: season.label ?? "Qualifying") }
                    }
                    Text("\(season.roundCount) rounds · \(season.championshipCount) championships")
                        .font(PP.sans(PP.TextSize.sm))
                        .foregroundStyle(PP.textMuted)
                    EmptyState(message: "Hub, schedule, standings, stats, results, entries and photos arrive in the next slice.")
                        .padding(.top, PP.Space.s4)
                }
            }
        }
        .background(PP.bg.ignoresSafeArea())
        // String(year): interpolating an Int into Text localises it as "2,026".
        .navigationTitle("\(season.seriesName) \(String(season.year))")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(PP.bg, for: .navigationBar)
    }
}
