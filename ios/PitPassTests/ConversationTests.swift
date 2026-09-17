import Foundation
import Testing
@testable import PitPass

@MainActor struct ConversationTests {
    private let person = ConversationPerson(entryId: 12, name: "Test Driver", car: "012", team: "Test Team")

    private func root() -> URL { FileManager.default.temporaryDirectory.appending(path: "conversations-\(UUID())") }

    @Test func journalRoundTripsAndListsOnlyAuthoredContacts() throws {
        let dir = root()
        defer { try? FileManager.default.removeItem(at: dir) }
        let book = ConversationBook(eventId: 5)
        book.open(server: "https://one.test", owner: "a@test", root: dir)
        #expect(book.people.isEmpty)
        var wanted = Conversation(person: person, kind: .planned)
        wanted.topic = "Tyre warm-up"
        #expect(book.save(wanted))
        var spoken = Conversation(person: person)
        spoken.session = "Qualifying"
        spoken.takeaway = "Better balance"
        spoken.ink = Data([1, 2, 3])
        spoken.bookmarks = [12, 35]
        spoken.transcript = [.init(seconds: 12, text: "Recorded answer")]
        #expect(book.save(spoken))
        #expect(book.people.count == 1)
        let restored = ConversationBook(eventId: 5)
        restored.open(server: "https://one.test", owner: "a@test", root: dir)
        #expect(restored.record(spoken.id) == spoken)
        #expect(restored.entries(for: person).count == 2)
        #expect(restored.people.first?.car == "012")
        #expect(restored.remove(wanted.id))
        #expect(restored.people.count == 1)
        #expect(restored.remove(spoken.id))
        #expect(restored.people.isEmpty)
    }

    @Test func accountsServersAndEventsAreIsolated() {
        let dir = root()
        defer { try? FileManager.default.removeItem(at: dir) }
        let book = ConversationBook(eventId: 1)
        book.open(server: "https://one.test", owner: "a@test", root: dir)
        #expect(book.save(Conversation(person: person)))
        for (server, owner, event) in [("https://one.test", "b@test", 1), ("https://two.test", "a@test", 1), ("https://one.test", "a@test", 2)] {
            let other = ConversationBook(eventId: event)
            other.open(server: server, owner: owner, root: dir)
            #expect(other.records.isEmpty)
        }
    }

    @Test func interruptedRecordingRetainsAudioAndCanBeRetried() throws {
        let dir = root()
        defer { try? FileManager.default.removeItem(at: dir) }
        let book = ConversationBook(eventId: 1)
        book.open(server: "s", owner: "a", root: dir)
        var record = Conversation(person: nil)
        record.audioFile = "recording.m4a"
        record.recording = true
        let url = try #require(book.audioURL("recording.m4a"))
        try Data([1, 2]).write(to: url)
        #expect(book.save(record))
        let reopened = ConversationBook(eventId: 1)
        reopened.open(server: "s", owner: "a", root: dir)
        #expect(reopened.record(record.id)?.recording == false)
        #expect(reopened.record(record.id)?.transcriptionError != nil)
        #expect(FileManager.default.fileExists(atPath: url.path))
        #expect(reopened.people.isEmpty)
        #expect(reopened.audioURL("../outside") == nil)
    }

    @Test func unreadableJournalIsNeverOverwritten() throws {
        let dir = root()
        defer { try? FileManager.default.removeItem(at: dir) }
        let book = ConversationBook(eventId: 1)
        book.open(server: "s", owner: "a", root: dir)
        let file = try #require(book.directory).appending(path: "conversations.json")
        let corrupt = Data("invalid journal".utf8)
        try corrupt.write(to: file)
        let reopened = ConversationBook(eventId: 1)
        reopened.open(server: "s", owner: "a", root: dir)
        #expect(!reopened.writable)
        #expect(!reopened.save(Conversation(person: person)))
        #expect(try Data(contentsOf: file) == corrupt)
    }
    @Test func sessionPickerPreservesImportedAndLegacyLabelsWithoutDuplicates() {
        let options = ConversationSessions.options([" qualifying ", "PRACTICE  1", "Qualifying GTD", "Old custom session", ""])
        #expect(options.filter { ConversationSessions.matches($0, "qualifying") }.count == 1)
        #expect(options.contains("Qualifying GTD"))
        #expect(options.contains("Old custom session"))
        #expect(ConversationSessions.matches(" General   WEEKEND ", "General weekend"))
        #expect(ConversationSessions.matches("", "General weekend"))
        #expect(!ConversationSessions.matches("Race 1", "Race 2"))
        #expect(ConversationSessions.selection(" QUALIFYING ", in: options) == "Qualifying")
    }

    @Test func driverDeletionRemovesAllSessionsAndAudioButLeavesOtherDrivers() throws {
        let dir = root()
        defer { try? FileManager.default.removeItem(at: dir) }
        let book = ConversationBook(eventId: 1)
        book.open(server: "s", owner: "a", root: dir)
        var spoken = Conversation(person: person, session: "Practice 1", audioFile: "audio.caf")
        spoken.takeaway = "A note"
        let url = try #require(book.audioURL("audio.caf"))
        try Data([1, 2]).write(to: url)
        #expect(book.save(spoken))
        #expect(book.save(Conversation(person: person, kind: .planned, session: "Race")))
        let other = Conversation(person: nil, takeaway: "Keep this")
        #expect(book.save(other))
        #expect(book.remove(ids: Set(book.entries(for: person).map(\.id))))
        #expect(book.people.isEmpty)
        #expect(book.records == [other])
        #expect(!FileManager.default.fileExists(atPath: url.path))
        let restored = ConversationBook(eventId: 1)
        restored.open(server: "s", owner: "a", root: dir)
        #expect(restored.records == [other])
    }

    @Test func batchDeletionRejectsActiveRecordingWithoutPartialRemoval() {
        let dir = root()
        defer { try? FileManager.default.removeItem(at: dir) }
        let book = ConversationBook(eventId: 1)
        book.open(server: "s", owner: "a", root: dir)
        let note = Conversation(person: person)
        let active = Conversation(person: person, recording: true)
        #expect(book.save(note))
        #expect(book.save(active))
        #expect(!book.remove(ids: [note.id, active.id]))
        #expect(book.records.count == 2)
    }

}
