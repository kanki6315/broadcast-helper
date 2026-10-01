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
/// gaps, sectors, pits and drive time. An admin can say where a series
/// weekend is filed — automatic, an event, or not in Pit Pass — as on the
/// website's Timing page. Polled, never stored, like all live timing.
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
                        ForEach(list) { w in
                            WeekendSection(weekend: w, isAdmin: isAdmin) {
                                await weekends.poll(session.client, path: "/api/live/weekends")
                            }
                        }
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
        // "Timing" is the screen's own title; the bar carries only the back button.
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .tint(PP.accentInk)
        .task { await status.run(session.client, path: "/api/live/status", every: .seconds(5)) }
        .task { await weekends.run(session.client, path: "/api/live/weekends", every: .seconds(30)) }
    }

    private var isAdmin: Bool {
        if case let .ready(me) = session.phase { return me.isAdmin }
        return false
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
    let isAdmin: Bool
    /// Re-reads the weekends after an admin changes where one is filed.
    let changed: () async -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                Text(weekend.track ?? "Unnamed track").font(.title3.weight(.semibold)).foregroundStyle(PP.ink)
                Text(dates).font(.subheadline).foregroundStyle(PP.textMuted)
            }
            VStack(spacing: 0) {
                ForEach(Array(weekend.championships.enumerated()), id: \.element.id) { index, c in
                    if index > 0 { Divider().overlay(PP.border) }
                    // The menu sits beside the link, not in it: a tap on it must not open the weekend.
                    HStack(spacing: 0) {
                        NavigationLink(value: TimingRoute.weekend(c.feedEventDbId)) {
                            ChampionshipRow(c: c)
                        }
                        .buttonStyle(.plain)
                        if isAdmin {
                            FiledUnderMenu(c: c, changed: changed)
                                .padding(.trailing, PP.Space.s4)
                        }
                    }
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

/// Where an admin files a series weekend — the website's "Filed under"
/// select: automatic (by championship; the default), an event, or not in
/// Pit Pass. The latter two hold until changed; automatic tries at once.
private struct FiledUnderMenu: View {
    @Environment(AppSession.self) private var session
    let c: WeekendChampionship
    let changed: () async -> Void
    @State private var busy = false
    @State private var problem: String?

    private var automatic: Bool { c.boundBy == nil || c.boundBy == "AUTO" }

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            if busy {
                ProgressView().frame(height: 30)
            } else {
                Menu {
                    Button { choose(FeedEventBinding(auto: true)) } label: {
                        option(c.boundBy == "AUTO" && c.eventId != nil ? "Automatic: \(c.eventName ?? "an event")" : "Automatic",
                               selected: automatic)
                    }
                    Button { choose(FeedEventBinding(none: true)) } label: {
                        option("Not in Pit Pass", selected: c.boundBy == "ADMIN_NONE")
                    }
                    if !c.candidates.isEmpty {
                        Section("File under") {
                            ForEach(c.candidates) { e in
                                // Name as the title, series and date beneath: one line each, not one wrapped run.
                                Button { choose(FeedEventBinding(eventId: e.id)) } label: {
                                    option(e.name, detail: [e.seriesName, e.date].compactMap { $0 }.filter { !$0.isEmpty }
                                               .joined(separator: " · "),
                                           selected: c.boundBy == "ADMIN" && c.eventId == e.id)
                                }
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text("Filed under").font(.subheadline)
                        Image(systemName: "chevron.up.chevron.down").font(.caption)
                    }
                }
                .menuStyle(.button)
                .buttonStyle(.bordered)
                .accessibilityLabel("Filed under, for \(c.title)")
                .accessibilityValue(c.filedUnder)
            }
            if let problem { Text(problem).font(.caption).foregroundStyle(PP.error).lineLimit(2) }
        }
    }

    /// A menu item: two Texts in a label are the title and subtitle; the chosen one is ticked.
    @ViewBuilder private func option(_ text: String, detail: String? = nil, selected: Bool) -> some View {
        if selected {
            Label {
                Text(text)
                if let detail { Text(detail) }
            } icon: {
                Image(systemName: "checkmark")
            }
        } else {
            Text(text)
            if let detail { Text(detail) }
        }
    }

    private func choose(_ binding: FeedEventBinding) {
        Task {
            busy = true
            defer { busy = false }
            do {
                let _: WeekendChampionship = try await session.client.putJSON(
                    "/api/live/feed-events/\(c.feedEventDbId)/event", body: binding)
                problem = nil
                await changed()
            } catch {
                problem = error.localizedDescription
            }
        }
    }
}
