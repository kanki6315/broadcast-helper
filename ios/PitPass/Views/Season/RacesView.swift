import SwiftUI

/// One chronological calendar for event sheets and imported session results.
struct RacesView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model
    @State private var selectedEvent: Int?

    private var events: [CalendarEvent] {
        (model.hub.value?.events ?? []).sorted {
            // ISO dates sort chronologically; undated events follow dated rounds.
            if $0.eventDate != $1.eventDate {
                return ($0.eventDate ?? "9999") < ($1.eventDate ?? "9999")
            }
            if $0.roundOrdinal != $1.roundOrdinal {
                return ($0.roundOrdinal ?? Int.max) < ($1.roundOrdinal ?? Int.max)
            }
            return $0.id < $1.id
        }
    }

    var body: some View {
        let today = Dates.today
        let nextEvent = events.first { ($0.eventDate ?? "") >= today }
        let selected = events.first { $0.id == selectedEvent } ?? nextEvent ?? events.last
        VStack(alignment: .leading, spacing: PP.Space.s4) {
            if let selected {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal) {
                        HStack(spacing: PP.Space.s2) {
                            ForEach(events) { event in
                                Button { selectedEvent = event.id } label: {
                                    VStack(spacing: 4) {
                                        Text(Venue.of(eventName: event.name, circuitName: event.circuitName))
                                            .font(.subheadline.weight(.semibold))
                                        Text([event.roundOrdinal.map { "Rd \($0)" }, event.eventDate].compactMap { $0 }.joined(separator: " · "))
                                            .font(.caption)
                                    }
                                    .padding(.vertical, 6)
                                }
                                .buttonStyle(.bordered)
                                .tint(event.id == selected.id ? PP.accentInk : PP.textMuted)
                                .accessibilityAddTraits(event.id == selected.id ? .isSelected : [])
                                .accessibilityLabel(event.name + (event.eventDate.map { ", " + $0 } ?? ""))
                                .id(event.id)
                            }
                        }
                    }
                    .scrollIndicators(.hidden)
                    .onAppear { proxy.scrollTo(selected.id, anchor: .center) }
                    .onChange(of: selected.id) { _, id in proxy.scrollTo(id, anchor: .center) }
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { _ in
                        proxy.scrollTo(selected.id, anchor: .center)
                    }
                }
                roundLabel(selected, isNext: selected.id == nextEvent?.id, today: today)
                roundContent(selected)
            } else {
                EmptyState(message: "No events yet — import a results file or entry list on the website.")
            }
        }
    }

    private func roundLabel(_ event: CalendarEvent, isNext: Bool, today: String) -> some View {
        VStack(alignment: .leading, spacing: PP.Space.s1) {
            HStack(spacing: PP.Space.s2) {
                if let round = event.roundOrdinal {
                    Text("Rd \(round)").font(PP.mono(PP.TextSize.sm, weight: 600))
                        .foregroundStyle(PP.textMuted)
                }
                Text(event.name).font(PP.sans(PP.TextSize.base, weight: 600))
                    .foregroundStyle(PP.ink)
                if session.downloads.record(for: .event(event.id)) != nil {
                    Image(systemName: "arrow.down.circle.fill")
                        .foregroundStyle(PP.textMuted)
                        .accessibilityLabel("Downloaded for offline use")
                }
            }
            Text([event.eventDate ?? "Date TBC", event.circuitName].compactMap { $0 }.joined(separator: " · "))
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: PP.Space.s2) { status(event, isNext: isNext, today: today) }
                VStack(alignment: .leading, spacing: PP.Space.s1) { status(event, isNext: isNext, today: today) }
            }
        }
        .padding(.vertical, PP.Space.s1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func status(_ event: CalendarEvent, isNext: Bool, today: String) -> some View {
        if event.eventDate == today {
            Badge(text: "Today")
        } else if isNext {
            Badge(text: "Next round")
        } else if let date = event.eventDate, date > today {
            Badge(text: "Upcoming")
        }
        if event.sessionCount > 0 {
            Text("\(event.sessionCount) session\(event.sessionCount == 1 ? "" : "s") available")
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.accentInk)
        }
        if event.entryCount > 0 {
            Text("\(event.entryCount) entries")
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
        }
    }

    private func roundContent(_ event: CalendarEvent) -> some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            NavigationLink(value: SheetRoute(eventId: event.id)) {
                Label("Open event sheet", systemImage: "doc.text")
            }
            .buttonStyle(.bordered)
            if event.sessionCount > 0 {
                ResultsView(eventId: event.id).id(event.id)
            } else {
                Text("No session results imported yet.")
                    .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            }
        }
        .padding(.vertical, PP.Space.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// `.badge.muted`
struct Badge: View {
    let text: String
    var body: some View {
        Text(text)
            .font(PP.sans(PP.TextSize.xs, weight: 600))
            .foregroundStyle(PP.textMuted)
            .padding(.vertical, 1).padding(.horizontal, 6)
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous).strokeBorder(PP.textMuted))
    }
}
