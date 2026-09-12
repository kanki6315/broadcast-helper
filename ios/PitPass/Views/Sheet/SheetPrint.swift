import SwiftUI
import UIKit

/// "Print / Save PDF": the compact light US-Letter sheet, regardless of the
/// on-screen theme — the web's `@media print` block. Pages break between
/// entries (an entry and its form strip never split), the class band stays
/// with the first entry of its table, and the column header repeats on every
/// page a class spans.
enum SheetPrint {
    static let pageSize = CGSize(width: 612, height: 792)   // US Letter, 72dpi points
    static let margin: CGFloat = 25.2                        // 0.35in
    /// The browser lays the print sheet out in CSS px at 96/in and scales to
    /// paper; laying out at the same px width keeps the web's column density.
    static let cssPerPoint: CGFloat = 96.0 / 72.0
    static var contentWidth: CGFloat { (pageSize.width - margin * 2) * cssPerPoint }
    static var contentHeight: CGFloat { (pageSize.height - margin * 2) * cssPerPoint }

    /// Print tokens (light literals from sheet.css).
    enum Ink {
        static let bg = Color.white
        static let surface = Color(hex: 0xEEF0F2)
        static let border = Color(hex: 0xC9CCD3)
        static let ink = Color(hex: 0x111111)
        static let text = Color(hex: 0x26242C)
        static let muted = Color(hex: 0x55585F)
        static let accentInk = Color(hex: 0x8F5E12)
        static let info = Color(hex: 0x2456A6)
        static let win = Color(hex: 0xBFECCD), top3 = Color(hex: 0xF7DCE9), top5 = Color(hex: 0xE2DCF8)
        static let dnfBg = Color(hex: 0x25262C), dnfInk = Color(hex: 0xE6E8EB)
    }

    struct Block {
        let cls: SheetClass
        let rounds: [FormRound]
        let entries: [SheetEntry]
        let continued: Bool
    }

    /// Rendered height of a print fragment at the page's content width.
    @MainActor
    static func measure(_ view: some View) -> CGFloat {
        let renderer = ImageRenderer(content: view.frame(width: contentWidth, alignment: .topLeading).environment(\.colorScheme, .light))
        renderer.scale = 1
        renderer.proposedSize = ProposedViewSize(width: contentWidth, height: nil)
        return renderer.uiImage?.size.height ?? 0
    }

    /// Pagination by measured heights: an entry never splits, a band never
    /// sits alone at the bottom of a page (break-after: avoid).
    @MainActor
    static func paginate(_ sheet: Sheet, images: [String: UIImage]) -> [[Block]] {
        var pages: [[Block]] = []
        var page: [Block] = []
        var used: CGFloat = measure(PrintTitle(sheet: sheet))
        for cls in sheet.classes {
            let rounds = sheet.formRounds.filter { r in cls.entries.contains { !$0.races(round: r.ordinal).isEmpty } }
            let overhead = measure(PrintBlock(sheet: sheet, block: Block(cls: cls, rounds: rounds, entries: [], continued: false), images: images))
            let heights = cls.entries.map { e in
                measure(PrintEntry(sheet: sheet, cls: cls, rounds: rounds, entry: e, zebra: false, images: images))
            }
            var index = 0
            var continued = false
            while index < cls.entries.count {
                var fit = 0
                var h = overhead
                while index + fit < cls.entries.count, used + h + heights[index + fit] <= contentHeight {
                    h += heights[index + fit]; fit += 1
                }
                if fit == 0 {
                    // Nothing fits under what's already on the page: turn the page.
                    if page.isEmpty {
                        fit = 1; h += heights[index]  // an entry taller than a page still ships
                    } else {
                        pages.append(page); page = []; used = 0
                        continue
                    }
                }
                page.append(Block(cls: cls, rounds: rounds, entries: Array(cls.entries[index..<index + fit]), continued: continued))
                used += h
                index += fit
                continued = true
                if index < cls.entries.count { pages.append(page); page = []; used = 0 }
            }
        }
        if !page.isEmpty { pages.append(page) }
        return pages
    }

    /// Photos and logos the pages need, fetched through the store first.
    @MainActor
    static func preloadImages(_ sheet: Sheet, loader: DataLoader) async -> [String: UIImage] {
        var out: [String: UIImage] = [:]
        for cls in sheet.classes {
            for e in cls.entries {
                if let v = e.imageVersion {
                    let p = "/api/entries/\(e.entryId)/image?variant=sheet&v=\(v)"
                    if let d = await loader.bytes(p), let img = ImageDecoding.decode(d) { out[p] = img }
                }
                if let v = e.manufacturerLogoVersion, let name = e.manufacturer {
                    let encoded = name.lowercased().addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name.lowercased()
                    let p = "/api/manufacturer-logos/\(encoded)/data?v=\(v)"
                    if let d = await loader.bytes(p), let img = ImageDecoding.decode(d) { out[p] = img }
                }
            }
        }
        return out
    }

    /// Renders every page into a PDF in the temporary directory.
    @MainActor
    static func render(_ sheet: Sheet, images: [String: UIImage]) -> URL? {
        let pages = paginate(sheet, images: images)
        let name = "\(sheet.seriesName) \(sheet.year) — \(sheet.eventName).pdf"
            .replacingOccurrences(of: "/", with: "-")
        let url = FileManager.default.temporaryDirectory.appending(path: name)
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: pageSize))
        do {
            try renderer.writePDF(to: url) { ctx in
                for (i, blocks) in pages.enumerated() {
                    ctx.beginPage()
                    let view = PrintPage(sheet: sheet, blocks: blocks, first: i == 0, images: images)
                        .frame(width: contentWidth, alignment: .topLeading)
                        .environment(\.colorScheme, .light)
                    let image = ImageRenderer(content: view)
                    image.scale = 3
                    image.proposedSize = ProposedViewSize(width: contentWidth, height: nil)
                    if let ui = image.uiImage {
                        let scale = 1 / cssPerPoint
                        ui.draw(in: CGRect(x: margin, y: margin, width: ui.size.width * scale, height: ui.size.height * scale))
                    }
                }
            }
            return url
        } catch {
            return nil
        }
    }
}

// MARK: - the printed page

private struct PrintPage: View {
    let sheet: Sheet
    let blocks: [SheetPrint.Block]
    let first: Bool
    let images: [String: UIImage]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if first { PrintTitle(sheet: sheet) }
            ForEach(Array(blocks.enumerated()), id: \.0) { _, block in
                PrintBlock(sheet: sheet, block: block, images: images)
            }
        }
        .background(SheetPrint.Ink.bg)
    }
}

struct PrintTitle: View {
    let sheet: Sheet
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(sheet.eventName).font(PP.sans(18, weight: 600)).foregroundStyle(SheetPrint.Ink.ink)
            Text([
                "\(sheet.seriesName) \(String(sheet.year))", sheet.roundOrdinal.map { "Round \($0)" },
                sheet.circuitName, sheet.eventDate,
            ].compactMap { $0 }.joined(separator: " · "))
                .font(PP.sans(11)).foregroundStyle(SheetPrint.Ink.muted)
                .padding(.top, 2).padding(.bottom, 10)
        }
    }
}

struct PrintBlock: View {
    let sheet: Sheet
    let block: SheetPrint.Block
    let images: [String: UIImage]

    private var scale: CGFloat { 1.25 }

    var body: some View {
        VStack(spacing: 0) {
            Text(block.cls.className + (block.continued ? " (continued)" : ""))
                .font(PP.sans(13 * scale, weight: 700)).foregroundStyle(classInk(block.cls.color))
                .padding(.vertical, 2 * scale).padding(.horizontal, 6 * scale)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(cssHex: block.cls.color) ?? SheetPrint.Ink.surface)
                .padding(.top, 10)
            PrintRow(scale: scale) { col in
                Text(header(col)).font(PP.sans(9 * scale, weight: 600)).foregroundStyle(SheetPrint.Ink.muted)
                    .frame(maxWidth: .infinity, alignment: col.alignment)
            }
            .background(SheetPrint.Ink.surface)
            ForEach(Array(block.entries.enumerated()), id: \.1.id) { i, e in
                PrintEntry(sheet: sheet, cls: block.cls, rounds: block.rounds, entry: e, zebra: i % 2 == 1, images: images)
            }
        }
    }

    private func header(_ col: SheetColumn) -> String {
        switch col {
        case .num: "#"; case .team: "Team"; case .mfr: "Mfr"; case .drivers: "Drivers"; case .q: "Start"
        case .prior: sheet.priorYearLabel; case .champ: "\(String(sheet.year)) champ"; case .photo: ""
        }
    }

}

/// One print table row at the sheet's percentage columns, every cell filling the row.
struct PrintRow<Cell: View>: View {
    let scale: CGFloat
    @ViewBuilder let cell: (SheetColumn) -> Cell

    var body: some View {
        PercentColumns(fractions: SheetColumn.allCases.map(\.fraction), alignments: SheetColumn.allCases.map(\.alignment), fill: true) {
            ForEach(SheetColumn.allCases, id: \.self) { col in
                cell(col)
                    .padding(.vertical, 2 * scale).padding(.horizontal, 4 * scale)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: col.alignment)
                    .overlay(Rectangle().strokeBorder(Color(hex: 0x999999), lineWidth: 0.5))
            }
        }
    }
}

/// One printed entry: the main row and its form strip.
struct PrintEntry: View {
    let sheet: Sheet
    let cls: SheetClass
    let rounds: [FormRound]
    let entry: SheetEntry
    let zebra: Bool
    let images: [String: UIImage]

    private var scale: CGFloat { 1.25 }

    var body: some View {
        let e = entry
        let classColor = Color(cssHex: cls.color) ?? SheetPrint.Ink.muted
        VStack(spacing: 0) {
            PrintRow(scale: scale) { col in
                switch col {
                case .num: Text(e.carNumber).font(PP.mono(12 * scale, weight: 700)).foregroundStyle(SheetPrint.Ink.ink)
                case .team:
                    VStack(alignment: .leading, spacing: 1) {
                        Text(e.teamName).font(PP.sans(9.5 * scale, weight: 500)).foregroundStyle(SheetPrint.Ink.ink)
                        if e.isGuest { Text("GUEST").font(PP.sans(8 * scale, weight: 600)).foregroundStyle(SheetPrint.Ink.muted) }
                    }
                case .mfr:
                    if let v = e.manufacturerLogoVersion, let name = e.manufacturer,
                       let img = images["/api/manufacturer-logos/\(name.lowercased().addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name.lowercased())/data?v=\(v)"] {
                        Image(uiImage: img).resizable().scaledToFit().frame(maxHeight: 0.3 * 72 * scale)
                    } else {
                        Text(e.manufacturer ?? "").font(PP.sans(8.5 * scale)).foregroundStyle(SheetPrint.Ink.muted).lineLimit(1).minimumScaleFactor(0.7)
                    }
                case .drivers:
                    VStack(alignment: .leading, spacing: 1) {
                        ForEach(Array(e.drivers.enumerated()), id: \.0) { _, d in
                            HStack(spacing: 4) {
                                if let f = Flags.emoji(d.nationality) { Text(f).font(.system(size: 9 * scale)) }
                                Text((d.rating.map { "(\($0)) " } ?? (d.isTbd ? "(?) " : "")) + d.name)
                                    .font(PP.sans(9.5 * scale)).foregroundStyle(SheetPrint.Ink.text)
                            }
                            .lineLimit(1)
                        }
                    }
                case .q:
                    VStack(spacing: 0) {
                        Text(e.qualifying ?? "").font(PP.mono(9.5 * scale)).foregroundStyle(SheetPrint.Ink.text).lineLimit(1)
                        if let s = e.startingDriver { Text(s).font(PP.sans(8 * scale)).foregroundStyle(SheetPrint.Ink.muted).lineLimit(1) }
                    }
                case .prior:
                    Text(e.priorYearNote ?? "").font(PP.sans(9.5 * scale)).foregroundStyle(e.priorYearAuto ? SheetPrint.Ink.info : SheetPrint.Ink.text)
                        .multilineTextAlignment(.center)
                case .champ:
                    Text(e.championship ?? "").font(PP.sans(9.5 * scale)).foregroundStyle(SheetPrint.Ink.text).multilineTextAlignment(.center)
                case .photo:
                    if let v = e.imageVersion, let img = images["/api/entries/\(e.entryId)/image?variant=sheet&v=\(v)"] {
                        Image(uiImage: img).resizable().scaledToFit().frame(maxHeight: 0.42 * 72 * scale)
                    } else {
                        Color.clear.frame(height: 1)
                    }
                }
            }
            .background(zebra ? classColor.opacity(0.08) : .clear)
            if !rounds.isEmpty, !e.form.isEmpty {
                HStack(spacing: 0) {
                    ForEach(rounds, id: \.ordinal) { r in
                        let races = e.races(round: r.ordinal)
                        VStack(spacing: 1) {
                            Text("R\(r.ordinal) \(r.venue)").font(PP.mono(8 * scale)).foregroundStyle(SheetPrint.Ink.muted)
                            HStack(spacing: 8 * scale) {
                                if races.isEmpty { Text("—").font(PP.mono(11 * scale)).foregroundStyle(SheetPrint.Ink.muted) }
                                ForEach(races, id: \.raceOrdinal) { race in printRace(race, showLabel: r.raceCount > 1) }
                            }
                        }
                        .padding(.vertical, 2 * scale).padding(.horizontal, 3 * scale)
                        .frame(maxWidth: .infinity)
                        .overlay(Rectangle().strokeBorder(Color(hex: 0x999999), lineWidth: 0.5))
                    }
                }
                .background(SheetPrint.Ink.surface)
            }
        }
    }

    private func printRace(_ race: FormRace, showLabel: Bool) -> some View {
        let abbr = RaceForm.statusAbbr(race.status)
        let finish = !abbr.isEmpty ? abbr : (race.finish.map(String.init) ?? "·")
        let quiet = abbr == "DNS" || (race.finish == nil && abbr.isEmpty)
        let tier = RaceForm.positionTier(finish: race.finish, nonResult: race.finish == nil || !abbr.isEmpty)
        let fill: Color? = switch tier {
        case .win: SheetPrint.Ink.win
        case .top3: SheetPrint.Ink.top3
        case .top5: SheetPrint.Ink.top5
        case .dnf: SheetPrint.Ink.dnfBg
        case .none: nil
        }
        return HStack(alignment: .firstTextBaseline, spacing: 2) {
            if showLabel { Text("R\(race.raceOrdinal)").font(PP.sans(7.5 * scale)).foregroundStyle(SheetPrint.Ink.muted) }
            HStack(spacing: 0) {
                if !quiet, let s = race.start {
                    if s == 1 { Text("P").fontWeight(.bold).foregroundStyle(SheetPrint.Ink.accentInk) } else { Text(String(s)) }
                    Text("/")
                }
                Text(finish)
            }
            .font(PP.mono(11 * scale))
            .foregroundStyle(quiet ? SheetPrint.Ink.muted : (tier == .dnf ? SheetPrint.Ink.dnfInk : SheetPrint.Ink.text))
            .padding(.horizontal, 3)
            .background((quiet ? nil : fill) ?? .clear, in: RoundedRectangle(cornerRadius: 2))
        }
    }
}
