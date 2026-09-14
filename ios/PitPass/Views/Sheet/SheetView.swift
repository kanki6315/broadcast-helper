import SwiftUI

/// Pushed from anywhere that names an event.
struct SheetRoute: Hashable {
    let eventId: Int
}

/// The event sheet — the on-screen broadcast reference for one event
/// (SheetPage.tsx / sheet.css): header, one class section per class with the
/// entry table and season-form strips, with four peer tabs. Read-only here:
/// prior-year notes are edited on the website.
struct SheetView: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    @Environment(WorkspaceState.self) private var workspace
    let eventId: Int
    @State private var sheet: Resource<Sheet>
    @State private var teamSheet: (page: Int, title: String)?
    @State private var storylinesOpen = false
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var page: Page = .sheet

    private enum Page: String { case sheet, recap, pitLane, scratchpad }
    @State private var padModel: PadModel?
    @State private var exporting = false
    @State private var exportURL: URL?

    init(eventId: Int) {
        self.eventId = eventId
        _sheet = State(initialValue: Resource<Sheet>("/api/events/\(eventId)/sheet"))
    }

    var body: some View {
        eventTabs
        .navigationTitle(sheet.value?.eventName ?? "Sheet")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(PP.bg, for: .navigationBar)
        .navigationBarBackButtonHidden()
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { dismiss() } label: {
                    Label("Back to series", systemImage: "chevron.left")
                }
            }
            ToolbarItem(placement: .principal) {
                if let value = sheet.value {
                    VStack(spacing: 2) {
                        Text(value.eventName).font(.headline)
                        Text("\(value.seriesName) · \(String(value.year))")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                if sheet.value?.storylinesPath != nil {
                    Button { storylinesOpen = true } label: {
                        Label("Storylines", systemImage: "doc.text")
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                StatusDownloadButton(target: .event(eventId), noun: "event")
            }
            ToolbarItem(placement: .topBarTrailing) {
                if let sheet = sheet.value {
                    Button {
                        Task { await export(sheet) }
                    } label: {
                        if exporting { ProgressView().controlSize(.small) } else { Label("Print / Save PDF", systemImage: "printer") }
                    }
                    .disabled(exporting)
                    .tint(PP.accentInk)
                }
            }
        }
        .onChange(of: page) { _, value in workspace.set("event.\(eventId).tab", value.rawValue) }
        .task(id: eventId) {
            page = Page(rawValue: workspace.value("event.\(eventId).tab") ?? "") ?? .sheet
            session.freshness.reset()
            await sheet.load(session.loader, connectivity: session.connectivity, freshness: session.freshness)
        }
        .onDisappear { padModel?.close() }
        .sheet(item: Binding(get: { teamSheet.map { TeamSheetTarget(page: $0.page, title: $0.title) } }, set: { teamSheet = $0.map { ($0.page, $0.title) } })) { target in
            if let path = sheet.value?.teamSheetsPath {
                PdfViewerSheet(path: path, title: target.title, page: target.page)
            }
        }
        .sheet(isPresented: $storylinesOpen) {
            if let path = sheet.value?.storylinesPath { PdfViewerSheet(path: path, title: "Storylines", page: 1) }
        }
        .sheet(item: $exportURL) { url in
            ExportSheet(url: url)
        }
    }

    private var eventTabs: some View {
        TabView(selection: $page) {
            Tab("Sheet", systemImage: "tablecells", value: Page.sheet) {
                sheetContent.environment(\.horizontalSizeClass, sizeClass)
            }
            Tab("Recap", systemImage: "chart.bar.xaxis", value: Page.recap) {
                recapContent.environment(\.horizontalSizeClass, sizeClass)
            }
            Tab("Pit lane", systemImage: "flag.checkered", value: Page.pitLane) {
                pitLaneContent.environment(\.horizontalSizeClass, sizeClass)
            }
            Tab("Scratchpad", systemImage: "pencil.and.scribble", value: Page.scratchpad) {
                ScratchpadSheet(eventId: eventId, model: $padModel)
                    .environment(\.horizontalSizeClass, sizeClass)
            }
            .badge(scratchpadAttentionBadge)
        }
        // Keep native Liquid Glass tabs at the bottom on iPad as well as iPhone.
        // Each tab restores the actual size class for its adaptive content.
        .environment(\.horizontalSizeClass, .compact)
        .tint(PP.accentInk)
        .background(PP.bg.ignoresSafeArea())
    }

    private var scratchpadAttentionBadge: Text? {
        guard let attention = session.pads.attention(eventId: eventId, owner: session.padOwner) else { return nil }
        return Text("!").accessibilityLabel(attention == .conflict
            ? "Scratchpad needs attention: changed elsewhere" : "Scratchpad has unsynced ink")
    }

    @ViewBuilder private var recapContent: some View {
        if let value = sheet.value {
            if let seasonId = value.seasonId {
                RecapSheet(seasonId: seasonId, currentEventId: eventId)
            } else {
                ContentUnavailableView("No season recap", systemImage: "chart.bar.xaxis",
                                       description: Text("This event is not linked to a season."))
            }
        } else { sheetLoadingState }
    }

    @ViewBuilder private var pitLaneContent: some View {
        if let value = sheet.value { PitLaneSheet(eventId: eventId, sheet: value) }
        else { sheetLoadingState }
    }

    private var sheetContent: some View {
        ScrollView {
            Group {
                if let sheet = sheet.value {
                    content(sheet)
                } else if let error = sheet.error {
                    ErrorPanel(message: error)
                } else {
                    SkeletonLines()
                }
            }
            .frame(maxWidth: 1240)
            .padding(.top, PP.Space.s4)
            .padding(.horizontal, PP.Space.s5)
            .padding(.bottom, PP.Space.s3)
            .frame(maxWidth: .infinity)
        }
        .background(PP.bg.ignoresSafeArea())
        .resumeScroll("event.\(eventId).sheet", ready: sheet.value != nil)
        .refreshable { await sheet.load(session.loader, connectivity: session.connectivity, freshness: session.freshness) }
    }

    private var sheetLoadingState: some View {
        Group {
            if let error = sheet.error { ErrorPanel(message: error) }
            else { SkeletonLines() }
        }
        .padding(PP.Space.s5)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(PP.bg)
    }

    private struct TeamSheetTarget: Identifiable {
        let page: Int
        let title: String
        var id: String { "\(page)-\(title)" }
    }

    // MARK: content

    private func content(_ sheet: Sheet) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(sheet.eventName).ppHeadline()
            Text([
                "\(sheet.seriesName) \(String(sheet.year))",
                sheet.roundOrdinal.map { "Round \($0)" },
                sheet.circuitName,
                sheet.eventDate,
            ].compactMap { $0 }.joined(separator: " · "))
                .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                .padding(.top, PP.Space.s1)
            Legend(items: [
                LegendItem(swatch: .win, text: "Win"), LegendItem(swatch: .top3, text: "Top 3"),
                LegendItem(swatch: .top5, text: "Top 5"), LegendItem(swatch: .dnf, text: "DNF"),
                LegendItem(text: "start/finish in class"), LegendItem(text: "P = pole", accent: true),
                LegendItem(text: "· = no result"),
                LegendItem(text: "Underlined = starting driver", accent: true),
            ])
            .padding(.top, PP.Space.s3)
            ForEach(sheet.classes) { cls in
                ClassSection(sheet: sheet, cls: cls, linked: sheet.teamSheetsPath != nil) { entry in
                    teamSheet = (entry.teamSheetPage ?? 1, "#\(entry.carNumber) \(entry.teamName)")
                }
                .padding(.top, PP.Space.s5)
            }
        }
    }

    // MARK: export

    private func export(_ sheet: Sheet) async {
        exporting = true
        defer { exporting = false }
        let images = await SheetPrint.preloadImages(sheet, loader: session.loader)
        if let url = SheetPrint.render(sheet, images: images) { exportURL = url }
    }
}

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}

// MARK: - class section

private struct ClassSection: View {
    @Environment(\.colorScheme) private var colorScheme
    let sheet: Sheet
    let cls: SheetClass
    let linked: Bool
    let openTeamSheet: (SheetEntry) -> Void

    /// Only rounds this class contested — a strip of "—" for the whole class is noise.
    private var classRounds: [FormRound] {
        sheet.formRounds.filter { r in cls.entries.contains { !$0.races(round: r.ordinal).isEmpty } }
    }

    var body: some View {
        VStack(spacing: 0) {
            ClassBand(label: cls.className, color: cls.color)
                .clipShape(UnevenRoundedRectangle(topLeadingRadius: PP.Radius.md, topTrailingRadius: PP.Radius.md, style: .continuous))
                .overlay(UnevenRoundedRectangle(topLeadingRadius: PP.Radius.md, topTrailingRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.borderStrong))
            ScrollView(.horizontal, showsIndicators: false) {
                VStack(spacing: 0) {
                    header
                    ForEach(Array(cls.entries.enumerated()), id: \.1.id) { i, entry in
                        EntryRows(sheet: sheet, cls: cls, entry: entry, rounds: classRounds,
                                  zebra: i % 2 == 1, linked: linked && entry.teamSheetPage != nil,
                                  last: i == cls.entries.count - 1, openTeamSheet: openTeamSheet)
                    }
                }
                .frame(minWidth: 760)
                .containerRelativeFrame(.horizontal, alignment: .leading) { width, _ in max(760, width) }
            }
            .resumeScroll("event.\(sheet.eventId).class.\(cls.id)")
            .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            .clipShape(UnevenRoundedRectangle(bottomLeadingRadius: PP.Radius.md, bottomTrailingRadius: PP.Radius.md, style: .continuous))
            .overlay(UnevenRoundedRectangle(bottomLeadingRadius: PP.Radius.md, bottomTrailingRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        }
    }

    private var header: some View {
        SheetColumns { col in
            Text(headerLabel(col)).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
                .frame(maxWidth: .infinity, alignment: col.alignment)
        }
        .padding(.vertical, 6)
        .background(PP.surface)
        .overlay(alignment: .bottom) { Rectangle().fill(PP.borderStrong).frame(height: 1) }
    }

    private func headerLabel(_ col: SheetColumn) -> String {
        switch col {
        case .num: "#"
        case .team: "Team"
        case .mfr: "Mfr"
        case .drivers: "Drivers"
        case .q: "Start"
        case .prior: sheet.priorYearLabel
        case .champ: "\(String(sheet.year)) champ"
        case .photo: ""
        }
    }
}

/// The sheet's fixed column budget (`table-layout: fixed`, widths in %).
enum SheetColumn: CaseIterable {
    case num, team, mfr, drivers, q, prior, champ, photo

    var fraction: CGFloat {
        switch self {
        case .num: 0.05
        case .team: 0.20
        case .mfr: 0.08
        case .drivers: 0.21
        case .q: 0.06
        case .prior: 0.09
        case .champ: 0.11
        case .photo: 0.20
        }
    }

    var alignment: Alignment { self == .team || self == .drivers ? .leading : .center }
}

/// Lays the eight columns out at their percentage widths of the row; the row
/// is as tall as its tallest cell (`table-layout: fixed`).
private struct SheetColumns<Cell: View>: View {
    @ViewBuilder let cell: (SheetColumn) -> Cell

    var body: some View {
        PercentColumns(fractions: SheetColumn.allCases.map(\.fraction), alignments: SheetColumn.allCases.map(\.alignment)) {
            ForEach(SheetColumn.allCases, id: \.self) { col in
                cell(col).padding(.horizontal, 6)
            }
        }
    }
}

/// A row of cells at fixed fractions of the available width, each cell
/// centred vertically, the row as tall as its tallest cell.
struct PercentColumns: Layout {
    let fractions: [CGFloat]
    let alignments: [Alignment]
    /// Propose the row height to every cell so flexible frames fill it (print grid).
    var fill = false

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 760
        var height: CGFloat = 0
        for (i, sub) in subviews.enumerated() {
            let w = width * (i < fractions.count ? fractions[i] : 0)
            height = max(height, sub.sizeThatFits(ProposedViewSize(width: w, height: nil)).height)
        }
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        for (i, sub) in subviews.enumerated() {
            let w = bounds.width * (i < fractions.count ? fractions[i] : 0)
            if fill {
                sub.place(at: CGPoint(x: x, y: bounds.minY), proposal: ProposedViewSize(width: w, height: bounds.height))
            } else {
                let size = sub.sizeThatFits(ProposedViewSize(width: w, height: nil))
                let alignment = i < alignments.count ? alignments[i] : .center
                let dx: CGFloat = alignment.horizontal == .leading ? 0 : alignment.horizontal == .trailing ? w - size.width : (w - size.width) / 2
                sub.place(at: CGPoint(x: x + max(0, dx), y: bounds.midY - size.height / 2),
                          proposal: ProposedViewSize(width: min(w, size.width), height: size.height))
            }
            x += w
        }
    }
}

/// One entry: the main row plus its season-form strip, tinted as a unit.
private struct EntryRows: View {
    @Environment(AppSession.self) private var session
    @Environment(\.colorScheme) private var colorScheme
    let sheet: Sheet
    let cls: SheetClass
    let entry: SheetEntry
    let rounds: [FormRound]
    let zebra: Bool
    let linked: Bool
    let last: Bool
    let openTeamSheet: (SheetEntry) -> Void

    private var classColor: Color { Color(cssHex: cls.color) ?? PP.textMuted }

    var body: some View {
        VStack(spacing: 0) {
            Button { if linked { openTeamSheet(entry) } } label: { mainRow }
                .buttonStyle(.plain)
                .disabled(!linked)
                .accessibilityLabel(linked ? "Open team sheet for #\(entry.carNumber) \(entry.teamName)" : "#\(entry.carNumber) \(entry.teamName)")
            if !rounds.isEmpty, !entry.form.isEmpty { formStrip }
        }
        .overlay(alignment: .bottom) { if !last { Rectangle().fill(PP.border).frame(height: 1) } }
    }

    private var mainRow: some View {
        SheetColumns { col in
            switch col {
            case .num:
                Text(entry.carNumber).font(PP.mono(PP.TextSize.base, weight: 700)).foregroundStyle(PP.ink)
            case .team:
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.teamName).font(PP.sans(PP.TextSize.sm, weight: 500)).foregroundStyle(PP.ink)
                    if entry.isGuest { Badge(text: "GUEST") }
                }
            case .mfr:
                ManufacturerMark(entry: entry)
            case .drivers:
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(entry.drivers.enumerated()), id: \.0) { _, d in
                        let isStarter = entry.isStartingDriver(d)
                        HStack(spacing: 6) {
                            if let flag = Flags.emoji(d.nationality) { Text(flag).font(.system(size: 13)) }
                            HStack(spacing: 0) {
                                if let r = d.rating { Text("(\(r)) ").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted) }
                                else if d.isTbd { Text("(?) ").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted) }
                                NameLink(text: d.name, target: d.isTbd ? nil : InfoTarget.driver(named: d.name),
                                         font: PP.sans(PP.TextSize.sm, weight: isStarter ? 600 : 400),
                                         color: isStarter ? PP.accentInk : PP.text)
                                    .underline(isStarter)
                                    .accessibilityLabel(d.name + (isStarter ? ", starting driver" : ""))
                            }
                        }
                        .lineLimit(1)
                    }
                }
            case .q:
                Text(entry.qualifying ?? "").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.text)
            case .prior:
                Text(entry.priorYearNote ?? "").font(PP.sans(PP.TextSize.sm))
                    .foregroundStyle(entry.priorYearAuto ? PP.info : PP.text)
                    .multilineTextAlignment(.center)
            case .champ:
                Text(entry.championship ?? "").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text).multilineTextAlignment(.center)
            case .photo:
                if let path = entry.imagePath {
                    CachedImage(path: path, contentMode: .fit)
                        .frame(height: 44)
                } else {
                    Color.clear.frame(height: 1)
                }
            }
        }
        .padding(.vertical, 6)
        .background(zebra ? classColor.opacity(0.08) : .clear)
    }

    /// `.form-strip`: one column per round the class contested, on the panel surface.
    private var formStrip: some View {
        HStack(spacing: 0) {
            ForEach(Array(rounds.enumerated()), id: \.1.ordinal) { i, r in
                let races = entry.races(round: r.ordinal)
                VStack(spacing: 2) {
                    Text("R\(r.ordinal) \(r.venue)").font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                    HStack(spacing: 6) {
                        if races.isEmpty {
                            Text("—").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                        } else {
                            ForEach(races, id: \.raceOrdinal) { race in
                                StripRace(race: race, showLabel: r.raceCount > 1)
                            }
                        }
                    }
                }
                .padding(.vertical, 4).padding(.horizontal, 6)
                .frame(maxWidth: .infinity)
                .overlay(alignment: .leading) { if i > 0 { Rectangle().fill(PP.border).frame(width: 1) } }
            }
        }
        .background(PP.surface)
    }
}

/// One race in the form strip, in the recap's `.race-line` vocabulary.
struct StripRace: View {
    let race: FormRace
    let showLabel: Bool

    var body: some View {
        let abbr = RaceForm.statusAbbr(race.status)
        let finish = !abbr.isEmpty ? abbr : (race.finish.map(String.init) ?? "·")
        let quiet = abbr == "DNS" || (race.finish == nil && abbr.isEmpty)
        let nonResult = race.finish == nil || !abbr.isEmpty
        let tier = RaceForm.positionTier(finish: race.finish, nonResult: nonResult)
        HStack(alignment: .firstTextBaseline, spacing: 3) {
            if showLabel { Text("R\(race.raceOrdinal)").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted) }
            if quiet {
                Text(finish).font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            } else {
                HStack(spacing: 0) {
                    if let start = race.start {
                        if start == 1 { Text("P").fontWeight(.bold).foregroundStyle(tier == .dnf ? ResultTint.dnfAccent : PP.accentInk) }
                        else { Text(String(start)) }
                        Text("/")
                    }
                    Text(finish)
                }
                .font(PP.mono(PP.TextSize.sm))
                .foregroundStyle(tier == .dnf ? ResultTint.dnfInk : PP.text)
                .padding(.horizontal, 4)
                .frame(minWidth: 36)
                .background(ResultTint.fill(tier) ?? .clear, in: RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous))
            }
        }
        .lineLimit(1)
        .fixedSize()
    }
}

/// The manufacturer column: the logo (PNG/WebP), or the name where the mark
/// is an SVG the image decoder can't read.
private struct ManufacturerMark: View {
    @Environment(AppSession.self) private var session
    @Environment(\.colorScheme) private var colorScheme
    let entry: SheetEntry
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                if colorScheme == .dark, entry.manufacturerLogoInvert {
                    Image(uiImage: image).renderingMode(.template).resizable().scaledToFit().foregroundStyle(.white)
                } else if colorScheme == .dark {
                    Image(uiImage: image).resizable().scaledToFit()
                        .padding(.vertical, 2).padding(.horizontal, 5)
                        .background(.white, in: RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous))
                } else {
                    Image(uiImage: image).resizable().scaledToFit()
                }
            } else {
                Text(entry.manufacturer ?? "").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).multilineTextAlignment(.center)
            }
        }
        .frame(maxHeight: 28)
        .task(id: entry.manufacturerLogoPath) {
            image = nil
            guard let path = entry.manufacturerLogoPath else { return }
            if let data = await session.loader.bytes(path), !Task.isCancelled, let decoded = ImageDecoding.decode(data) {
                image = decoded
            }
        }
    }
}


/// The exported PDF: a preview line and SwiftUI's ShareLink, whose share
/// sheet carries Print and Save to Files.
private struct ExportSheet: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: PP.Space.s4) {
                Text(url.lastPathComponent).ppTitle()
                Text("US Letter, light, one entry per block — the same sheet the website prints.")
                    .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                ShareLink(item: url, preview: SharePreview(url.lastPathComponent, image: Image(systemName: "doc.richtext"))) {
                    Label("Share, print or save", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(PPPrimaryButtonStyle())
                Spacer()
            }
            .padding(PP.Space.s5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PP.bg.ignoresSafeArea())
            .navigationTitle("PDF ready")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationBackground(PP.bg)
        .presentationDetents([.medium])
        .tint(PP.accentInk)
    }
}
