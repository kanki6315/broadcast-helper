import SwiftUI

/// DriverModal.tsx: photo, name with flag and rating, pronunciation, the
/// current seat, the bio facts, career stats, one result matrix per
/// championship, and the broadcast notes. Read-only: bio, photo and notes
/// are edited on the website.
struct DriverProfileView: View {
    @Environment(AppSession.self) private var session
    let driverId: Int
    @State private var profile: Resource<DriverProfile>
    @State private var stats: Resource<DriverStats>

    init(driverId: Int) {
        self.driverId = driverId
        _profile = State(initialValue: Resource<DriverProfile>("/api/drivers/\(driverId)/profile"))
        _stats = State(initialValue: Resource<DriverStats>("/api/drivers/\(driverId)/stats"))
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
                        bio(p)
                        // Stats are additive: the profile stands without them.
                        if let s = stats.value, !CareerLines.isEmpty(s) { CareerStatsView(stats: s) }
                        if p.championships.isEmpty {
                            QuietText(text: "No championship standings for this driver yet — they appear once a drivers standings file is imported.")
                        } else {
                            ForEach(p.championships) { DriverChampMatrixView(champ: $0) }
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
                ProfileSkeleton(photo: true)
            }
        }
        .navigationTitle(profile.value?.name ?? "Driver")
        .task(id: driverId) { await load() }
    }

    private func load() async {
        async let p: Void = profile.load(session.loader, connectivity: session.connectivity)
        async let s: Void = stats.load(session.loader)
        _ = await (p, s)
    }

    // MARK: pieces

    private func header(_ p: DriverProfile) -> some View {
        HStack(alignment: .top, spacing: PP.Space.s4) {
            if let path = p.photoPath {
                CachedImage(path: path, contentMode: .fill)
                    .frame(width: 84, height: 84)
                    .clipShape(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.borderStrong))
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                    Text(p.name).ppHeadline()
                    if let flag = Flags.emoji(p.country) {
                        Text(flag).font(.system(size: PP.TextSize.base)).accessibilityLabel(p.country ?? "")
                    }
                    if let rating = p.rating { RatingMark(rating: rating) }
                }
                if let pron = p.pronunciation, !pron.isEmpty {
                    Text("“\(pron)”").font(PP.sans(PP.TextSize.sm)).italic().foregroundStyle(PP.textMuted)
                        .padding(.top, 2)
                }
                if let car = p.carNumber, !car.isEmpty { seat(p, car: car) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Driver: \(p.name)")
    }

    /// `.dm-seat`: "#31 Team · Class · Series Year", the team a link.
    private func seat(_ p: DriverProfile, car: String) -> some View {
        var tail: [String] = []
        if let c = p.className, !c.isEmpty { tail.append(c) }
        if let s = p.seriesName, !s.isEmpty { tail.append(p.year.map { "\(s) \(String($0))" } ?? s) }
        return HStack(alignment: .firstTextBaseline, spacing: 4) {
            Text("#\(car)").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.text)
            if Bio.isPrivateer(p.teamName) {
                Text("Privateer").font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            } else if let team = p.teamName, !team.isEmpty {
                NameLink(text: team, target: InfoTarget.team(named: team), font: PP.sans(PP.TextSize.sm), color: PP.textMuted)
            }
            if !tail.isEmpty {
                Text("· " + tail.joined(separator: " · ")).font(PP.sans(PP.TextSize.sm)).foregroundStyle(PP.textMuted)
            }
        }
        .lineLimit(1)
        .padding(.top, 4)
    }

    private func bio(_ p: DriverProfile) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            let facts = Bio.facts(p)
            if facts.isEmpty { QuietText(text: "No bio yet.") } else { FactList(facts: facts) }
            Rectangle().fill(PP.border).frame(height: 1).padding(.top, PP.Space.s4)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Bio")
    }
}
