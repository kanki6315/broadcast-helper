import Foundation
import SQLite3

/// The replacement for the service worker's `api-data` cache: one row per API
/// path holding the last JSON body, its ETag and when it was fetched, in the
/// app's own container (Application Support — never purged by the OS the way
/// Caches or WebKit storage can be). Only the app writes here, only through
/// DataLoader, so what's on disk is exactly what the app decided to keep.
///
/// Every call swallows SQLite failures and degrades to "no cache": the pad and
/// the sheet must never break mid-broadcast because storage did.
actor OfflineStore {
    struct Entry: Sendable {
        let path: String
        let etag: String?
        let fetchedAt: Date
        let body: Data
    }

    struct Stats: Sendable, Equatable {
        let entries: Int
        let bytes: Int
    }

    /// What "Download this event / season" last completed, so a screen can say
    /// "Downloaded 2h ago" before any page is opened. Cleared with the data.
    struct DownloadRecord: Sendable, Equatable, Identifiable {
        let key: String
        let title: String
        let completedAt: Date
        let documents: Int
        let bytes: Int
        /// Documents the server refused (404 etc.) — the bundle is usable without them.
        let missing: Int

        var id: String { key }
    }

    // The handle is only touched from actor methods (and deinit); the annotation
    // is what lets a nonisolated deinit close it.
    private nonisolated(unsafe) let db: OpaquePointer?

    /// The app's store, under Application Support/PitPass/offline.sqlite.
    static func open() -> OfflineStore {
        do {
            let dir = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                                  appropriateFor: nil, create: true)
                .appending(path: "PitPass", directoryHint: .isDirectory)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            return OfflineStore(url: dir.appending(path: "offline.sqlite"))
        } catch {
            return OfflineStore(url: nil)
        }
    }

    /// `nil` = a store that never persists (storage unavailable).
    init(url: URL?) {
        var handle: OpaquePointer?
        if let url, sqlite3_open(url.path, &handle) == SQLITE_OK {
            db = handle
            // init is nonisolated in Swift 6; run the DDL straight on the handle.
            sqlite3_exec(handle, """
                CREATE TABLE IF NOT EXISTS cached_response (
                    path TEXT PRIMARY KEY,
                    etag TEXT,
                    fetched_at REAL NOT NULL,
                    body BLOB NOT NULL
                );
                CREATE TABLE IF NOT EXISTS download_record (
                    key TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    completed_at REAL NOT NULL,
                    documents INTEGER NOT NULL,
                    bytes INTEGER NOT NULL,
                    missing INTEGER NOT NULL
                );
                """, nil, nil, nil)
        } else {
            if let handle { sqlite3_close(handle) }
            db = nil
        }
    }

    deinit {
        if let db { sqlite3_close(db) }
    }

    func load(_ path: String) -> Entry? {
        guard let db else { return nil }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT etag, fetched_at, body FROM cached_response WHERE path = ?",
                                 -1, &stmt, nil) == SQLITE_OK else { return nil }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, path, -1, OfflineStore.transient)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        let etag = sqlite3_column_text(stmt, 0).map { String(cString: $0) }
        let fetchedAt = Date(timeIntervalSince1970: sqlite3_column_double(stmt, 1))
        let bytes = sqlite3_column_blob(stmt, 2)
        let length = Int(sqlite3_column_bytes(stmt, 2))
        let body = bytes.map { Data(bytes: $0, count: length) } ?? Data()
        return Entry(path: path, etag: etag, fetchedAt: fetchedAt, body: body)
    }

    /// Whether a body is stored for the path (no read of the body itself).
    func contains(_ path: String) -> Bool {
        guard let db else { return false }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT 1 FROM cached_response WHERE path = ?", -1, &stmt, nil) == SQLITE_OK else { return false }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, path, -1, OfflineStore.transient)
        return sqlite3_step(stmt) == SQLITE_ROW
    }

    /// Stored bytes across the given paths (paths with nothing stored count 0).
    func size(of paths: [String]) -> Int {
        guard let db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT length(body) FROM cached_response WHERE path = ?", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        var total = 0
        for path in paths {
            sqlite3_reset(stmt)
            sqlite3_bind_text(stmt, 1, path, -1, OfflineStore.transient)
            if sqlite3_step(stmt) == SQLITE_ROW { total += Int(sqlite3_column_int64(stmt, 0)) }
        }
        return total
    }

    func save(_ path: String, etag: String?, body: Data, at date: Date = .now) {
        guard let db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, """
            INSERT INTO cached_response (path, etag, fetched_at, body) VALUES (?, ?, ?, ?)
            ON CONFLICT(path) DO UPDATE SET etag = excluded.etag, fetched_at = excluded.fetched_at,
                                            body = excluded.body
            """, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, path, -1, OfflineStore.transient)
        if let etag {
            sqlite3_bind_text(stmt, 2, etag, -1, OfflineStore.transient)
        } else {
            sqlite3_bind_null(stmt, 2)
        }
        sqlite3_bind_double(stmt, 3, date.timeIntervalSince1970)
        body.withUnsafeBytes { raw in
            _ = sqlite3_bind_blob(stmt, 4, raw.baseAddress, Int32(raw.count), OfflineStore.transient)
        }
        _ = sqlite3_step(stmt)
    }

    /// The server confirmed the stored body is still current (304): move the
    /// freshness stamp without rewriting the body.
    func touch(_ path: String, at date: Date = .now) {
        guard let db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "UPDATE cached_response SET fetched_at = ? WHERE path = ?",
                                 -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_double(stmt, 1, date.timeIntervalSince1970)
        sqlite3_bind_text(stmt, 2, path, -1, OfflineStore.transient)
        _ = sqlite3_step(stmt)
    }

    func removeAll() {
        _ = exec("DELETE FROM cached_response")
        _ = exec("DELETE FROM download_record")
    }

    // MARK: download records

    func saveDownload(_ record: DownloadRecord) {
        guard let db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, """
            INSERT INTO download_record (key, title, completed_at, documents, bytes, missing) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET title = excluded.title, completed_at = excluded.completed_at,
                documents = excluded.documents, bytes = excluded.bytes, missing = excluded.missing
            """, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, record.key, -1, OfflineStore.transient)
        sqlite3_bind_text(stmt, 2, record.title, -1, OfflineStore.transient)
        sqlite3_bind_double(stmt, 3, record.completedAt.timeIntervalSince1970)
        sqlite3_bind_int64(stmt, 4, Int64(record.documents))
        sqlite3_bind_int64(stmt, 5, Int64(record.bytes))
        sqlite3_bind_int64(stmt, 6, Int64(record.missing))
        _ = sqlite3_step(stmt)
    }

    func removeDownload(_ key: String) {
        guard let db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "DELETE FROM download_record WHERE key = ?", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, key, -1, OfflineStore.transient)
        _ = sqlite3_step(stmt)
    }

    /// Every completed download, newest first.
    func downloads() -> [DownloadRecord] {
        guard let db else { return [] }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, """
            SELECT key, title, completed_at, documents, bytes, missing FROM download_record ORDER BY completed_at DESC
            """, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        var out: [DownloadRecord] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            out.append(DownloadRecord(
                key: String(cString: sqlite3_column_text(stmt, 0)),
                title: String(cString: sqlite3_column_text(stmt, 1)),
                completedAt: Date(timeIntervalSince1970: sqlite3_column_double(stmt, 2)),
                documents: Int(sqlite3_column_int64(stmt, 3)),
                bytes: Int(sqlite3_column_int64(stmt, 4)),
                missing: Int(sqlite3_column_int64(stmt, 5))))
        }
        return out
    }

    func stats() -> Stats {
        guard let db else { return Stats(entries: 0, bytes: 0) }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, "SELECT count(*), coalesce(sum(length(body)), 0) FROM cached_response",
                                 -1, &stmt, nil) == SQLITE_OK else { return Stats(entries: 0, bytes: 0) }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return Stats(entries: 0, bytes: 0) }
        return Stats(entries: Int(sqlite3_column_int64(stmt, 0)), bytes: Int(sqlite3_column_int64(stmt, 1)))
    }

    private func exec(_ sql: String) -> Bool {
        guard let db else { return false }
        return sqlite3_exec(db, sql, nil, nil, nil) == SQLITE_OK
    }

    /// SQLITE_TRANSIENT: tell SQLite to copy bound text/blobs before we return.
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
}
