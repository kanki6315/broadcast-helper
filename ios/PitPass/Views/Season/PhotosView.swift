import SwiftUI

/// PhotosPage, read-only: the season's car photos. Uploading and number
/// matching stay on the website (docs/IOS.md usage model).
struct PhotosView: View {
    @Environment(AppSession.self) private var session
    @Environment(SeasonModel.self) private var model
    @State private var images: Resource<CarImagesOverview>?

    var body: some View {
        Group {
            if let list = images?.value?.images {
                if list.isEmpty {
                    EmptyState(message: "No car photos yet — upload them on the website under Photos.")
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 220, maximum: 320), spacing: PP.Space.s4)], spacing: PP.Space.s4) {
                        ForEach(list.sorted { carSort($0.carNumber, $1.carNumber) }) { img in
                            VStack(alignment: .leading, spacing: PP.Space.s2) {
                                CachedImage(path: "/api/car-images/\(img.id)/data?variant=sheet&v=\(img.uploadedAt)")
                                    .aspectRatio(16 / 9, contentMode: .fit)
                                    .clipShape(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous))
                                    .overlay(RoundedRectangle(cornerRadius: PP.Radius.md, style: .continuous).strokeBorder(PP.border))
                                HStack(alignment: .firstTextBaseline, spacing: PP.Space.s2) {
                                    Text("#\(img.carNumber)").font(PP.mono(PP.TextSize.sm, weight: 700)).foregroundStyle(PP.ink)
                                    if let f = img.sourceFilename { Text(f).font(PP.sans(PP.TextSize.xs)).foregroundStyle(PP.textMuted).lineLimit(1) }
                                }
                            }
                        }
                    }
                    .padding(.top, PP.Space.s4)
                }
            } else if let error = images?.error {
                ErrorPanel(message: error)
            } else {
                SkeletonLines()
            }
        }
        .task(id: model.seasonId) {
            let r = Resource<CarImagesOverview>("/api/car-images?seasonId=\(model.seasonId)")
            images = r
            await r.load(session.loader, freshness: session.freshness)
        }
    }
}

/// An immutable binary through the offline store (cache-first, like logos).
struct CachedImage: View {
    @Environment(AppSession.self) private var session
    let path: String
    @State private var image: UIImage?
    @State private var failed = false

    var body: some View {
        // The frame comes from the caller's aspect ratio; the image fills it
        // and is clipped to it, never the other way round.
        Color.clear
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else if failed {
                    PP.surface2.overlay(Image(systemName: "photo").foregroundStyle(PP.textMuted))
                } else {
                    PP.surface
                }
            }
            .clipped()
        .task(id: path) {
            if let data = await session.loader.bytes(path), let decoded = UIImage(data: data) { image = decoded } else { failed = true }
        }
    }
}
