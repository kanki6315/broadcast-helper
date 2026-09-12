import SwiftUI

/// SchedulePage: the plain calendar table. Rows open the event's results.
struct ScheduleView: View {
    @Environment(SeasonModel.self) private var model
    @State private var openEvent: CalendarEvent?

    var body: some View {
        let events = model.hub.value?.events ?? []
        if events.isEmpty {
            EmptyState(message: "No events yet — import a results file or entry list on the website.")
        } else {
            let today = Dates.today
            VStack(spacing: 0) {
                header
                ForEach(events) { e in
                    let upcoming = e.eventDate.map { $0 >= today } ?? false
                    HStack(alignment: .firstTextBaseline, spacing: 0) {
                        cell(GridCell.num(e.roundOrdinal.map(String.init) ?? "—"), width: 56, align: .trailing)
                        HStack(spacing: PP.Space.s2) {
                            Text(e.name).font(PP.sans(PP.TextSize.sm, weight: 500)).foregroundStyle(PP.text).lineLimit(1)
                            if upcoming { Badge(text: "upcoming") }
                        }
                        .padding(.horizontal, 10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        cell(GridCell.text(e.circuitName ?? ""), width: 240)
                        cell(GridCell.num(e.eventDate ?? ""), width: 120, align: .trailing)
                        cell(GridCell.num(e.entryCount > 0 ? String(e.entryCount) : "—"), width: 80, align: .trailing)
                        cell(GridCell.num(e.sessionCount > 0 ? String(e.sessionCount) : "—"), width: 90, align: .trailing)
                    }
                    .padding(.vertical, 6)
                    .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
                }
            }
            .padding(.top, PP.Space.s3)
            .padding(.bottom, PP.Space.s5)
            Text("Round results live under Results; the event sheet stays on the website for now.")
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
        }
    }

    private var header: some View {
        HStack(spacing: 0) {
            head("Rd", width: 56, align: .trailing)
            head("Event").frame(maxWidth: .infinity, alignment: .leading)
            head("Circuit", width: 240)
            head("Date", width: 120, align: .trailing)
            head("Entries", width: 80, align: .trailing)
            head("Sessions", width: 90, align: .trailing)
        }
        .padding(.vertical, 6)
        .overlay(alignment: .bottom) { Rectangle().fill(PP.borderStrong).frame(height: 1) }
    }

    private func head(_ text: String, width: CGFloat? = nil, align: Alignment = .leading) -> some View {
        Text(text).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
            .padding(.horizontal, 10)
            .frame(width: width, alignment: align)
    }

    private func cell(_ view: AnyView, width: CGFloat, align: Alignment = .leading) -> some View {
        view.padding(.horizontal, 10).frame(width: width, alignment: align)
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
