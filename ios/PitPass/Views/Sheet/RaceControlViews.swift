import SwiftUI

// Race control's messages from the Al Kamel feed (web: RaceControl.tsx): a
// strip above the tower with what race control's screen shows now — or, when
// it shows nothing, its newest message — and the session's whole log as its
// own view. Race control's colours are only a bar beside the text, so the
// text keeps our ink in both appearances.

private struct MessageBar: View {
    let color: String?
    var body: some View {
        RoundedRectangle(cornerRadius: 1.5)
            .fill(color.flatMap { Color(cssHex: $0) } ?? PP.borderStrong)
            .frame(width: 3)
            .accessibilityHidden(true)
    }
}

struct RaceControlStrip: View {
    let now: RaceControlNow?
    let utcOffsetHours: Double?
    let showAll: () -> Void

    var body: some View {
        if let now, !now.lines.isEmpty || now.latest != nil {
            let latest = now.latest
            // The newest message is usually on the screen already; say it once.
            let latestOnScreen = latest.map { l in now.lines.contains { $0.text == l.text } } ?? false
            HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
                Text("RACE CONTROL")
                    .font(.caption2.weight(.semibold)).tracking(0.5)
                    .foregroundStyle(PP.textMuted)
                VStack(alignment: .leading, spacing: PP.Space.s1) {
                    ForEach(now.lines) { m in
                        HStack(spacing: PP.Space.s2) {
                            MessageBar(color: m.background)
                            if let group = m.group { Text(group).font(.caption.weight(.semibold)).foregroundStyle(PP.textMuted) }
                            Text(m.text).font(.subheadline.weight(.semibold)).foregroundStyle(PP.ink)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    if let latest, !latestOnScreen {
                        HStack(spacing: PP.Space.s2) {
                            MessageBar(color: latest.background)
                            if let t = TimingFormat.messageTime(dayTimeMs: latest.dayTimeMs, utcOffsetHours: utcOffsetHours) {
                                Text(t).font(.caption.monospacedDigit()).foregroundStyle(PP.textMuted)
                            }
                            if let group = latest.group { Text(group).font(.caption.weight(.semibold)).foregroundStyle(PP.textMuted) }
                            Text(latest.text).font(.subheadline).foregroundStyle(PP.text)
                        }
                        .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button("All messages", action: showAll).font(.caption)
            }
            .padding(.vertical, PP.Space.s2).padding(.horizontal, PP.Space.s3)
            .background(PP.surface, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(PP.border))
            .accessibilityElement(children: .combine)
            .accessibilityLabel("Race control")
        }
    }
}

/// The session's log, newest first. Times are at the track when its offset is known (the live session).
struct RaceControlSection: View {
    @Environment(AppSession.self) private var session
    let chosen: LiveSessionSummary
    let utcOffsetHours: Double?
    @State private var feed = LiveFeed<RaceControlLog>()

    var body: some View {
        Group {
            if let value = feed.value {
                if value.messages.isEmpty {
                    EmptyState(message: "No race control messages recorded for this session.")
                } else {
                    VStack(alignment: .leading, spacing: PP.Space.s3) {
                        if let error = feed.error {
                            Text("Not updating: \(error)").font(.caption).foregroundStyle(PP.error).lineLimit(1)
                        }
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(value.messages) { m in
                                row(m)
                                if m.id != value.messages.last?.id { Divider().overlay(PP.border) }
                            }
                        }
                        .background(PP.bg)
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .overlay(RoundedRectangle(cornerRadius: 6).stroke(PP.border))
                        if utcOffsetHours == nil {
                            Text("Times are this iPad's clock: the track's offset is known only while the session is live.")
                                .font(.caption).foregroundStyle(PP.textMuted)
                        }
                    }
                }
            } else if let error = feed.error {
                ErrorPanel(message: "Could not load race control messages: \(error)")
            } else {
                SkeletonLines()
            }
        }
        .task(id: chosen.sessionDbId) {
            await feed.run(session.client, path: "/api/live/race-control?session=\(chosen.sessionDbId)",
                           every: chosen.current ? .seconds(5) : .seconds(120))
        }
    }

    private func row(_ m: RaceControlMessage) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
            Text(TimingFormat.messageTime(dayTimeMs: m.dayTimeMs, utcOffsetHours: utcOffsetHours) ?? "—")
                .font(.subheadline.monospacedDigit()).foregroundStyle(PP.text)
                .frame(width: 72, alignment: .leading)
            Text(m.group ?? "").font(.subheadline).foregroundStyle(PP.textMuted)
                .frame(width: 80, alignment: .leading)
            Text(m.text).font(.subheadline).foregroundStyle(PP.ink)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, PP.Space.s2).padding(.horizontal, PP.Space.s3)
        .overlay(alignment: .leading) { MessageBar(color: m.background) }
        .accessibilityElement(children: .combine)
    }
}
