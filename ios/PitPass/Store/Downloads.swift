import Foundation
import Observation
import UIKit

/// "Download this event" / "Download this season": what the service worker
/// never could. The SW cached what had been *visited*; the app knows which
/// documents each screen reads and fetches the whole manifest up front, so a
/// sheet, its PDFs, the recap behind its FAB, or every season page still works
/// offline without ever having been opened on this iPad.
///
/// Nothing here has its own cache: every step goes through `DataLoader`, so a
/// download is a conditional GET per document (304s are free) plus the
/// versioned binaries the store doesn't have yet. Running it twice is cheap.
enum DownloadTarget: Hashable, Sendable {
    case event(Int)
    case season(Int)

    /// The store key (`download_record.key`).
    var key: String {
        switch self {
        case let .event(id): "event:\(id)"
        case let .season(id): "season:\(id)"
        }
    }
}

/// One thing to fetch: a JSON document (conditional GET, revalidated every
/// time) or an immutable, version-stamped binary (only fetched when absent).
enum Fetch: Hashable, Sendable {
    case document(String)
    case binary(String)

    var path: String {
        switch self {
        case let .document(p), let .binary(p): p
        }
    }
}

/// The manifests, derived from the payloads that name their children — the
/// same paths the views build, so a download and a visit store the same rows.
enum DownloadPlan {
    static func sheetPath(eventId: Int) -> String { "/api/events/\(eventId)/sheet" }
    static func resultsPath(eventId: Int) -> String { "/api/events/\(eventId)/results" }
    static func hubPath(seasonId: Int) -> String { "/api/seasons/\(seasonId)" }
    static func carImagesPath(seasonId: Int) -> String { "/api/car-images?seasonId=\(seasonId)" }

    /// Everything the sheet screen can open from the sheet payload alone:
    /// pit-lane assignments, the two PDFs, car photos and manufacturer marks.
    static func sheetAssets(_ sheet: Sheet) -> [Fetch] {
        var out: [Fetch] = [.document("/api/events/\(sheet.eventId)/pit-assignments")]
        if let p = sheet.teamSheetsPath { out.append(.binary(p)) }
        if let p = sheet.storylinesPath { out.append(.binary(p)) }
        for cls in sheet.classes {
            for entry in cls.entries {
                if let p = entry.imagePath { out.append(.binary(p)) }
                if let p = entry.manufacturerLogoPath { out.append(.binary(p)) }
            }
        }
        return unique(out)
    }

    /// What the Recap overlay reads on top of the sheet: the season model's
    /// hub extras and every championship grid it can switch to.
    static func recapDocuments(_ hub: SeasonHub) -> [Fetch] {
        unique([.document("/api/seasons"), .document("/api/series/\(hub.seriesId)/class-styles")] + recaps(hub))
    }

    /// Every season page: the strip's reference + lineups, all four stats
    /// tables, the photo index, each round's results, each championship's recap.
    static func seasonDocuments(_ hub: SeasonHub) -> [Fetch] {
        var out: [Fetch] = [
            .document("/api/seasons"),
            .document("/api/series/\(hub.seriesId)/class-styles"),
            .document("/api/seasons/\(hub.id)/reference"),
            .document("/api/seasons/\(hub.id)/lineups"),
            .document("/api/seasons/\(hub.id)/stats"),
            .document("/api/seasons/\(hub.id)/team-stats"),
            .document("/api/series/\(hub.seriesId)/stats"),
            .document("/api/series/\(hub.seriesId)/team-stats"),
            .document(carImagesPath(seasonId: hub.id)),
        ]
        out += hub.events.filter { $0.sessionCount > 0 }.map { .document(resultsPath(eventId: $0.id)) }
        out += recaps(hub)
        return unique(out)
    }

    /// Race control for the sessions that have it (ResultsView loads these on demand).
    static func flagDocuments(_ results: EventResults) -> [Fetch] {
        results.sessions.filter(\.hasFlags).map { .document("/api/sessions/\($0.sessionId)/flags") }
    }

    /// The sheet-size variant PhotosView shows.
    static func photoAssets(_ overview: CarImagesOverview) -> [Fetch] {
        overview.images.map { .binary("/api/car-images/\($0.id)/data?variant=sheet&v=\($0.uploadedAt)") }
    }

    private static func recaps(_ hub: SeasonHub) -> [Fetch] {
        hub.championships.filter { $0.rowCount > 0 }.map { .document("/api/championships/\($0.id)/recap") }
    }

    private static func unique(_ fetches: [Fetch]) -> [Fetch] {
        var seen = Set<Fetch>()
        return fetches.filter { seen.insert($0).inserted }
    }
}

/// Progress as the job reports it: steps done over steps planned so far
/// (the total grows as payloads reveal their children), and which part of
/// the bundle is in flight.
struct DownloadProgress: Sendable, Equatable {
    var done = 0
    var total = 0
    var phase = ""
}

struct DownloadOutcome: Sendable, Equatable {
    let title: String
    /// Every path the bundle stored (for the size tally).
    let paths: [String]
    let missing: Int
}

enum DownloadError: Error, LocalizedError, Equatable {
    /// The document the whole bundle hangs off couldn't be fetched or decoded.
    case rootUnavailable(String)
    case network(String)

    var errorDescription: String? {
        switch self {
        case let .rootUnavailable(what): "Couldn’t load the \(what)"
        case let .network(text): text
        }
    }
}

/// One download in flight. Fetches run four at a time; a document the server
/// refuses (404, an undecodable body) counts as missing and the rest of the
/// bundle carries on, but a dropped connection or a revoked sign-in ends it —
/// a half-fetched bundle is only worth finishing when the next request can
/// succeed.
actor PrefetchJob {
    private let loader: DataLoader
    private let report: @Sendable (DownloadProgress) -> Void
    private var progress = DownloadProgress()
    private var missing = 0
    private var stored: [String] = []
    private static let width = 4

    init(loader: DataLoader, report: @escaping @Sendable (DownloadProgress) -> Void) {
        self.loader = loader
        self.report = report
    }

    func run(_ target: DownloadTarget) async throws -> DownloadOutcome {
        switch target {
        case let .event(id): try await event(id)
        case let .season(id): try await season(id)
        }
    }

    // MARK: bundles

    private func event(_ eventId: Int) async throws -> DownloadOutcome {
        plan(1, phase: "sheet")
        guard let sheet = try await document(DownloadPlan.sheetPath(eventId: eventId), as: Sheet.self) else {
            throw DownloadError.rootUnavailable("event sheet")
        }
        let assets = DownloadPlan.sheetAssets(sheet)
        plan(assets.count, phase: "photos and PDFs")
        try await fetchAll(assets)

        plan(1, phase: "results")
        if let results = try await document(DownloadPlan.resultsPath(eventId: eventId), as: EventResults.self) {
            let flags = DownloadPlan.flagDocuments(results)
            plan(flags.count, phase: "race control")
            try await fetchAll(flags)
        }

        if let seasonId = sheet.seasonId {
            plan(1, phase: "standings")
            if let hub = try await document(DownloadPlan.hubPath(seasonId: seasonId), as: SeasonHub.self) {
                let docs = DownloadPlan.recapDocuments(hub)
                plan(docs.count, phase: "standings")
                try await fetchAll(docs)
            }
        }
        return DownloadOutcome(title: "\(sheet.eventName) · \(sheet.seriesName) \(sheet.year)", paths: stored, missing: missing)
    }

    private func season(_ seasonId: Int) async throws -> DownloadOutcome {
        plan(1, phase: "season")
        guard let hub = try await document(DownloadPlan.hubPath(seasonId: seasonId), as: SeasonHub.self) else {
            throw DownloadError.rootUnavailable("season")
        }
        let docs = DownloadPlan.seasonDocuments(hub)
        plan(docs.count, phase: "season pages")
        try await fetchAll(docs)

        // Children are named by documents that just landed in the store —
        // read them back from disk rather than fetching twice.
        var flags: [Fetch] = []
        for event in hub.events where event.sessionCount > 0 {
            if let results: Loaded<EventResults> = await loader.cached(DownloadPlan.resultsPath(eventId: event.id)) {
                flags += DownloadPlan.flagDocuments(results.value)
            }
        }
        plan(flags.count, phase: "race control")
        try await fetchAll(flags)

        if let overview: Loaded<CarImagesOverview> = await loader.cached(DownloadPlan.carImagesPath(seasonId: hub.id)) {
            let photos = DownloadPlan.photoAssets(overview.value)
            plan(photos.count, phase: "photos")
            try await fetchAll(photos)
        }
        let stage = hub.isQualifier ? " · \(hub.label ?? "Qualifying")" : ""
        return DownloadOutcome(title: "\(hub.seriesName) \(hub.year)\(stage)", paths: stored, missing: missing)
    }

    // MARK: steps

    private func plan(_ count: Int, phase: String) {
        progress.total += count
        progress.phase = phase
        report(progress)
    }

    private func finished(_ path: String, ok: Bool) {
        progress.done += 1
        if ok { stored.append(path) } else { missing += 1 }
        report(progress)
    }

    /// A document the bundle branches on: fetched, stored and decoded. Nil
    /// when the server refused it or the body doesn't decode (counted missing).
    private func document<T: Decodable & Sendable>(_ path: String, as type: T.Type) async throws -> T? {
        try Task.checkCancellation()
        do {
            let value: T = switch try await loader.refresh(path, as: type) {
            case let .unchanged(loaded), let .updated(loaded): loaded.value
            }
            finished(path, ok: true)
            return value
        } catch {
            if let fatal = PrefetchJob.fatal(error) { throw fatal }
            finished(path, ok: false)
            return nil
        }
    }

    private func fetchAll(_ fetches: [Fetch]) async throws {
        try await withThrowingTaskGroup(of: (String, Bool).self) { group in
            var inFlight = 0
            for fetch in fetches {
                if inFlight >= PrefetchJob.width, let next = try await group.next() {
                    inFlight -= 1
                    finished(next.0, ok: next.1)
                }
                try Task.checkCancellation()
                let loader = loader
                group.addTask {
                    do {
                        switch fetch {
                        case let .document(p): try await loader.prefetchDocument(p)
                        case let .binary(p): try await loader.ensureBytes(p)
                        }
                        return (fetch.path, true)
                    } catch {
                        if let fatal = PrefetchJob.fatal(error) { throw fatal }
                        return (fetch.path, false)
                    }
                }
                inFlight += 1
            }
            for try await (path, ok) in group { finished(path, ok: ok) }
        }
    }

    /// Errors that end the download; anything else is one missing document.
    private static func fatal(_ error: any Error) -> DownloadError? {
        switch error {
        case let e as APIError:
            switch e {
            case let .transport(text): .network(text)
            case .unauthorized, .forbidden: .network(e.localizedDescription)
            case .http, .decoding: nil
            }
        case is CancellationError: .network("Cancelled")
        default: nil
        }
    }
}

/// The downloads a person started, observed by the toolbar buttons, the
/// schedule's row marks and Settings. One job per target; the app-wide
/// instance lives on `AppSession` so a download outlives the screen that
/// began it.
@MainActor
@Observable
final class DownloadManager {
    enum Activity: Equatable {
        case running(DownloadProgress)
        case failed(String)
    }

    private(set) var activity: [String: Activity] = [:]
    private(set) var records: [String: OfflineStore.DownloadRecord] = [:]
    private var tasks: [String: Task<Void, Never>] = [:]

    var recordsNewestFirst: [OfflineStore.DownloadRecord] {
        records.values.sorted { $0.completedAt > $1.completedAt }
    }

    func activity(for target: DownloadTarget) -> Activity? { activity[target.key] }
    func record(for target: DownloadTarget) -> OfflineStore.DownloadRecord? { records[target.key] }
    func isRunning(_ target: DownloadTarget) -> Bool {
        if case .running = activity[target.key] { return true }
        return false
    }

    /// What earlier sessions completed, from the store.
    func load(from store: OfflineStore) async {
        records = Dictionary(uniqueKeysWithValues: await store.downloads().map { ($0.key, $0) })
    }

    /// The store was wiped (sign-out, server change, Clear offline data).
    func forget() {
        for task in tasks.values { task.cancel() }
        tasks = [:]
        activity = [:]
        records = [:]
    }

    func start(_ target: DownloadTarget, loader: DataLoader, store: OfflineStore) {
        guard !isRunning(target) else { return }
        let key = target.key
        activity[key] = .running(DownloadProgress())
        // Keep going for a while if the person switches to Safari mid-download.
        let background = UIApplication.shared.beginBackgroundTask(withName: "download \(key)")
        let job = PrefetchJob(loader: loader) { progress in
            Task { @MainActor [weak self] in
                guard let self, self.isRunning(target) else { return }
                self.activity[key] = .running(progress)
            }
        }
        tasks[key] = Task { [weak self] in
            defer { UIApplication.shared.endBackgroundTask(background) }
            do {
                let outcome = try await job.run(target)
                let record = OfflineStore.DownloadRecord(
                    key: key, title: outcome.title, completedAt: .now, documents: outcome.paths.count,
                    bytes: await store.size(of: outcome.paths), missing: outcome.missing)
                await store.saveDownload(record)
                guard let self, !Task.isCancelled else { return }
                self.records[key] = record
                self.activity[key] = nil
            } catch {
                guard let self, !Task.isCancelled else { return }
                self.activity[key] = .failed(error.localizedDescription)
            }
            self?.tasks[key] = nil
        }
    }

    /// Stops the job; what it already stored stays (it is all valid data).
    func cancel(_ target: DownloadTarget) {
        let key = target.key
        tasks[key]?.cancel()
        tasks[key] = nil
        activity[key] = nil
    }

    /// Drop a failure notice so the button offers Download again.
    func dismissFailure(_ target: DownloadTarget) {
        if case .failed = activity[target.key] { activity[target.key] = nil }
    }
}
