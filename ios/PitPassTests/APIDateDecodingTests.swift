import Foundation
import Testing
@testable import PitPass

struct APIDateDecodingTests {
    @Test(arguments: [
        "2026-09-16T12:34:56Z",
        "2026-09-16T12:34:56.123Z",
        "2026-09-16T12:34:56.123456Z",
        "2026-09-16T08:34:56-04:00",
        "2026-09-16T08:34:56.123-04:00",
        "2026-09-16T18:04:56.123+05:30"
    ])
    func acceptsAPITimestamps(_ timestamp: String) throws {
        // Preserve the server formats accepted before the Sendable migration.
        let legacy = ISO8601DateFormatter()
        legacy.formatOptions = timestamp.contains(".")
            ? [.withInternetDateTime, .withFractionalSeconds] : [.withInternetDateTime]
        let expected = try #require(legacy.date(from: timestamp))
        let decoded = try JSONDecoder.api.decode(Date.self, from: JSONEncoder().encode(timestamp))
        #expect(abs(decoded.timeIntervalSince(expected)) < 0.001)
    }

    @Test func rejectsInvalidDateWithItsCodingPath() throws {
        struct Document: Decodable { let timestamp: Date }
        do {
            _ = try JSONDecoder.api.decode(Document.self, from: Data(#"{"timestamp":"not-a-date"}"#.utf8))
            Issue.record("Invalid timestamps must fail decoding")
        } catch DecodingError.dataCorrupted(let context) {
            #expect(context.codingPath.map(\.stringValue) == ["timestamp"])
            #expect(context.debugDescription == "Bad date: not-a-date")
        }
    }
}
