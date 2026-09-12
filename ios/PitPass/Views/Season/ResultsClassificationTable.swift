import SwiftUI

/// Results use natural row heights: the complete crew and vehicle sit under
/// the team, while statistics remain aligned across the whole classification.
struct ResultsClassificationTable: View {
    let columns: [GridColumn]
    let rows: [GridRowItem]
    @State private var availableWidth: CGFloat = 0

    var body: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            ResultsClassificationLayout(columns: columns, availableWidth: availableWidth) {
                ForEach(columns) { column in
                    column.title
                        .multilineTextAlignment(column.align == .trailing ? .trailing : column.align == .center ? .center : .leading)
                        .fixedSize(horizontal: true, vertical: true)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: column.alignment)
                        .padding(6)
                        .padding(.trailing, column.id == columns.last?.id ? 10 : 0)
                        .background(PP.surface)
                        .overlay(alignment: .bottom) { Rectangle().fill(PP.borderStrong).frame(height: 1) }
                }
                ForEach(rows) { row in
                    let cells = row.ident + row.cells
                    ForEach(Array(columns.enumerated()), id: \.element.id) { index, column in
                        // Empty status cells still need a layout slot; an
                        // EmptyView alone disappears from Layout.Subviews.
                        ZStack(alignment: column.alignment) {
                            Color.clear
                            cells[index]
                        }
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: column.alignment)
                            .padding(6)
                            .padding(.trailing, column.id == columns.last?.id ? 10 : 0)
                            .overlay(alignment: .bottom) { Rectangle().fill(PP.border).frame(height: 1) }
                    }
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize, axes: .horizontal)
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { availableWidth = $0 }
        .background(PP.bg)
        .clipShape(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        .padding(.bottom, PP.Space.s5)
    }
}

/// Measure actual data (including long race times and custom class labels)
/// before sharing spare width between the entry and timing columns. At narrow
/// split-view widths the complete table scrolls instead of clipping values.
private struct ResultsClassificationLayout: Layout {
    let columns: [GridColumn]
    let availableWidth: CGFloat

    private struct Measurements {
        var widths: [CGFloat]
        var heights: [CGFloat]
        var size: CGSize { CGSize(width: widths.reduce(0, +), height: heights.reduce(0, +)) }
    }

    private func measure(_ subviews: Subviews) -> Measurements {
        guard !columns.isEmpty else { return Measurements(widths: [], heights: []) }
        var widths = columns.map(\.width)
        let entry = columns.firstIndex { $0.id == "entry" }
        // Explicit header line breaks keep words intact. Measure headers
        // and values so neither labels nor populated times are clipped.
        for index in subviews.indices {
            let column = index % columns.count
            if column != entry {
                widths[column] = max(widths[column], ceil(subviews[index].sizeThatFits(.unspecified).width))
            }
        }
        if let entry {
            var spare = max(0, availableWidth - widths.reduce(0, +))
            // First give names a useful reading width in portrait. Beyond
            // that, share the room with statistics instead of leaving a vast
            // empty entry cell beside tightly packed times in landscape.
            let readingRoom = min(spare, max(0, 260 - widths[entry]))
            widths[entry] += readingRoom
            spare -= readingRoom
            let statistics = columns.indices.filter { $0 > entry }
            let entryRoom = statistics.isEmpty ? spare : min(spare * 0.5, max(0, 440 - widths[entry]))
            widths[entry] += entryRoom
            spare -= entryRoom
            if !statistics.isEmpty {
                for column in statistics { widths[column] += spare / CGFloat(statistics.count) }
            }
        }
        var heights: [CGFloat] = []
        for start in stride(from: 0, to: subviews.count, by: columns.count) {
            var height: CGFloat = start == 0 ? 40 : 0
            for column in columns.indices where start + column < subviews.count {
                height = max(height, ceil(subviews[start + column].sizeThatFits(
                    ProposedViewSize(width: widths[column], height: nil)
                ).height))
            }
            heights.append(height)
        }
        return Measurements(widths: widths, heights: heights)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        measure(subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let measurements = measure(subviews)
        var y = bounds.minY
        for (row, height) in measurements.heights.enumerated() {
            var x = bounds.minX
            for (column, width) in measurements.widths.enumerated() {
                let index = row * columns.count + column
                if index < subviews.count {
                    subviews[index].place(at: CGPoint(x: x, y: y), anchor: .topLeading,
                                          proposal: ProposedViewSize(width: width, height: height))
                }
                x += width
            }
            y += height
        }
    }
}
