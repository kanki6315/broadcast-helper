import SwiftUI

/// Series directory: aligned logo rows with class chips (colour + code —
/// colour never stands alone) and always-visible season pills.
/// Active seasons first, then alphabetical; dormant series settle to the bottom.
struct SeriesDirectoryView: View {
    @Environment(AppSession.self) private var session
    @Environment(WorkspaceState.self) private var workspace
    let select: (Int) -> Void
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
            filter
                .frame(maxWidth: 340)
                .frame(maxWidth: .infinity, alignment: .trailing)
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
                    LazyVStack(spacing: 0) {
                        ForEach(filtered) { group in
                            SeriesRow(group: group, select: select)
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

    @ViewBuilder private var filter: some View {
        if let groups, !groups.isEmpty {
            FilterField(text: $query, placeholder: "Filter \(groups.count) series…")
        }
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
    var logoUrl: String? = nil
    var latest: SeasonSummary
    var past: [SeasonSummary]
    /// Qualifying stages, badged separately — never the card's current season.
    var qualifiers: [SeasonSummary]

    var id: String { name }

    var logoPath: String? {
        if let logoUrl { return logoUrl }
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
                                                   logoUrl: info?.logoUrl,
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

struct SeriesRow: View {
    @Environment(AppSession.self) private var session
    @Environment(WorkspaceState.self) private var workspace
    let group: SeriesGroup
    let select: (Int) -> Void
    @State private var classes: Resource<ClassStylesResponse>?

    private var available: [SeasonSummary] { [group.latest] + group.past + group.qualifiers }
    private var current: SeasonSummary {
        available.first { $0.id == workspace.snapshot.seriesSeasons[group.name] } ?? group.latest
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: PP.Space.s4) {
                Button { open(current) } label: {
                    Group {
                        if let path = group.logoPath {
                            SeriesLogo(path: path, fallback: group.monogram)
                        } else {
                            Monogram(text: group.monogram)
                        }
                    }
                    .frame(width: 76, height: 64, alignment: .center)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open \(group.name), \(current.year)")

                VStack(alignment: .leading, spacing: PP.Space.s1) {
                    Button { open(current) } label: {
                        HStack(spacing: PP.Space.s3) {
                            VStack(alignment: .leading, spacing: PP.Space.s1) {
                                Text(group.name).font(.headline).foregroundStyle(PP.ink)
                                if let styles = classes?.value?.styles, !styles.isEmpty {
                                    ClassChips(styles: styles)
                                }
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "arrow.up.right").foregroundStyle(PP.textMuted)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Open \(group.name), \(current.year)")

                    FlowLayout(horizontalSpacing: PP.Space.s2, verticalSpacing: PP.Space.s1) {
                        ForEach(available) { season in
                            Button { open(season) } label: {
                                SeasonChip(season: season)
                                    .frame(minWidth: 44, minHeight: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                        if let page = workspace.value("season.\(current.id).tab"),
                           let tab = SeasonView.Page(rawValue: page) {
                            Button { open(current) } label: {
                                Text("Resume \(tab.label.lowercased())")
                                    .font(.subheadline)
                                    .foregroundStyle(PP.accentInk)
                                    .frame(minHeight: 44)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.vertical, PP.Space.s3)
            Divider()
        }
        .task {
            guard let id = group.seriesId, classes == nil else { return }
            let resource = Resource<ClassStylesResponse>("/api/series/\(id)/class-styles")
            classes = resource
            await resource.load(session.loader)
        }
    }
    private func open(_ season: SeasonSummary) {
        workspace.select(season.id, series: group.name)
        select(season.id)
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
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .padding(.horizontal, 6)
            .frame(width: 76, height: 44)
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
                    .frame(width: 76, height: 44, alignment: .center)
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
