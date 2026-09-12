import SwiftUI

/// PitLaneModal, viewer side: the lane in physical order — box 1 / pit out at
/// the top, pit in at the bottom — with the landmark rows the broadcaster
/// orients by and collapsed runs of boxes used by the other series. Upload,
/// review and GPS anchors stay on the website; walk-to-box guidance follows
/// once Core Location is wired.
struct PitLaneSheet: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    let eventId: Int
    let sheet: Sheet
    @State private var assignments: Resource<PitAssignments>?

    var body: some View {
        NavigationStack {
            ScrollView {
                Group {
                    if let saved = assignments?.value {
                        lane(saved)
                    } else if let error = assignments?.error {
                        Text(error.contains("404") || error.lowercased().contains("no pit") ? "No pit assignments for this event yet." : error)
                            .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                    } else {
                        Text("Loading pit assignments…").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                    }
                }
                .padding(.vertical, PP.Space.s3).padding(.horizontal, 14)
                .frame(maxWidth: 680, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(PP.surface.ignoresSafeArea())
            .navigationTitle("Pit lane")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 0) {
                        Text("Pit lane").font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink)
                        if let saved = assignments?.value {
                            Text("\(saved.versionNote ?? saved.filename ?? "assignments") · uploaded \(saved.uploadedAt.prefix(10))")
                                .font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationBackground(PP.surface)
        .tint(PP.accentInk)
        .task {
            let r = Resource<PitAssignments>("/api/events/\(eventId)/pit-assignments")
            assignments = r
            await r.load(session.loader)
        }
    }

    private enum Item {
        case row(PitAssignmentRow)
        case mark(PitLandmark)
        case gap(Int)
    }

    private func items(_ saved: PitAssignments) -> [Item] {
        let rowByBox = Dictionary(saved.rows.map { ($0.boxNumber, $0) }, uniquingKeysWith: { a, _ in a })
        let marksAfter = Dictionary(grouping: saved.landmarks, by: \.afterBox)
        let maxBox = max(0, (saved.rows.map(\.boxNumber) + saved.landmarks.map(\.afterBox)).max() ?? 0)
        var out: [Item] = []
        var gap = 0
        func flush() { if gap > 0 { out.append(.gap(gap)) }; gap = 0 }
        for m in marksAfter[0] ?? [] { out.append(.mark(m)) }
        if maxBox >= 1 {
            for box in 1...maxBox {
                if let row = rowByBox[box] { flush(); out.append(.row(row)) } else { gap += 1 }
                if let marks = marksAfter[box] { flush(); marks.forEach { out.append(.mark($0)) } }
            }
        }
        flush()
        return out
    }

    private func lane(_ saved: PitAssignments) -> some View {
        let colors = Dictionary(sheet.classes.map { ($0.className, $0.color) }, uniquingKeysWith: { a, _ in a })
        return VStack(spacing: 0) {
            ForEach(Array(items(saved).enumerated()), id: \.0) { _, item in
                switch item {
                case let .mark(m): landmark(m)
                case let .gap(n):
                    Text(n == 1 ? "1 box · other series" : "\(n) boxes · other series")
                        .font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).padding(.vertical, 2)
                case let .row(r):
                    let color = r.className.flatMap { colors[$0] }
                    HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
                        Text(String(r.boxNumber)).font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.textMuted).frame(width: 28, alignment: .trailing)
                        Text("#\(r.carNumber)").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.ink).frame(width: 44, alignment: .leading)
                        Text(r.entryTeam ?? r.teamName ?? "").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text).lineLimit(1)
                        Spacer(minLength: 0)
                        if let cls = r.className { ClassTag(name: cls, color: color ?? ClassInfo.defaultColor) }
                    }
                    .padding(.vertical, 3).padding(.horizontal, PP.Space.s2)
                    .background((color.flatMap { Color(cssHex: $0) } ?? PP.textMuted).opacity(0.08))
                }
            }
        }
    }

    private func landmark(_ m: PitLandmark) -> some View {
        let l = m.label.uppercased()
        let kind: String = l.contains("PENALTY") ? "penalty"
            : (l.replacingOccurrences(of: " ", with: "").contains("S/F") || l.contains("TIMING")) ? "sf"
            : (l.hasPrefix("PIT IN") || l.hasPrefix("PIT OUT")) ? "end" : "break"
        return Text(m.label)
            .font(PP.sans(PP.TextSize.xs, weight: 600))
            .tracking(0.6)
            .foregroundStyle(kind == "sf" || kind == "penalty" ? PP.ink : PP.textMuted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 3).padding(.horizontal, PP.Space.s2)
            .background {
                switch kind {
                case "penalty": PP.accent.opacity(0.45)
                case "sf": Stripes()
                case "end": Color.clear
                default: PP.surface2
                }
            }
            .overlay(alignment: .top) { if kind == "sf" || kind == "end" { Rectangle().fill(PP.border).frame(height: 1) } }
            .overlay(alignment: .bottom) { if kind == "sf" || kind == "end" { Rectangle().fill(PP.border).frame(height: 1) } }
            .padding(.vertical, 2)
    }

    /// The S/F line reads like the timing stripe.
    private struct Stripes: View {
        var body: some View {
            GeometryReader { geo in
                HStack(spacing: 0) {
                    ForEach(0..<Int(geo.size.width / 8) + 1, id: \.self) { i in
                        Rectangle().fill(i % 2 == 0 ? PP.surface2 : PP.surface).frame(width: 8)
                    }
                }
            }
        }
    }
}
