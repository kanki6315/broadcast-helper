import SwiftUI

/// An immutable binary through the offline store (cache-first, like logos).
struct CachedImage: View {
    @Environment(AppSession.self) private var session
    let path: String
    var contentMode: ContentMode = .fill
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        // The frame comes from the caller's aspect ratio; the image fills it
        // and is clipped to it, never the other way round.
        Color.clear
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: contentMode)
                } else if failed {
                    PP.surface2.overlay(Image(systemName: "photo").foregroundStyle(PP.textMuted))
                } else {
                    PP.surface
                }
            }
            .clipped()
        .task(id: path) {
            if let data = await session.loader.bytes(path), let decoded = ImageDecoding.decode(data, maxHeight: 1200) { image = decoded } else { failed = true }
        }
    }
}
