import Foundation
import CryptoKit
import Observation

struct ConversationPerson: Codable, Hashable, Identifiable, Sendable {
    let entryId: Int
    let name: String
    let car: String
    let team: String
    var id: String { "\(entryId):\(name.lowercased())" }

    static func roster(_ sheet: Sheet) -> [Self] {
        sheet.classes.flatMap(\.entries).flatMap { entry in
            entry.drivers.filter { !$0.isTbd }.map {
                Self(entryId: entry.entryId, name: $0.name, car: entry.carNumber, team: entry.teamName)
            }
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
}

struct Conversation: Codable, Identifiable, Equatable, Sendable {
    enum Kind: String, Codable, CaseIterable { case spoken, planned }
    struct Passage: Codable, Identifiable, Equatable, Sendable {
        var id: UUID = UUID()
        var seconds: Double
        var text: String
    }
    var id = UUID()
    var person: ConversationPerson?
    var kind: Kind = .spoken
    var date = Date()
    var session = "General weekend"
    var context = "Before going live"
    var topic = ""
    var takeaway = ""
    var ink: Data?
    var audioFile: String?
    var duration: Double = 0
    var bookmarks: [Double] = []
    var transcript: [Passage] = []
    /// Persist the unfinished state so a killed recording is recoverable on launch.
    var recording = false
    var transcriptionError: String?
}

/// User-authored reporting is not a cache. Atomic files live outside OfflineStore
/// and survive cache clearing. The namespace isolates both account and server.
@MainActor @Observable
final class ConversationBook {
    let eventId: Int
    private(set) var records: [Conversation] = []
    private(set) var directory: URL?
    private(set) var writable = false
    var error: String?
    var workingSession = "General weekend"
    var workingContext = "Before going live"
    private var namespace: String?

    init(eventId: Int) { self.eventId = eventId }

    static func namespace(server: String, owner: String) -> String {
        SHA256.hash(data: Data("\(server)|\(owner.lowercased())".utf8))
            .map { String(format: "%02x", $0) }.joined()
    }

    func open(server: String, owner: String, root: URL? = nil) {
        let key = Self.namespace(server: server, owner: owner)
        guard namespace != key || !writable else { return }
        namespace = key
        records = []
        writable = false
        do {
            let base = try root ?? FileManager.default.url(for: .applicationSupportDirectory,
                in: .userDomainMask, appropriateFor: nil, create: true).appending(path: "PitPass/Conversations")
            let dir = base.appending(path: "\(key)/\(eventId)", directoryHint: .isDirectory)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            directory = dir
            let file = dir.appending(path: "conversations.json")
            if FileManager.default.fileExists(atPath: file.path) {
                records = try JSONDecoder().decode([Conversation].self, from: Data(contentsOf: file))
            }
            writable = true
            error = nil
            // Audio was written independently; offer retry after interruption/crash.
            for index in records.indices where records[index].recording {
                records[index].recording = false
                records[index].transcriptionError = "Recording was interrupted. Play the saved audio or retry transcription."
            }
            if let latest = records.max(by: { $0.date < $1.date }) {
                workingSession = latest.session
                workingContext = latest.context
            }
            try persist(records)
        } catch {
            self.error = "Could not open conversation storage: \(error.localizedDescription)"
            writable = false // Never overwrite an unreadable journal with an empty list.
        }
    }

    var people: [ConversationPerson] {
        Dictionary(grouping: records.compactMap(\.person), by: \.id).values.compactMap(\.first).sorted {
            let a = entries(for: $0).first?.date ?? .distantPast
            let b = entries(for: $1).first?.date ?? .distantPast
            return a == b ? $0.name < $1.name : a > b
        }
    }

    func entries(for person: ConversationPerson) -> [Conversation] {
        records.filter { $0.person?.id == person.id }.sorted { $0.date > $1.date }
    }

    func record(_ id: UUID) -> Conversation? { records.first { $0.id == id } }

    @discardableResult func save(_ record: Conversation) -> Bool {
        var next = records
        if let index = next.firstIndex(where: { $0.id == record.id }) { next[index] = record }
        else { next.append(record) }
        do {
            try persist(next)
            records = next
            error = nil
            return true
        } catch {
            self.error = "Conversation not saved: \(error.localizedDescription)"
            return false
        }
    }

    @discardableResult func remove(_ id: UUID) -> Bool {
        let old = record(id)
        let next = records.filter { $0.id != id }
        do {
            try persist(next)
            records = next
            if let file = old?.audioFile, let url = audioURL(file) { try? FileManager.default.removeItem(at: url) }
            return true
        } catch { self.error = "Could not delete conversation: \(error.localizedDescription)"; return false }
    }

    func audioURL(_ filename: String) -> URL? {
        guard filename == URL(fileURLWithPath: filename).lastPathComponent else { return nil }
        return directory?.appending(path: filename)
    }

    private func persist(_ values: [Conversation]) throws {
        guard writable, let directory else { throw CocoaError(.fileWriteNoPermission) }
        let data = try JSONEncoder().encode(values)
        try data.write(to: directory.appending(path: "conversations.json"), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
