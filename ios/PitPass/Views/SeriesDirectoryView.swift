import SwiftUI

/// The website's series directory (`SeriesDirectoryPage` + `.dir-*` in
/// season.css): one card per series with its current season, class chips
/// (colour + code — colour never stands alone), and earlier seasons as chips.
/// Active seasons first, then alphabetical; dormant series settle to the bottom.
struct SeriesDirectoryView: View {
    @Environment(AppSession.self) private var session
    @State private var seasons = Resource<[SeasonSummary]>("/api/seasons")
    @State private var series = Resource<[SeriesInfo]>("/api/series")
    @State private var query = ""
    @State private var nudgeSnoozedUntil: Date?

    private let columns = [GridItem(.adaptive(minimum: 300, maximum: 520), spacing: PP.Space.s4, alignment: .top)]

    private var groups: [SeriesGroup]? {
        guard let seasons = seasons.value else { return nil }
        return SeriesGroup.build(seasons: seasons, series: series.value ?? [])
    }

    private var filtered: [SeriesGroup]? {
        guard let groups else { return nil }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return groups }
        return groups.filter { $0.name.lowercased().contains(q) || ($0.abbreviation?.lowercased().contains(q) ?? false) }
    }

    private var hasPendingUpdate: Bool {
        (seasons.pendingUpdate != nil || series.pendingUpdate != nil)
            && (nudgeSnoozedUntil.map { $0 < .now } ?? true)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s5) {
            header
            if let error = seasons.error, seasons.value == nil {
                ErrorPanel(message: error)
            } else if let filtered, let groups {
                if groups.isEmpty {
                    EmptyState(message: "No series yet — import a results, standings, or entry-list file on the website to get started.")
                } else if filtered.isEmpty {
                    Text("No series match “\(query.trimmingCharacters(in: .whitespaces))”.")
                        .font(PP.sans(PP.TextSize.base))
                        .foregroundStyle(PP.textMuted)
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: PP.Space.s4) {
                        ForEach(filtered) { group in
                            SeriesCard(group: group)
                        }
                    }
                }
            } else {
                LazyVGrid(columns: columns, spacing: PP.Space.s4) {
                    ForEach(0..<6, id: \.self) { _ in SeriesCardSkeleton() }
                }
                .accessibilityLabel("Loading series")
            }
        }
        .overlay(alignment: .bottom) {
            if hasPendingUpdate {
                UpdateNudge {
                    withAnimation(PP.Motion.medium) {
                        seasons.applyPendingUpdate()
                        series.applyPendingUpdate()
                    }
                } dismiss: {
                    nudgeSnoozedUntil = .now.addingTimeInterval(5 * 60)
                }
                .padding(.bottom, PP.Space.s4)
                .transition(.opacity)
            }
        }
        .task { await load() }
        .refreshable { await load() }
    }

    private var header: some View {
        HStack(alignment: .firstTextBaseline, spacing: PP.Space.s4) {
            Text("Series")
                .font(PP.sans(PP.TextSize.xxl, weight: 650))
                .tracking(PP.TextSize.xxl * -0.02)
                .foregroundStyle(PP.ink)
            Spacer()
            if let groups, groups.count > 6 {
                FilterField(text: $query, placeholder: "Filter \(groups.count) series…")
                    .frame(maxWidth: 340)
            }
        }
        .padding(.top, PP.Space.s4)
    }

    private func load() async {
        session.freshness.reset()
        async let a: Void = seasons.load(session.loader, connectivity: session.connectivity, freshness: session.freshness)
        async let b: Void = series.load(session.loader, connectivity: session.connectivity, freshness: session.freshness)
        _ = await (a, b)
    }
}

/// `.dir-filter input`: hairline-strong field, amber focus.
struct FilterField: View {
    @Binding var text: String
    let placeholder: String
    @FocusState private var focused: Bool

    var body: some View {
        HStack(spacing: PP.Space.s2) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(PP.textMuted)
            TextField(placeholder, text: $text)
                .font(PP.sans(PP.TextSize.sm))
                .foregroundStyle(PP.text)
                .focused($focused)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            if !text.isEmpty {
                Button { text = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(PP.textMuted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear filter")
            }
        }
        .padding(.vertical, 7)
        .padding(.horizontal, 12)
        .background(PP.bg, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous)
            .strokeBorder(focused ? PP.accent : PP.borderStrong))
        .animation(PP.Motion.fast, value: focused)
    }
}

// MARK: - Grouping (ported from SeriesDirectoryPage.tsx)

struct SeriesGroup: Identifiable {
    let name: String
    let seriesId: Int?
    let abbreviation: String?
    let logoVersion: Int?
    var latest: SeasonSummary
    var past: [SeasonSummary]
    /// Qualifying stages, badged separately — never the card's current season.
    var qualifiers: [SeasonSummary]

    var id: String { name }

    var logoPath: String? {
        guard let seriesId, let logoVersion else { return nil }
        return "/api/series/\(seriesId)/logo/data?v=\(logoVersion)"
    }

    /// The abbreviation when short enough to read as a badge, else initials of
    /// the significant words.
    var monogram: String {
        if let abbr = abbreviation?.trimmingCharacters(in: .whitespaces), !abbr.isEmpty, abbr.count <= 5 {
            return abbr.uppercased()
        }
        let stop: Set<String> = ["the", "of", "and", "cup", "series", "championship"]
        let initials = name.split(whereSeparator: \.isWhitespace)
            .filter { !stop.contains($0.lowercased()) }
            .compactMap(\.first)
            .prefix(3)
        let text = initials.isEmpty ? String(name.prefix(2)) : String(initials)
        return text.uppercased()
    }

    static func build(seasons: [SeasonSummary], series: [SeriesInfo]) -> [SeriesGroup] {
        var byName: [String: SeriesGroup] = [:]
        var order: [String] = []
        for s in seasons {
            if byName[s.seriesName] == nil {
                let info = series.first { $0.name == s.seriesName }
                byName[s.seriesName] = SeriesGroup(name: s.seriesName, seriesId: info?.id,
                                                   abbreviation: info?.abbreviation,
                                                   logoVersion: info?.logoVersion,
                                                   latest: s, past: [], qualifiers: [])
                order.append(s.seriesName)
                if !s.isQualifier { continue }
            }
            if s.isQualifier {
                byName[s.seriesName]!.qualifiers.append(s)
            } else if byName[s.seriesName]!.latest.isQualifier {
                // First MAIN season displaces a qualifier that arrived earlier.
                byName[s.seriesName]!.latest = s
            } else {
                byName[s.seriesName]!.past.append(s)
            }
        }
        return order.map { name in
            var g = byName[name]!
            if g.latest.isQualifier { g.qualifiers.removeAll { $0.id == g.latest.id } }
            return g
        }
        .sorted { a, b in
            a.latest.year != b.latest.year ? a.latest.year > b.latest.year
                : a.name.localizedCaseInsensitiveCompare(b.name) == .orderedAscending
        }
    }
}

// MARK: - Card

struct SeriesCard: View {
    @Environment(AppSession.self) private var session
    let group: SeriesGroup
    @State private var classes: Resource<ClassStylesResponse>?

    var body: some View {
        NavigationLink(value: group.latest) {
            VStack(alignment: .leading, spacing: 0) {
                body_
                Spacer(minLength: 0)
                foot
            }
            .frame(maxHeight: .infinity, alignment: .top)
        }
        .buttonStyle(SeriesCardStyle())
        .accessibilityLabel("\(group.name), \(group.latest.year) season")
        .task {
            guard let id = group.seriesId, classes == nil else { return }
            let resource = Resource<ClassStylesResponse>("/api/series/\(id)/class-styles")
            classes = resource
            await resource.load(session.loader)
        }
    }

    private var body_: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            HStack(alignment: .top, spacing: PP.Space.s3) {
                if let path = group.logoPath {
                    SeriesLogo(path: path, fallback: group.monogram)
                } else {
                    Monogram(text: group.monogram)
                }
                Spacer(minLength: 0)
                Text("→")
                    .font(PP.sans(PP.TextSize.lg))
                    .foregroundStyle(PP.textMuted)
                    .accessibilityHidden(true)
            }
            .frame(minHeight: 44)
            Text(group.name)
                .font(PP.sans(PP.TextSize.lg, weight: 650))
                .tracking(PP.TextSize.lg * -0.01)
                .lineSpacing(PP.TextSize.lg * 0.25 - 4)
                .foregroundStyle(PP.ink)
                .fixedSize(horizontal: false, vertical: true)
            if let styles = classes?.value?.styles, styles.count > 1 {
                ClassChips(styles: styles)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(PP.Space.s5)
    }

    private var foot: some View {
        VStack(alignment: .leading, spacing: PP.Space.s2) {
            HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                Text(String(group.latest.year))
                    .font(PP.mono(PP.TextSize.lg, weight: 700))
                    .foregroundStyle(PP.ink)
                    .fixedSize()
                if group.latest.isQualifier {
                    QualifierBadge(text: group.latest.label ?? "Qualifying")
                }
                Text("\(group.latest.roundCount) round\(group.latest.roundCount == 1 ? "" : "s") · \(group.latest.championshipCount) championship\(group.latest.championshipCount == 1 ? "" : "s")")
                    .font(PP.sans(PP.TextSize.sm))
                    .foregroundStyle(PP.textMuted)
            }
            FlowLayout(horizontalSpacing: PP.Space.s2, verticalSpacing: PP.Space.s1) {
                if !group.past.isEmpty || !group.qualifiers.isEmpty {
                    if !group.past.isEmpty {
                        Text("Earlier").font(PP.sans(PP.TextSize.xs, weight: 600)).foregroundStyle(PP.textMuted).fixedSize()
                    }
                    ForEach(group.past) { season in
                        NavigationLink(value: season) { SeasonChip(season: season) }.buttonStyle(.plain)
                    }
                    ForEach(group.qualifiers) { season in
                        NavigationLink(value: season) { SeasonChip(season: season) }.buttonStyle(.plain)
                    }
                } else {
                    Text("No earlier seasons").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                }
            }
            .frame(minHeight: 22)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, PP.Space.s3)
        .padding(.horizontal, PP.Space.s5)
        .padding(.bottom, PP.Space.s4)
        .overlay(alignment: .top) { Rectangle().fill(PP.border).frame(height: 1) }
    }
}

/// `.dir-card`: hairline card on the ground; pressed = panel + strong hairline.
struct SeriesCardStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? PP.surface : PP.bg,
                        in: RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous)
                .strokeBorder(configuration.isPressed ? PP.borderStrong : PP.border))
            .animation(PP.Motion.fast, value: configuration.isPressed)
    }
}

struct SeriesCardSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: PP.Space.s3) {
            SkeletonBlock(height: 44, widthFraction: 0.55)
            SkeletonBlock(height: 20, widthFraction: 0.8)
            SkeletonBlock(height: 16, widthFraction: 0.45)
        }
        .padding(PP.Space.s5)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: PP.Radius.lg, style: .continuous).strokeBorder(PP.border))
    }
}

/// `.dir-monogram`: raised block, inset strong hairline, bold initials.
struct Monogram: View {
    let text: String

    var body: some View {
        Text(text)
            .font(PP.sans(PP.TextSize.lg, weight: 700))
            .tracking(PP.TextSize.lg * -0.01)
            .foregroundStyle(PP.text)
            .padding(.horizontal, 10)
            .frame(minWidth: 44, minHeight: 44)
            .background(PP.surface2, in: RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.borderStrong))
            .accessibilityHidden(true)
    }
}

/// `.dir-logo`: the uploaded series mark, cache-first bytes, 44pt tall.
struct SeriesLogo: View {
    @Environment(AppSession.self) private var session
    let path: String
    let fallback: String
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: 200, maxHeight: 44, alignment: .leading)
            } else if failed {
                Monogram(text: fallback)
            } else {
                Color.clear.frame(width: 72, height: 44)
            }
        }
        .task(id: path) {
            if let data = await session.loader.bytes(path), let decoded = ImageDecoding.decode(data) {
                image = decoded
            } else {
                failed = true
            }
        }
        .accessibilityHidden(true)
    }
}

/// `.dir-class`: 9pt swatch with an inset strong hairline (so a near-black
/// class never dissolves into the dark ground) + the class code.
struct ClassChips: View {
    let styles: [ClassStyle]

    var body: some View {
        FlowLayout(horizontalSpacing: PP.Space.s3, verticalSpacing: PP.Space.s1) {
            ForEach(styles, id: \.classCode) { style in
                HStack(spacing: 5) {
                    RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous)
                        .fill(Color(cssHex: style.color) ?? PP.surface2)
                        .overlay(RoundedRectangle(cornerRadius: PP.Radius.xs, style: .continuous).strokeBorder(PP.borderStrong))
                        .frame(width: 9, height: 9)
                    Text(style.classCode)
                }
                .font(PP.sans(PP.TextSize.xs, weight: 600))
                .foregroundStyle(PP.textMuted)
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Classes: \(styles.map(\.classCode).joined(separator: ", "))")
    }
}

/// `.qualifier-badge`: muted pill so the mark reads as metadata, never identity.
struct QualifierBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(PP.sans(PP.TextSize.xs, weight: 600))
            .foregroundStyle(PP.textMuted)
            .padding(.vertical, 1)
            .padding(.horizontal, 8)
            .overlay(Capsule().strokeBorder(PP.borderStrong))
    }
}

/// `.dir-season-chip`: mono year in a strong-hairline pill; qualifiers in the
/// prose face, muted.
struct SeasonChip: View {
    let season: SeasonSummary

    var body: some View {
        Group {
            if season.isQualifier {
                Text(season.label ?? "Qualifying").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
            } else {
                Text(String(season.year)).font(PP.mono(PP.TextSize.xs)).foregroundStyle(PP.text)
            }
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.vertical, 1)
        .padding(.horizontal, 8)
        .overlay(Capsule().strokeBorder(PP.borderStrong))
        .contentShape(Capsule())
        .accessibilityLabel("\(season.seriesName) \(season.label ?? String(season.year))")
    }
}
