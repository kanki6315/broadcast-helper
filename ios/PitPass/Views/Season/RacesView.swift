import SwiftUI

/// One chronological calendar for event sheets and imported session results.
struct RacesView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model
    @State private var expandedEvent: Int?

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
        VStack(alignment: .leading, spacing: 0) {
            if events.isEmpty {
                EmptyState(message: "No events yet — import a results file or entry list on the website.")
            } else {
                Text("Select a round for results and its event sheet.")
                    .ppBody()
                    .padding(.bottom, PP.Space.s3)
                ForEach(events) { event in
                    DisclosureGroup(isExpanded: Binding(
                        get: { expandedEvent == event.id },
                        set: { expandedEvent = $0 ? event.id : nil }
                    )) {
                        roundContent(event)
                    } label: {
                        roundLabel(event, isNext: event.id == nextEvent?.id, today: today)
                    }
                    .tint(PP.accentInk)
                    .padding(.vertical, PP.Space.s3)
                    .overlay(alignment: .bottom) {
                        Rectangle().fill(PP.border).frame(height: 1)
                    }
                }
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
            .buttonStyle(PPSecondaryButtonStyle())
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
