import CoreGraphics
import CryptoKit
import Foundation
import PDFKit
import Vision

/// The storylines PDF as citable blocks: paragraphs, and each table as one
/// block of "cell | cell / next row". Read with Vision rather than PDFKit's
/// text layer — IMSA's packs map their digits through a broken font encoding
/// ("2017" extracts as `5N"/`), and numbers are the point of a storyline.
enum StorylineText {
    /// Cached by `key` (the versioned storylines path), since OCR takes about
    /// a second a page and a new upload gets a new version.
    static func blocks(pdf: Data, key: String) async throws -> [String] {
        let file = try cacheURL(key)
        if let data = try? Data(contentsOf: file), let cached = try? JSONDecoder().decode([String].self, from: data) {
            return cached
        }
        let blocks = try await recognize(pdf: pdf)
        try? JSONEncoder().encode(blocks).write(to: file, options: .atomic)
        return blocks
    }

    static func recognize(pdf: Data) async throws -> [String] {
        guard let document = PDFDocument(data: pdf) else { throw CocoaError(.fileReadCorruptFile) }
        var pages: [[String]] = []
        for index in 0..<document.pageCount {
            try Task.checkCancellation()
            guard let page = document.page(at: index), let image = render(page) else { continue }
            let observations = try await RecognizeDocumentsRequest().perform(on: image)
            pages.append(observations.flatMap { blocks(in: $0.document) })
        }
        return tidy(pages)
    }

    // MARK: layout

    private static func render(_ page: PDFPage, scale: CGFloat = 2) -> CGImage? {
        let box = page.bounds(for: .mediaBox)
        let width = Int(box.width * scale), height = Int(box.height * scale)
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        ctx.setFillColor(CGColor(gray: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.scaleBy(x: scale, y: scale)
        page.draw(with: .mediaBox, to: ctx)
        return ctx.makeImage()
    }

    /// Paragraphs outside tables plus one block per table, top to bottom.
    private static func blocks(in container: DocumentObservation.Container) -> [String] {
        let tables = container.tables.map { ($0.boundingRegion.boundingBox.cgRect, tableText($0)) }
        var placed: [(top: CGFloat, text: String)] = tables.map { ($0.0.maxY, $0.1) }
        for paragraph in container.paragraphs {
            let rect = paragraph.boundingRegion.boundingBox.cgRect
            let center = CGPoint(x: rect.midX, y: rect.midY)
            if tables.contains(where: { $0.0.insetBy(dx: -0.005, dy: -0.005).contains(center) }) { continue }
            placed.append((rect.maxY, paragraph.transcript))
        }
        // Normalized coordinates grow upwards: the top of the page is y = 1.
        return placed.sorted { $0.top > $1.top }.map(\.text)
    }

    private static func tableText(_ table: DocumentObservation.Container.Table) -> String {
        table.rows.map { row in
            row.map { clean($0.content.text.transcript) }.filter { !$0.isEmpty }.joined(separator: " | ")
        }.filter { !$0.isEmpty }.joined(separator: " / ")
    }

    // MARK: cleanup

    static func clean(_ text: String) -> String {
        text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
    }

    /// Drop the running header and page numbers, and rejoin a sentence the
    /// recognizer split at a line break ("…the all-time" + "Manufacturer win…").
    static func tidy(_ pages: [[String]]) -> [String] {
        let cleaned = pages.map { $0.map(clean).filter { !$0.isEmpty } }
        var counts: [String: Int] = [:]
        for page in cleaned { for line in Set(page) { counts[line, default: 0] += 1 } }
        let running = Set(counts.filter { $0.value >= max(3, cleaned.count / 2) }.keys)
        var out: [String] = []
        for line in cleaned.joined() {
            if running.contains(line) || line.allSatisfy({ $0.isNumber }) { continue }
            let bullet = line.first.map { "•o▪-–".contains($0) } ?? false
            if let last = out.last, !bullet, !line.contains(" | "), !last.contains(" | "),
               last.first == "•", !(".!?)\"”".contains(last.last ?? ".")) {
                out[out.count - 1] = last + " " + line
            } else {
                out.append(line)
            }
        }
        return out
    }

    private static func cacheURL(_ key: String) throws -> URL {
        let dir = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appending(path: "PitPass/Storylines", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let name = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return dir.appending(path: "\(name).json")
    }
}
