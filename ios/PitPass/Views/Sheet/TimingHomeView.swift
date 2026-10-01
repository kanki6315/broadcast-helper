import SwiftUI

/// Pushed from the home toolbar, and from a Timing tab that is following
/// another series.
enum TimingRoute: Hashable {
    /// What is on track, and every recorded series weekend.
    case home
    /// One series weekend (Al Kamel's event id), filed under a Pit Pass event or not.
    case weekend(Int)
}

/// The Timing screen — the web's `/timing` (TimingHomePage.tsx): the shared
/// connection (an admin connects here for every series, filed by
/// championship), what is on track and where it is filed, and every series
/// weekend recorded in the last 60 days. Any of them opens as a timing screen
/// of its own, so a series Pit Pass does not follow still has its tower,
/// gaps, sectors, pits and drive time. Filing a weekend under an event is
/// done on the website. Polled, never stored, like all live timing.
struct TimingHomeView: View {
    @Environment(AppSession.self) private var session
    @State private var status = LiveFeed<LiveStatus>()
    @State private var weekends = LiveFeed<[LiveWeekend]>()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PP.Space.s4) {
                Text("Timing").ppTitle()
                LiveTimingBar(eventId: nil, status: status)
                if let value = status.value, value.desiredConnected, let onTrack = value.session {
                    OnTrackRow(status: value, onTrack: onTrack)
                }
                if let list = weekends.value {
                    if list.isEmpty {
                        EmptyState(message: "No sessions recorded in the last 60 days.")
                    } else {
                        ForEach(list) { WeekendSection(weekend: $0) }
                    }
                } else if let error = weekends.error {
                    ErrorPanel(message: "Could not load recorded sessions: \(error)")
                } else {
                    SkeletonLines()
                }
            }
            .padding(PP.Space.s5)
            .frame(maxWidth: 1100, alignment: .topLeading)
            .frame(maxWidth: .infinity)
        }
        .background(PP.bg)
        .navigationTitle("Timing")
        .navigationBarTitleDisplayMode(.inline)
        .tint(PP.accentInk)
        .task { await status.run(session.client, path: "/api/live/status", every: .seconds(5)) }
        .task { await weekends.run(session.client, path: "/api/live/weekends", every: .seconds(30)) }
    }
}

/// The session the feed is carrying, where it is filed, and a way into its timing.
private struct OnTrackRow: View {
    let status: LiveStatus
    let onTrack: LiveSession

    var body: some View {
        HStack(spacing: PP.Space.s3) {
            VStack(alignment: .leading, spacing: 2) {
                Text("On track: " + ([onTrack.championship, onTrack.name].compactMap { $0 }.joined(separator: " · ")))
                    .font(.subheadline.weight(.semibold)).foregroundStyle(PP.ink)
                Text(status.filedEventName.map { "Filed under \($0)" } ?? "Not filed under a Pit Pass event")
                    .font(.caption).foregroundStyle(PP.textMuted)
            }
            Spacer(minLength: PP.Space.s3)
            if let feedEvent = onTrack.feedEventDbId {
                NavigationLink(value: TimingRoute.weekend(feedEvent)) {
                    Label("Open timing", systemImage: "stopwatch")
                }
                .buttonStyle(.borderedProminent)
            }
        }
    }
}

/// One weekend: every series at one track within a few days.
private struct WeekendSection: View {
    let weekend: LiveWeekend

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                Text(weekend.track ?? "Unnamed track").font(.title3.weight(.semibold)).foregroundStyle(PP.ink)
                Text(dates).font(.subheadline).foregroundStyle(PP.textMuted)
            }
            VStack(spacing: 0) {
                ForEach(Array(weekend.championships.enumerated()), id: \.element.id) { index, c in
                    if index > 0 { Divider().overlay(PP.border) }
                    NavigationLink(value: TimingRoute.weekend(c.feedEventDbId)) {
                        ChampionshipRow(c: c)
                    }
                    .buttonStyle(.plain)
                }
            }
            .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.lg))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg).strokeBorder(PP.border))
        }
        .padding(.top, PP.Space.s3)
    }

    private var dates: String {
        guard let from = weekend.fromMs else { return "" }
        let style = Date.FormatStyle().day().month(.abbreviated)
        let start = Date(timeIntervalSince1970: Double(from) / 1000)
        let year = String(Calendar.current.component(.year, from: start))
        guard let to = weekend.toMs else { return "\(start.formatted(style)) \(year)" }
        let end = Date(timeIntervalSince1970: Double(to) / 1000)
        let a = start.formatted(style), b = end.formatted(style)
        return a == b ? "\(a) \(year)" : "\(a) – \(b) \(year)"
    }
}

private struct ChampionshipRow: View {
    let c: WeekendChampionship

    var body: some View {
        HStack(alignment: .center, spacing: PP.Space.s4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(c.title).font(.body.weight(.semibold)).foregroundStyle(PP.ink)
                if let name = c.feedEventName { Text(name).font(.caption).foregroundStyle(PP.textMuted) }
            }
            .frame(minWidth: 260, alignment: .leading)
            Text(c.filedUnder).font(.subheadline)
                .foregroundStyle(c.eventId == nil ? PP.textMuted : PP.text)
                .frame(minWidth: 200, alignment: .leading)
            // Schedule order: the API lists newest first.
            Text(c.sessions.reversed().map { ($0.name ?? "Session") + ($0.current ? " · live" : "") }.joined(separator: ", "))
                .font(.subheadline).foregroundStyle(PP.textMuted).lineLimit(2)
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(PP.textMuted)
                .accessibilityHidden(true)
        }
        .padding(.vertical, PP.Space.s3)
        .padding(.horizontal, PP.Space.s4)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint("Opens its timing")
    }
}
