import SwiftUI

/// PitLaneModal: the lane in physical order — box 1 / pit out at the top,
/// pit in at the bottom — with the landmark rows the broadcaster orients by
/// and collapsed runs of boxes used by the other series. Tapping a car starts
/// walk-to-box guidance from the GPS anchors; admins can also mark anchors
/// here, since the iPad in the lane is the device that knows where box 12 is
/// (the one write besides the scratchpad). Upload and review stay on the website.
struct PitLaneSheet: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    let eventId: Int
    let sheet: Sheet
    @State private var assignments: Resource<PitAssignments>?
    @State private var target: Target?
    @State private var location = LocationWatcher()
    @State private var anchorsOpen = false
    @State private var anchorBox = ""
    @State private var anchorError: String?
    @State private var sampling: Sampling?
    @State private var busy = false

    private struct Target: Equatable {
        let boxNumber: Int
        let carNumber: String
        let team: String
    }

    private struct Sampling {
        let box: Int
        var secondsLeft: Int
    }

    /// How long "Mark my location" samples before averaging.
    private static let sampleSeconds = 10

    private var isAdmin: Bool { if case let .ready(me) = session.phase { return me.isAdmin } else { return false } }

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
        .presentationSizing(.page)
        .tint(PP.accentInk)
        .task {
            let r = Resource<PitAssignments>("/api/events/\(eventId)/pit-assignments")
            assignments = r
            await r.load(session.loader)
        }
        .onDisappear { location.stop() }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
                if let target { guideBar(target) }
                if isAdmin, anchorsOpen, let saved = assignments?.value { anchorPanel(saved) }
                if isAdmin, assignments?.value != nil { adminFooter }
            }
        }
    }

    // MARK: guidance (.pl-guide)

    private func guideBar(_ target: Target) -> some View {
        let anchors = assignments?.value?.anchors ?? []
        let guidance = location.fix.map { PitLaneGeo.guide(anchors: anchors, fix: GeoFix(lat: $0.lat, lng: $0.lng), targetBox: target.boxNumber) } ?? nil
        return HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
            (Text("#\(target.carNumber) \(target.team)").fontWeight(.semibold).foregroundColor(PP.ink) + Text(" · box \(target.boxNumber)"))
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text).lineLimit(1)
            Group {
                if anchors.count < 2 {
                    Text(isAdmin ? "Set at least two GPS anchors below to enable guidance." : "GPS guidance is not set up for this event yet.")
                } else if let error = location.error {
                    Text(error)
                } else if let guidance {
                    if guidance.arrived {
                        Text("You're at box \(target.boxNumber)").fontWeight(.semibold).foregroundStyle(PP.ink)
                    } else {
                        (Text("\(PitLaneGeo.guidanceText(guidance)) toward ")
                         + Text(guidance.direction == .pitIn ? "pit in" : "pit out").fontWeight(.semibold).foregroundColor(PP.ink)
                         + Text(" · you're near box \(Int(guidance.currentBox.rounded()))")
                         + Text((location.fix?.accuracy ?? 0) > 25 ? " · GPS weak (±\(Int(((location.fix?.accuracy ?? 0) * PitLaneGeo.feetPerMeter).rounded())) ft)" : "").foregroundColor(PP.error))
                    }
                } else {
                    Text("Locating…")
                }
            }
            .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text)
            .frame(maxWidth: .infinity, alignment: .leading)
            Button { stopGuidance() } label: { Image(systemName: "xmark").font(.system(size: 13, weight: .medium)).foregroundStyle(PP.textMuted) }
                .buttonStyle(.plain)
                .accessibilityLabel("Stop guidance")
        }
        .padding(.vertical, 8).padding(.horizontal, 14)
        .background(PP.surface2)
        .overlay(alignment: .top) { Rectangle().fill(PP.border).frame(height: 1) }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private func startGuidance(_ t: Target) {
        target = t
        location.start()
    }

    private func stopGuidance() {
        target = nil
        if sampling == nil { location.stop() }
    }

    // MARK: anchors (.pl-anchors)

    private var adminFooter: some View {
        HStack(spacing: PP.Space.s3) {
            Button("GPS anchors (\(assignments?.value?.anchors.count ?? 0))") { withAnimation(PP.Motion.fast) { anchorsOpen.toggle() } }
                .buttonStyle(PPSecondaryButtonStyle())
            Spacer()
        }
        .padding(.vertical, 8).padding(.horizontal, 14)
        .background(PP.surface)
        .overlay(alignment: .top) { Rectangle().fill(PP.border).frame(height: 1) }
    }

    private func anchorPanel(_ saved: PitAssignments) -> some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            if saved.anchors.isEmpty {
                Text("No anchors yet — stand at a box and mark it.").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
            } else {
                FlowLayout(horizontalSpacing: PP.Space.s2, verticalSpacing: PP.Space.s1) {
                    ForEach(saved.anchors.sorted { $0.boxNumber < $1.boxNumber }, id: \.boxNumber) { a in
                        HStack(spacing: 4) {
                            Text("box \(a.boxNumber)").font(PP.sans(PP.TextSize.xs, weight: 500))
                            if let acc = a.accuracyM { Text("±\(Int(acc.rounded())) m").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted) }
                            Button { removeAnchor(a.boxNumber) } label: { Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).foregroundStyle(PP.textMuted) }
                                .buttonStyle(.plain).disabled(busy)
                                .accessibilityLabel("Remove anchor at box \(a.boxNumber)")
                        }
                        .padding(.vertical, 2).padding(.horizontal, 8)
                        .overlay(Capsule().strokeBorder(PP.borderStrong))
                    }
                }
            }
            HStack(spacing: PP.Space.s2) {
                if let sampling {
                    Text("Sampling box \(sampling.box)… \(sampling.secondsLeft)s · \(location.samples.count) \(location.samples.count == 1 ? "fix" : "fixes")\(location.fix.map { " · ±\(Int($0.accuracy.rounded())) m" } ?? "") — stand still")
                        .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text)
                    Button("Cancel") { stopSampling() }.buttonStyle(PPSecondaryButtonStyle())
                } else {
                    TextField("box #", text: $anchorBox)
                        .keyboardType(.numberPad)
                        .font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
                        .padding(.vertical, 5).padding(.horizontal, 10)
                        .frame(width: 96)
                        .background(PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.borderStrong))
                        .accessibilityLabel("Box number you are standing at")
                    Button("Mark my location") { markAnchor() }.buttonStyle(PPSecondaryButtonStyle()).disabled(busy)
                }
            }
            if let anchorError { Text(anchorError).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.error) }
        }
        .padding(.vertical, 8).padding(.horizontal, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PP.surface)
        .overlay(alignment: .top) { Rectangle().fill(PP.border).frame(height: 1) }
    }

    /// Sample for ten seconds and save the weighted average — one snapshot fix
    /// proved too jumpy against pit-building multipath.
    private func markAnchor() {
        guard let box = Int(anchorBox.trimmingCharacters(in: .whitespaces)), box >= 1 else {
            anchorError = "Enter the box number you are standing at."
            return
        }
        anchorError = nil
        location.stop()
        location.start()
        sampling = Sampling(box: box, secondsLeft: Self.sampleSeconds)
        Task {
            for _ in 0..<Self.sampleSeconds {
                try? await Task.sleep(for: .seconds(1))
                guard sampling != nil else { return }
                sampling?.secondsLeft -= 1
            }
            guard sampling != nil else { return }
            let samples = location.samples
            stopSampling()
            guard let averaged = PitLaneGeo.averageFixes(samples) else {
                anchorError = "No GPS fixes arrived — try again in the open."
                return
            }
            await saveAnchor(box, averaged)
        }
    }

    private func stopSampling() {
        sampling = nil
        if target == nil { location.stop() }
    }

    private func saveAnchor(_ box: Int, _ fix: PitLaneGeo.AveragedFix) async {
        busy = true
        defer { busy = false }
        do {
            let saved: PitAssignments = try await session.client.putJSON("/api/events/\(eventId)/pit-assignments/anchors/\(box)",
                                                                          body: ["lat": fix.lat, "lng": fix.lng, "accuracyM": fix.accuracyM])
            assignments?.replace(saved)
            anchorBox = ""
        } catch {
            anchorError = error.localizedDescription
        }
    }

    private func removeAnchor(_ box: Int) {
        busy = true
        anchorError = nil
        Task {
            defer { busy = false }
            do {
                let data = try await session.client.send("DELETE", "/api/events/\(eventId)/pit-assignments/anchors/\(box)")
                let saved: PitAssignments = try session.client.decode(data)
                assignments?.replace(saved)
            } catch {
                anchorError = error.localizedDescription
            }
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
                    Button {
                        startGuidance(Target(boxNumber: r.boxNumber, carNumber: r.carNumber, team: r.entryTeam ?? r.teamName ?? ""))
                    } label: {
                    HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
                        Text(String(r.boxNumber)).font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.textMuted).frame(width: 28, alignment: .trailing)
                        Text("#\(r.carNumber)").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.ink).frame(width: 44, alignment: .leading)
                        Text(r.entryTeam ?? r.teamName ?? "").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text).lineLimit(1)
                        Spacer(minLength: 0)
                        if let cls = r.className { ClassTag(name: cls, color: color ?? ClassInfo.defaultColor) }
                    }
                    .padding(.vertical, 3).padding(.horizontal, PP.Space.s2)
                    .background((color.flatMap { Color(cssHex: $0) } ?? PP.textMuted).opacity(target?.boxNumber == r.boxNumber ? 0.18 : 0.08))
                    .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Guide me to box \(r.boxNumber), #\(r.carNumber) \(r.entryTeam ?? r.teamName ?? "")")
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
