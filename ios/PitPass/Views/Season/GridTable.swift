import SwiftUI

// The signature component (`.grid-table`), natively: identity columns pinned on
// the left, data columns scrolling horizontally, class bands spanning both.
// Row heights are budgeted up front from a per-row line count so the two
// halves stay in register without measuring each other.

struct GridColumn: Identifiable {
    enum Align { case leading, trailing, center }
    let id: String
    let title: AnyView
    let width: CGFloat
    var align: Align = .leading
    /// Horizontal cell padding override (round cells run tighter, like `.race-cell`).
    var padH: CGFloat?

    init(id: String, width: CGFloat, align: Align = .leading, padH: CGFloat? = nil, @ViewBuilder title: () -> some View) {
        self.id = id
        self.width = width
        self.align = align
        self.padH = padH
        self.title = AnyView(title())
    }

    /// A plain caption header (`th`).
    static func text(_ id: String, _ label: String, width: CGFloat, align: Align = .leading) -> GridColumn {
        GridColumn(id: id, width: width, align: align) {
            Text(label).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
        }
    }

    /// `.round-head`: venue (mono, bold) over "Rd n".
    static func round(_ id: String, venue: String, round: Int, width: CGFloat = 66, current: Bool = false) -> GridColumn {
        GridColumn(id: id, width: width, align: .center, padH: 4) {
            VStack(spacing: 1) {
                Text(venue).font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(current ? PP.accentInk : PP.text)
                Text("Rd \(round)").font(PP.sans(PP.TextSize.xs, weight: 500)).foregroundStyle(current ? PP.accentInk : PP.textMuted)
            }
            .lineLimit(1)
        }
    }

    var alignment: Alignment {
        switch align {
        case .leading: .leading
        case .trailing: .trailing
        case .center: .center
        }
    }
}

struct GridRowItem: Identifiable {
    let id: String
    let ident: [AnyView]
    let cells: [AnyView]
    /// Stacked lines in the tallest cell — the row's height budget.
    let lines: Int
}

struct GridSection: Identifiable {
    let id: String
    /// A class band drawn across the full width, or nil for a bandless table.
    var band: (label: String, color: String)?
    let rows: [GridRowItem]
}

struct GridTable: View {
    let identColumns: [GridColumn]
    let dataColumns: [GridColumn]
    let sections: [GridSection]
    /// Points per stacked line; 20 fits the 14pt mono chips, 18 the xs crews.
    var lineHeight: CGFloat = 20
    var cellPadV: CGFloat = 4
    var cellPadH: CGFloat = 10
    var headerHeight: CGFloat = 40

    private let bandHeight: CGFloat = 26

    private func rowHeight(_ row: GridRowItem) -> CGFloat {
        CGFloat(max(1, row.lines)) * lineHeight + cellPadV * 2 + 1
    }

    private var identWidth: CGFloat { identColumns.reduce(0) { $0 + $1.width } }
    private var dataWidth: CGFloat { dataColumns.reduce(0) { $0 + $1.width } }
    private var totalHeight: CGFloat {
        headerHeight + sections.reduce(0) { acc, s in
            acc + (s.band != nil ? bandHeight : 0) + s.rows.reduce(0) { $0 + rowHeight($1) }
        }
    }

    var body: some View {
        // The data half fills whatever the identity half leaves (the web's
        // `grid-soak` column), scrolling only when the season is long.
        GeometryReader { geo in
            HStack(alignment: .top, spacing: 0) {
                column(identColumns, ident: true)
                    .background(PP.bg)
                    .zIndex(1)
                ScrollView(.horizontal, showsIndicators: true) {
                    column(dataColumns, ident: false)
                        .frame(minWidth: max(dataWidth, geo.size.width - identWidth), alignment: .leading)
                }
                .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
            }
        }
        .frame(height: totalHeight)
        .clipShape(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        .padding(.bottom, PP.Space.s5)
    }

    private func column(_ columns: [GridColumn], ident: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 0) {
                ForEach(columns) { col in
                    col.title
                        .padding(.horizontal, col.padH ?? cellPadH)
                        .frame(width: col.width, height: headerHeight, alignment: col.alignment)
                }
                if !ident { Spacer(minLength: 0) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(PP.surface)
            .overlay(alignment: .bottom) { Rectangle().fill(PP.borderStrong).frame(height: 1) }
            ForEach(sections) { section in
                if let band = section.band {
                    ClassBand(label: ident ? band.label : "", color: band.color)
                        .frame(height: bandHeight)
                }
                ForEach(section.rows) { row in
                    let views = ident ? row.ident : row.cells
                    HStack(alignment: .top, spacing: 0) {
                        ForEach(Array(zip(columns.indices, columns)), id: \.0) { i, col in
                            (i < views.count ? views[i] : AnyView(EmptyView()))
                                .padding(.horizontal, col.padH ?? cellPadH)
                                .padding(.vertical, cellPadV)
                                .frame(width: col.width, height: rowHeight(row), alignment: col.align == .center ? .top : Alignment(horizontal: col.alignment.horizontal, vertical: .top))
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
                }
            }
        }
        .frame(minWidth: columns.reduce(0) { $0 + $1.width }, alignment: .leading)
    }
}

// Cell helpers in the grid's vocabulary.

enum GridCell {
    static func pos(_ n: Int) -> AnyView {
        AnyView(Text(String(n)).font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.ink))
    }

    static func num(_ text: String, muted: Bool = false, bold: Bool = false) -> AnyView {
        AnyView(Text(text).font(PP.mono(PP.TextSize.sm, weight: bold ? 700 : 400)).foregroundStyle(muted ? PP.textMuted : PP.text))
    }

    static func car(_ text: String) -> AnyView {
        AnyView(Text(text).font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.text))
    }

    static func name(_ text: String, sub: [String] = []) -> AnyView {
        AnyView(VStack(alignment: .leading, spacing: 1) {
            Text(text).font(PP.sans(PP.TextSize.sm, weight: 500)).foregroundStyle(PP.ink).lineLimit(1).truncationMode(.tail)
            if !sub.isEmpty {
                Text(sub.joined(separator: " · ")).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).lineLimit(1)
            }
        })
    }

    static func text(_ text: String, muted: Bool = false) -> AnyView {
        AnyView(Text(text).font(PP.sans(PP.TextSize.sm)).foregroundStyle(muted ? PP.textMuted : PP.text).lineLimit(1))
    }

    static func skip() -> AnyView {
        AnyView(Text("—").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted).frame(maxWidth: .infinity))
    }

    static func empty() -> AnyView { AnyView(EmptyView()) }
}
