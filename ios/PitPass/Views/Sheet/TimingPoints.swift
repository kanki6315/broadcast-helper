import SwiftUI

/// The Timing tab's Points view — the championship calculator, beside the
/// tower (the web's TimingPoints.tsx). Live projects the championships of the
/// season the session on track is filed under, so it shows only on the screen
/// following that session. Scenario simulates this screen's event and needs
/// its season, so a series weekend's screen offers Live alone.
struct TimingPointsSection: View {
    /// This screen's event and season, for a scenario; nil on a series weekend's screen.
    let scenarioEventId: Int?
    let scenarioSeasonId: Int?
    let status: LiveStatus?
    let followingThis: Bool
    @State private var showsLive = true

    private var offersScenario: Bool { scenarioEventId != nil && scenarioSeasonId != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if offersScenario {
                Picker("Mode", selection: $showsLive) {
                    Text("Live").tag(true)
                    Text("Scenario").tag(false)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 280)
            }
            if showsLive || !offersScenario {
                liveContent
            } else if let eventId = scenarioEventId, let seasonId = scenarioSeasonId {
                Text("If the race finished like this. Compare selected teams using the latest imported points.")
                    .font(.subheadline).foregroundStyle(PP.textMuted)
                SeasonChampionships(seasonId: seasonId) { hub in
                    CalculatorClassWorkspace(championships: hub.championships.filter(ChampionshipCalculator.supported), eventId: eventId)
                }
            }
        }
    }

    /// Live needs an order from a session filed under an event — never just the
    /// connection's binding, which can outlive its series' session.
    @ViewBuilder private var liveContent: some View {
        if let status, status.configured {
            if !status.desiredConnected {
                EmptyState(message: "Live timing is off. Once it is connected, the standings are projected here as the field runs.")
            } else if !status.hasOrder {
                ProgressView("Connecting to live timing…")
            } else if !followingThis {
                EmptyState(message: "Live points follow the session on track, which is not this screen's. Its own timing screen projects them.")
                if let feedEvent = status.session?.feedEventDbId {
                    NavigationLink(value: TimingRoute.weekend(feedEvent)) {
                        Label("Open \(status.session?.championship ?? "that series")", systemImage: "stopwatch")
                    }
                    .buttonStyle(.bordered)
                }
            } else if let eventId = status.filedEventId, let seasonId = status.filedSeasonId {
                Text("As it stands. Every row of the standings, projected from where the field is running.")
                    .font(.subheadline).foregroundStyle(PP.textMuted)
                SeasonChampionships(seasonId: seasonId) { hub in
                    LiveChampionshipWorkspace(championships: hub.championships.filter(ChampionshipCalculator.supportedLive), eventId: eventId)
                }
                .id(seasonId)
            } else {
                EmptyState(message: "\(status.session?.championship ?? "The session on track") is not filed under a Pit Pass event, so nothing is projected. Sessions are filed once their cars match an event's entry list.")
            }
        } else if status != nil {
            EmptyState(message: "Live timing is not set up on this server.")
        } else {
            SkeletonLines()
        }
    }
}

/// A season's championships, loaded like any document (kept offline).
private struct SeasonChampionships<Content: View>: View {
    @Environment(AppSession.self) private var session
    @State private var hub: Resource<SeasonHub>
    let content: (SeasonHub) -> Content

    init(seasonId: Int, @ViewBuilder content: @escaping (SeasonHub) -> Content) {
        _hub = State(initialValue: Resource<SeasonHub>("/api/seasons/\(seasonId)"))
        self.content = content
    }

    var body: some View {
        Group {
            if let value = hub.value {
                content(value)
            } else if let error = hub.error {
                ErrorPanel(message: error)
                Button("Retry") { Task { await hub.load(session.loader) } }
            } else { ProgressView("Loading championships…") }
        }
        .task { await hub.load(session.loader, connectivity: session.connectivity, freshness: session.freshness) }
    }
}
