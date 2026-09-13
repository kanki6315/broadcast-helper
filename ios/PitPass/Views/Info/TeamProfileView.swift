import SwiftUI

/// TeamModal.tsx: the team's name and seasons, lineage links, the roster of
/// current cars and crews, career stats, one matrix per teams championship,
/// and the broadcast notes. Notes are edited on the website.
struct TeamProfileView: View {
    enum Query: Hashable {
        case id(Int)
        case name(String)

        var path: String {
            switch self {
            case let .id(id): "/api/teams/profile?id=\(id)"
            case let .name(name):
                "/api/teams/profile?name=\(name.addingPercentEncoding(withAllowedCharacters: .urlQueryValueAllowed) ?? name)"
            }
        }
    }

    @Environment(AppSession.self) private var session
    let query: Query
    @State private var profile: Resource<TeamProfile>
    @State private var stats: Resource<TeamStats>?

    init(query: Query) {
        self.query = query
        _profile = State(initialValue: Resource<TeamProfile>(query.path))
    }

    var body: some View {
        Group {
            if let p = profile.value {
                ScrollView {
                    VStack(alignment: .leading, spacing: PP.Space.s5) {
                        if profile.pendingUpdate != nil {
                            UpdateNudge(refresh: { profile.applyPendingUpdate() }, dismiss: {})
                        }
                        header(p)
                        if p.roster.isEmpty {
                            QuietText(text: "No entries for this team yet.")
                        } else {
                            ForEach(p.roster) { RosterBlock(season: $0) }
                        }
                        if let s = stats?.value, !CareerLines.isEmpty(s) { CareerStatsView(stats: s) }
                        if p.championships.isEmpty {
                            QuietText(text: "No teams-championship standings yet — they appear once a standings file is imported.")
                        } else {
                            ForEach(p.championships) { TeamChampMatrixView(champ: $0) }
                        }
                        NotesBlock(notes: p.notes)
                        ProfileFooter(isStale: profile.isStale, fetchedAt: profile.fetchedAt)
                    }
                    .padding(PP.Space.s5)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .background(PP.bg)
                .refreshable { await load() }
            } else if let error = profile.error {
                VStack(alignment: .leading, spacing: PP.Space.s3) {
                    ErrorPanel(message: error)
                    RetryButton { Task { await load() } }
                }
                .padding(PP.Space.s5)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .background(PP.bg)
            } else {
                ProfileSkeleton()
            }
        }
        .navigationTitle(profile.value?.name ?? "Team")
        .task(id: query) { await load() }
    }

    private func load() async {
        await profile.load(session.loader, connectivity: session.connectivity)
        // Stats need the entity id, which only the profile knows; a legacy
        // spelling without one simply has no stats.
        guard let teamId = profile.value?.teamId else { return }
        let path = "/api/teams/\(teamId)/stats"
        if stats?.path != path { stats = Resource<TeamStats>(path) }
        await stats?.load(session.loader)
    }

    private func header(_ p: TeamProfile) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(p.name).ppHeadline()
            if !p.roster.isEmpty {
                Text(p.roster.map { "\($0.seriesName) \(String($0.year))" }.joined(separator: " · "))
                    .font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted).lineLimit(1)
            }
            if let pred = p.lineage?.predecessor {
                lineage("Continued from", pred)
            }
            ForEach(p.lineage?.successors ?? []) { s in
                lineage("Entry transferred to", s)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Team: \(p.name)")
    }

    private func lineage(_ prefix: String, _ team: TeamRef) -> some View {
        HStack(spacing: 4) {
            Text(prefix).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            NameLink(text: team.name, target: .teamId(team.id), font: PP.sans(PP.TextSize.sm), color: PP.textMuted)
        }
        .lineLimit(1)
    }
}

/// `.dm-roster`: one season's cars — livery, number, class, crew, vehicle.
private struct RosterBlock: View {
    let season: TeamRosterSeason

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text("\(season.seriesName) \(String(season.year))").font(PP.sans(PP.TextSize.sm, weight: 600)).foregroundStyle(PP.ink)
                Text("· as of \(season.eventName)").font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
            }
            .lineLimit(1)
            .padding(.bottom, PP.Space.s2)
            ForEach(season.cars) { car in
                carRow(car)
                    .overlay(alignment: .top) { Rectangle().fill(PP.border).frame(height: 1) }
            }
            Rectangle().fill(PP.border).frame(height: 1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(season.seriesName) \(String(season.year)) cars")
    }

    private func carRow(_ car: TeamRosterCar) -> some View {
        HStack(alignment: .center, spacing: PP.Space.s3) {
            if let path = car.liveryPath {
                CachedImage(path: path, contentMode: .fit).frame(width: 64, height: 30).accessibilityHidden(true)
            }
            Text("#\(car.carNumber)").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.text)
                .frame(minWidth: 40, alignment: .trailing)
            ClassTag(name: car.className, color: car.classColor)
            crew(car.drivers)
                .frame(maxWidth: .infinity, alignment: .leading)
            if let vehicle = car.vehicle, !vehicle.isEmpty {
                Text(vehicle).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted)
                    .lineLimit(1).truncationMode(.tail).frame(maxWidth: 180, alignment: .trailing)
            }
        }
        .padding(.vertical, PP.Space.s2)
    }

    @ViewBuilder private func crew(_ drivers: [TeamRosterDriver]) -> some View {
        if drivers.isEmpty {
            Text("No drivers announced").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
        } else {
            FlowLayout(horizontalSpacing: 0, verticalSpacing: 2) {
                ForEach(Array(drivers.enumerated()), id: \.offset) { i, d in
                    HStack(spacing: 0) {
                        if i > 0 { Text(", ").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text) }
                        if d.isTbd {
                            Text("TBD").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.text)
                        } else {
                            NameLink(text: d.name, target: d.driverId.map { .driver(id: $0) } ?? InfoTarget.driver(named: d.name))
                        }
                        if let r = d.rating {
                            Text(" (\(r))").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
                        }
                    }
                }
            }
        }
    }
}
