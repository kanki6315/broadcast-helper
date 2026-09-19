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

struct PdfKitView: UIViewControllerRepresentable {
    let document: PDFDocument
    let page: Int

    func makeUIViewController(context: Context) -> PdfViewController {
        let controller = PdfViewController()
        controller.show(document: document, page: page)
        return controller
    }

    func updateUIViewController(_ controller: PdfViewController, context: Context) {
        controller.show(document: document, page: page)
    }
}

/// Apply deep links after the presentation has given PDFKit its final viewport.
/// SwiftUI updates must not jump back after the reader scrolls manually.
final class PdfViewController: UIViewController {
    let pdfView = PDFView()
    private var requestedDocument: PDFDocument?
    private var requestedPage: Int?
    private var pendingPage: PDFPage?
    private var finishedPresentation = false

    override func loadView() {
        pdfView.autoScales = true
        pdfView.displayMode = .singlePageContinuous
        pdfView.displayDirection = .vertical
        pdfView.backgroundColor = UIColor(PP.surface)
        view = pdfView
    }

    func show(document: PDFDocument, page: Int) {
        guard requestedDocument !== document || requestedPage != page else { return }
        loadViewIfNeeded()
        requestedDocument = document
        requestedPage = page
        if pdfView.document !== document { pdfView.document = document }
        let index = min(max(0, page - 1), max(0, document.pageCount - 1))
        pendingPage = document.page(at: index)
        view.setNeedsLayout()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !finishedPresentation else { return }
        finishedPresentation = true
        // The sheet animation can resize the viewport after its first layout.
        if let document = requestedDocument, let page = requestedPage {
            let index = min(max(0, page - 1), max(0, document.pageCount - 1))
            pendingPage = document.page(at: index)
            view.setNeedsLayout()
            view.layoutIfNeeded()
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard pdfView.window != nil, pdfView.bounds.width > 0, pdfView.bounds.height > 0,
              let target = pendingPage else { return }
        pendingPage = nil
        pdfView.layoutDocumentView()
        pdfView.go(to: target)
    }
}
