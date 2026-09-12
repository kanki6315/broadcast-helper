import SwiftDraw
import UIKit

/// Image bytes from the API → UIImage. UIKit decodes PNG/WebP/JPEG; SVG
/// (the manufacturer marks) goes through SwiftDraw, rasterised at a sane
/// height so a 2000px-wide mark doesn't become a 2000px bitmap.
enum ImageDecoding {
    static func decode(_ data: Data, maxHeight: CGFloat = 120) -> UIImage? {
        if let image = UIImage(data: data) { return image }
        guard looksLikeSVG(data), let svg = SVG(data: data) else { return nil }
        let size = svg.size
        guard size.height > 0 else { return svg.rasterize(scale: 3) }
        let scale = min(1, maxHeight / size.height)
        return svg.rasterize(size: CGSize(width: size.width * scale, height: size.height * scale), scale: 3)
    }

    private static func looksLikeSVG(_ data: Data) -> Bool {
        guard let head = String(data: data.prefix(512), encoding: .utf8) else { return false }
        return head.contains("<svg") || head.contains("<?xml")
    }
}
