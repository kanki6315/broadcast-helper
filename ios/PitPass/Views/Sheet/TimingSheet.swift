import SwiftUI

/// The event's Timing tab: the live timing tower; gaps, best sectors and pit
/// stops over a recorded session (TimingAnalysis.swift); and drive time — the
/// web's `/timing/:eventId` (TimingPage.tsx) on the iPad. Read-only apart from the
/// shared connect/disconnect switch, which stays in `LiveTimingBar` exactly as
/// on the calculator. Rules are edited on the website.
///
/// Everything here is polled through `LiveFeed` and never stored: the tower
/// every 2 s (a 304 when nothing moved), drive time every 10 s while shown, the
/// analysis views every 15 s while their session is live, a car's laps every
/// 10 s while its sheet is open.
struct TimingSheet: View {
    @Environment(AppSession.self) private var session
    let eventId: Int
    @State private var status = LiveFeed<LiveStatus>()
    @State private var tower = LiveFeed<Tower>()
    @State private var mode: Mode = .tower
    @State private var openCar: OpenCar?

    enum Mode: String { case tower, gaps, sectors, pits, drive }

    struct OpenCar: Identifiable {
        let carNumber: String
        let teamName: String?
        let className: String
        let color: String?
        let sessionDbId: Int?
        var id: String { carNumber }
    }

    private var followingThis: Bool { tower.value?.eventId == eventId }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PP.Space.s3) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Live timing").ppTitle()
                    Spacer(minLength: PP.Space.s3)
                    if followingThis, let value = tower.value { SessionClockView(tower: value) }
                }
                LiveTimingBar(eventId: eventId, status: status)
                HStack(spacing: PP.Space.s3) {
                    Picker("View", selection: $mode) {
                        Text("Tower").tag(Mode.tower)
                        Text("Gaps").tag(Mode.gaps)
                        Text("Sectors").tag(Mode.sectors)
                        Text("Pits").tag(Mode.pits)
                        Text("Drive time").tag(Mode.drive)
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 480)
                    if followingThis, let s = tower.value?.session { SessionLine(session: s) }
                    Spacer(minLength: 0)
                    if let error = tower.error, tower.value != nil {
                        Text("Not updating: \(error)").font(.caption).foregroundStyle(PP.error).lineLimit(1)
                    }
                }
                if mode == .tower {
                    towerContent
                } else {
                    RecordedSessions(eventId: eventId) { chosen in
                        switch mode {
                        case .gaps: GapsSection(chosen: chosen, open: { openAnalysis($0, chosen) })
                        case .sectors: SectorsSection(chosen: chosen, open: { openAnalysis($0, chosen) })
                        case .pits: PitsSection(chosen: chosen, open: { openAnalysis($0, chosen) })
                        default:
                            DriveTimeSection(chosen: chosen,
                                             classColors: followingThis ? Dictionary((tower.value?.classes ?? []).map { ($0.className.lowercased(), $0.color ?? "") },
                                                                                     uniquingKeysWith: { a, _ in a }) : [:],
                                             classOrder: followingThis ? (tower.value?.classes ?? []).map(\.className) : [])
                        }
                    }
                }
            }
            .padding(PP.Space.s5)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        .background(PP.bg)
        .tint(PP.accentInk)
        .task { await status.run(session.client, path: "/api/live/status", every: .seconds(5)) }
        .task { await tower.run(session.client, path: "/api/live/timing", every: .seconds(2)) }
        .sheet(item: $openCar) { target in
            LiveCarSheet(target: target)
        }
    }

    private func openAnalysis(_ target: AnalysisOpen, _ chosen: LiveSessionSummary) {
        openCar = OpenCar(carNumber: target.car.carNumber, teamName: target.car.teamName, className: target.car.className,
                          color: target.color, sessionDbId: chosen.sessionDbId)
    }

    @ViewBuilder private var towerContent: some View {
        if let value = tower.value {
            if value.state == "NOT_CONFIGURED" {
                EmptyState(message: "Live timing is not set up on this server.")
            } else if value.state == "OFF" || value.state == "STANDBY" {
                EmptyState(message: "Live timing is off. Once it is connected, the tower fills in here. Recorded sessions stay under Gaps, Sectors, Pits and Drive time.")
            } else if !followingThis {
                EmptyState(message: "Live timing is following \(value.eventName ?? "another event"), not this one. Open that event's Timing tab to follow it.")
            } else if value.classes.isEmpty {
                EmptyState(message: "Connected. Waiting for the feed's first running order.")
            } else {
                TowerGrid(tower: value) { car, cls in
                    openCar = OpenCar(carNumber: car.carNumber, teamName: car.teamName, className: cls.className,
                                      color: cls.color, sessionDbId: value.sessionDbId)
                }
                Text("\(value.matched) of \(value.total) cars matched to this event's entries. Tap a car for its laps and stints.")
                    .font(.caption).foregroundStyle(PP.textMuted)
            }
        } else if let error = tower.error {
            ErrorPanel(message: "Could not reach live timing: \(error)")
        } else {
            SkeletonLines()
        }
    }
}

/// The feed's session and flag, the flag as a tinted chip that always says its name.
private struct SessionLine: View {
    let session: LiveSession

    var body: some View {
        HStack(spacing: PP.Space.s2) {
            if let name = session.name { Text(name).font(.subheadline).foregroundStyle(PP.textMuted) }
            if let flag = TimingFormat.flagLabel(session.flag) {
                chip(flag, tone: TimingFormat.flagTone(session.flag))
            }
            if session.finished { chip("Finished", tone: .neutral) }
        }
    }

    private func chip(_ text: String, tone: TimingFormat.FlagTone) -> some View {
        let fill: Color = switch tone {
        case .green: ResultTint.win
        case .yellow: PP.accentTint
        case .red: PP.errorTint
        case .neutral: PP.surface2
        }
        return Text(text)
            .font(PP.sans(PP.TextSize.xs, weight: 600))
            .foregroundStyle(tone == .red ? PP.error : PP.ink)
            .padding(.horizontal, 8).padding(.vertical, 2)
            .background(fill, in: RoundedRectangle(cornerRadius: PP.Radius.sm))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.sm).strokeBorder(PP.borderStrong))
    }
}

/// The session clock, counted down on the device from the feed's status:
/// time to go (frozen, in error red and in words while stopped) or the
/// leader's lap, with the time of day at the track beneath while the feed is
/// current. The web page's `.timing-clock`.
private struct SessionClockView: View {
    let tower: Tower

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let wall = Int(context.date.timeIntervalSince1970 * 1000)
            let reading = TimingFormat.sessionClock(tower, wallMs: wall)
            let current = TimingFormat.feedNow(state: tower.state, finished: tower.session?.finished ?? false,
                                               feedClockMs: tower.feedClockMs, wallMs: wall) == wall
            let local = current ? TimingFormat.trackTime(wallMs: wall, utcOffsetHours: tower.session?.clock?.utcOffsetHours) : nil
            let sub = [reading?.laps, local.map { "\($0) at the track" }].compactMap { $0 }.joined(separator: " · ")
            VStack(alignment: .trailing, spacing: 2) {
                if let reading {
                    HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                        Text(reading.time)
                            .font(PP.sans(PP.TextSize.xl, weight: 600).monospacedDigit())
                            .foregroundStyle(reading.stopped ? PP.error : PP.ink)
                        if !reading.note.isEmpty {
                            Text(reading.note).font(.subheadline).foregroundStyle(reading.stopped ? PP.error : PP.textMuted)
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
                if !sub.isEmpty {
                    Text(sub).font(PP.sans(PP.TextSize.xs).monospacedDigit()).foregroundStyle(PP.textMuted)
                }
            }
        }
    }
}

// MARK: - Tower


private struct TowerGrid: View {
    let tower: Tower
    let open: (TowerCar, TowerClass) -> Void

    private var hasLaps: Bool { tower.classes.contains { $0.cars.contains { $0.lastLapMs != nil || $0.stintStartMs != nil } } }
    private var hasEnergy: Bool { tower.classes.contains { $0.cars.contains { $0.energyPct != nil } } }

    var body: some View {
        let ident: [GridColumn] = [
            .text("pos", "Pos", width: 48, align: .trailing),
            .text("car", "#", width: 56, align: .trailing),
            .text("driver", "Driver", width: 200),
        ]
        var data: [GridColumn] = [
            .text("team", "Team", width: 170),
            .text("laps", "Laps", width: 54, align: .trailing),
            .text("gap", "Gap", width: 96, align: .trailing),
            .text("int", "Int", width: 92, align: .trailing),
        ]
        if hasLaps {
            data += [.text("last", "Last", width: 92, align: .trailing),
                     .text("best", "Best", width: 96, align: .trailing),
                     .text("stint", "Stint", width: 116, align: .trailing)]
        }
        if hasEnergy { data.append(.text("energy", "Energy", width: 104, align: .trailing)) }
        data.append(GridColumn(id: "state", width: 70, growthWeight: 1) { Text("").accessibilityHidden(true) })

        let sections = tower.classes.map { cls in
            let best = TimingFormat.classBest(cls.cars)
            return GridSection(id: cls.className,
                               band: (label: cls.feedClass == cls.className ? cls.className : "\(cls.className) (\(cls.feedClass))",
                                      color: cls.color ?? ""),
                               rows: cls.cars.map { row($0, cls: cls, best: best) })
        }
        return GridTable(identColumns: ident, dataColumns: data, sections: sections,
                         lineHeight: 20, cellPadV: 5, cellPadH: 8, headerHeight: 34, separatesIdentity: true, centersCells: true)
    }

    /// A lap time in timing screens' purple (fastest in class: bold ink on the
    /// violet result tint) or green (the car's own best, on the green tint).
    /// The words are in the accessible name.
    private func lapCell(_ ms: Int?, mark: TimingFormat.LapMark?, muted: Bool) -> AnyView {
        let time = TimingFormat.lapTime(ms)
        let fill: Color = switch mark {
        case .classBest: ResultTint.top5
        case .personalBest: ResultTint.win
        case nil: .clear
        }
        return AnyView(
            Text(time)
                .font(PP.mono(PP.TextSize.sm, weight: mark == .classBest ? 700 : mark == .personalBest ? 600 : 400))
                .foregroundStyle(mark != nil ? PP.ink : muted ? PP.textMuted : PP.text)
                .padding(.horizontal, mark != nil ? 4 : 0)
                .background(fill, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
                .accessibilityLabel(mark == .classBest ? "\(time), fastest in class" : mark == .personalBest ? "\(time), personal best" : time))
    }

    private func row(_ car: TowerCar, cls: TowerClass, best: Int?) -> GridRowItem {
        let muted = !car.running
        let leader = car.position == 1
        let isClassBest = best != nil && car.bestLapMs == best
        let lastMark = TimingFormat.lastLapMark(car, classBest: best)
        var cells: [AnyView] = [
            GridCell.text(car.teamName ?? "—", muted: true),
            GridCell.num(car.laps.map(String.init) ?? "", muted: muted),
            GridCell.num(leader ? "" : TimingFormat.gap(ms: car.gapToLeaderMs, laps: car.gapToLeaderLaps), muted: muted),
            GridCell.num(leader ? "" : TimingFormat.gap(ms: car.intervalMs, laps: car.intervalLaps), muted: muted),
        ]
        if hasLaps {
            cells.append(lapCell(car.lastLapMs, mark: lastMark, muted: muted))
            cells.append(lapCell(car.bestLapMs, mark: isClassBest ? .classBest : nil, muted: muted))
            cells.append(AnyView(StintCell(car: car, tower: tower)))
        }
        if hasEnergy {
            cells.append(AnyView(HStack(spacing: PP.Space.s2) {
                if let e = car.energyPct { Text("\(Int(e.rounded()))%").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text) }
                if let left = car.energyLapsLeft {
                    Text("~\(Int(left)) L").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                }
            }))
        }
        cells.append(stateCell(car))

        let driver = AnyView(HStack(spacing: 6) {
            Text(car.driverName ?? "—").font(PP.sans(PP.TextSize.sm, weight: 500))
                .foregroundStyle(muted ? PP.textMuted : PP.ink).lineLimit(1)
            if let r = car.driverRating {
                Text(r).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
                    .accessibilityLabel(TimingFormat.ratingName(r) ?? r)
            }
        })
        let label = ["P\(car.position)", "car \(car.carNumber)", car.driverName, car.teamName,
                     car.inPit ? "in the pit" : nil, car.running ? nil : car.status?.lowercased()]
            .compactMap { $0 }.joined(separator: ", ")
        return GridRowItem(id: car.carNumber,
                           ident: [GridCell.num(String(car.position), muted: muted, bold: true),
                                   GridCell.car(car.carNumber), driver],
                           cells: cells, lines: 1,
                           onTap: { open(car, cls) }, tapLabel: label + ". Opens laps and stints.")
    }

    private func stateCell(_ car: TowerCar) -> AnyView {
        if !car.running {
            return GridCell.text(car.status?.lowercased().replacingOccurrences(of: "_", with: " ") ?? "", muted: true)
        }
        guard car.inPit else { return GridCell.empty() }
        return AnyView(Text("Pit").font(PP.sans(PP.TextSize.xs, weight: 700)).foregroundStyle(PP.ink)
            .padding(.horizontal, 6).padding(.vertical, 1)
            .background(PP.accentTint, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.xs).strokeBorder(PP.accent.opacity(0.45))))
    }
}

/// Stint laps and a running time that ticks every second, on the feed's clock.
private struct StintCell: View {
    let car: TowerCar
    let tower: Tower

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            HStack(spacing: PP.Space.s2) {
                if let laps = car.stintLaps, !car.inPit {
                    Text("\(laps) L").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
                }
                if let start = car.stintStartMs,
                   let now = TimingFormat.feedNow(state: tower.state, finished: tower.session?.finished ?? false,
                                                  feedClockMs: tower.feedClockMs,
                                                  wallMs: Int(context.date.timeIntervalSince1970 * 1000)) {
                    Text(TimingFormat.duration(now - start)).font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                        .frame(minWidth: 52, alignment: .trailing)
                }
            }
        }
    }
}

// MARK: - A car's laps and stints

private struct LiveCarSheet: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    let target: TimingSheet.OpenCar
    @State private var detail = LiveFeed<CarDetail>()
    /// The class's best sectors, to mark this car's that match them (a 304 between laps).
    @State private var sectors = LiveFeed<SectorsResponse>()
    @State private var showsStints = false

    private var classBest: [Int?] {
        sectors.value?.classes.first { $0.bests.cars.contains { $0.carNumber == target.carNumber } }?.bests.classBestSectorMs ?? []
    }

    private var path: String {
        let car = target.carNumber.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? target.carNumber
        return "/api/live/cars/\(car)" + (target.sessionDbId.map { "?session=\($0)" } ?? "")
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    HStack(spacing: PP.Space.s4) {
                        // In the content, not the toolbar: iOS 26 truncates leading toolbar items.
                        Text(target.className).font(PP.sans(PP.TextSize.xs, weight: 700))
                            .padding(.horizontal, 6).padding(.vertical, 1)
                            .foregroundStyle(classInk(target.color ?? ""))
                            .background(Color(cssHex: target.color ?? "") ?? PP.surface2, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
                            .overlay(RoundedRectangle(cornerRadius: PP.Radius.xs).strokeBorder(PP.borderStrong))
                        if let value = detail.value {
                            ForEach(value.drivers) { d in
                                HStack(spacing: 6) {
                                    Text(String(d.driverOrder)).font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                                    Text(d.name).font(.subheadline).foregroundStyle(PP.ink)
                                    if let r = d.rating { Text(r).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted) }
                                }
                            }
                        }
                    }
                    Picker("Detail", selection: $showsStints) {
                        Text("Laps\(detail.value.map { " (\($0.laps.count))" } ?? "")").tag(false)
                        Text("Stints\(detail.value.map { " (\($0.stints.count))" } ?? "")").tag(true)
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 320)
                    if let value = detail.value {
                        if showsStints { stints(value) } else { laps(value) }
                    } else if let error = detail.error {
                        EmptyState(message: error.contains("404") ? "No laps recorded for this car in this session yet." : "Could not load this car: \(error)")
                    } else {
                        SkeletonLines()
                    }
                }
                .padding(PP.Space.s4)
            }
            .background(PP.bg)
            .navigationTitle("#\(target.carNumber) \(target.teamName ?? "")")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
            }
        }
        // Page-sized: a lap row carries sectors, energy and notes, which a form sheet cuts off.
        .presentationSizing(.page)
        .tint(PP.accentInk)
        .task { await detail.run(session.client, path: path, every: .seconds(10)) }
        .task {
            await sectors.run(session.client, path: "/api/live/sectors" + (target.sessionDbId.map { "?session=\($0)" } ?? ""),
                              every: .seconds(10))
        }
    }

    private func driverLabel(_ value: CarDetail, _ order: Int?) -> String {
        guard let order else { return "—" }
        let d = value.drivers.first { $0.driverOrder == order }
        // Surnames, not the feed's three-letter codes: there is room.
        return d?.lastName ?? d?.shortName ?? "Driver \(order)"
    }

    private func laps(_ value: CarDetail) -> some View {
        let sectors = value.laps.map { $0.sectorMs?.count ?? 0 }.max() ?? 0
        let best = value.laps.filter { $0.valid != false }.compactMap(\.lapTimeMs).filter { $0 > 0 }.min()
        let ownBest = TimingFormat.bestSectors(value.laps, count: sectors)
        let theoretical: Int? = ownBest.isEmpty || ownBest.contains(where: { $0 == nil }) ? nil : ownBest.compactMap { $0 }.reduce(0, +)
        let classBest = self.classBest
        let hasEnergy = value.laps.contains { $0.energyPct != nil }
        var data: [GridColumn] = [.text("time", "Time", width: 96, align: .trailing)]
        data += (0..<sectors).map { .text("s\($0)", "S\($0 + 1)", width: 76, align: .trailing) }
        data += [.text("pos", "Pos", width: 48, align: .trailing), .text("speed", "Top speed", width: 86, align: .trailing)]
        if hasEnergy {
            data += [.text("energy", "Energy", width: 76, align: .trailing), .text("used", "Used", width: 64, align: .trailing)]
        }
        data.append(GridColumn(id: "notes", width: 130, growthWeight: 1) {
            Text("Notes").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
        })
        let rows = value.laps.reversed().map { l -> GridRowItem in
            let invalid = l.valid == false
            let isBest = best != nil && l.lapTimeMs == best && !invalid
            var cells: [AnyView] = [
                l.lapTimeMs == nil ? GridCell.text("In progress", muted: true)
                    : AnyView(Text(TimingFormat.lapTime(l.lapTimeMs)).font(PP.mono(PP.TextSize.sm, weight: isBest ? 700 : 400))
                        .foregroundStyle(invalid ? PP.textMuted : isBest ? PP.ink : PP.text)
                        .strikethrough(invalid, color: PP.error)
                        .accessibilityLabel(TimingFormat.lapTime(l.lapTimeMs) + (isBest ? ", best lap" : invalid ? ", invalid" : ""))),
            ]
            cells += (0..<sectors).map { i in
                let ms = (l.sectorMs?.count ?? 0) > i ? l.sectorMs?[i] ?? nil : nil
                let flag = (l.sectorFlags?.count ?? 0) > i ? l.sectorFlags?[i] ?? nil : nil
                let flagged = flag != nil && !(flag!.uppercased().contains("GREEN"))
                // The car's own best sector in weight; the class's best on the violet tint.
                let counts = ms != nil && !invalid
                let isClassBest = counts && classBest.indices.contains(i) && ms == classBest[i]
                let isOwnBest = counts && !isClassBest && ms == ownBest[i]
                return AnyView(Text(TimingFormat.lapTime(ms))
                    .font(PP.mono(PP.TextSize.sm, weight: isClassBest ? 700 : isOwnBest ? 600 : 400))
                    .foregroundStyle(isClassBest || isOwnBest ? PP.ink : PP.text)
                    .padding(.horizontal, isClassBest ? 4 : 0)
                    .background(isClassBest ? ResultTint.top5 : .clear, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
                    .underline(flagged, pattern: .dot, color: PP.accentInk)
                    .accessibilityLabel([TimingFormat.lapTime(ms), flagged ? "\(TimingFormat.flagLabel(flag) ?? "") flag" : nil,
                                         isClassBest ? "fastest in class" : isOwnBest ? "personal best" : nil]
                        .compactMap { $0 }.joined(separator: ", ")))
            }
            cells += [GridCell.num(l.position.map(String.init) ?? "", muted: true),
                      GridCell.num(l.topSpeed.map { String(format: "%.1f", $0) } ?? "", muted: true)]
            if hasEnergy {
                cells += [GridCell.num(TimingFormat.pct(l.energyPct)), GridCell.num(TimingFormat.pct(l.energyUsedPct), muted: true)]
            }
            let notes = [l.pitInMs != nil ? "Pit in" : nil, l.pitOutMs != nil ? "Pit out" : nil, invalid ? "Invalid" : nil,
                         (l.trackLimits ?? 0) > 0 ? "Track limits" : nil].compactMap { $0 }
            cells.append(GridCell.text(notes.joined(separator: " · "), muted: true))
            return GridRowItem(id: String(l.lap),
                               ident: [GridCell.num(String(l.lap), bold: true), GridCell.text(driverLabel(value, l.driverOrder))],
                               cells: cells, lines: 1)
        }
        return Group {
            if rows.isEmpty { EmptyState(message: "No laps recorded yet.") }
            else {
                if let theoretical, let best {
                    HStack(spacing: 4) {
                        Text("Best lap \(TimingFormat.lapTime(best)) · theoretical best \(TimingFormat.lapTime(theoretical))")
                            .foregroundStyle(PP.text)
                        if best > theoretical {
                            Text("(\(TimingFormat.lapTime(best - theoretical)) in hand)").foregroundStyle(PP.textMuted)
                        }
                    }
                    .font(.caption)
                }
                GridTable(identColumns: [.text("lap", "Lap", width: 56, align: .trailing), .text("driver", "Driver", width: 130)],
                          dataColumns: data, sections: [GridSection(id: "laps", rows: rows)],
                          cellPadV: 4, cellPadH: 8, headerHeight: 32, separatesIdentity: true, centersCells: true)
            }
        }
    }

    private func stints(_ value: CarDetail) -> some View {
        let hasEnergy = value.stints.contains { $0.avgEnergyPerLapPct != nil }
        var data: [GridColumn] = [.text("type", "Type", width: 150), .text("laps", "Laps", width: 80, align: .trailing),
                                  .text("start", "Started", width: 90, align: .trailing), .text("len", "Length", width: 80, align: .trailing),
                                  .text("track", "Driver track time", width: 140, align: .trailing)]
        if hasEnergy { data.append(.text("energy", "Energy / lap", width: 100, align: .trailing)) }
        let clock = Date.FormatStyle().hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits)
        let rows = value.stints.reversed().map { s -> GridRowItem in
            let laps = s.openLap.map { open in s.closeLap.map { "\(open)–\($0)" } ?? "\(open)–" } ?? ""
            let type = (s.type ?? "TRACK").uppercased() == "PIT" ? "Pit" : "Track"
            var cells: [AnyView] = [
                AnyView(HStack(spacing: 6) {
                    Text(type + (s.pitType.map { " · \($0.lowercased())" } ?? "")).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text)
                    if s.open {
                        Text("Current").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.ink)
                            .padding(.horizontal, 6).background(PP.surface2, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
                    }
                }),
                GridCell.num(laps),
                GridCell.num(Date(timeIntervalSince1970: Double(s.startTimeMs) / 1000).formatted(clock)),
                GridCell.num(s.open ? "" : TimingFormat.duration(s.finishTimeMs! - s.startTimeMs)),
                GridCell.num(TimingFormat.duration(s.driverAccumSessionTrackMs)),
            ]
            if hasEnergy { cells.append(GridCell.num(TimingFormat.pct(s.avgEnergyPerLapPct, digits: 2))) }
            return GridRowItem(id: String(s.startTimeMs), ident: [GridCell.text(driverLabel(value, s.driverOrder))], cells: cells, lines: 1)
        }
        return Group {
            if rows.isEmpty { EmptyState(message: "No stints recorded yet.") }
            else {
                GridTable(identColumns: [.text("driver", "Driver", width: 150)], dataColumns: data,
                          sections: [GridSection(id: "stints", rows: rows)],
                          cellPadV: 4, cellPadH: 8, headerHeight: 32, separatesIdentity: true, centersCells: true)
            }
        }
    }
}

// MARK: - Drive time

/// The views over one recorded session — gaps, sectors, pits, drive time —
/// share a session picker: the one chosen, else the one being fed, else the newest.
private struct RecordedSessions<Content: View>: View {
    @Environment(AppSession.self) private var session
    let eventId: Int
    @ViewBuilder let content: (LiveSessionSummary) -> Content
    @State private var sessions = LiveFeed<[LiveSessionSummary]>()
    @State private var chosen: Int?

    private var shown: LiveSessionSummary? {
        guard let list = sessions.value, !list.isEmpty else { return nil }
        return list.first { $0.sessionDbId == chosen } ?? list.first { $0.current } ?? list.first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if let list = sessions.value {
                if let shown {
                    if list.count > 1 {
                        Picker("Session", selection: Binding(get: { shown.sessionDbId }, set: { chosen = $0 })) {
                            ForEach(list) { s in
                                Text((s.name ?? "Session \(s.sessionDbId)") + (s.current ? " · live" : "")).tag(s.sessionDbId)
                            }
                        }
                        .pickerStyle(.segmented)
                        .frame(maxWidth: 520)
                    }
                    content(shown)
                } else {
                    EmptyState(message: "No timed sessions recorded for this event yet. Laps and stints are recorded while live timing is connected to this event.")
                }
            } else if let error = sessions.error {
                ErrorPanel(message: error)
            } else {
                SkeletonLines()
            }
        }
        .task { await sessions.run(session.client, path: "/api/live/sessions?eventId=\(eventId)", every: .seconds(30)) }
    }
}

private struct DriveTimeSection: View {
    @Environment(AppSession.self) private var session
    let chosen: LiveSessionSummary
    let classColors: [String: String]
    let classOrder: [String]
    @State private var drive = LiveFeed<DriveTimeResponse>()

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if let value = drive.value, value.sessionDbId == chosen.sessionDbId {
                DriveTable(drive: value, classColors: classColors, classOrder: classOrder)
                RulesList(rules: value.rules)
            } else if let error = drive.error {
                ErrorPanel(message: "Could not load drive time: \(error)")
            } else {
                SkeletonLines()
            }
        }
        .task(id: chosen.sessionDbId) {
            await drive.run(session.client, path: "/api/live/drive-time?session=\(chosen.sessionDbId)", every: .seconds(10))
        }
    }
}

private struct DriveTable: View {
    let drive: DriveTimeResponse
    let classColors: [String: String]
    /// The tower's class order, when it is following this event; else first seen.
    let classOrder: [String]

    var body: some View {
        let over = drive.drivers.filter { $0.status == "OVER_MAX" }.count
        let under = drive.drivers.filter { $0.status == "UNDER_MIN" }.count
        let ok = drive.drivers.filter { $0.status == "OK" }.count
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            if drive.drivers.isEmpty {
                EmptyState(message: "No drivers recorded for this session yet.")
            } else {
                if !drive.rules.isEmpty {
                    HStack(spacing: PP.Space.s4) {
                        if over > 0 { Text("\(over) over the maximum").foregroundStyle(PP.error) }
                        if under > 0 { Text("\(under) short of the minimum").foregroundStyle(PP.ink) }
                        if ok > 0 { Text("\(ok) within the rules").foregroundStyle(PP.ink) }
                    }
                    .font(.subheadline.weight(.semibold))
                }
                GridTable(identColumns: [.text("car", "#", width: 56, align: .trailing), .text("driver", "Driver", width: 210)],
                          // Status beside the time: it is the answer, and it must stay on screen in
                          // portrait; the meter is only shape, so it goes last.
                          dataColumns: [.text("rating", "Rating", width: 84), .text("time", "Drive time", width: 96, align: .trailing),
                                        .text("status", "Status", width: 180),
                                        .text("min", "Min", width: 80, align: .trailing), .text("max", "Max", width: 80, align: .trailing),
                                        GridColumn(id: "meter", width: 140, growthWeight: 1) { Text("").accessibilityHidden(true) }],
                          sections: sections,
                          cellPadV: 4, cellPadH: 8, headerHeight: 32, separatesIdentity: true, centersCells: true)
            }
        }
    }

    private var sections: [GridSection] {
        var order: [String] = []
        var byClass: [String: [DriveTimeResult]] = [:]
        for d in drive.drivers {
            let cls = d.className ?? "Unmatched"
            if byClass[cls] == nil { order.append(cls) }
            byClass[cls, default: []].append(d)
        }
        let rank = Dictionary(classOrder.enumerated().map { ($1.lowercased(), $0) }, uniquingKeysWith: { a, _ in a })
        let sorted = order.enumerated().sorted { a, b in
            (rank[a.element.lowercased()] ?? Int.max, a.offset) < (rank[b.element.lowercased()] ?? Int.max, b.offset)
        }.map(\.element)
        return sorted.map { cls in
            let drivers = byClass[cls]!.sorted { a, b in
                let byCar = a.car.compare(b.car, options: .numeric)
                return byCar == .orderedSame ? a.driverOrder < b.driverOrder : byCar == .orderedAscending
            }
            var seenCars = Set<String>()
            let rows = drivers.map { d -> GridRowItem in
                let first = seenCars.insert(d.car).inserted
                return GridRowItem(id: d.id,
                                   ident: [first ? GridCell.car(d.car) : GridCell.empty(),
                                           AnyView(HStack(spacing: PP.Space.s2) {
                                               Text(d.name ?? "Driver \(d.driverOrder)").font(PP.sans(PP.TextSize.sm, weight: 500))
                                                   .foregroundStyle(PP.ink).lineLimit(1)
                                               if d.inCar {
                                                   Text("In car").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.ink)
                                                       .padding(.horizontal, 6).background(PP.surface2, in: RoundedRectangle(cornerRadius: PP.Radius.xs))
                                               }
                                           })],
                                   cells: [GridCell.text(TimingFormat.ratingName(d.rating) ?? "—", muted: d.rating == nil),
                                           AnyView(Text(TimingFormat.duration(d.driveMs)).font(PP.mono(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink)),
                                           status(d),
                                           GridCell.num(TimingFormat.ruleTime(d.minMs), muted: true),
                                           GridCell.num(TimingFormat.ruleTime(d.maxMs), muted: true),
                                           AnyView(Meter(result: d))],
                                   lines: 1)
            }
            return GridSection(id: cls, band: (label: cls, color: classColors[cls.lowercased()] ?? ""), rows: rows)
        }
    }

    private func status(_ d: DriveTimeResult) -> AnyView {
        let (text, color): (String, Color) = switch d.status {
        case "OVER_MAX": ("Over by \(TimingFormat.duration(d.overMs))", PP.error)
        case "UNDER_MIN": ("\(TimingFormat.duration(d.owedMs)) to go", PP.text)
        case "OK": (d.remainingMs.map { "OK · \(TimingFormat.duration($0)) left" } ?? "OK", PP.success)
        default: ("No rule", PP.textMuted)
        }
        return AnyView(Text(text).font(PP.sans(PP.TextSize.sm, weight: d.status == "OVER_MAX" ? 600 : 500)).foregroundStyle(color))
    }
}

/// Drive time against the rule, for skimming: share of the maximum (or the
/// minimum when there is none), with a tick at the minimum. Shape only — the
/// status beside it is the fact.
private struct Meter: View {
    let result: DriveTimeResult

    var body: some View {
        if result.maxMs == nil && result.minMs == nil {
            Color.clear.frame(width: 120, height: 6)
        } else {
            let scale = Double(max(result.maxMs ?? 0, result.minMs ?? 0, result.driveMs, 1))
            let fill = min(1, Double(result.driveMs) / scale)
            let color: Color = result.status == "OVER_MAX" ? PP.error : result.status == "OK" ? PP.success : PP.textMuted
            ZStack(alignment: .leading) {
                Capsule().fill(PP.surface2).overlay(Capsule().strokeBorder(PP.border))
                Capsule().fill(color).frame(width: 120 * fill)
                if let min = result.minMs {
                    Rectangle().fill(PP.ink).frame(width: 2, height: 12).offset(x: 120 * Double(min) / scale - 1)
                }
            }
            .frame(width: 120, height: 6)
            .accessibilityHidden(true)
        }
    }
}

private struct RulesList: View {
    let rules: [DriveTimeRule]

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            Text("Drive-time rules").font(.headline).foregroundStyle(PP.ink)
            if rules.isEmpty {
                Text("No rules for this event. Rules are added on the website's Timing page.")
                    .font(.subheadline).foregroundStyle(PP.textMuted)
            } else {
                ForEach(rules) { r in
                    HStack(alignment: .firstTextBaseline, spacing: PP.Space.s4) {
                        Text(r.className).font(.subheadline.weight(.bold)).foregroundStyle(PP.ink).frame(minWidth: 80, alignment: .leading)
                        Text(r.rating.flatMap(TimingFormat.ratingName) ?? "Every rating").font(.subheadline).frame(minWidth: 110, alignment: .leading)
                        Text([r.minMs.map { "min \(TimingFormat.ruleTime($0))" }, r.maxMs.map { "max \(TimingFormat.ruleTime($0))" }]
                            .compactMap { $0 }.joined(separator: " · "))
                            .font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.ink)
                        if let note = r.note { Text(note).font(.subheadline).foregroundStyle(PP.textMuted) }
                    }
                }
                Text("Rules are edited on the website's Timing page.").font(.caption).foregroundStyle(PP.textMuted)
            }
        }
        .padding(PP.Space.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.lg))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg).strokeBorder(PP.border))
    }
}
