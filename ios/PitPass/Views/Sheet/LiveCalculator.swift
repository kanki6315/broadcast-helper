import SwiftUI

/// Where the live timing connection stands, and — for an admin — the switch.
/// The account behind the feed allows one login, held by the server for
/// everyone, so connecting and disconnecting are shared acts: disconnecting
/// asks first.
struct LiveTimingBar: View {
    @Environment(AppSession.self) private var session
    let eventId: Int
    let status: LiveFeed<LiveStatus>
    @State private var busy = false
    @State private var actionError: String?
    @State private var confirmingDisconnect = false

    private var isAdmin: Bool {
        if case let .ready(me) = session.phase { return me.isAdmin }
        return false
    }

    var body: some View {
        if let value = status.value, value.configured {
            VStack(alignment: .leading, spacing: PP.Space.s2) {
                HStack(alignment: .firstTextBaseline, spacing: PP.Space.s3) {
                    Circle().fill(dot(value)).frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(headline(value)).font(.subheadline.weight(.semibold))
                        if let detail = detail(value) {
                            Text(detail).font(.caption).foregroundStyle(PP.textMuted)
                        }
                    }
                    Spacer(minLength: PP.Space.s3)
                    if isAdmin { controls(value) }
                }
                if let actionError { Text(actionError).font(.caption).foregroundStyle(PP.error) }
            }
            .padding(.vertical, PP.Space.s3)
            .padding(.horizontal, PP.Space.s4)
            .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Live timing")
            .confirmationDialog("Disconnect live timing?", isPresented: $confirmingDisconnect, titleVisibility: .visible) {
                Button("Disconnect for everyone", role: .destructive) { Task { await disconnect() } }
            } message: {
                Text("The server holds one connection for every Pit Pass user. Live points stop for all of them.")
            }
        }
    }

    @ViewBuilder private func controls(_ value: LiveStatus) -> some View {
        if busy {
            ProgressView()
        } else if !value.desiredConnected {
            Button("Connect for this event") { Task { await connect() } }.buttonStyle(.borderedProminent)
        } else {
            if value.eventId != eventId {
                Button("Score this event") { Task { await connect() } }.buttonStyle(.bordered)
            }
            Button("Disconnect") { confirmingDisconnect = true }.buttonStyle(.bordered)
        }
    }

    private func dot(_ value: LiveStatus) -> Color {
        switch shownState(value) {
        case "LIVE": PP.success
        case "BACKING_OFF": PP.error
        case "CONNECTING", "STANDBY": PP.accent
        default: PP.textMuted
        }
    }

    /// What to say the connection is. Nobody asking for it outranks whatever
    /// the socket is still doing.
    private func shownState(_ value: LiveStatus) -> String { value.desiredConnected ? value.state : "OFF" }

    private func headline(_ value: LiveStatus) -> String {
        switch shownState(value) {
        case "LIVE":
            let session = [value.session?.name, value.session?.flag.map { $0.replacingOccurrences(of: "_", with: " ").capitalized }]
                .compactMap { $0 }.joined(separator: " · ")
            return session.isEmpty ? "Live timing connected" : "Live · \(session)"
        case "BACKING_OFF": return "Live timing lost — retrying"
        case "CONNECTING", "STANDBY": return "Connecting to live timing…"
        default: return "Live timing is off"
        }
    }

    private func detail(_ value: LiveStatus) -> String? {
        let state = shownState(value)
        if state == "BACKING_OFF" { return value.lastError ?? "Showing the last known order." }
        if value.desiredConnected, value.eventId != eventId {
            return "Scoring against \(value.eventName ?? "another event"), not this one."
        }
        if state == "LIVE" {
            return [value.session?.championship, value.session?.event].compactMap { $0 }.joined(separator: " · ")
                + (value.replaying ? " · replay" : "")
        }
        if state == "OFF" { return isAdmin ? "Connect during a session to project the standings as they run." : "An admin can connect it during a session." }
        return nil
    }

    private func connect() async {
        busy = true
        defer { busy = false }
        do {
            let updated: LiveStatus = try await session.client.postJSON("/api/live/connect", body: LiveConnectRequest(eventId: eventId))
            status.replace(updated)
            actionError = nil
        } catch { actionError = error.localizedDescription }
    }

    private func disconnect() async {
        busy = true
        defer { busy = false }
        do {
            let data = try await session.client.send("POST", "/api/live/disconnect")
            let updated: LiveStatus = try session.client.decode(data)
            status.replace(updated)
            actionError = nil
        } catch { actionError = error.localizedDescription }
    }
}

/// Live mode: every class of one championship kind, projected from where the
/// field is running. Read-only — there is nothing to set.
struct LiveChampionshipWorkspace: View {
    let championships: [ChampionshipSummary]
    let eventId: Int
    var initialRecaps: [Int: Recap] = [:]
    var initialLive: [Int: LiveChampionship] = [:]
    @State private var kind = "TEAMS"
    @State private var hidden = Set<String>()

    private var kinds: [String] {
        ChampionshipCalculator.liveKinds.filter { kind in championships.contains { $0.kind == kind } }
    }
    private var shownKind: String { kinds.contains(kind) ? kind : kinds.first ?? "TEAMS" }
    private var available: [ChampionshipSummary] {
        var seen = Set<String>()
        return championships.filter { $0.kind == shownKind && seen.insert($0.className ?? "").inserted }
    }
    private var shown: [ChampionshipSummary] {
        let visible = available.filter { !hidden.contains($0.className ?? "") }
        return visible.isEmpty ? Array(available.prefix(1)) : visible
    }
    private func label(_ kind: String) -> String {
        championships.first { $0.kind == kind }?.kindLabel ?? kind.capitalized
    }

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if available.isEmpty {
                EmptyState(message: "Import IMSA WeatherTech or Michelin Pilot Challenge standings to project them live.")
            } else {
                Picker("Championship", selection: Binding(get: { shownKind }, set: { kind = $0 })) {
                    ForEach(kinds, id: \.self) { Text(label($0)).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 420)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: PP.Space.s2) { classButtons }
                    VStack(alignment: .leading, spacing: PP.Space.s1) { classButtons }
                }
                // One column always: a live table is read across, and two side by
                // side would each need scrolling sideways on an iPad.
                LazyVStack(alignment: .leading, spacing: PP.Space.s5) {
                    ForEach(shown) { champ in
                        VStack(alignment: .leading, spacing: PP.Space.s3) {
                            Divider()
                            Text(champ.className ?? champ.title).font(.headline)
                            LiveChampionshipPanel(championship: champ, eventId: eventId,
                                                  initialRecap: initialRecaps[champ.id], initialLive: initialLive[champ.id])
                                .id(champ.id)
                        }
                        .frame(maxWidth: .infinity, alignment: .topLeading)
                        .accessibilityElement(children: .contain)
                        .accessibilityLabel("\(champ.className ?? champ.title) live \(label(shownKind).lowercased())")
                    }
                }
                Text("Provisional. Projected = imported + points for the positions as they run. Guest eligibility, drive-time minimums, penalties still to come and official tie-breaks are not applied; equal totals remain tied. The Endurance Cup is not projected live.")
                    .font(.caption).foregroundStyle(PP.textMuted)
            }
        }
        .tint(PP.accentInk)
    }

    @ViewBuilder private var classButtons: some View {
        ForEach(available) { champ in
            let name = champ.className ?? ""
            let active = shown.contains { $0.id == champ.id }
            Button {
                if active { hidden.insert(name) } else { hidden.remove(name) }
            } label: {
                Label(name, systemImage: active ? "checkmark.circle.fill" : "circle")
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.bordered)
            .tint(active ? PP.accentInk : PP.textMuted)
            .disabled(active && shown.count == 1)
            .accessibilityLabel("Show \(name)")
            .accessibilityValue(active ? "Shown" : "Hidden")
        }
    }
}

/// One class: imported standings (loaded once, stored like any document) and
/// the live positions (polled, never stored).
private struct LiveChampionshipPanel: View {
    @Environment(AppSession.self) private var session
    let championship: ChampionshipSummary
    let eventId: Int
    private let initialLive: LiveChampionship?
    private let injected: Bool
    @State private var recap: Resource<Recap>
    @State private var live = LiveFeed<LiveChampionship>()
    @State private var showsAll = false

    init(championship: ChampionshipSummary, eventId: Int, initialRecap: Recap? = nil, initialLive: LiveChampionship? = nil) {
        self.championship = championship
        self.eventId = eventId
        self.initialLive = initialLive
        injected = initialRecap != nil
        let resource = Resource<Recap>("/api/championships/\(championship.id)/calculator")
        if let initialRecap { resource.replace(initialRecap) }
        _recap = State(initialValue: resource)
    }

    private var current: LiveChampionship? { live.value ?? initialLive }

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if let value = recap.value {
                if let issue = ChampionshipCalculator.baselineIssue(value, eventId: eventId) {
                    ErrorPanel(message: issue)
                } else if let current {
                    LiveChampionshipTable(recap: value, live: current, showsAll: $showsAll)
                } else if let error = live.error {
                    ErrorPanel(message: error)
                } else { ProgressView("Waiting for the running order…") }
            } else if let error = recap.error {
                ErrorPanel(message: error)
                Button("Retry") { Task { await recap.load(session.loader) } }
            } else { ProgressView("Loading imported standings…") }
        }
        .task {
            guard !injected else { return }
            await recap.load(session.loader, connectivity: session.connectivity, freshness: session.freshness)
        }
        .task {
            guard !injected else { return }
            await live.run(session.client, path: "/api/live/championships/\(championship.id)")
        }
        // Nothing here is typed in, so a newer import has no draft to protect.
        .onChange(of: recap.pendingUpdate != nil) { _, pending in if pending { recap.applyPendingUpdate() } }
    }
}

struct LiveChampionshipTable: View {
    let recap: Recap
    let live: LiveChampionship
    @Binding var showsAll: Bool
    private let shortList = 12

    private var phases: [String] { ChampionshipCalculator.phases(seriesName: recap.championship.seriesName) }
    private var lines: [ChampionshipCalculator.LiveLine] { ChampionshipCalculator.liveLines(recap, live: live) }
    private func number(_ value: Double) -> String { value.formatted(.number.precision(.fractionLength(0...2))) }

    var body: some View {
        let lines = lines
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            ForEach(notices, id: \.self) { Text($0).font(.caption).foregroundStyle(PP.textMuted) }
            if live.livePhase != nil || live.qualifyingImported {
                table(showsAll ? lines : Array(lines.prefix(shortList)))
                if lines.count > shortList {
                    Button(showsAll ? "Show the top \(shortList)" : "Show all \(lines.count)") { showsAll.toggle() }
                        .frame(minHeight: 44)
                }
            }
            if !live.newcomers.isEmpty {
                Text("Scoring without a standings row (baseline 0): "
                     + live.newcomers.sorted { $0.position < $1.position }
                        .map { "\($0.name) · #\($0.carNumber) · P\($0.position)" }.joined(separator: "; "))
                    .font(.caption).foregroundStyle(PP.textMuted)
            }
        }
    }

    private var notices: [String] {
        var result: [String] = []
        if live.state == "BACKING_OFF" { result.append("Connection lost — this is the last known order.") }
        switch live.livePhase {
        case "RACE":
            if phases.contains("Qualifying"), !live.qualifyingImported {
                result.append("This weekend's qualifying result is not imported, so its points are missing from the projection. Import it to include them.")
            }
        case "QUALIFYING":
            result.append(phases.contains("Qualifying") ? "Qualifying is running: positions fill the qualifying column. Classes that are not on track show none."
                          : "Qualifying does not pay championship points in this series.")
        default:
            result.append("The session on track does not pay points. The table fills when qualifying or the race is running.")
        }
        return result
    }

    private func table(_ lines: [ChampionshipCalculator.LiveLine]) -> some View {
        // Sized so the whole table fits an 11" iPad in portrait (786pt) with
        // both scoring columns; the name column takes whatever a wider screen adds.
        var who = GridColumn.text("who", live.kind == "DRIVERS" ? "Driver" : live.kind == "MANUFACTURERS" ? "Manufacturer" : "Team", width: 166)
        who.growthWeight = 1
        let identity = [GridColumn.text("rank", "Rank", width: 58), GridColumn.text("move", "±", width: 44, align: .center), who]
        let data = [GridColumn.text("total", "Projected", width: 86, align: .trailing),
                    GridColumn.text("gap", "Gap", width: 58, align: .trailing),
                    GridColumn.text("base", "Imported", width: 78, align: .trailing)]
            + phases.enumerated().map { GridColumn.text("phase\($0.offset)", $0.element, width: 82, align: .center) }
            + [GridColumn.text("running", "Running", width: 126)]
        let items = lines.map { line in
            GridRowItem(id: line.id,
                        ident: [AnyView(Text("\(line.projection.tied ? "=" : "")\(line.projection.rank)").lineLimit(1)),
                                AnyView(movement(line.movement)),
                                AnyView(Text(ChampionshipCalculator.liveName(line.projection.row, kind: live.kind)).font(.subheadline).lineLimit(2))],
                        cells: cells(line), lines: 2)
        }
        return GridTable(identColumns: identity, dataColumns: data, sections: [GridSection(id: "live", rows: items)], lineHeight: 22, cellPadV: 6)
    }

    @ViewBuilder private func movement(_ places: Int) -> some View {
        if places == 0 {
            Text("–").foregroundStyle(PP.textMuted).accessibilityLabel("No change")
        } else {
            Text("\(places > 0 ? "▲" : "▼")\(abs(places))")
                .font(.caption.weight(.semibold))
                .foregroundStyle(places > 0 ? PP.success : PP.error)
                .accessibilityLabel("\(places > 0 ? "Up" : "Down") \(abs(places)) \(abs(places) == 1 ? "place" : "places")")
        }
    }

    private func cells(_ line: ChampionshipCalculator.LiveLine) -> [AnyView] {
        var result = [AnyView(Text(number(line.projection.total)).bold().foregroundStyle(PP.accentInk)),
                      AnyView(Text(number(line.projection.gap))),
                      AnyView(Text(number(line.projection.row.totalPoints)))]
        for (i, award) in line.projection.awards.enumerated() {
            let position = line.positions.indices.contains(i) ? line.positions[i] : 0
            result.append(AnyView(VStack(spacing: 0) {
                Text(position > 0 ? "P\(position)" : "–").font(.subheadline.weight(.medium))
                Text(position > 0 ? "+\(number(award))" : " ").font(.caption).foregroundStyle(PP.textMuted)
            }.accessibilityElement(children: .combine)))
        }
        result.append(AnyView(VStack(alignment: .leading, spacing: 0) {
            if let running = line.running {
                Text("#\(running.carNumber) · \(ChampionshipCalculator.liveGap(running))").font(.subheadline).lineLimit(1)
                Text(running.status.map { $0 == "CLASSIFIED" ? "" : $0.replacingOccurrences(of: "_", with: " ").capitalized } ?? "")
                    .font(.caption).foregroundStyle(PP.textMuted)
            } else {
                Text("Not running").font(.caption).foregroundStyle(PP.textMuted)
            }
        }))
        return result
    }
}
