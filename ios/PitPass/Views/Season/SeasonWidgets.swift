import SwiftUI

// The season pages' shared vocabulary: segmented controls, class chips / tags /
// bands, result chips, legends — each a port of a rule in App.css / season.css.

/// `.seg` + `.seg-btn`: the instrument-key segmented control. Active = the
/// ground lifted with the raise shadow.
struct Segmented<ID: Hashable>: View {
    struct Option: Identifiable {
        let id: ID
        let label: String
        var mono = false
    }

    let options: [Option]
    @Binding var selection: ID?
    var size: Size = .regular

    enum Size { case regular, year, stage }

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options) { option in
                let active = option.id == selection
                Button {
                    withAnimation(PP.Motion.fast) { selection = option.id }
                } label: {
                    Text(option.label)
                        .font(font(option))
                        .foregroundStyle(active ? PP.ink : PP.textMuted)
                        .padding(.vertical, padV)
                        .padding(.horizontal, padH)
                        .background {
                            if active {
                                RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous)
                                    .fill(PP.bg)
                                    .shadow(color: .black.opacity(0.08), radius: 1, y: 1)
                                    .shadow(color: .black.opacity(0.06), radius: 4, y: 2)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .padding(2)
        .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        .fixedSize()
    }

    private func font(_ option: Option) -> Font {
        switch size {
        case .regular: option.mono ? PP.mono(PP.TextSize.sm, weight: 500) : PP.sans(PP.TextSize.sm, weight: 500)
        case .year: PP.mono(PP.TextSize.sm, weight: 600)
        case .stage: PP.sans(PP.TextSize.xs, weight: 500)
        }
    }

    private var padV: CGFloat { size == .stage ? 2 : (size == .year ? 3 : 4) }
    private var padH: CGFloat { size == .stage ? 9 : (size == .year ? 10 : 12) }
}

/// Multi-toggle variant of `.seg` (formats shown on the stats page).
struct SegmentedToggles<ID: Hashable>: View {
    let options: [Segmented<ID>.Option]
    @Binding var on: Set<ID>
    var toggle: (ID) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options) { option in
                let active = on.contains(option.id)
                Button { withAnimation(PP.Motion.fast) { toggle(option.id) } } label: {
                    Text(option.label)
                        .font(PP.sans(PP.TextSize.sm, weight: 500))
                        .foregroundStyle(active ? PP.ink : PP.textMuted)
                        .padding(.vertical, 4).padding(.horizontal, 12)
                        .background {
                            if active {
                                RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous).fill(PP.bg)
                                    .shadow(color: .black.opacity(0.08), radius: 1, y: 1)
                                    .shadow(color: .black.opacity(0.06), radius: 4, y: 2)
                            }
                        }
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .padding(2)
        .background(PP.surface, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
        .fixedSize()
    }
}

/// `.class-chip`: swatch + code; amber carries selection, the swatch identity.
struct ClassChipRow: View {
    let classes: [ClassInfo]
    @Binding var selection: String?

    var body: some View {
        FlowLayout(horizontalSpacing: PP.Space.s2, verticalSpacing: PP.Space.s2) {
            chip(label: "All classes", color: nil, active: selection == nil) { selection = nil }
            ForEach(classes) { c in
                chip(label: c.name, color: Color(cssHex: c.color), active: selection == c.name) {
                    selection = selection == c.name ? nil : c.name
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Class filter")
    }

    private func chip(label: String, color: Color?, active: Bool, action: @escaping () -> Void) -> some View {
        Button { withAnimation(PP.Motion.fast, action) } label: {
            HStack(spacing: 6) {
                if let color {
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(color)
                        .overlay(RoundedRectangle(cornerRadius: 3, style: .continuous).strokeBorder(PP.borderStrong))
                        .frame(width: 10, height: 10)
                }
                Text(label)
            }
            .font(PP.sans(PP.TextSize.sm, weight: 600))
            .foregroundStyle(active ? PP.ink : PP.text)
            .padding(.vertical, 3).padding(.horizontal, 10)
            .background((active ? (color ?? PP.accent).opacity(0.14) : Color.clear), in: Capsule())
            .overlay(Capsule().strokeBorder(active ? PP.accent : PP.borderStrong, lineWidth: active ? 2 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

/// Readable ink on a user-configured class colour: white above the measured
/// lightness pivot (0.62 in OKLCH; a relative-luminance proxy here).
func classInk(_ cssHex: String) -> Color {
    guard let ui = Color(cssHex: cssHex).map(UIColor.init) else { return .white }
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    ui.getRed(&r, green: &g, blue: &b, alpha: &a)
    func lin(_ c: CGFloat) -> CGFloat { c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
    let y = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    // L* ≈ 0.62 corresponds to Y ≈ 0.31.
    return y > 0.31 ? Color(hex: 0x0B0812) : .white
}

/// `.class-tag`: filled code pill with the strong hairline.
struct ClassTag: View {
    let name: String
    let color: String

    var body: some View {
        Text(name)
            .font(PP.sans(PP.TextSize.xs, weight: 700))
            .foregroundStyle(classInk(color))
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(Color(cssHex: color) ?? PP.textMuted, in: RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous).strokeBorder(PP.borderStrong))
            .lineLimit(1)
            .fixedSize()
    }
}

/// `.class-band`: the full-width class divider in stacked grids.
struct ClassBand: View {
    let label: String
    let color: String

    var body: some View {
        Text(label)
            .font(PP.sans(PP.TextSize.sm, weight: 700))
            .foregroundStyle(classInk(color))
            .padding(.vertical, 4).padding(.horizontal, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(cssHex: color) ?? PP.surface2)
    }
}

/// The result tints (`--res-*`).
enum ResultTint {
    static let win = PP.dynamicPublic(0xBFECCD, 0x124631)
    static let top3 = PP.dynamicPublic(0xF7DCE9, 0x4B2337)
    static let top5 = PP.dynamicPublic(0xE2DCF8, 0x362A58)
    static let dnfBg = PP.dynamicPublic(0x25262C, 0xA2A4AE)
    static let dnfInk = PP.dynamicPublic(0xE6E8EB, 0x101117)
    /// The pole mark ON the inverted DNF chip.
    static let dnfAccent = PP.dynamicPublic(0xF0B84A, 0x8F5E12)

    static func fill(_ tier: RaceForm.Tier) -> Color? {
        switch tier {
        case .win: win
        case .top3: top3
        case .top5: top5
        case .dnf: dnfBg
        case .none: nil
        }
    }
}

/// `.race-line`: one start→finish chip, tinted by tier, with the race tag
/// outside the chip and the retirement `R` mark on it.
struct RaceLineView: View {
    let race: RecapRace
    var tag: String?

    private var isDns: Bool { (race.status ?? "").lowercased().contains("not started") }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 5) {
            if let tag {
                Text(tag).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
            }
            chip
        }
    }

    @ViewBuilder private var chip: some View {
        if isDns {
            Text("DNS").font(PP.mono(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                .frame(minWidth: 36).padding(.horizontal, 4)
        } else {
            let tier = RaceForm.positionTier(finish: race.finish, nonResult: race.notFinished)
            let ink: Color = tier == .dnf ? ResultTint.dnfInk : PP.text
            let poleInk: Color = tier == .dnf ? ResultTint.dnfAccent : PP.accentInk
            HStack(spacing: 0) {
                if let start = race.start {
                    if start == 1 {
                        Text("P").foregroundStyle(poleInk).fontWeight(.bold)
                    } else {
                        Text(String(start))
                    }
                    Text("/")
                }
                Text(race.finish.map(String.init) ?? "–")
                if race.notFinished {
                    Text("R").font(PP.mono(PP.TextSize.sm * 0.72, weight: 700)).baselineOffset(5).padding(.leading, 1)
                }
            }
            .font(PP.mono(PP.TextSize.sm))
            .foregroundStyle(ink)
            .lineLimit(1)
            .fixedSize()
            .padding(.horizontal, 4)
            .frame(minWidth: 36)
            .background(ResultTint.fill(tier) ?? .clear, in: RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous))
            .accessibilityLabel(accessibility)
        }
    }

    private var accessibility: String {
        var parts: [String] = []
        if let tag { parts.append(tag) }
        if let s = race.start { parts.append(s == 1 ? "from pole" : "started \(s)") }
        if let f = race.finish { parts.append("finished \(f)") }
        if race.notFinished { parts.append("retired") }
        return parts.joined(separator: ", ")
    }
}

/// `RaceCell`: the lines of one round cell — one per race, or one per car for
/// a team-keyed row that ran several.
struct RaceCellView: View {
    let races: [RecapRace]?
    let raceTags: [Int: String?]

    var body: some View {
        if let races, !races.isEmpty {
            let byCar = Dictionary(grouping: races, by: { $0.carNumber ?? "" })
            if byCar.count <= 1 {
                VStack(alignment: .center, spacing: 2) {
                    ForEach(races, id: \.self) { r in RaceLineView(race: r, tag: raceTags[r.race] ?? nil) }
                }
            } else {
                VStack(alignment: .center, spacing: 2) {
                    ForEach(byCar.keys.sorted(by: carSort), id: \.self) { car in
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(car).font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted)
                                .frame(minWidth: 22, alignment: .trailing)
                            ForEach(byCar[car]!.sorted { $0.race < $1.race }, id: \.self) { r in
                                RaceLineView(race: r, tag: raceTags[r.race] ?? nil)
                            }
                        }
                    }
                }
            }
        } else {
            Text("·").foregroundStyle(PP.textMuted).accessibilityLabel("Did not enter this round")
        }
    }

    /// Number of stacked lines this cell draws (for row-height budgeting).
    static func lines(_ races: [RecapRace]?) -> Int {
        guard let races, !races.isEmpty else { return 1 }
        let cars = Set(races.map { $0.carNumber ?? "" })
        return cars.count <= 1 ? races.count : cars.count
    }
}

/// Numeric-first car ordering: 7 before 17 before 77, letters after numbers.
func carSort(_ a: String, _ b: String) -> Bool {
    switch (Int(a), Int(b)) {
    case let (x?, y?): return x != y ? x < y : a < b
    case (_?, nil): return true
    case (nil, _?): return false
    default: return a < b
    }
}

/// `.legend`: caption-sized decoding of what's on screen.
struct LegendItem: Identifiable {
    enum Swatch { case none, win, top3, top5, dnf, sample }
    let id = UUID()
    var swatch: Swatch = .none
    var text: String
    var accent = false
    var error = false
}

struct Legend: View {
    let items: [LegendItem]

    var body: some View {
        FlowLayout(horizontalSpacing: PP.Space.s3, verticalSpacing: PP.Space.s1) {
            ForEach(items) { item in
                HStack(spacing: 5) {
                    switch item.swatch {
                    case .none: EmptyView()
                    case .sample:
                        Text("2/5").font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.text)
                    default:
                        RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous)
                            .fill(swatchColor(item.swatch))
                            .overlay(RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous).strokeBorder(PP.borderStrong))
                            .frame(width: 12, height: 12)
                    }
                    Text(item.text)
                        .font(PP.sans(PP.TextSize.xs, weight: item.accent || item.error ? 700 : 400))
                        .foregroundStyle(item.accent ? PP.accentInk : item.error ? PP.error : PP.textMuted)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Legend")
    }

    private func swatchColor(_ s: LegendItem.Swatch) -> Color {
        switch s {
        case .win: ResultTint.win
        case .top3: ResultTint.top3
        case .top5: ResultTint.top5
        case .dnf: ResultTint.dnfBg
        default: .clear
        }
    }
}

/// `.page-title-row h2`.
struct PageTitle: View {
    let text: String
    var trailing: String?

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s4) {
            Text(text).ppTitle()
            if let trailing { Text(trailing).font(PP.sans(PP.TextSize.base)).foregroundStyle(PP.textMuted) }
            Spacer()
        }
        .padding(.top, PP.Space.s5)
    }
}

/// `.skeleton-block`: three pulsing lines.
struct SkeletonLines: View {
    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            SkeletonBlock(height: 16, widthFraction: 0.4)
            SkeletonBlock(height: 16, widthFraction: 1)
            SkeletonBlock(height: 16, widthFraction: 0.92)
        }
        .padding(.vertical, PP.Space.s4)
    }
}

/// `.hs-retry` — a dropped request costs a click, not a reload.
struct RetryButton: View {
    let action: () -> Void
    var body: some View {
        Button("Retry", action: action)
            .font(PP.sans(PP.TextSize.xs, weight: 500))
            .foregroundStyle(PP.text)
            .padding(.vertical, 2).padding(.horizontal, 8)
            .background(PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.sm, style: .continuous).strokeBorder(PP.borderStrong))
            .buttonStyle(.plain)
    }
}

extension PP {
    /// Public wrapper so other files can build theme-aware pairs.
    static func dynamicPublic(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }
}
