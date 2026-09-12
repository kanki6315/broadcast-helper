import PDFKit
import SwiftUI

/// PdfModal: team sheets (deep-linked to a car's page) and storylines, through
/// the offline store so a prepped weekend's PDFs open with no network.
struct PdfViewerSheet: View {
    @Environment(AppSession.self) private var session
    @Environment(\.dismiss) private var dismiss
    let path: String
    let title: String
    let page: Int
    @State private var document: PDFDocument?
    @State private var failed = false

    var body: some View {
        NavigationStack {
            Group {
                if let document {
                    PdfKitView(document: document, page: page)
                } else if failed {
                    ErrorPanel(message: "Couldn’t load the PDF — it may not be downloaded yet.").padding(PP.Space.s5)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(PP.surface.ignoresSafeArea())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationBackground(PP.surface)
        .presentationSizing(.page)
        .tint(PP.accentInk)
        .task(id: path) {
            if let data = await session.loader.bytes(path), let doc = PDFDocument(data: data) { document = doc } else { failed = true }
        }
    }
}

private struct PdfKitView: UIViewRepresentable {
    let document: PDFDocument
    let page: Int

    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.document = document
        view.autoScales = true
        view.displayMode = .singlePageContinuous
        view.displayDirection = .vertical
        view.backgroundColor = UIColor(PP.surface)
        if let target = document.page(at: max(0, page - 1)) {
            DispatchQueue.main.async { view.go(to: target) }
        }
        return view
    }

    func updateUIView(_ view: PDFView, context: Context) {}
}
