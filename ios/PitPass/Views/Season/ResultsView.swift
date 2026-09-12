import SwiftUI

/// ResultsPage: round chips → session tabs → the classification, with the
/// stewards' notes above it and race control (flags + messages) below.
struct ResultsView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model
    @State private var selectedEvent: Int?
    @State private var selectedSession: Int?
    @State private var results: Resource<EventResults>?
    @State private var showGrid = false

    private var rounds: [CalendarEvent] {
        (model.hub.value?.events ?? []).filter { $0.roundOrdinal != nil && $0.sessionCount > 0 }
    }

    var body: some View {
        let selected = rounds.first { $0.id == selectedEvent } ?? rounds.last
        if rounds.isEmpty {
            EmptyState(message: "No session results yet — import a results or grid file on the website.")
        } else {
            VStack(alignment: .leading, spacing: 0) {
                roundChips(selected)
                if let results = results?.value {
                    event(results)
                } else if let error = results?.error {
                    ErrorPanel(message: error)
                } else {
                    SkeletonLines()
                }
            }
            .task(id: selected?.id) {
                guard let selected else { return }
                let r = Resource<EventResults>("/api/events/\(selected.id)/results")
                results = r
                selectedSession = nil
                showGrid = false
                await r.load(session.loader, connectivity: session.connectivity, freshness: session.freshness)
            }
        }
    }

    private func roundChips(_ selected: CalendarEvent?) -> some View {
        FlowLayout(horizontalSpacing: PP.Space.s2, verticalSpacing: PP.Space.s2) {
            ForEach(rounds) { e in
                let active = e.id == selected?.id
                Button { withAnimation(PP.Motion.fast) { selectedEvent = e.id } } label: {
                    VStack(spacing: 0) {
                        Text(Venue.of(eventName: e.name, circuitName: e.circuitName))
                            .font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(active ? PP.ink : PP.text)
                        Text("Rd \(e.roundOrdinal ?? 0)").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                    }
                    .padding(.vertical, 4).padding(.horizontal, 12)
                    .background(active ? PP.accentTint : .clear, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(active ? PP.accent : PP.borderStrong))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(e.name)
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .padding(.vertical, PP.Space.s4)
    }

    @ViewBuilder private func event(_ results: EventResults) -> some View {
        let sessions = results.sessions
        let races = sessions.filter(\.isRace)
        let active = sessions.first { $0.sessionId == selectedSession } ?? races.first ?? sessions.first
        PageTitle(text: results.eventName, trailing: [results.circuitName, results.eventDate].compactMap { $0 }.joined(separator: " · "))
            .padding(.top, 0)
        if let active {
            let hasGrid = active.isRace && !active.grid.isEmpty
            HStack(spacing: PP.Space.s3) {
                if sessions.count > 1 {
                    Segmented(options: sessions.map { .init(id: $0.sessionId, label: sessionLabel($0, raceCount: races.count)) },
                              selection: Binding(get: { active.sessionId }, set: { selectedSession = $0 }))
                }
                Spacer()
                if hasGrid {
                    Button("Starting grid") { showGrid = true }.buttonStyle(PPSecondaryButtonStyle())
                }
            }
            .padding(.vertical, PP.Space.s3)
            if !active.notes.isEmpty { NotesPanel(session: active) }
            if let basis = active.gridBasis {
                Text("Grid set by: \(basis)").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted).padding(.bottom, PP.Space.s2)
            }
            ResultsTable(session: active)
            if active.hasFlags { RaceControlView(sessionId: active.sessionId).id(active.sessionId) }
            if hasGrid {
                Color.clear.frame(height: 0)
                    .sheet(isPresented: $showGrid) {
                        StartingGridSheet(rows: active.grid, title: "\(results.eventName) · \(sessionLabel(active, raceCount: races.count))")
                    }
            }
        } else {
            EmptyState(message: "No qualifying or race sessions imported for this event.")
        }
    }

    private func sessionLabel(_ s: SessionResults, raceCount: Int) -> String {
        if !s.isRace { return "Qualifying" }
        return raceCount == 1 ? "Race" : s.name
    }
}

/// `.session-notes`: the stewards' notes verbatim; a non-Official mark rides along.
private struct NotesPanel: View {
    let session: SessionResults

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s1) {
            HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                Text("Stewards’ notes").font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink)
                if let mark = session.reportMark, mark != "Official" {
                    Text(mark).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.accentInk)
                        .padding(.horizontal, 6)
                        .overlay(RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous).strokeBorder(PP.accent.opacity(0.45)))
                }
            }
            ForEach(session.notes, id: \.text) { n in
                HStack(alignment: .top, spacing: 6) {
                    Text("•").foregroundStyle(PP.textMuted)
                    Text(n.text).foregroundStyle(PP.text)
                }
                .font(PP.sans(PP.TextSize.sm))
            }
        }
        .padding(.vertical, PP.Space.s3).padding(.horizontal, PP.Space.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        .padding(.bottom, PP.Space.s3)
    }
}

/// One session's classification. Columns follow the session and the data the
/// import carried — an empty column is a column of doubt.
private struct ResultsTable: View {
    @Environment(SeasonModel.self) private var model
    let session: SessionResults

    var body: some View {
        let isQualifying = session.isQualifying
        let all = session.results
        let rows = model.classFilter.map { f in all.filter { $0.className == f } } ?? all
        if rows.isEmpty {
            EmptyState(message: all.isEmpty ? "Nothing imported for this session yet — import a results file on the website."
                       : "No \(model.classFilter ?? "") cars in this session.")
        } else {
            table(isQualifying: isQualifying, all: all, rows: rows)
        }
    }

    private func table(isQualifying: Bool, all: [ResultRow], rows: [ResultRow]) -> some View {
        let gridByCar = Dictionary(session.grid.compactMap { g in g.posOverall.map { (g.carNumber, $0) } }, uniquingKeysWith: { a, _ in a })
        let gapInClass = ResultGaps.classGaps(all)
        let carNotes = notesByCar()
        func timeOf(_ r: ResultRow) -> String? { isQualifying ? r.gapFirst : (r.posOverall == 1 ? r.elapsedTime : r.gapFirst) }
        func change(_ r: ResultRow) -> Int? { guard let s = gridByCar[r.carNumber], let p = r.posOverall else { return nil }; return s - p }
        let has = (
            laps: all.contains { $0.laps != nil },
            time: all.contains { timeOf($0) != nil },
            fastest: all.contains { $0.fastestLapTime != nil },
            onLap: all.contains { $0.fastestLapNumber != nil },
            status: all.contains { !ResultGaps.isClassified($0.status) },
            positionChange: !isQualifying && all.contains { change($0) != nil },
            pitStops: !isQualifying && !all.isEmpty && all.allSatisfy { $0.pitStops != nil },
            fastestBy: all.contains { $0.fastestLapDriver != nil },
            qualifiedBy: all.contains { $0.qualifyingDriver != nil },
            classGap: isQualifying && Set(all.map(\.className)).count > 1 && all.contains { gapInClass[$0.carNumber] != nil }
        )
        let teamInformative = all.contains { r in
            guard let t = r.teamName?.trimmingCharacters(in: .whitespaces), !t.isEmpty else { return false }
            return t != r.drivers?.trimmingCharacters(in: .whitespaces) && t != r.fastestLapDriver?.trimmingCharacters(in: .whitespaces)
                && t != r.qualifyingDriver?.trimmingCharacters(in: .whitespaces)
        }
        let multiDriver = all.contains { ($0.drivers ?? "").contains(", ") }
        let teamPos: String = !teamInformative ? "hidden" : (multiDriver ? "before" : "after")
        let driverHead = isQualifying && has.qualifiedBy ? "Qualified by" : (isQualifying && has.fastestBy ? "Fastest lap by" : "Drivers")

        var ident: [GridColumn] = [.text("pos", "Pos", width: 52, align: .trailing), .text("pic", "PIC", width: 52, align: .trailing)]
        if has.positionChange { ident.append(.text("chg", "± Pos", width: 64, align: .trailing)) }
        ident.append(.text("cls", "Class", width: 88))
        ident.append(.text("car", "#", width: 56, align: .trailing))
        var data: [GridColumn] = []
        if teamPos == "before" { data.append(.text("team", "Team", width: 220)) }
        data.append(.text("drv", driverHead, width: 300))
        if teamPos == "after" { data.append(.text("team", "Team", width: 220)) }
        data.append(.text("veh", "Car", width: 200))
        if has.laps { data.append(.text("laps", "Laps", width: 64, align: .trailing)) }
        if has.pitStops { data.append(.text("pit", "Pit stops", width: 84, align: .trailing)) }
        if has.fastest { data.append(.text("fl", isQualifying ? "Best lap" : "Fastest", width: 96, align: .trailing)) }
        if has.onLap { data.append(.text("onlap", "On lap", width: 72, align: .trailing)) }
        if has.time { data.append(.text("time", isQualifying ? "Gap" : "Time / Gap", width: 110, align: .trailing)) }
        if has.classGap { data.append(.text("cgap", "Class gap", width: 96, align: .trailing)) }
        if has.status { data.append(.text("status", "Status", width: 140)) }

        let items: [GridRowItem] = rows.map { r in
            var identCells: [AnyView] = [GridCell.pos(r.posOverall ?? 0), GridCell.num(r.posInClass.map(String.init) ?? "—")]
            if r.posOverall == nil { identCells[0] = GridCell.num("—", muted: true) }
            if has.positionChange { identCells.append(GridCell.num(change(r).map { $0 > 0 ? "+\($0)" : String($0) } ?? "")) }
            identCells.append(r.className.map { AnyView(ClassTag(name: $0, color: model.classColor($0))) } ?? GridCell.empty())
            identCells.append(AnyView(HStack(spacing: 2) {
                Text(r.carNumber).font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.text)
                if let notes = carNotes[r.carNumber] {
                    Text("※").font(.system(size: 9)).foregroundStyle(PP.accentInk).accessibilityLabel("Stewards' note: \(notes.joined(separator: "; "))")
                }
            }))
            let attributed: String? = (isQualifying && has.qualifiedBy) ? r.qualifyingDriver : ((isQualifying && has.fastestBy) ? r.fastestLapDriver : nil)
            var cells: [AnyView] = []
            if teamPos == "before" { cells.append(GridCell.name(r.teamName ?? "")) }
            cells.append(GridCell.text(attributed ?? r.drivers ?? ""))
            if teamPos == "after" { cells.append(GridCell.name(r.teamName ?? "")) }
            cells.append(GridCell.text(r.vehicle ?? ""))
            if has.laps { cells.append(GridCell.num(r.laps.map(String.init) ?? "")) }
            if has.pitStops { cells.append(GridCell.num(r.pitStops.map(String.init) ?? "")) }
            if has.fastest { cells.append(GridCell.num(r.fastestLapTime ?? "")) }
            if has.onLap { cells.append(GridCell.num(r.fastestLapNumber.map(String.init) ?? "", muted: true)) }
            if has.time { cells.append(GridCell.num(timeOf(r) ?? "")) }
            if has.classGap { cells.append(GridCell.num(gapInClass[r.carNumber] ?? "")) }
            if has.status {
                cells.append(ResultGaps.isClassified(r.status) ? GridCell.empty()
                             : AnyView(Text(r.status ?? "").font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.error).lineLimit(1)))
            }
            return GridRowItem(id: "\(r.carNumber)-\(r.posOverall ?? 0)", ident: identCells, cells: cells, lines: 1)
        }
        return GridTable(identColumns: ident, dataColumns: data, sections: [GridSection(id: "results", band: nil, rows: items)])
    }

    private func notesByCar() -> [String: [String]] {
        guard !session.notes.isEmpty else { return [:] }
        func strip(_ n: String) -> String { n.replacingOccurrences(of: #"^0+(?=\d)"#, with: "", options: .regularExpression) }
        let exact = Set(session.results.map(\.carNumber))
        var stripped: [String: String] = [:]
        for r in session.results where stripped[strip(r.carNumber)] == nil { stripped[strip(r.carNumber)] = r.carNumber }
        var out: [String: [String]] = [:]
        for note in session.notes {
            for num in note.carNumbers {
                guard let key = exact.contains(num) ? num : stripped[strip(num)] else { continue }
                out[key, default: []].append(note.text)
            }
        }
        return out
    }
}

/// Race control: flag periods and the message log, fetched on first open.
private struct RaceControlView: View {
    @Environment(AppSession.self) private var session
    let sessionId: Int
    @State private var open = false
    @State private var flags: Resource<[FlagRecord]>?
    @State private var carFilter: String?

    private static let flagLabel = ["GF": "Green", "FCY": "Full course yellow", "FF": "Chequered"]

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            Button { withAnimation(PP.Motion.fast) { open.toggle() } } label: {
                HStack(spacing: PP.Space.s1) {
                    Text(open ? "▾" : "▸").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                    Text("Race control").font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.text)
                }
            }
            .buttonStyle(.plain)
            if open {
                if let records = flags?.value {
                    body(records)
                } else if let error = flags?.error {
                    ErrorPanel(message: error)
                } else {
                    SkeletonLines()
                }
            }
        }
        .padding(.bottom, PP.Space.s5)
        .task(id: open) {
            guard open, flags == nil else { return }
            let r = Resource<[FlagRecord]>("/api/sessions/\(sessionId)/flags")
            flags = r
            await r.load(session.loader)
        }
    }

    @ViewBuilder private func body(_ records: [FlagRecord]) -> some View {
        let periods = records.filter { $0.recType != "RCMessage" }
        let allLog = records.filter { $0.recType == "RCMessage" }
        let cars = Array(Set(allLog.flatMap(\.carNumbers))).sorted(by: carSort)
        let log = carFilter.map { f in allLog.filter { $0.carNumbers.contains(f) } } ?? allLog
        if !periods.isEmpty {
            FlowLayout(horizontalSpacing: PP.Space.s2, verticalSpacing: PP.Space.s2) {
                ForEach(periods) { f in
                    HStack(spacing: 0) {
                        Text(Self.flagLabel[f.recType] ?? f.flag ?? f.recType).fontWeight(.semibold)
                        if let lap = f.lap, lap > 0 { Text(" · Lap \(lap)") }
                        if let t = f.flagTime, t != "-" { Text(" · \(t)") }
                    }
                    .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.ink)
                    .padding(.vertical, 2).padding(.horizontal, 10)
                    .background(periodTint(f.recType), in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                }
            }
            .padding(.bottom, PP.Space.s2)
        }
        if !cars.isEmpty {
            FlowLayout(horizontalSpacing: PP.Space.s1, verticalSpacing: PP.Space.s1) {
                carChip("All cars", mono: false, active: carFilter == nil) { carFilter = nil }
                ForEach(cars, id: \.self) { car in
                    carChip(car, mono: true, active: carFilter == car) { carFilter = carFilter == car ? nil : car }
                }
            }
            .padding(.bottom, PP.Space.s2)
        }
        if log.isEmpty {
            Text(carFilter.map { "No race-control messages name car \($0)." } ?? "No race-control messages in this session.")
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
        } else {
            VStack(spacing: 0) {
                ForEach(log) { f in
                    HStack(alignment: .top, spacing: PP.Space.s3) {
                        Text((f.elapsed != nil && f.elapsed != "-") ? f.elapsed! : (f.wallTime ?? ""))
                            .font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted).frame(width: 96, alignment: .trailing)
                        Text(f.message ?? "").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text)
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 3).padding(.horizontal, 10)
                    .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
                }
            }
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        }
    }

    private func periodTint(_ type: String) -> Color {
        switch type {
        case "GF": ResultTint.win
        case "FCY": PP.accentTint
        case "RED": PP.errorTint
        default: PP.surface2
        }
    }

    private func carChip(_ label: String, mono: Bool, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(mono ? PP.mono(PP.TextSize.xs) : PP.sans(PP.TextSize.xs))
                .foregroundStyle(active ? PP.ink : PP.text)
                .padding(.vertical, 1).padding(.horizontal, 8)
                .background(active ? PP.accentTint : .clear, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(active ? PP.accent : PP.borderStrong))
        }
        .buttonStyle(.plain)
    }
}

/// The starting grid as a grid: pole at the front, cars staggered left/right.
private struct StartingGridSheet: View {
    @Environment(SeasonModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let rows: [StartingGridRow]
    let title: String

    var body: some View {
        NavigationStack {
            ScrollView {
                let hasTimes = rows.contains { $0.qualifyingTime != nil }
                let hasDrivers = rows.contains { $0.startingDriver != nil }
                let sorted = rows.sorted { ($0.posOverall ?? 999) < ($1.posOverall ?? 999) }
                LazyVGrid(columns: [GridItem(.flexible(), spacing: PP.Space.s3), GridItem(.flexible(), spacing: PP.Space.s3)],
                          alignment: .leading, spacing: PP.Space.s2) {
                    ForEach(Array(sorted.enumerated()), id: \.0) { i, g in
                        let dimmed = model.classFilter != nil && g.className != model.classFilter
                        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                            Text(g.posOverall.map(String.init) ?? "—").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.textMuted).frame(width: 28, alignment: .trailing)
                            Text(g.carNumber).font(PP.mono(PP.TextSize.base, weight: 700)).foregroundStyle(PP.ink).frame(width: 40, alignment: .trailing)
                            if let cls = g.className { ClassTag(name: cls, color: model.classColor(cls)) }
                            VStack(alignment: .leading, spacing: 0) {
                                Text(g.teamName ?? "").font(PP.sans(PP.TextSize.sm, weight: 500)).foregroundStyle(PP.ink).lineLimit(1)
                                if hasDrivers, let d = g.startingDriver { Text(d).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).lineLimit(1) }
                            }
                            Spacer()
                            if hasTimes { Text(g.qualifyingTime ?? "").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text) }
                        }
                        .padding(.vertical, 6).padding(.horizontal, 10)
                        .background(dimmed ? PP.surface : PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
                        .opacity(dimmed ? 0.7 : 1)
                        .padding(.top, i % 2 == 1 ? PP.Space.s4 : 0)
                    }
                }
                .padding(PP.Space.s5)
            }
            .background(PP.bg.ignoresSafeArea())
            .navigationTitle("Starting grid")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationBackground(PP.bg)
        .tint(PP.accentInk)
    }
}
