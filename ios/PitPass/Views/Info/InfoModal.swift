import SwiftUI

/// What a name link opens — InfoModalProvider's `Open` union plus the
/// by-name driver case the website resolves through `/api/drivers/search`.
enum InfoTarget: Hashable, Identifiable {
    case driver(id: Int)
    case driverName(String)
    case team(name: String)
    case teamId(Int)

    var id: String {
        switch self {
        case let .driver(id): "driver:\(id)"
        case let .driverName(name): "driverName:\(name)"
        case let .team(name): "team:\(name)"
        case let .teamId(id): "teamId:\(id)"
        }
    }

    /// A crew member's link, or nil for the seats that never resolve (TBD).
    static func driver(named name: String) -> InfoTarget? {
        DriverLookup.isLookupable(name) ? .driverName(name.trimmingCharacters(in: .whitespaces)) : nil
    }

    /// A team's link, or nil for a blank name or the Privateer placeholder.
    static func team(named name: String?) -> InfoTarget? {
        guard let name = name?.trimmingCharacters(in: .whitespaces), !name.isEmpty, !Bio.isPrivateer(name) else { return nil }
        return .team(name: name)
    }
}

/// Where a link's target goes: the host presents it as a sheet; inside the
/// sheet a second presenter pushes it, so driver → team → driver reads as
/// drill-through navigation (the website replaces one modal with the next).
@MainActor
@Observable
final class InfoModalPresenter {
    var root: InfoTarget?
    var path: [InfoTarget] = []
    /// Closes the whole sheet. Inside a pushed level the environment's
    /// `dismiss` only pops, so the sheet hands its own down.
    var close: () -> Void = {}
    private let pushes: Bool

    init(pushing: Bool = false) {
        pushes = pushing
    }

    func open(_ target: InfoTarget) {
        if pushes { path.append(target) } else { root = target }
    }
}

extension EnvironmentValues {
    /// Nil where no host is installed: names then render as plain text
    /// rather than dead buttons.
    @Entry var infoModals: InfoModalPresenter?
}

/// Installs the driver/team profile sheet for everything below. Attach once
/// per presentation context (the home stack, the recap sheet).
private struct InfoModalHost: ViewModifier {
    @State private var presenter = InfoModalPresenter()

    func body(content: Content) -> some View {
        @Bindable var presenter = presenter
        content
            .environment(\.infoModals, presenter)
            .sheet(item: $presenter.root) { target in
                InfoModalSheet(root: target)
            }
    }
}

extension View {
    func infoModalHost() -> some View { modifier(InfoModalHost()) }
}

/// The modal: a navigation stack rooted at the tapped name.
struct InfoModalSheet: View {
    @Environment(\.dismiss) private var dismiss
    let root: InfoTarget
    @State private var nav = InfoModalPresenter(pushing: true)

    var body: some View {
        @Bindable var nav = nav
        NavigationStack(path: $nav.path) {
            InfoTargetView(target: root)
                .navigationDestination(for: InfoTarget.self) { InfoTargetView(target: $0) }
        }
        .presentationBackground(PP.bg)
        .presentationSizing(.page)
        .tint(PP.accentInk)
        .environment(\.infoModals, nav)
        .onAppear { nav.close = { dismiss() } }
    }
}

/// One screen of the modal; every level carries its own Done.
private struct InfoTargetView: View {
    @Environment(\.infoModals) private var modals
    let target: InfoTarget

    var body: some View {
        Group {
            switch target {
            case let .driver(id): DriverProfileView(driverId: id)
            case let .driverName(name): DriverLookupView(name: name)
            case let .team(name): TeamProfileView(query: .name(name))
            case let .teamId(id): TeamProfileView(query: .id(id))
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(PP.bg, for: .navigationBar)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { modals?.close() } } }
    }
}

/// `openDriverByName`: the exact match among the top search hits, else the
/// first. Network first so a rename lands; a stored answer serves offline.
private struct DriverLookupView: View {
    @Environment(AppSession.self) private var session
    let name: String
    @State private var driverId: Int?
    @State private var missing = false
    @State private var error: String?

    var body: some View {
        Group {
            if let driverId {
                DriverProfileView(driverId: driverId)
            } else if missing {
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    Text(name).ppHeadline()
                    EmptyState(message: "No driver profile for this name yet — profiles appear once an entry list or results file names the driver.")
                }
                .padding(PP.Space.s5)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(PP.bg)
                .navigationTitle(name)
            } else if let error {
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    ErrorPanel(message: error)
                    RetryButton { Task { await lookup() } }
                }
                .padding(PP.Space.s5)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(PP.bg)
                .navigationTitle(name)
            } else {
                ProfileSkeleton(photo: true)
                    .navigationTitle(name)
            }
        }
        .task(id: name) { await lookup() }
    }

    private func lookup() async {
        error = nil
        missing = false
        do {
            let hits: Loaded<[DriverSearchHit]> = try await session.loader.networkFirst(DriverLookup.searchPath(name))
            if let hit = DriverLookup.pick(hits.value, wanted: name) {
                driverId = hit.id
            } else {
                missing = true
            }
        } catch {
            self.error = error.localizedDescription
        }
    }
}

/// `.drv-link`: a name that opens its profile. Inherits the cell's type so
/// tables stay tables; plain text where nothing would resolve or no host is
/// installed.
struct NameLink: View {
    @Environment(\.infoModals) private var modals
    let text: String
    let target: InfoTarget?
    var font: Font = PP.sans(PP.TextSize.sm)
    var color: Color = PP.text
    var lineLimit: Int? = 1

    var body: some View {
        if let target, let modals {
            Button { modals.open(target) } label: {
                label.contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens the profile")
        } else {
            label
        }
    }

    private var label: some View {
        Text(text).font(font).foregroundStyle(color).lineLimit(lineLimit).truncationMode(.tail)
    }
}

extension GridCell {
    /// `GridCell.name` with the primary label (and optional team sub-line
    /// entries) opening profiles.
    static func nameLink(_ text: String, target: InfoTarget?, sub: [(text: String, target: InfoTarget?)] = []) -> AnyView {
        AnyView(VStack(alignment: .leading, spacing: 1) {
            NameLink(text: text, target: target, font: PP.sans(PP.TextSize.sm, weight: 500), color: PP.ink)
            if !sub.isEmpty {
                HStack(spacing: 0) {
                    ForEach(Array(sub.enumerated()), id: \.offset) { i, s in
                        if i > 0 { Text(" · ").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted) }
                        NameLink(text: s.text, target: s.target, font: PP.sans(PP.TextSize.xs), color: PP.textMuted)
                    }
                }
                .lineLimit(1)
            }
        })
    }
}
